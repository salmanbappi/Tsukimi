package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R

class IconsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private var iconSize = LayoutParams.WRAP_CONTENT
	private var iconSpacing = 0

	val iconsCount: Int
		get() {
			var count = 0
			repeat(childCount) { i ->
				if (getChildAt(i).isVisible) {
					count++
				}
			}
			return count
		}

	init {
		context.withStyledAttributes(attrs, R.styleable.IconsView) {
			iconSize = getDimensionPixelSize(R.styleable.IconsView_iconSize, iconSize)
			iconSpacing = getDimensionPixelOffset(R.styleable.IconsView_iconSpacing, iconSpacing)
		}
	}

	fun setIcons(icons: List<Any>) {
		// 'icons' can contain Drawables or Int (resIds)
		var index = 0
		for (icon in icons) {
			val imageView = (getChildAt(index) as? ImageView) ?: addImageView()
			
			// Only update/invalidate if changed
			if (icon is Int) {
				// We can't easily check current drawable res id without tagging, 
				// but setting same res id is usually cheap in ImageView.
				// However, visibility check is crucial.
				if (imageView.tag != icon) {
					imageView.setImageResource(icon)
					imageView.tag = icon
				}
			} else if (icon is Drawable) {
				if (imageView.drawable != icon) {
					imageView.setImageDrawable(icon)
					imageView.tag = null
				}
			}
			
			if (!imageView.isVisible) {
				imageView.isVisible = true
			}
			index++
		}
		
		// Hide remaining
		for (i in index until childCount) {
			val imageView = getChildAt(i) as? ImageView ?: continue
			if (imageView.isVisible) {
				imageView.setImageDrawable(null)
				imageView.tag = null
				imageView.isVisible = false
			}
		}
	}

	fun clearIcons() {
		// Deprecated in favor of setIcons for batch updates to avoid multiple layout passes
		repeat(childCount) { i ->
			val view = getChildAt(i)
			if (view.isVisible) view.isVisible = false
		}
	}

	fun addIcon(drawable: Drawable) {
		val imageView = getNextImageView()
		imageView.setImageDrawable(drawable)
		imageView.tag = null
		if (!imageView.isVisible) imageView.isVisible = true
	}

	fun addIcon(@DrawableRes resId: Int) {
		val imageView = getNextImageView()
		if (imageView.tag != resId) {
			imageView.setImageResource(resId)
			imageView.tag = resId
		}
		if (!imageView.isVisible) imageView.isVisible = true
	}

	private fun getNextImageView(): ImageView {
		repeat(childCount) { i ->
			val child = getChildAt(i)
			if (child is ImageView && !child.isVisible) {
				return child
			}
		}
		return addImageView()
	}

	private fun addImageView() = ImageView(context).also {
		it.scaleType = ImageView.ScaleType.FIT_CENTER
		val lp = LayoutParams(iconSize, iconSize)
		if (isNotEmpty()) {
			lp.marginStart = iconSpacing
		}
		addView(it, lp)
	}
}
