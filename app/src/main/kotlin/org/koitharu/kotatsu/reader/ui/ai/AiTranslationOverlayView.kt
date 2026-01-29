package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

class AiTranslationOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var blocks: List<TranslatedBlock> = emptyList()
	private val backgroundPaint = Paint().apply {
		color = Color.WHITE
		alpha = 230
		style = Paint.Style.FILL
	}
	private val textPaint = TextPaint().apply {
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
			
			val width = block.boundingBox.width()
			if (width > 0) {
				val layout = StaticLayout.Builder.obtain(block.text, 0, block.text.length, textPaint, width)
					.setAlignment(Layout.Alignment.ALIGN_CENTER)
					.build()
				
				canvas.save()
				canvas.translate(block.boundingBox.left.toFloat(), block.boundingBox.top.toFloat())
				layout.draw(canvas)
				canvas.restore()
			}
		}
	}
}
