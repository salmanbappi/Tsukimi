package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: OnListItemClickListener<MangaListModel>,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	itemView.setTag(R.id.item_type, ListItemType.MANGA_GRID.ordinal)
	sizeResolver.attachToView(itemView, binding.textViewTitle, binding.progressView)

	bind { payloads ->
		itemView.tag = item.id
		if (binding.textViewTitle.text != item.title) {
			binding.textViewTitle.text = item.title
		}
		binding.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		
		val coverView = binding.imageViewCover
		val currentCover = coverView.getTag(R.id.cover_url) as? String
		if (currentCover != item.coverUrl) {
			coverView.setImageAsync(item.coverUrl, item.manga)
			coverView.setTag(R.id.cover_url, item.coverUrl)
		}
		
		binding.iconsView.updateIcons(item.isSaved, item.isFavorite)
		
		val badge = binding.badge
		if (badge.number != item.counter) {
			badge.number = item.counter
		}
		val shouldShowBadge = item.counter > 0
		if (badge.isVisible != shouldShowBadge) {
			badge.isVisible = shouldShowBadge
		}
	}
}
