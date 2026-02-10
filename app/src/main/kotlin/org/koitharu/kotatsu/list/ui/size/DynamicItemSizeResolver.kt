package org.koitharu.kotatsu.list.ui.size

import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import kotlin.math.roundToInt

class DynamicItemSizeResolver(
	resources: Resources,
	lifecycleOwner: LifecycleOwner,
	private val settings: AppSettings,
	private val adjustWidth: Boolean,
) : ItemSizeResolver, SharedPreferences.OnSharedPreferenceChangeListener, DefaultLifecycleObserver {

	private val gridWidth = resources.getDimension(R.dimen.preferred_grid_width)
	private val scaleFactor: Float
		get() = settings.gridSize / 100f

	override val cellWidth: Int
		get() = (gridWidth * scaleFactor).roundToInt()

	private val observers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<SizeObserver, Boolean>())

	init {
		lifecycleOwner.lifecycle.addObserver(this)
		settings.subscribe(this)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_GRID_SIZE) {
			observers.forEach { it.update() }
		}
	}

	override fun onDestroy(owner: LifecycleOwner) {
		settings.unsubscribe(this)
		observers.clear()
	}

	override fun attachToView(
		view: View,
		textView: TextView?,
		progressView: ReadingProgressView?
	) {
		val observer = SizeObserver(view, textView, progressView)
		observers.add(observer)
		if (view.isAttachedToWindow) {
			observer.update()
		}
	}

	private inner class SizeObserver(
		private val view: View,
		private val textView: TextView?,
		private val progressView: ReadingProgressView?,
	) {

		private val widthThreshold = view.resources.getDimensionPixelSize(R.dimen.small_grid_width)

		@StyleRes
		private var prevTextAppearance = 0

		fun update() {
			val newWidth = cellWidth
			textView?.adjustTextAppearance(newWidth)
			if (adjustWidth) {
				val lp = view.layoutParams
				if (lp != null && lp.width != newWidth) {
					lp.width = newWidth
					view.layoutParams = lp
				}
			}
			progressView?.adjustSize(newWidth)
		}

		private fun ReadingProgressView.adjustSize(width: Int) {
			val lp = layoutParams ?: return
			val size = resources.getDimensionPixelSize(
				if (width < widthThreshold) {
					R.dimen.card_indicator_size_small
				} else {
					R.dimen.card_indicator_size
				},
			)
			if (lp.width != size || lp.height != size) {
				lp.width = size
				lp.height = size
				layoutParams = lp
			}
		}

		private fun TextView.adjustTextAppearance(width: Int) {
			val textAppearanceResId = if (width < widthThreshold) {
				R.style.TextAppearance_Kotatsu_GridTitle_Small
			} else {
				R.style.TextAppearance_Kotatsu_GridTitle
			}
			if (textAppearanceResId != prevTextAppearance) {
				prevTextAppearance = textAppearanceResId
				TextViewCompat.setTextAppearance(this, textAppearanceResId)
			}
		}
	}
}
