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
import org.koitharu.kotatsu.core.prefs.AppSettings
import kotlin.math.max

class AiTranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var blocks: List<TranslatedBlock> = emptyList()
	private var ssiv: SubsamplingScaleImageView? = null
	private var isSeamlessMode = false
	
	private var settings: AppSettings? = null
	
	// State tracking for smart invalidation
	private var lastScale = -1f
	private var lastCenterX = -1f
	private var lastCenterY = -1f
	
	init {
		setLayerType(LAYER_TYPE_HARDWARE, null)
	}
	
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 250 
		style = Paint.Style.FILL
		isAntiAlias = true
	}
	
	private val seamlessPaint = Paint().apply {
		style = Paint.Style.FILL
		isAntiAlias = true
	}
	
	private val baseTextPaint = TextPaint().apply {
		color = Color.BLACK
		isAntiAlias = true
		typeface = Typeface.DEFAULT_BOLD
	}

	private data class PreparedBlock(
		val sourceRect: RectF,
		val layout: StaticLayout,
		val paddingX: Float,
		val paddingY: Float,
		val yOffset: Float,
		val backgroundColor: Int
	)
	private var preparedBlocks = mutableListOf<PreparedBlock>()

	fun setSettings(appSettings: AppSettings) {
		this.settings = appSettings
		isSeamlessMode = appSettings.isAiSeamlessTranslationEnabled
	}

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		blocks = newBlocks.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
		prepareLayouts()
		invalidate() // Start the smart redraw loop
	}

	fun clear() {
		blocks = emptyList()
		preparedBlocks.clear()
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
			
			val sourceRect = block.boundingBox
			val sourceW = sourceRect.width()
			val sourceH = sourceRect.height()
			
			if (sourceW <= 0 || sourceH <= 0) continue

			// Calculate padding in source pixels (tighter for better fit)
			val paddingX = sourceW * 0.04f
			val paddingY = sourceH * 0.04f
			val availableWidth = (sourceW - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (sourceH - 2 * paddingY).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))
			
			// Auto-size font based on source pixels (starting at a more reasonable size)
			var textSize = (sourceH * 0.18f).coerceAtMost(sourceW * 0.25f).coerceAtMost(40f)
			val minTextSize = 10f
			val step = 1f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f

			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize
				val maxWordWidth = words.maxOfOrNull { paint.measureText(it) } ?: 0f
				
				if (maxWordWidth > availableWidth) {
					textSize -= step
					continue
				}

				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 1.0f) // Standard line spacing to prevent clipping
					.setIncludePad(false)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)

				val layout = builder.build()
				if (layout.height <= availableHeight) {
					finalLayout = layout
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
				finalYOffset = max(0f, (availableHeight - finalLayout.height) / 2f)
			}

			preparedBlocks.add(PreparedBlock(
				sourceRect = sourceRect,
				layout = finalLayout,
				paddingX = paddingX,
				paddingY = paddingY,
				yOffset = finalYOffset,
				backgroundColor = block.backgroundColor
			))
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = this.ssiv ?: return
		if (!ssiv.isReady || preparedBlocks.isEmpty()) return

		val count = preparedBlocks.size
		for (i in 0 until count) {
			val prep = preparedBlocks[i]
			val sourceRect = prep.sourceRect
			
			// View coordinates for the bubble box
			val vLeft = sourceRect.left * currentScale + tx
			val vTop = sourceRect.top * currentScale + ty
			val vRight = sourceRect.right * currentScale + tx
			val vBottom = sourceRect.bottom * currentScale + ty
			
			val cornerRadius = ((vRight - vLeft).coerceAtMost(vBottom - vTop) * 0.45f).coerceAtMost(80f)
			
			if (isSeamlessMode) {
				seamlessPaint.color = prep.backgroundColor
				canvas.drawRoundRect(vLeft, vTop, vRight, vBottom, cornerRadius, cornerRadius, seamlessPaint)
			} else {
				canvas.drawRoundRect(vLeft, vTop, vRight, vBottom, cornerRadius, cornerRadius, backgroundPaint)
			}

			canvas.save()
			// Move to the padding-inset start position
			canvas.translate(vLeft + prep.paddingX * currentScale, vTop + (prep.paddingY + prep.yOffset) * currentScale)
			// Scale the canvas so we can draw the layout using source-pixel dimensions
			canvas.scale(currentScale, currentScale)
			
			val layoutPaint = prep.layout.paint
			
			if (isSeamlessMode) {
				// Stroke for readability on varied backgrounds
				layoutPaint.style = Paint.Style.STROKE
				layoutPaint.strokeWidth = prep.layout.paint.textSize * 0.1f
				layoutPaint.color = prep.backgroundColor
				prep.layout.draw(canvas)
				
				val r = Color.red(prep.backgroundColor)
				val g = Color.green(prep.backgroundColor)
				val b = Color.blue(prep.backgroundColor)
				val lum = 0.299 * r + 0.587 * g + 0.114 * b
				
				layoutPaint.style = Paint.Style.FILL
				layoutPaint.color = if (lum > 150) Color.BLACK else Color.WHITE
			} else {
				layoutPaint.style = Paint.Style.FILL
				layoutPaint.color = Color.BLACK
			}
			
			prep.layout.draw(canvas)
			canvas.restore()
		}
		
		// Smart redraw check
		if (Math.abs(currentScale - lastScale) > 0.001f || 
			Math.abs(center.x - lastCenterX) > 0.5f || Math.abs(center.y - lastCenterY) > 0.5f) {
			lastScale = currentScale
			lastCenterX = center.x
			lastCenterY = center.y
			postInvalidateOnAnimation()
		}
	}
}
