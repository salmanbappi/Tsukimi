package org.koitharu.kotatsu.settings.extension.catalog

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityExtensionCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration

@AndroidEntryPoint
class ExtensionCatalogActivity : BaseActivity<ActivityExtensionCatalogBinding>() {

	private val viewModel by viewModels<ExtensionCatalogViewModel>()
	private var filterMenu: PopupMenu? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityExtensionCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		setTitle("Extension Catalog")

		val catalogAdapter = ExtensionCatalogAdapter(
			onItemInstallClick = { ext ->
				viewModel.installExtension(ext)
			},
			onItemAddClick = { ext ->
				viewModel.toggleExtensionSource(ext)
			}
		)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = catalogAdapter
		}

		viewModel.extensions.observe(this) { list: List<CatalogItem> ->
			catalogAdapter.submitList(list)
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.opt_extension_catalog, menu)
		val searchItem = menu.findItem(R.id.action_search)
		val searchView = searchItem.actionView as SearchView
		searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
			override fun onQueryTextSubmit(query: String?): Boolean = false
			override fun onQueryTextChange(newText: String?): Boolean {
				viewModel.setQuery(newText.orEmpty())
				return true
			}
		})
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_filter -> {
			showFilterMenu(findViewById(R.id.action_filter))
			true
		}
		else -> super.onOptionsItemSelected(item)
	}

	private fun showFilterMenu(view: View) {
		val menu = PopupMenu(this, view)
		filterMenu = menu
		
		val languages = viewModel.languages.value

		menu.menu.add(Menu.NONE, 0, 0, "All Languages")
		languages.forEachIndexed { index: Int, lang: String ->
			menu.menu.add(Menu.NONE, index + 1, index + 1, lang.uppercase())
		}
		
		menu.setOnMenuItemClickListener { menuItem ->
			if (menuItem.itemId == 0) {
				viewModel.setLanguage(null)
			} else {
				viewModel.setLanguage(languages.getOrNull(menuItem.itemId - 1))
			}
			true
		}
		menu.show()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}
}