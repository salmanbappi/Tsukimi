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
	private val iconMargin = 16 // dp
	private var accentColor: Int = 0

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
				// Swipe Right (End): Toggle Read/Unseen
				if (item.isUnread) {
					viewModel.markChaptersAsRead(listOf(item.chapter.id))
				} else {
					viewModel.markChaptersAsUnread(listOf(item.chapter.id))
				}
			} else if (direction == ItemTouchHelper.START) {
				// Swipe Left (Start): Toggle Bookmark
				viewModel.toggleChapterBookmark(item.chapter.id)
			}
		}
		
		// Post to ensure ItemTouchHelper finishes its cycle before we re-bind the view
		viewHolder.itemView.post {
			adapter.notifyItemChanged(position)
		}
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
			
			val foreground = itemView.findViewById<View>(R.id.foreground)
			val bgStart = itemView.findViewById<View>(R.id.swipe_bg_start)
			val bgEnd = itemView.findViewById<View>(R.id.swipe_bg_end)
			val iconStart = itemView.findViewById<ImageView>(R.id.swipe_icon_start)
			
			if (foreground != null && bgStart != null && bgEnd != null && item != null) {
				// Translate only the foreground
				foreground.translationX = dX
				
				if (dX > 0) {
					// Swiping Right: Toggle Read (Green/Gray)
					bgStart.visibility = View.VISIBLE
					bgEnd.visibility = View.INVISIBLE
					bgStart.setBackgroundColor(if (item.isUnread) {
						ContextCompat.getColor(recyclerView.context, R.color.common_green)
					} else {
						android.graphics.Color.GRAY
					})
					iconStart?.setImageResource(if (item.isUnread) R.drawable.ic_check else R.drawable.ic_eye_off)
				} else if (dX < 0) {
					// Swiping Left: Toggle Bookmark (Blue/Red)
					bgEnd.visibility = View.VISIBLE
					bgStart.visibility = View.INVISIBLE
					bgEnd.setBackgroundColor(if (item.isBookmarked) {
						ContextCompat.getColor(recyclerView.context, R.color.common_red)
					} else {
						if (accentColor == 0) accentColor = recyclerView.context.getThemeColor(androidx.appcompat.R.attr.colorAccent)
						accentColor
					})
				} else {
					bgStart.visibility = View.INVISIBLE
					bgEnd.visibility = View.INVISIBLE
				}
			}
		} else {
			super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
		}
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		val foreground = viewHolder.itemView.findViewById<View>(R.id.foreground)
		val bgStart = viewHolder.itemView.findViewById<View>(R.id.swipe_bg_start)
		val bgEnd = viewHolder.itemView.findViewById<View>(R.id.swipe_bg_end)
		
		foreground?.translationX = 0f
		bgStart?.visibility = View.INVISIBLE
		bgEnd?.visibility = View.INVISIBLE
		
		super.clearView(recyclerView, viewHolder)
	}
	
	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.3f
}
