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

			// Optimization: Downscale bitmap for faster OCR processing
			val maxDim = 1440
			val ocrScale = if (bitmap.width > 0 && bitmap.height > 0) {
				Math.min(1f, maxDim.toFloat() / Math.max(bitmap.width, bitmap.height))
			} else 1f
			
			val ocrBitmap = if (ocrScale < 1f) {
				val targetW = (bitmap.width * ocrScale).toInt().coerceAtLeast(1)
				val targetH = (bitmap.height * ocrScale).toInt().coerceAtLeast(1)
				Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
			} else bitmap

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

						val bubbleResult = detectBubbleBounds(it.boundingBox, ocrBitmap)
						val bubbleRect = bubbleResult.bounds
						
						// Map back to original captured bitmap coordinates
						val rectInOriginalBitmap = RectF(
							bubbleRect.left / ocrScale,
							bubbleRect.top / ocrScale,
							bubbleRect.right / ocrScale,
							bubbleRect.bottom / ocrScale
						)

						// ABSOLUTE IMAGE ANCHORING:
						// Convert bitmap coordinates to actual source image coordinates
						val sourceRect = RectF(
							(rectInOriginalBitmap.left - vTranslateX) / viewScale,
							(rectInOriginalBitmap.top - vTranslateY) / viewScale,
							(rectInOriginalBitmap.right - vTranslateX) / viewScale,
							(rectInOriginalBitmap.bottom - vTranslateY) / viewScale
						)
						
						// Safety guard: skip giant broken OCR blocks (>99% of captured area)
						if (rectInOriginalBitmap.width() > bitmap.width * 0.99f || 
							rectInOriginalBitmap.height() > bitmap.height * 0.99f) return@async null

						val backgroundColor = detectBackgroundColorFromMask(bubbleResult.mask, ocrBitmap)

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

		// Sort by Manga reading order: Right-to-Left columns, Top-to-Bottom within columns
		val sorted = blocks.sortedWith { b1, b2 ->
			val r1 = b1.boundingBox ?: return@sortedWith 0
			val r2 = b2.boundingBox ?: return@sortedWith 0
			
			// If blocks are in roughly the same vertical column (RTL), sort by Top
			if (Math.abs(r1.centerX() - r2.centerX()) < (r1.width() + r2.width()) / 4) {
				r1.top.compareTo(r2.top)
			} else {
				// Otherwise, Right-most column comes first
				r2.centerX().compareTo(r1.centerX())
			}
		}
		
		val merged = mutableListOf<IntermediateBlock>()

		for (block in sorted) {
			val rect = block.boundingBox ?: continue
			val text = block.text

			var isMerged = false
			for (i in merged.indices.reversed()) {
				val m = merged[i]
				if (areBlocksInSameBubble(m.boundingBox, rect)) {
					// Append text with space or newline
					if (m.text.isNotEmpty() && !m.text.endsWith("\n")) {
						m.text.append("\n")
					}
					m.text.append(text)
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

	private fun areBlocksInSameBubble(r1: Rect, r2: Rect): Boolean {
		val avgHeight = (r1.height() + r2.height()) / 2f
		val avgWidth = (r1.width() + r2.width()) / 2f
		
		// More aggressive vertical merging for Japanese text
		val horizontalThreshold = (avgWidth * 0.8f).toInt().coerceAtLeast(20)
		val verticalThreshold = (avgHeight * 1.2f).toInt().coerceAtLeast(40)
		
		val expanded = Rect(r1)
		expanded.inset(-horizontalThreshold, -verticalThreshold)
		return Rect.intersects(expanded, r2)
	}
	
	private data class BubbleResult(val bounds: Rect, val mask: java.util.BitSet)

	private fun detectBubbleBounds(textRect: Rect, bitmap: Bitmap): BubbleResult {
		try {
			val width = bitmap.width
			val height = bitmap.height
			
			if (textRect.left < 0 || textRect.top < 0 || textRect.right > width || textRect.bottom > height) {
				return BubbleResult(textRect, java.util.BitSet())
			}

			val visited = java.util.BitSet(width * height)
			val queue = java.util.ArrayDeque<Int>()
			
			var minX = textRect.left
			var maxX = textRect.right
			var minY = textRect.top
			var maxY = textRect.bottom

			val step = (textRect.width() / 15).coerceAtLeast(1)
			for (x in textRect.left until textRect.right step step) {
				for (y in textRect.top until textRect.bottom step step) {
					val idx = y * width + x
					if (!visited.get(idx)) {
						visited.set(idx)
						queue.add(idx)
					}
				}
			}

			val maxPixels = (width * height * 0.20).toInt()
			var processedPixels = 0
			
			val maxDistX = (textRect.width() * 1.8).toInt().coerceAtLeast(120).coerceAtMost(width / 2)
			val maxDistY = (textRect.height() * 1.8).toInt().coerceAtLeast(120).coerceAtMost(height / 2)

			val dx = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
			val dy = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)

			while (queue.isNotEmpty() && processedPixels < maxPixels) {
				val curr = queue.removeFirst()
				processedPixels++
				
				val cx = curr % width
				val cy = curr / width
				
				minX = Math.min(minX, cx)
				maxX = Math.max(maxX, cx)
				minY = Math.min(minY, cy)
				maxY = Math.max(maxY, cy)

				for (i in 0 until 8) {
					val nx = cx + dx[i]
					val ny = cy + dy[i]
					
					if (nx in 0 until width && ny in 0 until height) {
						val nIdx = ny * width + nx
						if (!visited.get(nIdx)) {
							if (Math.abs(nx - textRect.centerX()) > maxDistX || 
								Math.abs(ny - textRect.centerY()) > maxDistY) continue
								
							// Hole Plugging: If a pixel has multiple dark neighbors, it's likely part of a boundary line
							var darkNeighbors = 0
							for (j in 0 until 8) {
								val nnx = nx + dx[j]
								val nny = ny + dy[j]
								if (nnx !in 0 until width || nny !in 0 until height || !isPixelLight(bitmap, nnx, nny)) {
									darkNeighbors++
								}
							}
							
							if (darkNeighbors >= 3) continue

							if (isPixelLight(bitmap, nx, ny)) {
								visited.set(nIdx)
								queue.add(nIdx)
							}
						}
					}
				}
			}

			val padding = 2
			return BubbleResult(
				Rect(
					(minX - padding).coerceAtLeast(0),
					(minY - padding).coerceAtLeast(0),
					(maxX + padding).coerceAtMost(width - 1),
					(maxY + padding).coerceAtMost(height - 1)
				),
				visited
			)
		} catch (e: Exception) {
			return BubbleResult(textRect, java.util.BitSet())
		}
	}

	private fun detectBackgroundColorFromMask(mask: java.util.BitSet, bitmap: Bitmap): Int {
		if (mask.isEmpty) return Color.WHITE
		
		val width = bitmap.width
		var r = 0L; var g = 0L; var b = 0L
		var count = 0
		
		// Sample up to 500 pixels from the mask
		val totalSet = mask.cardinality()
		val sampleStep = (totalSet / 500).coerceAtLeast(1)
		
		var idx = mask.nextSetBit(0)
		var sampled = 0
		while (idx >= 0 && sampled < 500) {
			val pixel = bitmap.getPixel(idx % width, idx / width)
			r += Color.red(pixel)
			g += Color.green(pixel)
			b += Color.blue(pixel)
			count++
			sampled++
			
			var next = idx + 1
			repeat(sampleStep - 1) {
				if (next >= 0) next = mask.nextSetBit(next + 1)
			}
			idx = if (next >= 0) mask.nextSetBit(next) else -1
		}

		return if (count > 0) Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
		else Color.WHITE
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
		try {
			val pixel = bitmap.getPixel(x, y)
			val red = Color.red(pixel)
			val green = Color.green(pixel)
			val blue = Color.blue(pixel)
			// Slightly more lenient threshold (200 instead of 220) to capture slightly darker bubbles
			val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
			return luminance >= 200 
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