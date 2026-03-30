package org.koitharu.kotatsu.settings.extension.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemExtensionCatalogBinding
import org.koitharu.kotatsu.databinding.ItemExtensionHeaderBinding
import org.koitharu.kotatsu.extension.model.ExtensionJsonObject

sealed class CatalogItem {
	data class Header(val title: String) : CatalogItem()
	data class Extension(val extension: ExtensionJsonObject) : CatalogItem()
}

class ExtensionCatalogAdapter(
	private val onItemInstallClick: (ExtensionJsonObject) -> Unit,
) : ListAdapter<CatalogItem, RecyclerView.ViewHolder>(DiffCallback) {

	override fun getItemViewType(position: Int): Int = when (getItem(position)) {
		is CatalogItem.Header -> R.layout.item_extension_header
		is CatalogItem.Extension -> R.layout.item_extension_catalog
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return when (viewType) {
			R.layout.item_extension_header -> {
				HeaderViewHolder(ItemExtensionHeaderBinding.inflate(inflater, parent, false))
			}
			else -> {
				ExtensionViewHolder(
					ItemExtensionCatalogBinding.inflate(inflater, parent, false),
					onItemInstallClick
				)
			}
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		val item = getItem(position)
		when {
			holder is HeaderViewHolder && item is CatalogItem.Header -> holder.bind(item)
			holder is ExtensionViewHolder && item is CatalogItem.Extension -> holder.bind(item)
		}
	}

	class HeaderViewHolder(private val binding: ItemExtensionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
		fun bind(item: CatalogItem.Header) {
			binding.textTitle.text = item.title
		}
	}

	class ExtensionViewHolder(
		private val binding: ItemExtensionCatalogBinding,
		private val onItemInstallClick: (ExtensionJsonObject) -> Unit,
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(item: CatalogItem.Extension) {
			val ext = item.extension
			binding.textName.text = ext.name.removePrefix("Tachiyomi: ")
			binding.textVersion.text = "v${ext.version} • ${ext.lang}"
			if (ext.nsfw == 1) {
				binding.textVersion.append(" • 18+")
			}
			
			val iconUrl = "${ext.repoUrl}/icon/${ext.pkg}.png"
			binding.imageIcon.load(iconUrl) {
				placeholder(R.drawable.ic_extension)
				error(R.drawable.ic_extension)
			}
			
			if (ext.isInstalled) {
				binding.buttonInstall.isVisible = false
				binding.buttonAdd.isVisible = true
				binding.buttonAdd.setIconResource(R.drawable.ic_add)
			} else {
				binding.buttonInstall.isVisible = true
				binding.buttonAdd.isVisible = false
			}
			
			binding.buttonInstall.setOnClickListener {
				onItemInstallClick(ext)
			}
			
			binding.buttonAdd.setOnClickListener {
				// Stub for adding the source
			}
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<CatalogItem>() {
		override fun areItemsTheSame(oldItem: CatalogItem, newItem: CatalogItem): Boolean {
			return when {
				oldItem is CatalogItem.Header && newItem is CatalogItem.Header -> oldItem.title == newItem.title
				oldItem is CatalogItem.Extension && newItem is CatalogItem.Extension -> oldItem.extension.pkg == newItem.extension.pkg
				else -> false
			}
		}

		override fun areContentsTheSame(oldItem: CatalogItem, newItem: CatalogItem): Boolean {
			return oldItem == newItem
		}
	}
}