package com.bupt.schedule.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Keeps long descriptions and all actions reachable on compact displays. */
class DetailScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
            else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
