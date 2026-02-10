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
	
	companion object {
		private const val REFERENCE_SCALE = 1.5f
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
		// Premium polish: subtle drop shadow for depth
		setShadowLayer(2f, 1f, 1f, Color.parseColor("#40000000"))
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
		ssiv.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
			override fun onScaleChanged(newScale: Float, origin: Int) {
				invalidate()
			}
			override fun onCenterChanged(newCenter: PointF?, origin: Int) {
				invalidate()
			}
		})
	}

	private fun prepareLayouts() {
		preparedBlocks.clear()
		
		for (block in blocks) {
			val text = block.text
			if (text.isBlank()) continue
			
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
		
		val count = preparedBlocks.size
		for (i in 0 until count) {
			val prep = preparedBlocks[i]
			val sourceRect = prep.sourceRect
			
			// Use standard 3-arg sourceToViewCoord with pre-allocated PointF
			ssiv.sourceToViewCoord(sourceRect.left, sourceRect.top, vPoint)
			val vLeft = vPoint.x
			val vTop = vPoint.y
			
			ssiv.sourceToViewCoord(sourceRect.right, sourceRect.bottom, vPoint)
			val vRight = vPoint.x
			val vBottom = vPoint.y
			
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
	}
}