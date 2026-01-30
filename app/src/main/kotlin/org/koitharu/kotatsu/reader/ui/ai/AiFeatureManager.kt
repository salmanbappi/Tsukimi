package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiFeatureManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings
) {

	private val textRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
	
	suspend fun translatePage(bitmap: Bitmap, targetLanguage: String = TranslateLanguage.ENGLISH): List<TranslatedBlock> = withContext(Dispatchers.Default) {
		if (!settings.isAiTranslationEnabled) return@withContext emptyList()

		val inputImage = InputImage.fromBitmap(bitmap, 0)
		val visionText = textRecognizer.process(inputImage).await()
		
		val options = TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.JAPANESE)
			.setTargetLanguage(targetLanguage)
			.build()
		
		val translator = Translation.getClient(options)
		translator.downloadModelIfNeeded().await()
		
		val result = mutableListOf<TranslatedBlock>()
		
		val textBlocks = visionText.textBlocks
		val mergedBlocks = mergeNearbyBlocks(textBlocks)

		for (block in mergedBlocks) {
			val translatedText = translator.translate(block.text.toString()).await()
			result.add(
				TranslatedBlock(
					text = translatedText,
					boundingBox = block.boundingBox
				)
			)
		}
		
		result
	}

	private fun mergeNearbyBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): List<MergedText> {
		if (blocks.isEmpty()) return emptyList()

		// Sort blocks top-down, then left-to-right to process in reading order
		val sorted = blocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
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
					// Add a newline for separation if needed, or space
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
		// Calculate a threshold based on the block sizes (e.g., 50% of the average line height)
		// This allows merging lines within a bubble but keeping separate bubbles distinct
		val avgHeight = (r1.height() + r2.height()) / 2f
		val threshold = (avgHeight * 0.5f).toInt().coerceAtLeast(10)

		// Expand r1 by threshold and check for intersection
		val expanded = Rect(r1)
		expanded.inset(-threshold, -threshold)
		
		return Rect.intersects(expanded, r2)
	}

	private data class MergedText(
		val text: StringBuilder,
		val boundingBox: Rect
	)

	// Super-resolution placeholder
	fun upscaleImage(bitmap: Bitmap): Bitmap {
		if (!settings.isAiUpscalingEnabled) return bitmap
		// Implementation would go here using TFLite ESRGAN model
		return bitmap
	}
}

data class TranslatedBlock(
	val text: String,
	val boundingBox: Rect
)
