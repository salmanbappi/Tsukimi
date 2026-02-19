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
import javax.inject.Inject
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
	
	// Pre-allocated PointF to ensure zero object creation in onDraw loop
	private val vPoint = PointF()
	
	// State tracking for performance
	private var lastScale = -1f
	private var lastCenterX = -1f
	private var lastCenterY = -1f
	
	companion object {
		private const val REFERENCE_SCALE = 1.5f
	}
	
	init {
		setLayerType(LAYER_TYPE_HARDWARE, null)
	}
	
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 240 
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
	
	private val strokePaint = TextPaint().apply {
		color = Color.WHITE
		style = Paint.Style.STROKE
		strokeWidth = 3f * REFERENCE_SCALE
		isAntiAlias = true
		typeface = Typeface.DEFAULT_BOLD
		strokeJoin = Paint.Join.ROUND
	}

	private data class PreparedBlock(
		val sourceRect: RectF,
		val layout: StaticLayout,
		val textSize: Float,
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
			
			val refWidth = block.boundingBox.width() * REFERENCE_SCALE
			val refHeight = block.boundingBox.height() * REFERENCE_SCALE
			
			if (refWidth <= 0 || refHeight <= 0) continue

			// Tighter padding to maximize space usage
			val paddingX = refWidth * 0.05f
			val paddingY = refHeight * 0.05f
			val availableWidth = (refWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (refHeight - 2 * paddingY).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))
			
			// Start with a large font size and shrink until it fits
			var textSize = (refHeight * 0.8f).coerceAtMost(48f * REFERENCE_SCALE)
			val minTextSize = 10f * REFERENCE_SCALE
			val step = 1f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f
			var finalTextSize = minTextSize

			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize
				val maxWordWidth = words.maxOfOrNull { paint.measureText(it) } ?: 0f
				
				// Ensure no word is wider than available space
				if (maxWordWidth > availableWidth) {
					textSize -= step
					continue
				}

				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 0.9f) // Tighter line spacing for more efficient space usage
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
				yOffset = finalYOffset / REFERENCE_SCALE,
				backgroundColor = block.backgroundColor
			))
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = this.ssiv ?: return
		if (!ssiv.isReady || preparedBlocks.isEmpty()) return

		val currentScale = ssiv.scale
		val center = ssiv.getCenter() ?: return
		
		// Update state for smart invalidation
		lastScale = currentScale
		lastCenterX = center.x
		lastCenterY = center.y
		
		// Calculate global translation once per frame to avoid matrix math in the loop
		val origin = ssiv.viewToSourceCoord(0f, 0f) ?: return
		val tx = -origin.x * currentScale
		val ty = -origin.y * currentScale
		
		val count = preparedBlocks.size
		for (i in 0 until count) {
			val prep = preparedBlocks[i]
			val sourceRect = prep.sourceRect
			
			// Faster manual coordinate mapping
			val vLeft = sourceRect.left * currentScale + tx
			val vTop = sourceRect.top * currentScale + ty
			val vRight = sourceRect.right * currentScale + tx
			val vBottom = sourceRect.bottom * currentScale + ty
			
			val cornerRadius = ((vRight - vLeft).coerceAtMost(vBottom - vTop) * 0.4f).coerceAtMost(60f)
			
			if (isSeamlessMode) {
				seamlessPaint.color = prep.backgroundColor
				canvas.drawRoundRect(vLeft, vTop, vRight, vBottom, cornerRadius, cornerRadius, seamlessPaint)
			} else {
				canvas.drawRoundRect(vLeft, vTop, vRight, vBottom, cornerRadius, cornerRadius, backgroundPaint)
			}

			canvas.save()
			canvas.translate(vLeft + prep.paddingX * currentScale, vTop + prep.paddingY * currentScale + prep.yOffset * currentScale)
			canvas.scale(currentScale / REFERENCE_SCALE, currentScale / REFERENCE_SCALE)
			
			val layoutPaint = prep.layout.paint
			
			layoutPaint.style = Paint.Style.STROKE
			layoutPaint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
			prep.layout.draw(canvas)
			
			layoutPaint.style = Paint.Style.FILL
			if (isSeamlessMode) {
				val r = Color.red(prep.backgroundColor)
				val g = Color.green(prep.backgroundColor)
				val b = Color.blue(prep.backgroundColor)
				val lum = 0.299 * r + 0.587 * g + 0.114 * b
				layoutPaint.color = if (lum > 128) Color.BLACK else Color.WHITE
			} else {
				layoutPaint.color = Color.BLACK
			}
			prep.layout.draw(canvas)
			
			canvas.restore()
		}
		
		// SMART REDRAW: Only request another frame if the image is still moving/zooming.
		// Use a small threshold to avoid constant redraws due to tiny floating point changes.
		val newScale = ssiv.scale
		val newCenter = ssiv.getCenter()
		val scaleChanged = Math.abs(newScale - lastScale) > 0.001f
		val centerChanged = newCenter != null && (Math.abs(newCenter.x - lastCenterX) > 0.5f || Math.abs(newCenter.y - lastCenterY) > 0.5f)
		
		if (scaleChanged || centerChanged) {
			lastScale = newScale
			lastCenterX = newCenter?.x ?: -1f
			lastCenterY = newCenter?.y ?: -1f
			postInvalidateOnAnimation()
		}
	}
}