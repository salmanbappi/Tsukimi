package org.koitharu.kotatsu.details.ui.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class ChaptersSelectionDecoration(context: Context) : AbstractSelectionItemDecoration() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val radius = context.resources.getDimension(appcompatR.dimen.abc_control_corner_material)
	private val checkIcon = ContextCompat.getDrawable(context, materialR.drawable.ic_mtrl_checked_circle)
	private val iconOffset = context.resources.getDimensionPixelOffset(R.dimen.chapter_check_offset)
	private val iconSize = context.resources.getDimensionPixelOffset(R.dimen.chapter_check_size)
	private val strokeColor = context.getThemeColor(appcompatR.attr.colorPrimary, Color.RED)
	private val fillColor = ColorUtils.setAlphaComponent(
		ColorUtils.blendARGB(strokeColor, context.getThemeColor(materialR.attr.colorSurface), 0.8f),
		0x74,
	)

	init {
		paint.color = ColorUtils.setAlphaComponent(
			context.getThemeColor(appcompatR.attr.colorPrimary, Color.DKGRAY),
			48, // Reduced alpha for more subtle selection (approx 18%)
		)
		paint.style = Paint.Style.FILL
		hasBackground = true
		hasForeground = true
		isIncludeDecorAndMargins = false

		paint.strokeWidth = context.resources.getDimension(R.dimen.selection_stroke_width)
		checkIcon?.setTint(strokeColor)
	}

	override fun getItemId(parent: RecyclerView, child: View): Long {
		val holder = parent.findContainingViewHolder(child) ?: return RecyclerView.NO_ID
		val item = holder.getItem(ChapterListItem::class.java) ?: return RecyclerView.NO_ID
		return item.chapter.id
	}

	override fun onDrawBackground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State,
	) {
		// Use a fixed alpha background for selection
		paint.style = Paint.Style.FILL
		canvas.drawRoundRect(bounds, radius, radius, paint)
	}

	override fun onDrawForeground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State
	) {
		// Only draw check icon for list items (not grid)
		val holder = parent.findContainingViewHolder(child)
		val item = holder?.getItem(ChapterListItem::class.java)
		if (item != null && !item.isGrid) {
			checkIcon?.run {
				setBounds(
					(bounds.right - iconSize - iconOffset).toInt(),
					(bounds.top + iconOffset).toInt(),
					(bounds.right - iconOffset).toInt(),
					(bounds.top + iconOffset + iconSize).toInt(),
				)
				draw(canvas)
			}
		}
	}
}
