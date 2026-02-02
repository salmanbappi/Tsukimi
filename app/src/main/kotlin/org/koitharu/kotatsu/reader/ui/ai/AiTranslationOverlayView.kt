package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
	
	// Not using @Inject here because Views are not automatically injected by Hilt.
	// We rely on setSettings() being called from the Fragment/Activity.
	private var settings: AppSettings? = null
	
	companion object {
		private const val REFERENCE_SCALE = 1.5f
	}
	
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 240 // 94% opaque for a premium feel
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

	// Cache layouts to eliminate lag in onDraw
	private data class PreparedBlock(
		val sourceRect: RectF,
		val layout: StaticLayout,
		val textSize: Float,
		val paddingX: Float,
		val paddingY: Float,
		val yOffset: Float,
		val backgroundColor: Int,
		val customPath: android.graphics.Path? = null,
		val isActionBubble: Boolean = false
	)
	private var preparedBlocks = mutableListOf<PreparedBlock>()

	init {
		// Manual dependency injection since View is not Hilt-injected by default
		// Assuming context is Activity/Hilt context or we can get it via EntryPoint if needed.
		// For now, we'll try to get it if the context is right, or fallback.
		// Actually, let's inject it via setter from Fragment to be safe.
	}
	
	fun setSettings(appSettings: AppSettings) {
		this.settings = appSettings
		isSeamlessMode = appSettings.isAiSeamlessTranslationEnabled
	}

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		// Sort by area descending so larger bubbles (backgrounds) are drawn first,
		// allowing smaller nested bubbles to appear on top.
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
			
			// Reference width for typesetting in source pixels
			val refWidth = block.boundingBox.width() * REFERENCE_SCALE
			val refHeight = block.boundingBox.height() * REFERENCE_SCALE
			
			if (refWidth <= 0 || refHeight <= 0) continue

			// Adaptive padding: smaller percentage for smaller bubbles, with a hard cap
			val paddingX = (refWidth * 0.08f).coerceAtMost(40f * REFERENCE_SCALE).coerceAtLeast(4f * REFERENCE_SCALE)
			val paddingY = (refHeight * 0.08f).coerceAtMost(40f * REFERENCE_SCALE).coerceAtLeast(4f * REFERENCE_SCALE)
			
			// PRO DIAMOND TYPESETTING: Manga bubbles are typically oval/diamond.
			// By slightly reducing the available width compared to a rectangle, we force the text into a diamond shape.
			val availableWidth = (refWidth - 2 * paddingX).toInt().coerceAtLeast(1)
			val availableHeight = (refHeight - 2 * paddingY).toInt().coerceAtLeast(1)
			
			// For diamond fitting, we start with a tighter width and expand only if needed
			val diamondWidth = (availableWidth * 0.85f).toInt().coerceAtLeast(1)

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
				
				// Try diamond width first, then fallback to full width
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

			val path = block.outline?.let { points ->
				if (points.isEmpty()) return@let null
				android.graphics.Path().apply {
					moveTo(points[0].x, points[0].y)
					for (i in 1 until points.size) {
						lineTo(points[i].x, points[i].y)
					}
					close()
				}
			}

			preparedBlocks.add(PreparedBlock(
					sourceRect = block.boundingBox,
					layout = finalLayout,
					textSize = finalTextSize,
					paddingX = paddingX / REFERENCE_SCALE,
					paddingY = paddingY / REFERENCE_SCALE,
					yOffset = finalYOffset / REFERENCE_SCALE,
					backgroundColor = block.backgroundColor,
					customPath = path,
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
						
						// Map source coordinates to current screen pixels accurately
						val tl = ssiv.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: continue
						val br = ssiv.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: continue
						
						val viewLeft = tl.x
						val viewTop = tl.y
						val viewRight = br.x
						val viewBottom = br.y
						
						val viewWidth = viewRight - viewLeft
						val viewHeight = viewBottom - viewTop
			
						// Draw background bubble
						val paint = if (isSeamlessMode) seamlessPaint else backgroundPaint
						paint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
						if (isSeamlessMode) paint.alpha = 255 else paint.alpha = 240
			
												if (prep.customPath != null) {
			
													// We need to scale the source-coordinate path to view coordinates
			
													canvas.save()
			
													val matrix = Matrix()
			
													// Map source points directly to view using SSIV's scale and translation
			
													val vTrans = ssiv.sourceToViewCoord(0f, 0f) ?: PointF(0f, 0f)
			
													matrix.postScale(currentScale, currentScale)
			
													matrix.postTranslate(vTrans.x, vTrans.y)
			
													
			
													val drawPath = Path(prep.customPath)
			
													drawPath.transform(matrix)
			
													
			
													// PRO INK PRESERVATION: We already have the bubble shape.
			
													// To avoid breaking borders, we draw the path but we could also use PorterDuff to mask.
			
													// For now, drawing the exact Path from BFS is already much safer than a rectangle.
			
													canvas.drawPath(drawPath, paint)
			
													canvas.restore()
			
												} else {							// Fallback to rounded rect if no custom path available
							val cornerRadius = (viewWidth.coerceAtMost(viewHeight) * 0.4f).coerceAtMost(60f)
							canvas.drawRoundRect(viewLeft, viewTop, viewRight, viewBottom, cornerRadius, cornerRadius, paint)
						}
			
						// Fast Scaling & Drawing
						canvas.save()
						// Move to the bubble's top-left (plus padding)
						canvas.translate(viewLeft + prep.paddingX * currentScale, viewTop + prep.paddingY * currentScale + prep.yOffset * currentScale)
						// Scale the canvas to match the current zoom perfectly
						canvas.scale(currentScale / REFERENCE_SCALE, currentScale / REFERENCE_SCALE)
						
						val workPaint = prep.layout.paint
						workPaint.set(strokePaint)
						workPaint.textSize = prep.textSize
						// Thicker stroke for action bubbles
						workPaint.strokeWidth = if (prep.isActionBubble) 5f * REFERENCE_SCALE else 3f * REFERENCE_SCALE
						
						workPaint.color = if (isSeamlessMode) prep.backgroundColor else Color.WHITE
						
						prep.layout.draw(canvas)
						
						workPaint.set(baseTextPaint)
						workPaint.textSize = prep.textSize
						// Bolder typeface for action bubbles
						workPaint.typeface = if (prep.isActionBubble) Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC) else Typeface.DEFAULT_BOLD
						
						// Simple luminance check for text color
						if (isSeamlessMode) {
							val bgLum = Color.luminance(prep.backgroundColor)
							workPaint.color = if (bgLum > 0.5) Color.BLACK else Color.WHITE
						} else {
							workPaint.color = Color.BLACK
						}
						
						prep.layout.draw(canvas)
						
						canvas.restore()
					}
					
					// Continuous tracking during zoom/pan
					postInvalidateOnAnimation()	}
}