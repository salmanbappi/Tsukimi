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
	}
	// Initial text paint configuration
	private val baseTextPaint = TextPaint().apply {
		color = Color.BLACK
		isAntiAlias = true
		typeface = Typeface.SANS_SERIF
	}

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		blocks = newBlocks
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		for (block in blocks) {
			val rect = block.boundingBox
			val text = block.text

			// Draw background bubble (white, solid)
			// Using slightly larger rect for background to ensure coverage
			canvas.drawRect(rect, backgroundPaint)

			if (rect.width() <= 0 || rect.height() <= 0 || text.isBlank()) continue

			// Auto-sizing Logic
			var textSize = 60f // Start large
			val minTextSize = 12f
			val step = 2f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f

			// Create a working paint for this block
			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize

				// Build layout with high quality breaking
				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, rect.width())
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 1.0f)
					.setIncludePad(false)
					.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
					.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE) // Prevent word splitting

				val layout = builder.build()

				// Check if it fits vertically
				if (layout.height <= rect.height()) {
					finalLayout = layout
					// Calculate vertical center offset
					finalYOffset = (rect.height() - layout.height) / 2f
					break
				}

				textSize -= step
			}

			// If even the smallest size didn't fit perfect (rare), use the last valid layout or min size
			if (finalLayout == null) {
				paint.textSize = minTextSize
				finalLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, rect.width())
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
					.build()
				finalYOffset = max(0f, (rect.height() - finalLayout.height) / 2f)
			}

			// Draw text
			canvas.save()
			canvas.translate(rect.left.toFloat(), rect.top.toFloat() + finalYOffset)
			finalLayout.draw(canvas)
			canvas.restore()
		}
	}
}
