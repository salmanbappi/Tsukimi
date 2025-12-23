package org.koitharu.kotatsu.details.ui.adapter

import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	binding.buttonDownload.setOnClickListener { clickListener.onItemClick(item, it) }

	bind {
		binding.textViewTitle.text = item.getTitle(context.resources)
		
		val description = StringBuilder()
		item.description?.let { description.append(it) }

		if (item.readPage >= 0) {
			if (description.isNotEmpty()) description.append(" • ")
			description.append("Page: ").append(item.readPage + 1)
		}

		item.timeAgo?.let {
			if (description.isNotEmpty()) description.append(" • ")
			description.append(it.format(context))
		}

		binding.textViewDescription.textAndVisible = description.toString()

		val isDownloading = item.downloadProgress >= 0f
		binding.progressDownload.isVisible = isDownloading
		if (isDownloading) {
			binding.progressDownload.progress = (item.downloadProgress * 100).toInt()
			binding.buttonDownload.setImageDrawable(null)
		} else {
			binding.buttonDownload.setImageResource(
				when {
					item.isDeletionConfirmation -> R.drawable.ic_delete
					item.isDownloaded -> R.drawable.ic_delete
					else -> R.drawable.ic_download
				}
			)
		}

		if (item.isDeletionConfirmation) {
			binding.buttonDownload.setColorFilter(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
		} else {
			binding.buttonDownload.clearColorFilter()
			binding.buttonDownload.rotation = 0f
		}

		when {
			item.isCurrent -> {
				binding.textViewTitle.drawableStart = ContextCompat.getDrawable(context, R.drawable.ic_current_chapter)
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
				binding.textViewDescription.typeface = Typeface.DEFAULT_BOLD
			}

			item.isUnread -> {
				binding.textViewTitle.drawableStart = if (item.isNew) {
					ContextCompat.getDrawable(context, R.drawable.ic_new)
				} else {
					null
				}
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(materialR.attr.colorOutline))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
			}

			else -> {
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
			}
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
	}
}
