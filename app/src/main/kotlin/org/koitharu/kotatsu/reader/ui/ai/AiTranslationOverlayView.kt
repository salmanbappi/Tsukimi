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
	
	companion object {
		private const val REFERENCE_SCALE = 1.5f
	}
	
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 240 // 94% opaque for a premium feel
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

	// Cache layouts to eliminate lag in onDraw
	private data class PreparedBlock(
		val sourceRect: RectF,
		val layout: StaticLayout,
		val textSize: Float,
		val paddingX: Float,
		val paddingY: Float,
		val yOffset: Float
	)
	private var preparedBlocks = mutableListOf<PreparedBlock>()

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		blocks = newBlocks
		prepareLayouts()
		invalidate()
	}

	fun setupWithSSIV(ssiv: SubsamplingScaleImageView) {
		this.ssiv = ssiv
	}

	private fun prepareLayouts() {
		preparedBlocks.clear()
		
		for (block in blocks) {
			val text = block.text
			if (text.isBlank()) continue
			
			// Reference width for typesetting in source pixels
			val refWidth = block.boundingBox.width() * REFERENCE_SCALE
			val refHeight = block.boundingBox.height() * REFERENCE_SCALE
			
			if (refWidth <= 0 || refHeight <= 0) continue

			val paddingX = refWidth * 0.12f
			val paddingY = refHeight * 0.12f
			val availableWidth = (refWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (refHeight - 2 * paddingY).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))
			var textSize = 40f * REFERENCE_SCALE
			val minTextSize = 8f * REFERENCE_SCALE
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

			preparedBlocks.add(PreparedBlock(
				sourceRect = block.boundingBox,
				layout = finalLayout,
				textSize = finalTextSize,
				paddingX = paddingX / REFERENCE_SCALE,
				paddingY = paddingY / REFERENCE_SCALE,
				yOffset = finalYOffset / REFERENCE_SCALE
			))
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = this.ssiv ?: return
		if (!ssiv.isReady || preparedBlocks.isEmpty()) return

		val currentScale = ssiv.scale

		for (prep in preparedBlocks) {
			val sourceRect = prep.sourceRect
			
			// Map source coordinates to current screen pixels accurately
			val tl = ssiv.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: continue
			val br = ssiv.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: continue
			
			val viewLeft = tl.x
			val viewTop = tl.y
			val viewRight = br.x
			val viewBottom = br.y
			
			val viewWidth = viewRight - viewLeft
			val viewHeight = viewBottom - viewTop

			// Draw rounded background bubble
			val cornerRadius = (viewWidth.coerceAtMost(viewHeight) * 0.4f).coerceAtMost(60f)
			canvas.drawRoundRect(viewLeft, viewTop, viewRight, viewBottom, cornerRadius, cornerRadius, backgroundPaint)

			// Fast Scaling & Drawing
			canvas.save()
			// Move to the bubble's top-left (plus padding)
			canvas.translate(viewLeft + prep.paddingX * currentScale, viewTop + prep.paddingY * currentScale + prep.yOffset * currentScale)
			// Scale the canvas to match the current zoom perfectly
			canvas.scale(currentScale / REFERENCE_SCALE, currentScale / REFERENCE_SCALE)
			
			val workPaint = prep.layout.paint
			workPaint.set(strokePaint)
			workPaint.textSize = prep.textSize
			prep.layout.draw(canvas)
			
			workPaint.set(baseTextPaint)
			workPaint.textSize = prep.textSize
			prep.layout.draw(canvas)
			
			canvas.restore()
		}
		
		// Continuous tracking during zoom/pan
		postInvalidateOnAnimation()
	}
}