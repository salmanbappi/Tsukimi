package org.koitharu.kotatsu.settings.extension.repos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentExtensionReposBinding

@AndroidEntryPoint
class ExtensionReposFragment : BaseFragment<FragmentExtensionReposBinding>() {

	private val viewModel by viewModels<ExtensionReposViewModel>()
	private var adapter: ExtensionRepoAdapter? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentExtensionReposBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentExtensionReposBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		adapter = ExtensionRepoAdapter(
			onDeleteClick = { repo ->
				viewModel.deleteRepo(repo.baseUrl)
			}
		)
		binding.recyclerView.layoutManager = LinearLayoutManager(context)
		binding.recyclerView.adapter = adapter

		binding.fabAdd.setOnClickListener {
			showAddRepoDialog()
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.repos.collect { repos ->
				adapter?.submitList(repos)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		v.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onResume() {
		super.onResume()
		activity?.title = "Extension Repos"
	}

	override fun onDestroyView() {
		adapter = null
		super.onDestroyView()
	}

	private fun showAddRepoDialog() {
		val context = context ?: return
		val input = EditText(context).apply {
			hint = "https://example.com/index.min.json"
		}
		
		AlertDialog.Builder(context)
			.setTitle("Add Extension Repo")
			.setView(input)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				val url = input.text.toString()
				if (url.isNotBlank()) {
					viewModel.addRepo(url) { success, error ->
						if (!success) {
							// Show error toast or snackbar
						}
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}
}