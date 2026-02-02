package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.*
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
import java.util.BitSet

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

			// PRO OCR OPTIMIZATION: Higher resolution for small/thin manga text
			val maxDim = 2048 
			val ocrScale = if (bitmap.width > 0 && bitmap.height > 0) {
				Math.min(1f, maxDim.toFloat() / Math.max(bitmap.width, bitmap.height))
			} else 1f
			
			val ocrBitmap = if (ocrScale < 1f) {
				val targetW = (bitmap.width * ocrScale).toInt().coerceAtLeast(1)
				val targetH = (bitmap.height * ocrScale).toInt().coerceAtLeast(1)
				Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
			} else bitmap

			// PASS 1: Standard OCR
			val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
			val visionText = textRecognizer.process(inputImage).await()
			
			// PASS 2: High Contrast Recovery for missed bubbles (stylized/faint text)
			val recoveryBitmap = enhanceForOcr(ocrBitmap)
			val recoveryText = textRecognizer.process(InputImage.fromBitmap(recoveryBitmap, 0)).await()
			
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
				// Combine and deduplicate detections from both passes
				val allBlocks = (visionText.textBlocks + recoveryText.textBlocks)
					.distinctBy { "${it.boundingBox?.centerX()}_${it.boundingBox?.centerY()}" }
				
				val mergedBlocks = mergeNearbyBlocks(allBlocks, ocrBitmap)

				// CONTEXTUAL BATCH TRANSLATION: Engines now understand whole-page flow
				val cleanTexts = mergedBlocks.map { it.text.toString().replace(Regex("[\\n\\s]+"), " ").trim() }
				if (cleanTexts.isEmpty()) return@withLock emptyList<TranslatedBlock>()

				val translatedTexts = try {
					when (engine) {
						TranslationEngine.GROQ -> translateBatchWithGroq(cleanTexts, targetLanguage)
						TranslationEngine.DEEPL -> translateBatchWithDeepL(cleanTexts, targetLanguage)
						else -> null
					}
				} catch (e: Exception) {
					null
				}

				val translationJobs = mergedBlocks.mapIndexed { index, it ->
					async {
						val cleanText = cleanTexts[index]
						if (cleanText.isBlank()) return@async null

						val translatedText = translatedTexts?.getOrNull(index) ?: try {
							if (engine == TranslationEngine.ML_KIT) {
								mlKitTranslator?.translate(cleanText)?.await()
							} else {
								when (engine) {
									TranslationEngine.DEEPL -> translateWithDeepL(cleanText, targetLanguage)
									TranslationEngine.GROQ -> translateWithGroq(cleanText, targetLanguage)
									else -> null
								}
							}
						} catch (e: Exception) {
							null
						}

							if (translatedText.isNullOrBlank()) return@async null

							val bubbleResult = detectBubbleBounds(it.boundingBox!!, ocrBitmap)
							val bubbleRect = bubbleResult.bounds
							
							// Map back to original captured bitmap coordinates
							val rectInOriginalBitmap = RectF(
								bubbleRect.left / ocrScale,
								bubbleRect.top / ocrScale,
								bubbleRect.right / ocrScale,
							bubbleRect.bottom / ocrScale
						)

							// ABSOLUTE IMAGE ANCHORING
							val sourceRect = RectF(
								(rectInOriginalBitmap.left - vTranslateX) / viewScale,
								(rectInOriginalBitmap.top - vTranslateY) / viewScale,
								(rectInOriginalBitmap.right - vTranslateX) / viewScale,
								(rectInOriginalBitmap.bottom - vTranslateY) / viewScale
						)
							
							if (rectInOriginalBitmap.width() > bitmap.width * 0.99f || 
								rectInOriginalBitmap.height() > bitmap.height * 0.99f) return@async null

							val backgroundColor = detectBackgroundColorFromMask(bubbleResult.mask, ocrBitmap)
							val outline = generateOutline(bubbleResult.mask, ocrBitmap.width, bubbleRect)
							
							// PRO AI ERASURE: Generate a diffusion-inpainted patch for seamless look
							val patch = generateInpaintedPatch(ocrBitmap, bubbleResult.mask, ocrBitmap.width, bubbleRect)

							val sourceOutline = outline.map { p: PointF ->
								PointF(
									(p.x / ocrScale - vTranslateX) / viewScale,
									(p.y / ocrScale - vTranslateY) / viewScale
								)
							}

							val isSpiky = detectIfSpiky(bubbleResult.mask, ocrBitmap.width, bubbleRect)

							TranslatedBlock(
								text = translatedText,
								boundingBox = sourceRect,
								backgroundColor = backgroundColor,
							outline = sourceOutline,
							isActionBubble = isSpiky,
						inpaintedPatch = patch
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
				if (recoveryBitmap != ocrBitmap) recoveryBitmap.recycle()
				globalMutex.withLock {
					translationMutexes.remove(pageKey)
				}
			}
			
			result
		}
	}

	private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
		val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
		val width = output.width
		val height = output.height
		val pixels = IntArray(width * height)
		output.getPixels(pixels, 0, width, 0, 0, width, height)

		for (i in pixels.indices) {
			val p = pixels[i]
			val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
			val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
			// Boost contrast: make text darker, backgrounds whiter
			if (lum > 160) pixels[i] = Color.WHITE
			else if (lum < 120) pixels[i] = Color.BLACK
		}
		output.setPixels(pixels, 0, width, 0, 0, width, height)
		return output
	}

	private fun generateInpaintedPatch(bitmap: Bitmap, mask: BitSet, width: Int, bounds: Rect): Bitmap {
		val patchW = bounds.width().coerceAtLeast(1)
		val patchH = bounds.height().coerceAtLeast(1)
		val patch = Bitmap.createBitmap(patchW, patchH, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(patch)
		
		canvas.drawBitmap(bitmap, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), Rect(0, 0, patchW, patchH), null)
		
		val pixels = IntArray(patchW * patchH)
		patch.getPixels(pixels, 0, patchW, 0, 0, patchW, patchH)
		
		val isMasked = BooleanArray(patchW * patchH)
		for (y in 0 until patchH) {
			val my = y + bounds.top
			for (x in 0 until patchW) {
				val mx = x + bounds.left
				if (mask.get(my * width + mx)) {
					isMasked[y * patchW + x] = true
				}
			}
		}
		
		// 2-Pass Diffusion (AI Eraser Simulation)
		repeat(2) {
			for (i in pixels.indices) {
				if (isMasked[i]) {
					val x = i % patchW; val y = i / patchW
					var r = 0; var g = 0; var b = 0; var count = 0
					val neighbors = intArrayOf(-1, 1, -patchW, patchW)
					for (offset in neighbors) {
						val ni = i + offset
						if (ni in pixels.indices) {
							val nx = ni % patchW; val ny = ni / patchW
							if (Math.abs(nx - x) <= 1 && Math.abs(ny - y) <= 1) {
								val p = pixels[ni]
								r += Color.red(p); g += Color.green(p); b += Color.blue(p)
								count++
							}
						}
					}
					if (count > 0) pixels[i] = Color.rgb(r/count, g/count, b/count)
				}
			}
		}
		
		// Monochromatic noise for screentone/grain matching
		val rnd = java.util.Random()
		for (i in pixels.indices) {
			if (isMasked[i]) {
				val p = pixels[i]
				val noise = rnd.nextInt(4) - 2
				pixels[i] = Color.rgb(
					(Color.red(p) + noise).coerceIn(0, 255),
					(Color.green(p) + noise).coerceIn(0, 255),
					(Color.blue(p) + noise).coerceIn(0, 255)
				)
			}
		}
		
		patch.setPixels(pixels, 0, patchW, 0, 0, patchW, patchH)
		return patch
	}

	private suspend fun translateBatchWithDeepL(texts: List<String>, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext null
		val isFree = apiKey.endsWith(":fx")
		val url = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		
		val body = buildJsonObject {
			putJsonArray("text") { texts.forEach { add(it) } }
			put("target_lang", targetLanguage.uppercase())
			put("context", "This is text from a manga/comic page. Maintain natural dialogue flow.")
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
					jsonResult.jsonObject["translations"]?.jsonArray?.map { 
						it.jsonObject["text"]?.jsonPrimitive?.content ?: "" 
					}
				}
			} catch (e: Exception) {
				null
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
					put("content", "You are a professional manga translator. Translate the following Japanese texts into natural $langName. Maintain consistent tone across all texts as they appear on the same page. Return a JSON object with a 'translations' array containing the translated strings in the same order as the input.")
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

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>, bitmap: Bitmap? = null): List<IntermediateBlock> {
		if (blocks.isEmpty()) return emptyList()

		// Sort by Manga reading order: Right-to-Left columns, Top-to-Bottom within columns
		val sorted = blocks.sortedWith { b1, b2 ->
			val r1 = b1.boundingBox ?: return@sortedWith 0
			val r2 = b2.boundingBox ?: return@sortedWith 0
			
			if (Math.abs(r1.centerX() - r2.centerX()) < (r1.width() + r2.width()) / 2) {
				r1.top.compareTo(r2.top)
			} else {
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
				if (areBlocksInSameBubble(m.boundingBox, rect, bitmap)) {
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

	private fun areBlocksInSameBubble(r1: Rect, r2: Rect, bitmap: Bitmap? = null): Boolean {
		val avgHeight = (r1.height() + r2.height()) / 2f
		val avgWidth = (r1.width() + r2.width()) / 2f
		
		val horizontalThreshold = (avgWidth * 1.2f).toInt().coerceAtLeast(40)
		val verticalThreshold = (avgHeight * 1.8f).toInt().coerceAtLeast(70)
		
		val expanded = Rect(r1)
		expanded.inset(-horizontalThreshold, -verticalThreshold)
		
		if (Rect.intersects(expanded, r2)) {
			// Ink Check: If there is a solid line between blocks, separate them
			if (bitmap != null) {
				val cx1 = r1.centerX()
				val cy1 = r1.centerY()
				val cx2 = r2.centerX()
				val cy2 = r2.centerY()
				val steps = 10
				var darkCount = 0
				for (i in 1 until steps) {
					val sx = cx1 + (cx2 - cx1) * i / steps
					val sy = cy1 + (cy2 - cy1) * i / steps
					if (!isPixelLight(bitmap, sx, sy)) {
						darkCount++
					}
				}
				if (darkCount > steps * 0.3) return false
			}
			return true
		}
		return false
	}
	
	private data class BubbleResult(val bounds: Rect, val mask: BitSet)

	private fun generateOutline(mask: BitSet, width: Int, bounds: Rect): List<PointF> {
		val outline = mutableListOf<PointF>()
		val step = 4 
		for (x in bounds.left..bounds.right step step) {
			for (y in bounds.top..bounds.bottom) {
				if (mask.get(y * width + x)) {
					outline.add(PointF(x.toFloat(), y.toFloat()))
					break
				}
			}
		}
		for (y in bounds.top..bounds.bottom step step) {
			for (x in bounds.right downTo bounds.left) {
				if (mask.get(y * width + x)) {
					outline.add(PointF(x.toFloat(), y.toFloat()))
					break
				}
			}
		}
		for (x in bounds.right downTo bounds.left step step) {
			for (y in bounds.bottom downTo bounds.top) {
				if (mask.get(y * width + x)) {
					outline.add(PointF(x.toFloat(), y.toFloat()))
					break
				}
			}
		}
		for (y in bounds.bottom downTo bounds.top step step) {
			for (x in bounds.left..bounds.right) {
				if (mask.get(y * width + x)) {
					outline.add(PointF(x.toFloat(), y.toFloat()))
					break
				}
			}
		}
		return outline
	}

	private fun detectIfSpiky(mask: BitSet, width: Int, bounds: Rect): Boolean {
		val centerX = bounds.centerX()
		val centerY = bounds.centerY()
		val radii = mutableListOf<Double>()
		val step = 10
		for (x in bounds.left..bounds.right step step) {
			for (y in bounds.top..bounds.bottom step step) {
				if (mask.get(y * width + x)) {
					var isEdge = false
					if (x + 1 >= width || x - 1 < 0 || y + 1 >= (mask.size() / width) || y - 1 < 0 ||
						!mask.get(y * width + (x + 1)) || !mask.get(y * width + (x - 1)) ||
						!mask.get((y + 1) * width + x) || !mask.get((y - 1) * width + x)) {
						isEdge = true
					}
					if (isEdge) {
						val dx = (x - centerX).toDouble()
						val dy = (y - centerY).toDouble()
						radii.add(Math.sqrt(dx * dx + dy * dy))
					}
				}
			}
		}
		if (radii.size < 10) return false
		val avg = radii.average()
		val variance = radii.map { Math.abs(it - avg) }.average()
		return (variance / avg) > 0.18
	}

	private fun detectBubbleBounds(textRect: Rect, bitmap: Bitmap): BubbleResult {
		try {
			val width = bitmap.width
			val height = bitmap.height
			if (textRect.left < 0 || textRect.top < 0 || textRect.right > width || textRect.bottom > height) {
				return BubbleResult(textRect, BitSet())
			}
			val inkMask = BitSet(width * height)
			val searchArea = Rect(
				(textRect.left - 400).coerceAtLeast(0),
				(textRect.top - 400).coerceAtLeast(0),
				(textRect.right + 400).coerceAtMost(width - 1),
				(textRect.bottom + 400).coerceAtMost(height - 1)
			)
			for (y in searchArea.top..searchArea.bottom) {
				for (x in searchArea.left..searchArea.right) {
					if (!isPixelLight(bitmap, x, y)) {
						inkMask.set(y * width + x)
						if (x + 1 < width) inkMask.set(y * width + (x + 1))
						if (x - 1 >= 0) inkMask.set(y * width + (x - 1))
						if (y + 1 < height) inkMask.set((y + 1) * width + x)
						if (y - 1 >= 0) inkMask.set((y - 1) * width + x)
					}
				}
			}
			val visited = BitSet(width * height)
			val queue = java.util.ArrayDeque<Int>()
			var minX = textRect.left; var maxX = textRect.right
			var minY = textRect.top; var maxY = textRect.bottom
			val stepX = (textRect.width() / 8).coerceAtLeast(1)
			val stepY = (textRect.height() / 8).coerceAtLeast(1)
			for (x in textRect.left..textRect.right step stepX) {
				for (y in textRect.top..textRect.bottom step stepY) {
					val idx = y * width + x
					if (!visited.get(idx)) {
						visited.set(idx); queue.add(idx)
					}
				}
			}
			val maxPixels = (width * height * 0.20).toInt()
			var processedPixels = 0
			val maxDistX = (textRect.width() * 2.5).toInt().coerceAtLeast(300).coerceAtMost(width / 2)
			val maxDistY = (textRect.height() * 2.5).toInt().coerceAtLeast(300).coerceAtMost(height / 2)
			val dx = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)
			val dy = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
			while (queue.isNotEmpty() && processedPixels < maxPixels) {
				val curr = queue.removeFirst()
				processedPixels++
				val cx = curr % width; val cy = curr / width
				minX = Math.min(minX, cx); maxX = Math.max(maxX, cx)
				minY = Math.min(minY, cy); maxY = Math.max(maxY, cy)
				for (i in 0 until 8) {
					val nx = cx + dx[i]; val ny = cy + dy[i]
					if (nx in 0 until width && ny in 0 until height) {
						val nIdx = ny * width + nx
						if (!visited.get(nIdx)) {
							if (inkMask.get(nIdx)) {
								if (Math.abs(nx - textRect.centerX()) > textRect.width() / 2 ||
									Math.abs(ny - textRect.centerY()) > textRect.height() / 2) {
									continue
								}
							}
							if (Math.abs(nx - textRect.centerX()) > maxDistX || 
								Math.abs(ny - textRect.centerY()) > maxDistY) continue
							visited.set(nIdx); queue.add(nIdx)
						}
					}
				}
			}
			return BubbleResult(Rect(minX, minY, maxX, maxY), visited)
		} catch (e: Exception) {
			return BubbleResult(textRect, BitSet())
		}
	}

	private fun detectBackgroundColorFromMask(mask: BitSet, bitmap: Bitmap): Int {
		if (mask.isEmpty) return Color.WHITE
		val width = bitmap.width
		var r = 0L; var g = 0L; var b = 0L; var count = 0
		val totalSet = mask.cardinality()
		val sampleStep = (totalSet / 500).coerceAtLeast(1)
		var idx = mask.nextSetBit(0)
		var sampled = 0
		while (idx >= 0 && sampled < 500) {
			val pixel = bitmap.getPixel(idx % width, idx / width)
			r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel)
			count++; sampled++
			var next = idx + 1
			repeat(sampleStep - 1) { if (next >= 0) next = mask.nextSetBit(next + 1) }
			idx = if (next >= 0) mask.nextSetBit(next) else -1
		}
		return if (count > 0) Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
		else Color.WHITE
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
		try {
			val pixel = bitmap.getPixel(x, y)
			val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
			val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
			return luminance >= 220 
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
	val backgroundColor: Int = Color.WHITE,
	val outline: List<PointF>? = null,
	val isActionBubble: Boolean = false,
	val inpaintedPatch: Bitmap? = null
)
