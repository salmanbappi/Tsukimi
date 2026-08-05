package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
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
import kotlin.math.min

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
		setLayerType(LAYER_TYPE_NONE, null)
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
		val picture: Picture,
		val backgroundColor: Int,
		val sourceCornerRadius: Float
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

			// Pre-calculate text color based on background luminance
			val r = Color.red(block.backgroundColor)
			val g = Color.green(block.backgroundColor)
			val b = Color.blue(block.backgroundColor)
			val lum = 0.299 * r + 0.587 * g + 0.114 * b
			val textColor = if (lum > 160) Color.BLACK else Color.WHITE
			
			finalLayout.paint.style = Paint.Style.FILL
			finalLayout.paint.color = textColor

			val picture = Picture()
			val picCanvas = picture.beginRecording(sourceW.toInt(), sourceH.toInt())
			picCanvas.translate(paddingX, paddingY + finalYOffset)
			finalLayout.draw(picCanvas)
			picture.endRecording()

			preparedBlocks.add(PreparedBlock(
				sourceRect = sourceRect,
				picture = picture,
				backgroundColor = block.backgroundColor,
				sourceCornerRadius = min(sourceW, sourceH) * 0.20f
			))
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val ssiv = this.ssiv ?: return
		if (!ssiv.isReady || preparedBlocks.isEmpty()) return

		val currentScale = ssiv.scale
		val center = ssiv.getCenter() ?: return
		
		// Use sourceToViewCoord(0,0) as a direct anchor to prevent coordinate drift/jitter
		val vOrigin = ssiv.sourceToViewCoord(0f, 0f) ?: return
		val tx = vOrigin.x
		val ty = vOrigin.y

		canvas.save()
		// Push transformation to GPU
		canvas.translate(tx, ty)
		canvas.scale(currentScale, currentScale)

		seamlessPaint.alpha = 255
		seamlessPaint.style = Paint.Style.FILL

		val count = preparedBlocks.size
		for (i in 0 until count) {
			val prep = preparedBlocks[i]
			
			seamlessPaint.color = prep.backgroundColor
			canvas.drawRoundRect(prep.sourceRect, prep.sourceCornerRadius, prep.sourceCornerRadius, seamlessPaint)

			canvas.save()
			canvas.translate(prep.sourceRect.left, prep.sourceRect.top)
			canvas.drawPicture(prep.picture)
			canvas.restore()
		}
		
		canvas.restore()
		
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
