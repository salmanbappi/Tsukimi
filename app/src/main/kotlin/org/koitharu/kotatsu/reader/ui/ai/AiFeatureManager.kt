package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.content.SharedPreferences
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
) : SharedPreferences.OnSharedPreferenceChangeListener {

	private val json = Json { ignoreUnknownKeys = true }
	private val textRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
	
	private val translationCache = LruCache<String, List<TranslatedBlock>>(50)
	private val translationMutexes = mutableMapOf<String, Mutex>()
	private val globalMutex = Mutex()

	init {
		settings.subscribe(this)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_AI_TRANSLATION,
			AppSettings.KEY_AI_AUTO_TRANSLATION,
			AppSettings.KEY_AI_TRANSLATION_ENGINE,
			AppSettings.KEY_AI_TRANSLATION_DEEPL_KEY,
			AppSettings.KEY_AI_TRANSLATION_GROQ_KEY,
			AppSettings.KEY_AI_SEAMLESS_TRANSLATION -> clearCache()
		}
	}

	fun isCached(pageKey: String): Boolean = translationCache.get(pageKey) != null
	
	fun getFromCache(pageKey: String): List<TranslatedBlock>? = translationCache.get(pageKey)

	fun clearCache() {
		translationCache.evictAll()
	}

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
			val maxDim = 2400
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
						if (cleanText.length < 2) return@async null

						// Skip sound effects (short Japanese-only strings)
						val isLikelySFX = cleanText.length <= 4 && 
							cleanText.all { c -> c in '\u30A0'..'\u30FF' || c in '\u3040'..'\u309F' || c == 'っ' || c == 'ッ' || c == 'ー' }
						if (isLikelySFX) return@async null

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
						
						// Safety guard: skip giant broken OCR blocks (>65% width or >45% height)
						// Real speech bubbles are rarely this large compared to the page.
						val srcW = (bitmap.width / captureScale)
						val srcH = (bitmap.height / captureScale)
						if (sourceRect.width() > srcW * 0.65f || 
							sourceRect.height() > srcH * 0.45f) return@async null

						val bubbleRectInOriginal = Rect(
							(bubbleRect.left / ocrScale).toInt(),
							(bubbleRect.top / ocrScale).toInt(),
							(bubbleRect.right / ocrScale).toInt(),
							(bubbleRect.bottom / ocrScale).toInt()
						)
						val backgroundColor = detectBackgroundColor(bubbleRectInOriginal, bitmap)

						TranslatedBlock(
							text = translatedText,
							boundingBox = sourceRect,
							backgroundColor = backgroundColor
						)
					}
				}
				
				val rawBlocks = translationJobs.awaitAll().filterNotNull()
				val cleanedBlocks = resolveOverlappingBlocks(rawBlocks)
				result.addAll(cleanedBlocks)
				
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

	private fun resolveOverlappingBlocks(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
		if (blocks.size <= 1) return blocks
		
		val resolved = mutableListOf<TranslatedBlock>()
		// Sort by area descending so we process the largest expanded bubbles first
		val sorted = blocks.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
		
		for (block in sorted) {
			var merged = false
			for (i in resolved.indices) {
				val existing = resolved[i]
				val intersection = RectF(existing.boundingBox)
				
				if (intersection.intersect(block.boundingBox)) {
					val overlapArea = intersection.width() * intersection.height()
					val blockArea = block.boundingBox.width() * block.boundingBox.height()
					
					// If the new block is highly engulfed by an existing bubble (e.g., > 40%), merge them
					if (overlapArea > blockArea * 0.4f) {
						val newBounds = RectF(existing.boundingBox).apply { union(block.boundingBox) }
						// Combine the text before caching
						val newText = if (existing.text.contains(block.text) || block.text.contains(existing.text)) {
							// Simple deduplication if one text is a subset of another (common with OCR fragments)
							if (existing.text.length >= block.text.length) existing.text else block.text
						} else {
							existing.text + " " + block.text
						}
						resolved[i] = existing.copy(text = newText, boundingBox = newBounds)
						merged = true
						break
					}
				}
			}
			if (!merged) {
				resolved.add(block)
			}
		}
		return resolved
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
					put("content", """
						You are an expert manga translator and OCR corrector. 
						The following Japanese texts were extracted using a basic OCR tool that often mixes furigana into the kanji (e.g., reading 漢字 as 漢か字んじ) and fragments sentences.
						
						Step 1: Mentally strip out the misplaced furigana and fix the Japanese grammar.
						Step 2: Translate the corrected text into natural $langName.
						Step 3: Keep the tone casual and appropriate for manga.
						
						Return a JSON object with a 'translations' array containing ONLY the final translated strings in the same order as the input.
					""".trimIndent())
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
					put("content", """
						You are an expert manga translator and OCR corrector. 
						The following Japanese text was extracted using a basic OCR tool that often mixes furigana into the kanji (e.g., reading 漢字 as 漢か字んじ).
						
						Step 1: Mentally strip out the misplaced furigana and fix the Japanese grammar.
						Step 2: Translate the corrected text into natural $langName.
						Step 3: Keep the tone casual and appropriate for manga.
						
						Only return the translated text.
					""".trimIndent())
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
		// Don't merge blocks with no overlap on their shared axis
		if (isVertical) {
			val verticalOverlap = minOf(r1.bottom, r2.bottom) - maxOf(r1.top, r2.top)
			if (verticalOverlap < 0) return false
		} else {
			val horizontalOverlap = minOf(r1.right, r2.right) - maxOf(r1.left, r2.left)
			if (horizontalOverlap < 0) return false
		}

		val h1 = r1.height()
		val h2 = r2.height()
		val w1 = r1.width()
		val w2 = r2.width()
		
		val avgH = (h1 + h2) / 2f
		val avgW = (w1 + w2) / 2f
		
		return if (isVertical) {
			// Manga: Tight vertical, even tighter horizontal (don't cross panels)
			val thresholdX = (avgH * 0.3f).toInt().coerceIn(5, 28)
			val thresholdY = (avgH * 0.2f).toInt().coerceIn(3, 18)
			val expanded = Rect(r1)
			expanded.inset(-thresholdX, -thresholdY)
			Rect.intersects(expanded, r2)
		} else {
			// Webtoon: Tight horizontal, tight vertical
			val thresholdX = (avgW * 0.2f).toInt().coerceIn(3, 18)
			val thresholdY = (avgW * 0.3f).toInt().coerceIn(5, 28)
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

			// Allow bubbles to expand reasonably based on text size (Widened after bench testing)
			val maxExpandX = (textRect.width() * 0.6).toInt().coerceAtMost(width / 5).coerceAtLeast(30)
			val maxExpandY = (textRect.height() * 0.5).toInt().coerceAtMost(height / 8).coerceAtLeast(30)

			fun scan(startX: Int, startY: Int, dx: Int, dy: Int, maxDist: Int): Int {
				var x = startX
				var y = startY
				var dist = 0
				var tolerance = 3 // Increased tolerance to skip small artifacts/hair
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

			// Dense scanning (5 points) for better irregular bubble fitting
			val leftDist = maxOf(
				scan(textRect.left, textRect.top, -1, 0, maxExpandX),
				scan(textRect.left, textRect.top + textRect.height() / 4, -1, 0, maxExpandX),
				scan(textRect.left, centerY, -1, 0, maxExpandX),
				scan(textRect.left, textRect.bottom - textRect.height() / 4, -1, 0, maxExpandX),
				scan(textRect.left, textRect.bottom, -1, 0, maxExpandX)
			)
			val rightDist = maxOf(
				scan(textRect.right, textRect.top, 1, 0, maxExpandX),
				scan(textRect.right, textRect.top + textRect.height() / 4, 1, 0, maxExpandX),
				scan(textRect.right, centerY, 1, 0, maxExpandX),
				scan(textRect.right, textRect.bottom - textRect.height() / 4, 1, 0, maxExpandX),
				scan(textRect.right, textRect.bottom, 1, 0, maxExpandX)
			)
			val topDist = maxOf(
				scan(textRect.left, textRect.top, 0, -1, maxExpandY),
				scan(textRect.left + textRect.width() / 4, textRect.top, 0, -1, maxExpandY),
				scan(centerX, textRect.top, 0, -1, maxExpandY),
				scan(textRect.right - textRect.width() / 4, textRect.top, 0, -1, maxExpandY),
				scan(textRect.right, textRect.top, 0, -1, maxExpandY)
			)
			val bottomDist = maxOf(
				scan(textRect.left, textRect.bottom, 0, 1, maxExpandY),
				scan(textRect.left + textRect.width() / 4, textRect.bottom, 0, 1, maxExpandY),
				scan(centerX, textRect.bottom, 0, 1, maxExpandY),
				scan(textRect.right - textRect.width() / 4, textRect.bottom, 0, 1, maxExpandY),
				scan(textRect.right, textRect.bottom, 0, 1, maxExpandY)
			)

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
		// Refined contrast matrix: moderate scale, smaller offset
		val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
			1.8f, 0f, 0f, 0f, -60f,
			0f, 1.8f, 0f, 0f, -60f,
			0f, 0f, 1.8f, 0f, -60f,
			0f, 0f, 0f, 1f, 0f
		))
		paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
		canvas.drawBitmap(src, 0f, 0f, paint)
		if (src.width > 1) src.recycle()
		return bmOut
	}

	private fun detectBackgroundColor(rect: Rect, bitmap: Bitmap): Int {
		// Sample pixels just inside the detected bounds to find the bubble color
		// AVOID the center to prevent sampling the original text itself
		val samples = mutableListOf<Int>()
		val insetX = (rect.width() * 0.05).toInt().coerceAtLeast(1)
		val insetY = (rect.height() * 0.05).toInt().coerceAtLeast(1)
		
		try {
			// Sample corners and mid-edges (8 points total)
			samples.add(bitmap.getPixel(rect.left + insetX, rect.top + insetY))
			samples.add(bitmap.getPixel(rect.right - insetX, rect.top + insetY))
			samples.add(bitmap.getPixel(rect.left + insetX, rect.bottom - insetY))
			samples.add(bitmap.getPixel(rect.right - insetX, rect.bottom - insetY))
			samples.add(bitmap.getPixel(rect.centerX(), rect.top + insetY))
			samples.add(bitmap.getPixel(rect.centerX(), rect.bottom - insetY))
			samples.add(bitmap.getPixel(rect.left + insetX, rect.centerY()))
			samples.add(bitmap.getPixel(rect.right - insetX, rect.centerY()))
		} catch (e: Exception) {
			return Color.WHITE
		}

		var r = 0; var g = 0; var b = 0
		for (c in samples) {
			r += Color.red(c)
			g += Color.green(c)
			b += Color.blue(c)
		}
		r /= samples.size
		g /= samples.size
		b /= samples.size
		
		// Force 100% opacity (no transparent Japanese text leakage)
		return Color.rgb(r, g, b)
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		try {
			val pixel = bitmap.getPixel(x, y)
			val red = Color.red(pixel)
			val green = Color.green(pixel)
			val blue = Color.blue(pixel)
			val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
			return luminance >= 210 // Stricter threshold after bench testing
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