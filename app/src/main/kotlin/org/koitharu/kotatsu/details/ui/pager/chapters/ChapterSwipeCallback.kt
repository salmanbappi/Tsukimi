package org.koitharu.kotatsu.details.ui.pager.chapters

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.details.ui.adapter.ChaptersAdapter
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel

class ChapterSwipeCallback(
	private val viewModel: ChaptersPagesViewModel,
	private val adapter: ChaptersAdapter,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {

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
			if (direction == ItemTouchHelper.START) {
				// Swipe Left (in LTR): Mark as read
				viewModel.markChaptersAsRead(listOf(item.chapter.id))
			} else if (direction == ItemTouchHelper.END) {
				// Swipe Right (in LTR): Bookmark
				viewModel.toggleChapterBookmark(item.chapter.id)
			}
		}
		
		// Always notify changed to reset the swiped state
		adapter.notifyItemChanged(position)
	}
	
	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.3f
}
