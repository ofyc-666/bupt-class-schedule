package com.bupt.schedule.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Root container for detail dialogs that constrains dialog height to the screen's usable area.
 *
 * It dynamically divides height between non-scrollable children (such as headers and action bars)
 * and the middle [ScrollView], ensuring:
 * 1. Short content shrinks the dialog naturally to fit.
 * 2. Long content is capped at the maximum allowed height, with the [ScrollView] handling scroll
 *    and action buttons fixed and reachable at the bottom.
 */
class DetailDialogContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        requestLayout()
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxAllowedHeight = resolveMaxAllowedHeight(heightMeasureSpec)

        var scrollChild: View? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ScrollView) {
                scrollChild = child
                break
            }
        }

        if (scrollChild == null || scrollChild.visibility == View.GONE) {
            val constrainedSpec = MeasureSpec.makeMeasureSpec(maxAllowedHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, constrainedSpec)
            return
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        var nonScrollHeight = paddingTop + paddingBottom
        var maxChildWidth = 0

        // Measure non-scrollable children first
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === scrollChild || child.visibility == View.GONE) continue

            val lp = child.layoutParams as? MarginLayoutParams
            val marginH = (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
            val marginV = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)

            val childWidthSpec = getChildMeasureSpec(
                widthMeasureSpec,
                paddingLeft + paddingRight + marginH,
                lp?.width ?: LayoutParams.WRAP_CONTENT
            )
            val childHeightSpec = getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(maxAllowedHeight, MeasureSpec.AT_MOST),
                0,
                lp?.height ?: LayoutParams.WRAP_CONTENT
            )
            child.measure(childWidthSpec, childHeightSpec)

            nonScrollHeight += child.measuredHeight + marginV
            maxChildWidth = maxOf(maxChildWidth, child.measuredWidth + marginH)
        }

        // Measure scrollable child with remaining height
        val scrollLp = scrollChild.layoutParams as? MarginLayoutParams
        val scrollMarginH = (scrollLp?.leftMargin ?: 0) + (scrollLp?.rightMargin ?: 0)
        val scrollMarginV = (scrollLp?.topMargin ?: 0) + (scrollLp?.bottomMargin ?: 0)

        val maxScrollHeight = computeScrollAvailableHeight(maxAllowedHeight, nonScrollHeight, scrollMarginV)
        val scrollWidthSpec = getChildMeasureSpec(
            widthMeasureSpec,
            paddingLeft + paddingRight + scrollMarginH,
            scrollLp?.width ?: LayoutParams.MATCH_PARENT
        )
        val scrollHeightSpec = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
        scrollChild.measure(scrollWidthSpec, scrollHeightSpec)

        maxChildWidth = maxOf(maxChildWidth, scrollChild.measuredWidth + scrollMarginH)

        val totalContentHeight = nonScrollHeight + scrollChild.measuredHeight + scrollMarginV
        val finalHeight = computeFinalHeight(totalContentHeight, maxAllowedHeight)

        val finalWidth = if (widthMode == MeasureSpec.EXACTLY) {
            widthSize
        } else {
            val desiredWidth = maxChildWidth + paddingLeft + paddingRight
            if (widthMode == MeasureSpec.AT_MOST) minOf(desiredWidth, widthSize) else desiredWidth
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }

    private fun resolveMaxAllowedHeight(heightMeasureSpec: Int): Int {
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels

        var topInset = 0
        var bottomInset = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context is Activity) {
            val windowMetrics = (context as Activity).windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topInset = insets.top
            bottomInset = insets.bottom
        } else {
            val rootInsets = rootWindowInsets ?: (context as? Activity)?.window?.decorView?.rootWindowInsets
            if (rootInsets != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val insets = rootInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    topInset = insets.top
                    bottomInset = insets.bottom
                } else {
                    @Suppress("DEPRECATION")
                    topInset = rootInsets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottomInset = rootInsets.systemWindowInsetBottom
                }
            }
        }

        if (topInset == 0) {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) topInset = resources.getDimensionPixelSize(resId)
        }
        if (bottomInset == 0) {
            val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resId > 0) bottomInset = resources.getDimensionPixelSize(resId)
        }

        val topSafetyMargin = (24 * density).toInt()
        val usableHeight = computeUsableHeight(screenHeight, topInset, bottomInset, topSafetyMargin)
        val targetMax = computeMaxHeight(usableHeight, 0.92f)

        val specMode = MeasureSpec.getMode(heightMeasureSpec)
        val specSize = MeasureSpec.getSize(heightMeasureSpec)

        return if (specMode == MeasureSpec.UNSPECIFIED || specSize <= 0) {
            targetMax
        } else {
            minOf(targetMax, specSize)
        }
    }

    companion object {
        fun computeUsableHeight(screenHeight: Int, topInset: Int, bottomInset: Int, topSafetyMargin: Int): Int {
            return maxOf(0, screenHeight - topInset - bottomInset - topSafetyMargin)
        }

        fun computeMaxHeight(usableHeight: Int, ratio: Float = 0.92f): Int {
            return (usableHeight * ratio).toInt()
        }

        fun computeScrollAvailableHeight(maxAllowedHeight: Int, nonScrollHeight: Int, scrollMargins: Int): Int {
            return maxOf(0, maxAllowedHeight - nonScrollHeight - scrollMargins)
        }

        fun computeFinalHeight(totalContentHeight: Int, maxAllowedHeight: Int): Int {
            return minOf(totalContentHeight, maxAllowedHeight)
        }
    }
}
