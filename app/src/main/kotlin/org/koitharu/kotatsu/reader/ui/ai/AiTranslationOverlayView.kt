package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.*
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
	
	companion object {
		private const val REFERENCE_SCALE = 1.5f
	}
	
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 245
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
		typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
	}
	
	private val strokePaint = TextPaint().apply {
		color = Color.WHITE
		style = Paint.Style.STROKE
		strokeWidth = 3f * REFERENCE_SCALE
		isAntiAlias = true
		typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
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

			// Fixed Professional Padding
			val paddingX = (refWidth * 0.12f).coerceAtMost(40f * REFERENCE_SCALE)
			val paddingY = (refHeight * 0.12f).coerceAtMost(40f * REFERENCE_SCALE)
			
			val availableWidth = (refWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (refHeight - 2 * paddingY).toInt().coerceAtLeast(1)
			
			// Diamond fit logic
			val diamondWidth = (availableWidth * 0.85f).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))
			var textSize = 42f * REFERENCE_SCALE
			val minTextSize = 8f * REFERENCE_SCALE
			val step = 2.0f
			
			var finalLayout: StaticLayout? = null
			var finalYOffset = 0f
			var finalTextSize = minTextSize
			val paint = TextPaint(baseTextPaint)

			while (textSize >= minTextSize) {
				paint.textSize = textSize
				val maxWordWidth = words.maxOfOrNull { paint.measureText(it) } ?: 0f
				val currentWidth = if (maxWordWidth <= diamondWidth) diamondWidth else availableWidth
				
				if (maxWordWidth > currentWidth && textSize > minTextSize) {
					textSize -= step
					continue
				}

				val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, currentWidth)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.setLineSpacing(0f, 1.0f)
					.setIncludePad(false)
					.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)

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

		for (prep in preparedBlocks) {
			val sourceRect = prep.sourceRect
			val tl = ssiv.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: continue
			val br = ssiv.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: continue
			
			// Background Rect
			val paint = if (isSeamlessMode) seamlessPaint else backgroundPaint
			paint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
			if (isSeamlessMode) paint.alpha = 255 else paint.alpha = 245

			val cornerRadius = ((br.x - tl.x).coerceAtMost(br.y - tl.y) * 0.4f).coerceAtMost(60f)
			canvas.drawRoundRect(tl.x, tl.y, br.x, br.y, cornerRadius, cornerRadius, paint)

			// Text
			canvas.save()
			canvas.translate(tl.x + prep.paddingX * currentScale, tl.y + prep.paddingY * currentScale + prep.yOffset * currentScale)
			canvas.scale(currentScale / REFERENCE_SCALE, currentScale / REFERENCE_SCALE)
			
			val workPaint = prep.layout.paint
			workPaint.set(strokePaint)
			workPaint.textSize = prep.textSize
			workPaint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
			prep.layout.draw(canvas)
			
			workPaint.set(baseTextPaint)
			workPaint.textSize = prep.textSize
			if (isSeamlessMode) {
				val lum = Color.luminance(prep.backgroundColor)
				workPaint.color = if (lum > 0.5) Color.BLACK else Color.WHITE
			} else {
				workPaint.color = Color.BLACK
			}
			prep.layout.draw(canvas)
			canvas.restore()
		}
		postInvalidateOnAnimation()
	}
}