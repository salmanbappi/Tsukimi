package org.koitharu.kotatsu.settings.extension.repos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemExtensionRepoBinding
import org.koitharu.kotatsu.extension.model.ExtensionRepo

class ExtensionRepoAdapter(
	private val onDeleteClick: (ExtensionRepo) -> Unit,
) : ListAdapter<ExtensionRepo, ExtensionRepoAdapter.ViewHolder>(DiffCallback) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemExtensionRepoBinding.inflate(
			LayoutInflater.from(parent.context), parent, false
		)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	inner class ViewHolder(
		private val binding: ItemExtensionRepoBinding
	) : RecyclerView.ViewHolder(binding.root) {

		init {
			binding.buttonDelete.setOnClickListener {
				val position = bindingAdapterPosition
				if (position != RecyclerView.NO_POSITION) {
					onDeleteClick(getItem(position))
				}
			}
		}

		fun bind(item: ExtensionRepo) {
			binding.textName.text = item.name
			binding.textUrl.text = item.baseUrl
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<ExtensionRepo>() {
		override fun areItemsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo): Boolean {
			return oldItem.baseUrl == newItem.baseUrl
		}

		override fun areContentsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo): Boolean {
			return oldItem == newItem
		}
	}
}