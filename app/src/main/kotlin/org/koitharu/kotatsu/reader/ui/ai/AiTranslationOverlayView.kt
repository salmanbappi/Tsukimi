package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class AiTranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var blocks: List<TranslatedBlock> = emptyList()
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 200
		style = Paint.Style.FILL
	}
	private val textPaint = Paint().apply {
		color = Color.BLACK
		textSize = 32f
		isAntiAlias = true
	}

	fun setTranslatedBlocks(newBlocks: List<TranslatedBlock>) {
		blocks = newBlocks
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		for (block in blocks) {
			canvas.drawRect(block.boundingBox, backgroundPaint)
			
			// Simple multi-line text drawing could be better, but this is a start
			val lines = block.text.split("\n")
			var y = block.boundingBox.top.toFloat() + textPaint.textSize
			for (line in lines) {
				canvas.drawText(line, block.boundingBox.left.toFloat(), y, textPaint)
				y += textPaint.textSize
			}
		}
	}
}
