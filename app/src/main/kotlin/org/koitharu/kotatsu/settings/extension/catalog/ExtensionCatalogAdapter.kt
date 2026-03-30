package org.koitharu.kotatsu.settings.extension.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
	private val onItemAddClick: (ExtensionJsonObject) -> Unit,
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
					onItemInstallClick,
					onItemAddClick
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
		private val onItemAddClick: (ExtensionJsonObject) -> Unit,
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(item: CatalogItem.Extension) {
			val ext = item.extension
			binding.textName.text = ext.name.removePrefix("Tachiyomi: ")
			binding.textVersion.text = "v${ext.version} • ${ext.lang}"
			if (ext.nsfw == 1) {
				binding.textVersion.append(" • 18+")
			}
			
			binding.textSources.text = ext.sources?.joinToString { it.name }
			binding.textSources.isVisible = !ext.sources.isNullOrEmpty()
			
			val iconUrl = "${ext.repoUrl}/icon/${ext.pkg}.png"
			binding.imageIcon.placeholderDrawable = androidx.core.content.ContextCompat.getDrawable(itemView.context, R.drawable.ic_extension)
			binding.imageIcon.errorDrawable = binding.imageIcon.placeholderDrawable
			binding.imageIcon.setImageAsync(iconUrl)
			
			if (ext.isInstalled) {
				binding.buttonInstall.isVisible = false
				binding.buttonAdd.isVisible = true
				binding.divider.isVisible = true
				binding.buttonAdd.setImageResource(R.drawable.ic_add)
			} else {
				binding.buttonInstall.isVisible = true
				binding.buttonAdd.isVisible = false
				binding.divider.isVisible = false
			}
			
			binding.buttonInstall.setOnClickListener {
				onItemInstallClick(ext)
			}
			
			binding.buttonAdd.setOnClickListener {
				onItemAddClick(ext)
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