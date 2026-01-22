package org.koitharu.kotatsu.list.ui.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.SheetMangaPreviewBinding
import org.koitharu.kotatsu.parsers.model.Manga

class MangaPreviewSheet : BaseAdaptiveSheet<SheetMangaPreviewBinding>() {

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetMangaPreviewBinding {
		return SheetMangaPreviewBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetMangaPreviewBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val manga = requireArguments().getParcelable<ParcelableManga>(AppRouter.KEY_MANGA)?.manga ?: return
		binding.headerBar.title = manga.title
		if (savedInstanceState == null) {
			childFragmentManager.commit {
				replace(R.id.preview_container, PreviewFragment().withArgs(1) {
					putParcelable(AppRouter.KEY_MANGA, ParcelableManga(manga))
				})
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		return insets
	}

	companion object {
		fun newInstance(manga: Manga): MangaPreviewSheet {
			return MangaPreviewSheet().withArgs(1) {
				putParcelable(AppRouter.KEY_MANGA, ParcelableManga(manga))
			}
		}
	}
}