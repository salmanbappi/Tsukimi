package org.koitharu.kotatsu.details.ui.pager.chapters

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import kotlin.math.abs

class ChapterSwipeCallback(
	private val viewModel: ChaptersPagesViewModel,
	private val adapter: ChaptersAdapter,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {

	private val paint = Paint()
	private val iconMargin = 16 // dp, will convert to px

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean = false

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		val position = viewHolder.bindingAdapterPosition
		val item = adapter.items.getOrNull(position) as? ChapterListItem
		
		if (item != null) {
			viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
			if (direction == ItemTouchHelper.END) {
				// Swipe Right: Toggle Read/Unseen
				if (item.isUnread) {
					viewModel.markChaptersAsRead(listOf(item.chapter.id))
				} else {
					viewModel.markChaptersAsUnread(listOf(item.chapter.id))
				}
			} else if (direction == ItemTouchHelper.START) {
				// Swipe Left: Toggle Bookmark
				viewModel.toggleChapterBookmark(item.chapter.id)
			}
		}
		
		// Always notify changed to reset the swiped state (Snap-back)
		adapter.notifyItemChanged(position)
	}

	override fun onChildDraw(
		c: Canvas,
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		dX: Float,
		dY: Float,
		actionState: Int,
		isCurrentlyActive: Boolean,
	) {
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
			val itemView = viewHolder.itemView
			val item = adapter.items.getOrNull(viewHolder.bindingAdapterPosition) as? ChapterListItem
			val height = itemView.bottom.toFloat() - itemView.top.toFloat()
			val margin = (iconMargin * recyclerView.context.resources.displayMetrics.density).toInt()

			if (dX > 0 && item != null) {
				// Swiping Right: Toggle Read (Green/Gray)
				paint.color = if (item.isUnread) {
					ContextCompat.getColor(recyclerView.context, R.color.common_green)
				} else {
					android.graphics.Color.GRAY
				}
				val background = RectF(
					itemView.left.toFloat(),
					itemView.top.toFloat(),
					itemView.left.toFloat() + dX,
					itemView.bottom.toFloat()
				)
				c.drawRect(background, paint)

				val iconRes = if (item.isUnread) R.drawable.ic_check else R.drawable.ic_eye_off
				val icon = ContextCompat.getDrawable(recyclerView.context, iconRes)
				if (icon != null) {
					icon.setTint(android.graphics.Color.WHITE)
					val iconHeight = icon.intrinsicHeight
					val iconWidth = icon.intrinsicWidth
					val swipeProgress = abs(dX) / itemView.width
					val scale = (swipeProgress * 3).coerceIn(0.5f, 1.2f)
					
					val iconTop = itemView.top + (height - iconHeight * scale) / 2
					val iconLeft = itemView.left + margin
					val iconRight = itemView.left + margin + iconWidth * scale
					val iconBottom = iconTop + iconHeight * scale
					icon.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
					icon.draw(c)
				}
			} else if (dX < 0 && item != null) {
				// Swiping Left: Toggle Bookmark (Blue/Red)
				paint.color = if (item.isBookmarked) {
					ContextCompat.getColor(recyclerView.context, R.color.common_red)
				} else {
					recyclerView.context.getThemeColor(androidx.appcompat.R.attr.colorAccent)
				}
				val background = RectF(
					itemView.right.toFloat() + dX,
					itemView.top.toFloat(),
					itemView.right.toFloat(),
					itemView.bottom.toFloat()
				)
				c.drawRect(background, paint)

				val icon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_bookmark)
				if (icon != null) {
					icon.setTint(android.graphics.Color.WHITE)
					val iconHeight = icon.intrinsicHeight
					val iconWidth = icon.intrinsicWidth
					val swipeProgress = abs(dX) / itemView.width
					val scale = (swipeProgress * 3).coerceIn(0.5f, 1.2f)

					val iconTop = itemView.top + (height - iconHeight * scale) / 2
					val iconLeft = itemView.right - margin - iconWidth * scale
					val iconRight = itemView.right - margin
					val iconBottom = iconTop + iconHeight * scale
					icon.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
					icon.draw(c)
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}
	
	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.3f
}
