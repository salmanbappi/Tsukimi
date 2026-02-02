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
			translationCache.get(pageKey)?.let { return@withLock it }

			// SPEED: Single pass high-res OCR
			val maxDim = 1600
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
				// SPEED: Smart ink-aware merging
				val mergedBlocks = mergeNearbyBlocks(textBlocks, ocrBitmap)

				val cleanTexts = mergedBlocks.map { it.text.toString().replace(Regex("[\\n\\s]+"), " ").trim() }
				if (cleanTexts.isNotEmpty()) {
					val translatedTexts = try {
						when (engine) {
							TranslationEngine.GROQ -> translateBatchWithGroq(cleanTexts, targetLanguage)
							TranslationEngine.DEEPL -> translateBatchWithDeepL(cleanTexts, targetLanguage)
							else -> null
						}
					} catch (e: Exception) { null }

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
							} catch (e: Exception) { null }

								if (translatedText.isNullOrBlank()) return@async null

							// STABILITY: Use refined box expansion that stops at ink barriers
							val finalBox = expandToBubble(it.boundingBox!!, ocrBitmap)
							
							val rectInOriginalBitmap = RectF(
								finalBox.left / ocrScale,
								finalBox.top / ocrScale,
								finalBox.right / ocrScale,
								finalBox.bottom / ocrScale
							)

							val sourceRect = RectF(
								(rectInOriginalBitmap.left - vTranslateX) / viewScale,
								(rectInOriginalBitmap.top - vTranslateY) / viewScale,
								(rectInOriginalBitmap.right - vTranslateX) / viewScale,
								(rectInOriginalBitmap.bottom - vTranslateY) / viewScale
						)
							
							if (rectInOriginalBitmap.width() > bitmap.width * 0.99f || 
								rectInOriginalBitmap.height() > bitmap.height * 0.99f) return@async null

							val backgroundColor = detectBackgroundColor(finalBox, ocrBitmap)

							TranslatedBlock(
								text = translatedText,
								boundingBox = sourceRect,
								backgroundColor = backgroundColor
						)
						}
					}
					result.addAll(translationJobs.awaitAll().filterNotNull())
				}
				
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

	private fun expandToBubble(textRect: Rect, bitmap: Bitmap): Rect {
		val width = bitmap.width
		val height = bitmap.height
		var left = textRect.left; var right = textRect.right
		var top = textRect.top; var bottom = textRect.bottom
		
		// Stop at ink lines or max 15% expansion
		val limitX = (width * 0.15f).toInt()
		val limitY = (height * 0.15f).toInt()
		
		while (left > 0 && (textRect.left - left) < limitX && isPixelLight(bitmap, left - 1, textRect.centerY())) left--
		while (right < width - 1 && (right - textRect.right) < limitX && isPixelLight(bitmap, right + 1, textRect.centerY())) right++
		while (top > 0 && (textRect.top - top) < limitY && isPixelLight(bitmap, textRect.centerX(), top - 1)) top--
		while (bottom < height - 1 && (bottom - textRect.bottom) < limitY && isPixelLight(bitmap, textRect.centerX(), bottom + 1)) bottom++
		
		return Rect(left, top, right, bottom)
	}

	private fun detectBackgroundColor(rect: Rect, bitmap: Bitmap): Int {
		val cx = rect.centerX(); val cy = rect.centerY()
		// Sample a few points inside the expanded box but away from text
		val sampleX = (rect.left + 5).coerceAtMost(bitmap.width - 1)
		val sampleY = (rect.top + 5).coerceAtMost(bitmap.height - 1)
		return bitmap.getPixel(sampleX, sampleY)
	}

	private suspend fun translateBatchWithDeepL(texts: List<String>, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext null
		val isFree = apiKey.endsWith(":fx")
		val url = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		val body = buildJsonObject {
			putJsonArray("text") { texts.forEach { add(it) } }
			put("target_lang", targetLanguage.uppercase())
		}
		val request = Request.Builder().url(url).addHeader("Authorization", "DeepL-Auth-Key $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType())).build()
		try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				json.parseToJsonElement(response.body?.string() ?: "").jsonObject["translations"]?.jsonArray?.map { 
					it.jsonObject["text"]?.jsonPrimitive?.content ?: "" 
				}
			}
		} catch (e: Exception) { null }
	}

	private suspend fun translateBatchWithGroq(texts: List<String>, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val langName = getLanguageName(targetLanguage)
		val input = buildJsonObject { putJsonArray("texts") { texts.forEach { add(it) } } }
		val body = buildJsonObject {
			put("model", "llama-3.1-8b-instant")
			put("response_format", buildJsonObject { put("type", "json_object") })
			putJsonArray("messages") {
				add(buildJsonObject { put("role", "system"); put("content", "Professional manga translator.") })
				add(buildJsonObject { put("role", "user"); put("content", input.toString()) })
			}
		}
		val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").addHeader("Authorization", "Bearer $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType())).build()
		try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				val content = json.parseToJsonElement(response.body?.string() ?: "").jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return@withContext null
				json.parseToJsonElement(content).jsonObject["translations"]?.jsonArray?.map { it.jsonPrimitive.content }
			}
		} catch (e: Exception) { null }
	}

	private suspend fun translateWithDeepL(text: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext null
		val isFree = apiKey.endsWith(":fx")
		val url = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		val body = buildJsonObject { putJsonArray("text") { add(text) }; put("target_lang", targetLanguage.uppercase()) }
		val request = Request.Builder().url(url).addHeader("Authorization", "DeepL-Auth-Key $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType())).build()
		try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				json.parseToJsonElement(response.body?.string() ?: "").jsonObject["translations"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
			}
		} catch (e: Exception) { null }
	}

	private suspend fun translateWithGroq(text: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val langName = getLanguageName(targetLanguage)
		val body = buildJsonObject {
			put("model", "llama-3.1-8b-instant")
			putJsonArray("messages") {
				add(buildJsonObject { put("role", "system"); put("content", "Professional manga translator.") })
				add(buildJsonObject { put("role", "user"); put("content", text) })
			}
		}
		val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions").addHeader("Authorization", "Bearer $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType())).build()
		try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				json.parseToJsonElement(response.body?.string() ?: "").jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
			}
		} catch (e: Exception) { null }
	}

	private fun getLanguageName(code: String): String = when (code.lowercase()) {
		"en" -> "English"; "ja" -> "Japanese"; else -> code
	}

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>, bitmap: Bitmap): List<IntermediateBlock> {
		if (blocks.isEmpty()) return emptyList()
		val sorted = blocks.sortedWith { b1, b2 ->
			val r1 = b1.boundingBox ?: return@sortedWith 0; val r2 = b2.boundingBox ?: return@sortedWith 0
			if (Math.abs(r1.centerX() - r2.centerX()) < (r1.width() + r2.width()) / 2) r1.top.compareTo(r2.top)
			else r2.centerX().compareTo(r1.centerX())
		}
		val merged = mutableListOf<IntermediateBlock>()
		for (block in sorted) {
			val rect = block.boundingBox ?: continue
			var isMerged = false
			for (i in merged.indices.reversed()) {
				val m = merged[i]
				if (areBlocksInSameBubble(m.boundingBox, rect, bitmap)) {
					if (m.text.isNotEmpty() && !m.text.endsWith("\n")) m.text.append("\n")
					m.text.append(block.text); m.boundingBox.union(rect); isMerged = true; break
				}
			}
			if (!isMerged) merged.add(IntermediateBlock(StringBuilder(block.text), Rect(rect)))
		}
		return merged
	}

	private fun areBlocksInSameBubble(r1: Rect, r2: Rect, bitmap: Bitmap): Boolean {
		val avgHeight = (r1.height() + r2.height()) / 2f; val avgWidth = (r1.width() + r2.width()) / 2f
		val hT = (avgWidth * 1.0f).toInt().coerceAtLeast(30); val vT = (avgHeight * 1.5f).toInt().coerceAtLeast(50)
		val expanded = Rect(r1); expanded.inset(-hT, -vT)
		if (Rect.intersects(expanded, r2)) {
			val cx1 = r1.centerX(); val cy1 = r1.centerY(); val cx2 = r2.centerX(); val cy2 = r2.centerY()
			val steps = 10; var darkCount = 0
			for (i in 1 until steps) {
				if (!isPixelLight(bitmap, cx1 + (cx2 - cx1) * i / steps, cy1 + (cy2 - cy1) * i / steps)) darkCount++
			}
			return darkCount <= steps * 0.3
		}
		return false
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
		try {
			val pixel = bitmap.getPixel(x, y)
			val luminance = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
			return luminance >= 220 
		} catch (e: Exception) { return false }
	}

	private data class IntermediateBlock(val text: StringBuilder, val boundingBox: Rect)
}

data class TranslatedBlock(
	val text: String,
	val boundingBox: RectF,
	val backgroundColor: Int = Color.WHITE
)