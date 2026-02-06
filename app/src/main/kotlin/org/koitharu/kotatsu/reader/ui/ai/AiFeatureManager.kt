package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
	private val recognizers = mutableMapOf<String, com.google.mlkit.vision.text.TextRecognizer>()
	private val langIdentifier = LanguageIdentification.getClient()
	
	private val translationCache = LruCache<String, List<TranslatedBlock>>(50)
	private val translationMutexes = mutableMapOf<String, Mutex>()
	private val globalMutex = Mutex()

	private fun getRecognizer(lang: String): com.google.mlkit.vision.text.TextRecognizer {
		return recognizers.getOrPut(lang) {
			when (lang) {
				TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
				TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
				TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
				else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
			}
		}
	}

	fun isCached(pageKey: String): Boolean = translationCache.get(pageKey) != null
	
	fun getFromCache(pageKey: String): List<TranslatedBlock>? = translationCache.get(pageKey)

	suspend fun translatePage(
		pageKey: String,
		bitmap: Bitmap,
		viewScale: Float,
		vTranslateX: Float,
		vTranslateY: Float,
		targetLanguage: String? = null
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
			val maxDim = 1200 
			val ocrScale = if (bitmap.width > 0 && bitmap.height > 0) {
				Math.min(1f, maxDim.toFloat() / Math.max(bitmap.width, bitmap.height))
			} else 1f
			
			val ocrBitmap = if (ocrScale < 1f) {
				val targetW = (bitmap.width * ocrScale).toInt().coerceAtLeast(1)
				val targetH = (bitmap.height * ocrScale).toInt().coerceAtLeast(1)
				Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
			} else bitmap

			val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
			
			val preferredSource = settings.aiTranslationSourceLanguage
			val targetLang = targetLanguage ?: settings.aiTranslationTargetLanguage
			
			val initialRecognizer = if (preferredSource == "auto") getRecognizer(TranslateLanguage.JAPANESE) else getRecognizer(preferredSource)
			val visionText = initialRecognizer.process(inputImage).await()
			
			val sourceLang = if (preferredSource == "auto") {
				val detected = langIdentifier.identifyLanguage(visionText.text).await()
				if (detected == "und") TranslateLanguage.JAPANESE else detected
			} else preferredSource

			val engine = settings.aiTranslationEngine
			
			val mlKitTranslator = if (engine == TranslationEngine.ML_KIT) {
				val options = TranslatorOptions.Builder()
					.setSourceLanguage(sourceLang)
					.setTargetLanguage(targetLang)
					.build()
				val mlKit = Translation.getClient(options)
				mlKit.downloadModelIfNeeded().await()
				mlKit
			} else null

			val result = mutableListOf<TranslatedBlock>()
			
			try {
				val textBlocks = visionText.textBlocks
				val mergedBlocks = mergeNearbyBlocks(textBlocks, sourceLang == TranslateLanguage.JAPANESE)

				val translatedTexts = if (engine == TranslationEngine.GROQ && mergedBlocks.size > 1) {
					translateBatchWithGroq(mergedBlocks.map { it.text.toString().replace(Regex("[\\n\\s]+"), "") }, sourceLang, targetLang)
				} else null

				val translationJobs = mergedBlocks.mapIndexed {
					index, it ->
					async {
						val cleanText = it.text.toString().replace(Regex("[\\n\\s]+"), "")
						if (cleanText.isBlank()) return@async null

						val translatedText = translatedTexts?.getOrNull(index) ?: try {
							when (engine) {
								TranslationEngine.ML_KIT -> mlKitTranslator?.translate(cleanText)?.await()
								TranslationEngine.DEEPL -> translateWithDeepL(cleanText, sourceLang, targetLang)
								TranslationEngine.GROQ -> translateWithGroq(cleanText, sourceLang, targetLang)
							}
						} catch (e: Exception) {
							null
						}

						if (translatedText.isNullOrBlank()) return@async null

						val bubbleRect = detectBubbleBounds(it.boundingBox, ocrBitmap)
						
						val rectInOriginalBitmap = RectF(
							bubbleRect.left / ocrScale,
							bubbleRect.top / ocrScale,
							bubbleRect.right / ocrScale,
							bubbleRect.bottom / ocrScale
						)

						val sourceRect = RectF(
							(rectInOriginalBitmap.left - vTranslateX) / viewScale,
							(rectInOriginalBitmap.top - vTranslateY) / viewScale,
							(rectInOriginalBitmap.right - vTranslateX) / viewScale,
							(rectInOriginalBitmap.bottom - vTranslateY) / viewScale
						)
						
						if (rectInOriginalBitmap.width() > bitmap.width * 0.99f || 
							rectInOriginalBitmap.height() > bitmap.height * 0.99f) return@async null

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

	private suspend fun translateBatchWithGroq(texts: List<String>, sourceLanguage: String, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val srcName = getLanguageName(sourceLanguage)
		val targetName = getLanguageName(targetLanguage)
		
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
					put("content", "You are a professional manga translator. Translate the following $srcName texts into natural $targetName. Maintain consistent tone across all texts. Return a JSON object with a 'translations' array containing the translated strings in the same order as the input.")
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

	private suspend fun translateWithDeepL(text: String, sourceLanguage: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext null
		val isFree = apiKey.endsWith(":fx")
		val url = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
		
		val body = buildJsonObject {
			putJsonArray("text") { add(text) }
			put("source_lang", sourceLanguage.uppercase())
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

	private suspend fun translateWithGroq(text: String, sourceLanguage: String, targetLanguage: String): String? = withContext(Dispatchers.IO) {
		val apiKey = settings.groqApiKey ?: return@withContext null
		val srcName = getLanguageName(sourceLanguage)
		val targetName = getLanguageName(targetLanguage)
		
		val body = buildJsonObject {
			put("model", "llama-3.1-8b-instant")
			putJsonArray("messages") {
				add(buildJsonObject {
					put("role", "system")
					put("content", "You are a professional manga translator. Translate the following $srcName text to natural $targetName. Keep it concise and preserve the tone. Only return the translated text.")
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

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>, isJapanese: Boolean): List<IntermediateBlock> {
		if (blocks.isEmpty()) return emptyList()

		val sorted = if (isJapanese) {
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
				if (areBlocksClose(m.boundingBox, rect)) {
					m.text.append("\n").append(text)
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

	private fun areBlocksClose(r1: Rect, r2: Rect): Boolean {
		val avgHeight = (r1.height() + r2.height()) / 2f
		val threshold = (avgHeight * 0.5f).toInt().coerceAtLeast(10)
		val expanded = Rect(r1)
		expanded.inset(-threshold, -threshold)
		return Rect.intersects(expanded, r2)
	}
	
	private fun detectBubbleBounds(textRect: Rect, bitmap: Bitmap): Rect {
		try {
			val width = bitmap.width
			val height = bitmap.height
			val centerX = textRect.centerX()
			val centerY = textRect.centerY()

			if (centerX !in 0 until width || centerY !in 0 until height) return textRect

			val maxExpandX = (textRect.width().toDouble() * 0.4).coerceAtMost((width * 0.15).toDouble()).coerceAtLeast(30.0).toInt()
			val maxExpandY = (textRect.height().toDouble() * 0.4).coerceAtMost((height * 0.15).toDouble()).coerceAtLeast(30.0).toInt()

			var left = textRect.left
			var dist = 0
			while (left > 0 && dist < maxExpandX && isPixelLight(bitmap, left, centerY)) {
				left--
				dist++
			}

			var right = textRect.right
			dist = 0
			while (right < width - 1 && dist < maxExpandX && isPixelLight(bitmap, right, centerY)) {
				right++
				dist++
			}

			var top = textRect.top
			dist = 0
			while (top > 0 && dist < maxExpandY && isPixelLight(bitmap, centerX, top)) {
				top--
				dist++
			}

			var bottom = textRect.bottom
			dist = 0
			while (bottom < height - 1 && dist < maxExpandY && isPixelLight(bitmap, centerX, bottom)) {
				bottom++
				dist++
			}

			return Rect(left, top, right, bottom)
		} catch (e: Exception) {
			return textRect
		}
	}

	private fun detectBackgroundColor(rect: Rect, bitmap: Bitmap): Int {
		val samples = mutableListOf<Int>()
		val startX = (rect.left + rect.width() * 0.1).toInt()
		val endX = (rect.right - rect.width() * 0.1).toInt()
		val startY = (rect.top + rect.height() * 0.1).toInt()
		val endY = (rect.bottom - rect.height() * 0.1).toInt()
		
		try {
			samples.add(bitmap.getPixel(rect.centerX(), rect.centerY()))
			samples.add(bitmap.getPixel(startX, startY))
			samples.add(bitmap.getPixel(endX, startY))
			samples.add(bitmap.getPixel(startX, endY))
			samples.add(bitmap.getPixel(endX, endY))
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
		
		return Color.rgb(r, g, b)
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		try {
			val pixel = bitmap.getPixel(x, y)
			val red = Color.red(pixel)
			val green = Color.green(pixel)
			val blue = Color.blue(pixel)
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
	val backgroundColor: Int = Color.WHITE
)
