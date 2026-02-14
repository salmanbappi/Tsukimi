package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.core.model.TranslationEngine
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiEntryPoint {
	fun aiFeatureManager(): AiFeatureManager
}

@Singleton
class AiFeatureManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	@BaseHttpClient private val client: OkHttpClient
) {

	private val json = Json { ignoreUnknownKeys = true }
	private val textRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
	
	private val translationCache = LruCache<String, List<TranslatedBlock>>(50)
	private val translationMutexes = mutableMapOf<String, Mutex>()
	private val globalMutex = Mutex()

	fun isCached(pageKey: String): Boolean = translationCache.get(pageKey) != null
	
	fun getFromCache(pageKey: String): List<TranslatedBlock>? = translationCache.get(pageKey)

	suspend fun translatePage(
		pageKey: String,
		bitmap: Bitmap,
		viewScale: Float,
		vTranslateX: Float,
		vTranslateY: Float,
		captureScale: Float = 1f,
		targetLanguage: String = TranslateLanguage.ENGLISH
	): List<TranslatedBlock> = withContext(Dispatchers.Default) {
		if (!settings.isAiTranslationEnabled) return@withContext emptyList()

		translationCache.get(pageKey)?.let { return@withContext it }

		val mutex = globalMutex.withLock {
			translationMutexes.getOrPut(pageKey) { Mutex() }
		}

		mutex.withLock {
			// Double-check cache after acquiring lock
			translationCache.get(pageKey)?.let { return@withLock it }

			// Optimization: Downscale bitmap for faster OCR processing if needed
			// Note: bitmap is already scaled by captureScale in ReaderActivity
			val maxDim = 1800
			val ocrScale = if (bitmap.width > 0 && bitmap.height > 0) {
				Math.min(1f, maxDim.toFloat() / Math.max(bitmap.width, bitmap.height))
			} else 1f
			
			val ocrBitmap = if (ocrScale < 1f) {
				val targetW = (bitmap.width * ocrScale).toInt().coerceAtLeast(1)
				val targetH = (bitmap.height * ocrScale).toInt().coerceAtLeast(1)
				val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
				enhanceForOcr(scaled)
			} else {
				enhanceForOcr(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true))
			}

			val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
			val visionText = textRecognizer.process(inputImage).await()
			
			val engine = settings.aiTranslationEngine
			
			val mlKitTranslator = if (engine == TranslationEngine.ML_KIT) {
				val options = TranslatorOptions.Builder()
					.setSourceLanguage(TranslateLanguage.JAPANESE)
					.setTargetLanguage(targetLanguage)
					.build()
				val mlKit = Translation.getClient(options)
				mlKit.downloadModelIfNeeded().await()
				mlKit
			} else null

			val result = mutableListOf<TranslatedBlock>()
			
			try {
				val textBlocks = visionText.textBlocks
				val mergedBlocks = mergeNearbyBlocks(textBlocks)

				val translatedTexts = if (engine == TranslationEngine.GROQ && mergedBlocks.size > 1) {
					translateBatchWithGroq(mergedBlocks.map { it.text.toString().replace(Regex("[\\n\\s]+"), "") }, targetLanguage)
				} else null

				val translationJobs = mergedBlocks.mapIndexed { index, it ->
					async {
						val cleanText = it.text.toString().replace(Regex("[\\n\\s]+"), "")
						if (cleanText.isBlank()) return@async null

						val translatedText = translatedTexts?.getOrNull(index) ?: try {
							when (engine) {
								TranslationEngine.ML_KIT -> mlKitTranslator?.translate(cleanText)?.await()
								TranslationEngine.DEEPL -> translateWithDeepL(cleanText, targetLanguage)
								TranslationEngine.GROQ -> translateWithGroq(cleanText, targetLanguage)
							}
						} catch (e: Exception) {
							null
						}

						if (translatedText.isNullOrBlank()) return@async null

						val bubbleRect = detectBubbleBounds(it.boundingBox, ocrBitmap)
						
						// Map back to captured bitmap coordinates
						val rectInBitmap = RectF(
							bubbleRect.left / ocrScale,
							bubbleRect.top / ocrScale,
							bubbleRect.right / ocrScale,
							bubbleRect.bottom / ocrScale
						)

						// Map back to view coordinates using captureScale
						val rectInView = RectF(
							rectInBitmap.left / captureScale,
							rectInBitmap.top / captureScale,
							rectInBitmap.right / captureScale,
							rectInBitmap.bottom / captureScale
						)

						// ABSOLUTE IMAGE ANCHORING:
						// Convert view coordinates to actual source image coordinates
						val sourceRect = RectF(
							(rectInView.left - vTranslateX) / viewScale,
							(rectInView.top - vTranslateY) / viewScale,
							(rectInView.right - vTranslateX) / viewScale,
							(rectInView.bottom - vTranslateY) / viewScale
						)
						
						// Safety guard: skip giant broken OCR blocks (>99% of captured area)
						if (rectInBitmap.width() > bitmap.width * 0.99f || 
							rectInBitmap.height() > bitmap.height * 0.99f) return@async null

						val backgroundColor = detectBackgroundColor(bubbleRect, ocrBitmap)

						TranslatedBlock(
							text = translatedText,
							boundingBox = sourceRect,
							backgroundColor = backgroundColor
						)
					}
				}
				
				result.addAll(translationJobs.awaitAll().filterNotNull())
				
				if (result.isNotEmpty()) {
					translationCache.put(pageKey, result)
				}
			} finally {
				mlKitTranslator?.close()
				if (ocrBitmap != bitmap && ocrBitmap.width > 1) ocrBitmap.recycle()
				globalMutex.withLock {
					translationMutexes.remove(pageKey)
				}
			}
			
			result
		}
	}

	private suspend fun translateBatchWithGroq(texts: List<String>, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val langName = getLanguageName(targetLanguage)
		
		val input = buildJsonObject {
			putJsonArray("texts") {
				texts.forEach { add(it) }
			}
		}

		val body = buildJsonObject {
			put("model", "llama-3.1-8b-instant")
			put("response_format", buildJsonObject { put("type", "json_object") })
			putJsonArray("messages") {
				add(buildJsonObject {
					put("role", "system")
					put("content", "You are a professional manga translator. Translate the following Japanese texts into natural $langName. Maintain consistent tone across all texts. Return a JSON object with a 'translations' array containing the translated strings in the same order as the input.")
				})
				add(buildJsonObject {
					put("role", "user")
					put("content", input.toString())
				})
			}
		}
		
		val request = Request.Builder()
			.url("https://api.groq.com/openai/v1/chat/completions")
			.addHeader("Authorization", "Bearer $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType()))
			.build()
			
			try {
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) return@withContext null
					val jsonResult = json.parseToJsonElement(response.body?.string() ?: "")
					val content = jsonResult.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return@withContext null
					val batchResult = json.parseToJsonElement(content).jsonObject["translations"]?.jsonArray
					batchResult?.map { it.jsonPrimitive.content }
				}
			} catch (e: Exception) {
				null
			}
	}

	private suspend fun translateWithDeepL(text: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext null
		val isFree = apiKey.endsWith(":fx")
		val url = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		
		val body = buildJsonObject {
			putJsonArray("text") { add(text) }
			put("target_lang", targetLanguage.uppercase())
		}
		
		val request = Request.Builder()
			.url(url)
			.addHeader("Authorization", "DeepL-Auth-Key $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType()))
			.build()
			
			try {
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) return@withContext null
					val jsonResult = json.parseToJsonElement(response.body?.string() ?: "")
					jsonResult.jsonObject["translations"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
				}
			} catch (e: Exception) {
				null
			}
	}

	private suspend fun translateWithGroq(text: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val langName = getLanguageName(targetLanguage)
		
		val body = buildJsonObject {
			put("model", "llama-3.1-8b-instant")
			putJsonArray("messages") {
				add(buildJsonObject {
					put("role", "system")
					put("content", "You are a professional manga translator. Translate the following Japanese text to natural $langName. Keep it concise and preserve the tone. Only return the translated text.")
				})
				add(buildJsonObject {
					put("role", "user")
					put("content", text)
				})
			}
		}
		
		val request = Request.Builder()
			.url("https://api.groq.com/openai/v1/chat/completions")
			.addHeader("Authorization", "Bearer $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType()))
			.build()
			
			try {
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) return@withContext null
					val jsonResult = json.parseToJsonElement(response.body?.string() ?: "")
					jsonResult.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
				}
			} catch (e: Exception) {
				null
			}
	}

	private fun getLanguageName(code: String): String {
		return when (code.lowercase()) {
			"en" -> "English"
			"ru" -> "Russian"
			"es" -> "Spanish"
			"fr" -> "French"
			"de" -> "German"
			"it" -> "Italian"
			"ja" -> "Japanese"
			"ko" -> "Korean"
			"zh" -> "Chinese"
			else -> code
		}
	}

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): List<IntermediateBlock> {
		if (blocks.isEmpty()) return emptyList()

		// Determine dominant orientation (Vertical vs Horizontal)
		var verticalCount = 0
		var horizontalCount = 0
		for (block in blocks) {
			val rect = block.boundingBox ?: continue
			if (rect.height() > rect.width() * 1.2f) verticalCount++
			else if (rect.width() > rect.height() * 1.2f) horizontalCount++
		}
		val isLikelyVertical = verticalCount > horizontalCount

		val sorted = if (isLikelyVertical) {
			blocks.sortedWith(
				compareByDescending<com.google.mlkit.vision.text.Text.TextBlock> { it.boundingBox?.right ?: 0 }
					.thenBy { it.boundingBox?.top ?: 0 }
			)
		} else {
			blocks.sortedWith(
				compareBy<com.google.mlkit.vision.text.Text.TextBlock> { it.boundingBox?.top ?: 0 }
					.thenBy { it.boundingBox?.left ?: 0 }
			)
		}
		
		val merged = mutableListOf<IntermediateBlock>()

		for (block in sorted) {
			val rect = block.boundingBox ?: continue
			val text = block.text

			var isMerged = false
			for (i in merged.indices.reversed()) {
				val m = merged[i]
				if (areBlocksClose(m.boundingBox, rect, isLikelyVertical)) {
					if (isLikelyVertical) {
						m.text.append("\n").append(text)
					} else {
						m.text.append(" ").append(text)
					}
					m.boundingBox.union(rect)
					isMerged = true
					break
				}
			}

			if (!isMerged) {
				merged.add(IntermediateBlock(StringBuilder(text), Rect(rect)))
			}
		}
		return merged
	}

	private fun areBlocksClose(r1: Rect, r2: Rect, isVertical: Boolean): Boolean {
		val h1 = r1.height()
		val h2 = r2.height()
		val w1 = r1.width()
		val w2 = r2.width()
		
		val avgH = (h1 + h2) / 2f
		val avgW = (w1 + w2) / 2f
		
		return if (isVertical) {
			// Manga: Tight vertical, wider horizontal (columns)
			val thresholdX = (avgH * 1.8f).toInt().coerceAtLeast(40)
			val thresholdY = (avgH * 0.7f).toInt().coerceAtLeast(15)
			val expanded = Rect(r1)
			expanded.inset(-thresholdX, -thresholdY)
			Rect.intersects(expanded, r2)
		} else {
			// Webtoon: Tight horizontal, wider vertical (rows)
			val thresholdX = (avgW * 0.7f).toInt().coerceAtLeast(15)
			val thresholdY = (avgW * 1.2f).toInt().coerceAtLeast(30)
			val expanded = Rect(r1)
			expanded.inset(-thresholdX, -thresholdY)
			Rect.intersects(expanded, r2)
		}
	}
	
	private fun detectBubbleBounds(textRect: Rect, bitmap: Bitmap): Rect {
		try {
			val width = bitmap.width
			val height = bitmap.height
			val centerX = textRect.centerX()
			val centerY = textRect.centerY()

			if (centerX !in 0 until width || centerY !in 0 until height) return textRect

			// More generous expansion for modern high-res displays
			val maxExpandX = (textRect.width() * 0.6).toInt().coerceAtMost(width / 5).coerceAtLeast(30)
			val maxExpandY = (textRect.height() * 0.6).toInt().coerceAtMost(height / 5).coerceAtLeast(30)

			fun scan(startX: Int, startY: Int, dx: Int, dy: Int, maxDist: Int): Int {
				var x = startX
				var y = startY
				var dist = 0
				var tolerance = 3 // Allow up to 3 dark pixels (screentone noise)
				var lastValidDist = 0
				
				while (dist < maxDist) {
					x += dx
					y += dy
					if (x !in 0 until width || y !in 0 until height) break
					
					if (isPixelLight(bitmap, x, y)) {
						dist++
						lastValidDist = dist
						tolerance = 3 // Reset tolerance
					} else {
						if (tolerance > 0) {
							dist++
							tolerance--
						} else {
							break
						}
					}
				}
				return lastValidDist
			}

			val leftDist = scan(textRect.left, centerY, -1, 0, maxExpandX)
			val rightDist = scan(textRect.right, centerY, 1, 0, maxExpandX)
			val topDist = scan(centerX, textRect.top, 0, -1, maxExpandY)
			val bottomDist = scan(centerX, textRect.bottom, 0, 1, maxExpandY)

			return Rect(
				textRect.left - leftDist,
				textRect.top - topDist,
				textRect.right + rightDist,
				textRect.bottom + bottomDist
			)
		} catch (e: Exception) {
			return textRect
		}
	}

	private fun enhanceForOcr(src: Bitmap): Bitmap {
		val width = src.width
		val height = src.height
		val config = src.config ?: Bitmap.Config.ARGB_8888
		val bmOut = Bitmap.createBitmap(width, height, config)
		
		val canvas = android.graphics.Canvas(bmOut)
		val paint = android.graphics.Paint()
		// High contrast matrix: increase scale, decrease offset
		val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
			2.5f, 0f, 0f, 0f, -120f,
			0f, 2.5f, 0f, 0f, -120f,
			0f, 0f, 2.5f, 0f, -120f,
			0f, 0f, 0f, 1f, 0f
		))
		paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
		canvas.drawBitmap(src, 0f, 0f, paint)
		if (src.width > 1) src.recycle()
		return bmOut
	}

	private fun detectBackgroundColor(rect: Rect, bitmap: Bitmap): Int {
		// Sample pixels just inside the detected bounds to find the bubble color
		val samples = mutableListOf<Int>()
		val startX = (rect.left + rect.width() * 0.1).toInt()
		val endX = (rect.right - rect.width() * 0.1).toInt()
		val startY = (rect.top + rect.height() * 0.1).toInt()
		val endY = (rect.bottom - rect.height() * 0.1).toInt()
		
		try {
			// Sample 5 points: center and 4 corners (inset)
			samples.add(bitmap.getPixel(rect.centerX(), rect.centerY()))
			samples.add(bitmap.getPixel(startX, startY))
			samples.add(bitmap.getPixel(endX, startY))
			samples.add(bitmap.getPixel(startX, endY))
			samples.add(bitmap.getPixel(endX, endY))
		} catch (e: Exception) {
			return Color.WHITE
		}

		// Calculate average luminance to decide if we should use white or the sampled color
		// Most manga bubbles are white or very light grey.
		// If distinct colors found, average them.
		var r = 0; var g = 0; var b = 0
		for (c in samples) {
			r += Color.red(c)
			g += Color.green(c)
			b += Color.blue(c)
		}
		r /= samples.size
		g /= samples.size
		b /= samples.size
		
		return Color.rgb(r, g, b)
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		try {
			val pixel = bitmap.getPixel(x, y)
			val red = Color.red(pixel)
			val green = Color.green(pixel)
			val blue = Color.blue(pixel)
			val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
			return luminance >= 190 // Relaxed threshold for better detection on screentones
		} catch (e: Exception) {
			return false
		}
	}

	private data class IntermediateBlock(
		val text: StringBuilder,
		val boundingBox: Rect
	)
}

data class TranslatedBlock(
	val text: String,
	val boundingBox: RectF,
	val backgroundColor: Int = Color.WHITE
)