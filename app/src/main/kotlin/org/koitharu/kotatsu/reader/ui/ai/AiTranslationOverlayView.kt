package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
		color = Color.BLACK
		isAntiAlias = true
		typeface = Typeface.DEFAULT_BOLD
	}
	
	private val strokePaint = TextPaint().apply {
		color = Color.WHITE
		style = Paint.Style.STROKE
		strokeWidth = 8f
		isAntiAlias = true
		typeface = Typeface.DEFAULT_BOLD
		strokeJoin = Paint.Join.ROUND
	}

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		blocks = newBlocks
		invalidate()
	}

	// Dynamic scaling simplified: bubbles are stored as percentages of the parent view.
	// This makes them immune to library-internal state bugs.
	fun setupWithSSIV(ssiv: View) {
		// Just a placeholder to maintain interface compatibility
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val viewWidth = width.toFloat()
		val viewHeight = height.toFloat()

		for (block in blocks) {
			val pctRect = block.boundingBox
			val text = block.text

			// Convert percentages back to actual view pixels
			val viewLeft = pctRect.left * viewWidth
			val viewTop = pctRect.top * viewHeight
			val viewRight = pctRect.right * viewWidth
			val viewBottom = pctRect.bottom * viewHeight
			
			val currentWidth = viewRight - viewLeft
			val currentHeight = viewBottom - viewTop

			if (currentWidth <= 0 || currentHeight <= 0 || text.isBlank()) continue

			// Draw background bubble
			val cornerRadius = (currentWidth.coerceAtMost(currentHeight) * 0.4f).coerceAtMost(60f)
			canvas.drawRoundRect(
				viewLeft,
				viewTop,
				viewRight,
				viewBottom,
				cornerRadius,
				cornerRadius,
				backgroundPaint
			)

			// Calculate padding
			val paddingX = (currentWidth * 0.12f).coerceAtLeast(8f)
			val paddingY = (currentHeight * 0.12f).coerceAtLeast(8f)
			val availableWidth = (currentWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (currentHeight - 2 * paddingY).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))

			// Auto-sizing Logic
			var textSize = 60f 
			val minTextSize = 10f
			val step = 1f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f
			var finalTextSize = minTextSize

			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize
				
				val maxWordWidth = words.maxOfOrNull { paint.measureText(it) } ?: 0f
				if (maxWordWidth > availableWidth && textSize > minTextSize) {
					textSize -= step
					continue
				}

				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 1.0f)
					.setIncludePad(false)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
					.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

				val layout = builder.build()

				if (layout.height <= availableHeight) {
					finalLayout = layout
					finalTextSize = textSize
					finalYOffset = (availableHeight - layout.height) / 2f
					break
				}

				textSize -= step
			}

			if (finalLayout == null) {
				paint.textSize = minTextSize
				finalLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
					.build()
				finalTextSize = minTextSize
				finalYOffset = max(0f, (availableHeight - finalLayout.height) / 2f)
			}

			canvas.save()
			canvas.translate(viewLeft + paddingX, viewTop + paddingY + finalYOffset)
			
			val workPaint = finalLayout.paint
			workPaint.set(strokePaint)
			workPaint.textSize = finalTextSize
			finalLayout.draw(canvas)
			
			workPaint.set(baseTextPaint)
			workPaint.textSize = finalTextSize
			finalLayout.draw(canvas)
			
			canvas.restore()
		}
	}
}
