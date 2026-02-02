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
	private var settings: AppSettings? = null
	
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
		val backgroundColor: Int,
		val isActionBubble: Boolean = false
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

			// Professional adaptive padding
			val paddingX = (refWidth * 0.10f).coerceAtMost(45f * REFERENCE_SCALE).coerceAtLeast(6f * REFERENCE_SCALE)
			val paddingY = (refHeight * 0.10f).coerceAtMost(45f * REFERENCE_SCALE).coerceAtLeast(6f * REFERENCE_SCALE)
			
			val availableWidth = (refWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (refHeight - 2 * paddingY).toInt().coerceAtLeast(1)
			
			// DIAMOND TYPESETTING: Better fit for manga ovals
			val diamondWidth = (availableWidth * 0.88f).toInt().coerceAtLeast(1)

			val words = text.split(Regex("\\s+"))
			var textSize = 44f * REFERENCE_SCALE
			val minTextSize = 6f * REFERENCE_SCALE
			val step = 1.5f
			
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
					.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)

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
				backgroundColor = block.backgroundColor,
				isActionBubble = block.isActionBubble
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
			
			val viewWidth = br.x - tl.x
			val viewHeight = br.y - tl.y

			// Professional Rounded Rectangle Erasure (No Distortion)
			val paint = if (isSeamlessMode) seamlessPaint else backgroundPaint
			paint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
			if (isSeamlessMode) paint.alpha = 255 else paint.alpha = 240

			val cornerRadius = (viewWidth.coerceAtMost(viewHeight) * 0.45f).coerceAtMost(70f)
			canvas.drawRoundRect(tl.x, tl.y, br.x, br.y, cornerRadius, cornerRadius, paint)

			// Render Translation Text
			canvas.save()
			canvas.translate(tl.x + prep.paddingX * currentScale, tl.y + prep.paddingY * currentScale + prep.yOffset * currentScale)
			canvas.scale(currentScale / REFERENCE_SCALE, currentScale / REFERENCE_SCALE)
			
			val workPaint = prep.layout.paint
			workPaint.set(strokePaint)
			workPaint.textSize = prep.textSize
			workPaint.strokeWidth = if (prep.isActionBubble) 5f * REFERENCE_SCALE else 3f * REFERENCE_SCALE
			workPaint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
			prep.layout.draw(canvas)
			
			workPaint.set(baseTextPaint)
			workPaint.textSize = prep.textSize
			workPaint.typeface = if (prep.isActionBubble) Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC) else Typeface.DEFAULT_BOLD
			
			if (isSeamlessMode) {
				val bgLum = Color.luminance(prep.backgroundColor)
				workPaint.color = if (bgLum > 0.5) Color.BLACK else Color.WHITE
			} else {
				workPaint.color = Color.BLACK
			}
			prep.layout.draw(canvas)
			canvas.restore()
		}
		postInvalidateOnAnimation()
	}
}
