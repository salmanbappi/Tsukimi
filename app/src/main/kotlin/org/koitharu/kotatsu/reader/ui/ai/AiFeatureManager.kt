package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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

@Singleton
class AiFeatureManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	@BaseHttpClient private val client: OkHttpClient
) {

	private val json = Json { ignoreUnknownKeys = true }
	private val textRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
	
	suspend fun translatePage(bitmap: Bitmap, targetLanguage: String = TranslateLanguage.ENGLISH): List<TranslatedBlock> = withContext(Dispatchers.Default) {
		if (!settings.isAiTranslationEnabled) return@withContext emptyList()

		val inputImage = InputImage.fromBitmap(bitmap, 0)
		val visionText = textRecognizer.process(inputImage).await()
		
		val engine = settings.aiTranslationEngine
		
		// Setup ML Kit translator if selected
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

			// Process all blocks in parallel for "Premium" speed and responsiveness
			val translationJobs = mergedBlocks.map {
				async {
					val cleanText = it.text.toString().replace(Regex("[\\n\\s]+"), "")
					if (cleanText.isBlank()) return@async null

					val translatedText = try {
						when (engine) {
							TranslationEngine.ML_KIT -> mlKitTranslator?.translate(cleanText)?.await() ?: "Error"
							TranslationEngine.DEEPL -> translateWithDeepL(cleanText, targetLanguage)
							TranslationEngine.OPENAI -> translateWithOpenAI(cleanText, targetLanguage)
						}
					} catch (e: Exception) {
						"Error"
					}

					// Try to expand the bounding box to the speech bubble borders
					val bubbleRect = detectBubbleBounds(it.boundingBox, bitmap)
					
					TranslatedBlock(
						text = translatedText,
						boundingBox = bubbleRect
					)
				}
			}
			
			result.addAll(translationJobs.awaitAll().filterNotNull())
		} finally {
			mlKitTranslator?.close()
		}
		
		result
	}

	private suspend fun translateWithDeepL(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
		val apiKey = settings.deeplApiKey ?: return@withContext "Error: Missing API Key"
		// DeepL Free API uses a different domain than Pro
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
				if (!response.isSuccessful) return@withContext "Error: ${response.code}"
				val jsonResult = json.parseToJsonElement(response.body?.string() ?: "")
				jsonResult.jsonObject["translations"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Error"
			}
		} catch (e: Exception) {
			"Error"
		}
	}

	private suspend fun translateWithOpenAI(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
		val apiKey = settings.openaiApiKey ?: return@withContext "Error: Missing API Key"
		val langName = getLanguageName(targetLanguage)
		
		val body = buildJsonObject {
			put("model", "gpt-4o-mini")
			putJsonArray("messages") {
				add(buildJsonObject {
					put("role", "system")
					put("content", "You are a professional manga translator. Translate the following Japanese text to natural $langName. Keep it concise, preserve the tone, and ensure it fits well in a speech bubble. Only return the translated text.")
				})
				add(buildJsonObject {
					put("role", "user")
					put("content", text)
				})
			}
		}
		
		val request = Request.Builder()
			.url("https://api.openai.com/v1/chat/completions")
			.addHeader("Authorization", "Bearer $apiKey")
			.post(body.toString().toRequestBody("application/json".toMediaType()))
			.build()
			
		try {
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext "Error: ${response.code}"
				val jsonResult = json.parseToJsonElement(response.body?.string() ?: "")
				jsonResult.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim() ?: "Error"
			}
		} catch (e: Exception) {
			"Error"
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

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): List<MergedText> {
		if (blocks.isEmpty()) return emptyList()

		// Sort blocks Right-to-Left (primary) then Top-to-Bottom (secondary)
		// This better matches standard Japanese vertical text layout
		val sorted = blocks.sortedWith(
			compareByDescending<com.google.mlkit.vision.text.Text.TextBlock> { it.boundingBox?.right ?: 0 }
				.thenBy { it.boundingBox?.top ?: 0 }
		)
		val merged = mutableListOf<MergedText>()

		for (block in sorted) {
			val rect = block.boundingBox ?: continue
			val text = block.text

			var isMerged = false
			// Check if this block is close to any existing merged block
			// We iterate backwards as the most likely merge candidate is the last one
			for (i in merged.indices.reversed()) {
				val m = merged[i]
				if (areBlocksClose(m.boundingBox, rect)) {
					// Merge into existing block
					m.text.append("\n").append(text)
					m.boundingBox.union(rect)
					isMerged = true
					break
				}
			}

			if (!isMerged) {
				merged.add(MergedText(StringBuilder(text), Rect(rect)))
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
		val width = bitmap.width
		val height = bitmap.height
		val centerX = textRect.centerX()
		val centerY = textRect.centerY()

		if (centerX !in 0 until width || centerY !in 0 until height) return textRect

		val maxExpandX = (textRect.width() * 2).coerceAtLeast(200)
		val maxExpandY = (textRect.height() * 2).coerceAtLeast(200)

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
	}

	private fun isPixelLight(bitmap: Bitmap, x: Int, y: Int): Boolean {
		val pixel = bitmap.getPixel(x, y)
		val red = Color.red(pixel)
		val green = Color.green(pixel)
		val blue = Color.blue(pixel)
		val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
		return luminance >= 180
	}

	private data class MergedText(
		val text: StringBuilder,
		val boundingBox: Rect
	)

	fun upscaleImage(bitmap: Bitmap): Bitmap {
		if (!settings.isAiUpscalingEnabled) return bitmap
		return bitmap
	}
}

data class TranslatedBlock(
	val text: String,
	val boundingBox: Rect
)