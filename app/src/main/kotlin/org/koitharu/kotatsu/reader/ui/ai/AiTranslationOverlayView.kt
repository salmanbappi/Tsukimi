package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.max

class AiTranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var blocks: List<TranslatedBlock> = emptyList()
	private var ssiv: SubsamplingScaleImageView? = null
	
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

	fun setupWithSSIV(ssiv: SubsamplingScaleImageView) {
		this.ssiv = ssiv
		ssiv.viewTreeObserver.addOnScrollChangedListener { invalidate() }
		ssiv.viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = this.ssiv ?: return
		if (!ssiv.isReady) return

		for (block in blocks) {
			val sourceRect = block.boundingBox
			val text = block.text

			// Standard public way to map source to view coordinates
			val tl = ssiv.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: continue
			val br = ssiv.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: continue
			
			val viewLeft = tl.x
			val viewTop = tl.y
			val viewRight = br.x
			val viewBottom = br.y
			
			val viewWidth = viewRight - viewLeft
			val viewHeight = viewBottom - viewTop

			if (viewWidth <= 0 || viewHeight <= 0 || text.isBlank()) continue

			// Draw background bubble (rounded)
			val cornerRadius = (viewWidth.coerceAtMost(viewHeight) * 0.4f).coerceAtMost(60f)
			canvas.drawRoundRect(
				viewLeft,
				viewTop,
				viewRight,
				viewBottom,
				cornerRadius,
				cornerRadius,
				backgroundPaint
			)

			// Calculate padding (12% of dimension, min 8px)
			val paddingX = (viewWidth * 0.12f).coerceAtLeast(8f)
			val paddingY = (viewHeight * 0.12f).coerceAtLeast(8f)
			val availableWidth = (viewWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (viewHeight - 2 * paddingY).toInt().coerceAtLeast(1)

			// Pre-split words to ensure no word is broken mid-way
			val words = text.split(Regex("\\s+"))

			// Auto-sizing Logic
			var textSize = 60f * (ssiv.scale / 1.5f).coerceAtLeast(0.5f)
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