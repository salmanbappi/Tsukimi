package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AiTranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var blocks: List<TranslatedBlock> = emptyList()
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		style = Paint.Style.FILL
		isAntiAlias = true
	}
	
	private val baseTextPaint = TextPaint().apply {
// ... (omitting lines for brevity in instruction, but will include in new_string)
	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		for (block in blocks) {
			val rect = block.boundingBox
			val text = block.text

			// Draw background bubble (rounded for a premium speech bubble feel)
			val cornerRadius = (rect.width().coerceAtMost(rect.height()) * 0.4f).coerceAtMost(60f)
			canvas.drawRoundRect(
				rect.left.toFloat(),
				rect.top.toFloat(),
				rect.right.toFloat(),
				rect.bottom.toFloat(),
				cornerRadius,
				cornerRadius,
				backgroundPaint
			)

			if (rect.width() <= 0 || rect.height() <= 0 || text.isBlank()) continue

			// Calculate padding (12% of dimension, min 8px)
			val paddingX = (rect.width() * 0.12f).toInt().coerceAtLeast(8)
			val paddingY = (rect.height() * 0.12f).toInt().coerceAtLeast(8)
			val availableWidth = (rect.width() - 2 * paddingX).coerceAtLeast(1)
			val availableHeight = (rect.height() - 2 * paddingY).coerceAtLeast(1)

			// Pre-split words to ensure no word is broken mid-way
			val words = text.split(Regex("\\s+"))

			// Auto-sizing Logic
			var textSize = 60f // Start large
			val minTextSize = 10f
			val step = 1f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f
			var finalTextSize = minTextSize

			// Create a working paint for layout calculation
			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize
				
				// Ensure the longest word fits horizontally without breaking
				val maxWordWidth = words.maxOfOrNull { paint.measureText(it) } ?: 0f
				if (maxWordWidth > availableWidth && textSize > minTextSize) {
					textSize -= step
					continue
				}

				// Build layout with balanced strategy (better for speech bubbles)
				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 1.0f)
					.setIncludePad(false)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
					.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

				val layout = builder.build()

				// Check if it fits vertically
				if (layout.height <= availableHeight) {
					finalLayout = layout
					finalTextSize = textSize
					// Calculate vertical center offset within the padded area
					finalYOffset = (availableHeight - layout.height) / 2f
					break
				}

				textSize -= step
			}

			// Fallback if no size fits
			if (finalLayout == null) {
				paint.textSize = minTextSize
				finalLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
					.build()
				finalTextSize = minTextSize
				finalYOffset = max(0f, (availableHeight - finalLayout.height) / 2f)
			}

			// Draw text
			canvas.save()
			// Translate to padded position
			canvas.translate((rect.left + paddingX).toFloat(), (rect.top + paddingY).toFloat() + finalYOffset)
			
			// Draw Stroke
			val workPaint = finalLayout.paint
			workPaint.set(strokePaint)
			workPaint.textSize = finalTextSize
			finalLayout.draw(canvas)
			
			// Draw Fill
			workPaint.set(baseTextPaint)
			workPaint.textSize = finalTextSize
			finalLayout.draw(canvas)
			
			canvas.restore()
		}
	}
}
