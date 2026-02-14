package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import org.koitharu.kotatsu.core.util.ext.findActivity
import java.lang.ref.WeakReference

class TitleScrollCoordinator(
	private val titleView: TextView,
) : NestedScrollView.OnScrollChangeListener {

	private val location = IntArray(2)
	private var activityRef: WeakReference<AppCompatActivity>? = null

	override fun onScrollChange(v: NestedScrollView, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
		val actionBar = getActivity(v.context)?.supportActionBar ?: return
		// Use relative position to scroll container instead of screen coordinates
		val threshold = titleView.bottom
		actionBar.setDisplayShowTitleEnabled(scrollY > threshold)
	}

	fun attach(scrollView: NestedScrollView) {
		scrollView.setOnScrollChangeListener(this)
		scrollView.doOnLayout {
			onScrollChange(scrollView, 0, 0, 0, 0)
		}
	}

	private fun getActivity(context: Context): AppCompatActivity? {
		activityRef?.get()?.let {
			if (!it.isDestroyed) return it
		}
		val activity = context.findActivity() as? AppCompatActivity
		if (activity == null || activity.isDestroyed) {
			return null
		}
		activityRef = WeakReference(activity)
		return activity
	}
}
