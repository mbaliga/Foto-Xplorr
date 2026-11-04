package com.fotoxplorr.app.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Lays a widget's `RemoteViews` out the way a launcher would at an exact size and reports what a
 * person would see go wrong: a view pushed outside its parent's padded area (clipped), a line of
 * text given less height than it needs (cut through), or a picture squeezed below a usable size.
 */
internal object WidgetLayoutFit {
    /** The smallest a picture may be drawn and still read as one. */
    const val MIN_PICTURE_DP = 40f

    fun layOut(context: Context, views: RemoteViews, widthDp: Float, heightDp: Float): View {
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).roundToInt()
        val h = (heightDp * density).roundToInt()
        val parent = FrameLayout(context)
        val root = views.apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(w, h))
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        return root
    }

    fun problems(root: View): List<String> {
        val density = root.resources.displayMetrics.density
        val out = mutableListOf<String>()
        fun name(v: View): String = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: v.javaClass.simpleName
        fun visit(v: View, parent: ViewGroup?) {
            if (v.visibility != View.VISIBLE) return
            if (parent != null) {
                val slack = 1
                val outside = v.top < parent.paddingTop - slack ||
                    v.bottom > parent.height - parent.paddingBottom + slack ||
                    v.left < parent.paddingLeft - slack ||
                    v.right > parent.width - parent.paddingRight + slack
                if (outside) out += "${name(v)} outside ${name(parent)} (${v.left},${v.top},${v.right},${v.bottom} in ${parent.width}x${parent.height})"
            }
            if (v is TextView && !v.text.isNullOrBlank()) {
                val layout = v.layout
                if (layout != null) {
                    val needed = layout.height + v.totalPaddingTop + v.totalPaddingBottom
                    if (v.height + 1 < needed) out += "${name(v)} cut: ${v.height}px for ${needed}px (\"${v.text}\")"
                }
            }
            if (v is ImageView && (v.width < MIN_PICTURE_DP * density - 1 || v.height < MIN_PICTURE_DP * density - 1)) {
                out += "${name(v)} squeezed to ${(v.width / density).roundToInt()}x${(v.height / density).roundToInt()}dp"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), v)
        }
        visit(root, null)
        return out
    }
}
