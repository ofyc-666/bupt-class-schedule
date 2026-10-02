package com.bupt.schedule.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class ToggleSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private var isChecked = false
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    private val offTrackColor = Color.rgb(222, 226, 235)
    private val onTrackColor = Color.rgb(97, 114, 213)
    private val thumbColor = Color.WHITE
    private val offStrokeColor = Color.rgb(209, 214, 224)

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            toggle(animate = true)
        }
    }

    fun isChecked(): Boolean = isChecked

    fun setChecked(checked: Boolean, animate: Boolean = true) {
        if (isChecked == checked) return
        isChecked = checked
        val target = if (checked) 1f else 0f
        animator?.cancel()
        animator = null
        if (animate) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = target
            invalidate()
        }
    }

    fun toggle(animate: Boolean = true) {
        setChecked(!isChecked, animate)
        onCheckedChangeListener?.invoke(isChecked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultWidth = dp(34f).toInt()
        val defaultHeight = dp(18f).toInt()
        val width = resolveSize(defaultWidth, widthMeasureSpec)
        val height = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)

        val currentTrackColor = evaluateColor(progress, offTrackColor, onTrackColor)
        trackPaint.color = currentTrackColor
        trackPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        if (progress < 1f) {
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = dp(0.8f)
            strokePaint.color = Color.argb(alpha, Color.red(offStrokeColor), Color.green(offStrokeColor), Color.blue(offStrokeColor))
            canvas.drawRoundRect(trackRect, radius, radius, strokePaint)
        }

        val thumbPadding = dp(2f)
        val thumbRadius = radius - thumbPadding
        val leftX = radius
        val rightX = w - radius
        val thumbCenterX = leftX + (rightX - leftX) * progress
        val thumbCenterY = radius

        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = Color.argb(35, 0, 0, 0)
        canvas.drawCircle(thumbCenterX, thumbCenterY + dp(0.7f), thumbRadius, thumbPaint)

        thumbPaint.color = thumbColor
        canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, thumbPaint)
    }

    private fun evaluateColor(fraction: Float, startColor: Int, endColor: Int): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (fraction * (endA - startA))).toInt(),
            (startR + (fraction * (endR - startR))).toInt(),
            (startG + (fraction * (endG - startG))).toInt(),
            (startB + (fraction * (endB - startB))).toInt(),
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
