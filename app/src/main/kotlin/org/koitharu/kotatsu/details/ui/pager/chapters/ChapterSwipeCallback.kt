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
	private val rect = RectF()
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
				// Swipe End (Right in LTR): Toggle Read/Unseen
				if (item.isUnread) {
					viewModel.markChaptersAsRead(listOf(item.chapter.id))
				} else {
					viewModel.markChaptersAsUnread(listOf(item.chapter.id))
				}
			} else if (direction == ItemTouchHelper.START) {
				// Swipe Start (Left in LTR): Toggle Bookmark
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
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
			val itemView = viewHolder.itemView
			val item = adapter.items.getOrNull(viewHolder.bindingAdapterPosition) as? ChapterListItem
			val height = itemView.bottom.toFloat() - itemView.top.toFloat()
			val margin = (iconMargin * recyclerView.context.resources.displayMetrics.density).toInt()
			val isRtl = recyclerView.layoutDirection == RecyclerView.LAYOUT_DIRECTION_RTL

			// Determine if we are swiping in 'End' direction (Read Toggle) or 'Start' direction (Bookmark)
			// In LTR: End is dX > 0, Start is dX < 0
			// In RTL: End is dX < 0, Start is dX > 0
			val isEndSwipe = if (isRtl) dX < 0 else dX > 0

			if (isEndSwipe && item != null) {
				// Toggle Read Visuals
				paint.color = if (item.isUnread) {
					ContextCompat.getColor(recyclerView.context, R.color.common_green)
				} else {
					android.graphics.Color.GRAY
				}
				
				if (dX > 0) {
					rect.set(itemView.left.toFloat(), itemView.top.toFloat(), itemView.left.toFloat() + dX, itemView.bottom.toFloat())
				} else {
					rect.set(itemView.right.toFloat() + dX, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
				}
				c.drawRect(rect, paint)

				val iconRes = if (item.isUnread) R.drawable.ic_check else R.drawable.ic_eye_off
				val icon = ContextCompat.getDrawable(recyclerView.context, iconRes)
				if (icon != null) {
					icon.setTint(android.graphics.Color.WHITE)
					val swipeProgress = abs(dX) / itemView.width
					val scale = (swipeProgress * 3).coerceIn(0.5f, 1.2f)
					val iconHeight = (icon.intrinsicHeight * scale).toInt()
					val iconWidth = (icon.intrinsicWidth * scale).toInt()
					
					val iconTop = itemView.top + (height - iconHeight) / 2
					val iconLeft = if (dX > 0) itemView.left + margin else itemView.right - margin - iconWidth
					icon.setBounds(iconLeft.toInt(), iconTop.toInt(), iconLeft.toInt() + iconWidth, iconTop.toInt() + iconHeight)
					icon.draw(c)
				}
			} else if (item != null) {
				// Toggle Bookmark Visuals
				paint.color = if (item.isBookmarked) {
					ContextCompat.getColor(recyclerView.context, R.color.common_red)
				} else {
					recyclerView.context.getThemeColor(androidx.appcompat.R.attr.colorAccent)
				}
				
				if (dX > 0) {
					rect.set(itemView.left.toFloat(), itemView.top.toFloat(), itemView.left.toFloat() + dX, itemView.bottom.toFloat())
				} else {
					rect.set(itemView.right.toFloat() + dX, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
				}
				c.drawRect(rect, paint)

				val icon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_bookmark)
				if (icon != null) {
					icon.setTint(android.graphics.Color.WHITE)
					val swipeProgress = abs(dX) / itemView.width
					val scale = (swipeProgress * 3).coerceIn(0.5f, 1.2f)
					val iconHeight = (icon.intrinsicHeight * scale).toInt()
					val iconWidth = (icon.intrinsicWidth * scale).toInt()

					val iconTop = itemView.top + (height - iconHeight) / 2
					val iconLeft = if (dX > 0) itemView.left + margin else itemView.right - margin - iconWidth
					icon.setBounds(iconLeft.toInt(), iconTop.toInt(), iconLeft.toInt() + iconWidth, iconTop.toInt() + iconHeight)
					icon.draw(c)
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		// Ensure any remaining translation or custom drawing is cleared
		viewHolder.itemView.translationX = 0f
	}
	
	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.3f
}
