package org.koitharu.kotatsu.settings.extension.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemExtensionCatalogBinding
import org.koitharu.kotatsu.extension.model.ExtensionJsonObject

class ExtensionCatalogAdapter : ListAdapter<ExtensionJsonObject, ExtensionCatalogAdapter.ViewHolder>(DiffCallback) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemExtensionCatalogBinding.inflate(
			LayoutInflater.from(parent.context), parent, false
		)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	inner class ViewHolder(
		private val binding: ItemExtensionCatalogBinding
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(item: ExtensionJsonObject) {
			binding.textName.text = item.name.removePrefix("Tachiyomi: ")
			binding.textVersion.text = "v${item.version} • ${item.lang}"
			binding.buttonInstall.setOnClickListener {
				// Stub: This is where we would trigger APK download and installation
			}
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<ExtensionJsonObject>() {
		override fun areItemsTheSame(oldItem: ExtensionJsonObject, newItem: ExtensionJsonObject): Boolean {
			return oldItem.pkg == newItem.pkg
		}

		override fun areContentsTheSame(oldItem: ExtensionJsonObject, newItem: ExtensionJsonObject): Boolean {
			return oldItem == newItem
		}
	}
}