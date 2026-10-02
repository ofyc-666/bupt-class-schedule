package com.bupt.schedule.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.DecelerateInterpolator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.bupt.schedule.ui.model.CourseTone
import com.bupt.schedule.domain.logic.ScheduleLogic
import com.bupt.schedule.ui.model.ScheduleItem
import android.text.TextUtils
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.ui.model.ScheduleAssignmentItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScheduleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val courseTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val reusableRect = RectF()
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var courseRecords: List<ScheduleItem> = emptyList()
    private var dayDates = Array(DAY_COUNT) { "" }
    private var todayIndex = -1
    private var assignmentRecords: List<ScheduleAssignmentItem> = emptyList()
    private var showAssignments: Boolean = false
    private var assignmentModeProgress: Float = 0f
    private var modeAnimator: ValueAnimator? = null
    var onAssignmentClickListener: ((ScheduleAssignmentItem) -> Unit)? = null
    private val ribbonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribbonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ribbonTimelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ribbonAnchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribbonAnchorCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val ribbonShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribbonPath = android.graphics.Path()
    private val assignmentTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val positionedAssignments = mutableListOf<PositionedAssignment>()

    private var currentColumnWidth = 0f
    private var horizontalScrollOffset = 0f
    private var maxScrollOffset = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    var onCourseClickListener: ((String) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setCourseRecords(records: List<ScheduleItem>?) {
        courseRecords = records?.toList().orEmpty()
        invalidate()
    }

    fun setAssignmentRecords(records: List<ScheduleAssignmentItem>?) {
        assignmentRecords = records?.filter { !it.status.isFinished }.orEmpty()
        invalidate()
    }

    fun setShowAssignments(show: Boolean, animate: Boolean = true) {
        if (showAssignments != show) {
            showAssignments = show
            val target = if (show) 1f else 0f
            if (animate && isAttachedToWindow) {
                modeAnimator?.cancel()
                modeAnimator = ValueAnimator.ofFloat(assignmentModeProgress, target).apply {
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        assignmentModeProgress = anim.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                modeAnimator?.cancel()
                assignmentModeProgress = target
                invalidate()
            }
        }
    }

    fun isShowingAssignments(): Boolean = showAssignments

    fun setWeekHeader(dateLabels: List<String>, currentDayIndex: Int) {
        require(dateLabels.size == DAY_COUNT)
        dayDates = dateLabels.toTypedArray()
        todayIndex = currentDayIndex.takeIf { it in 0 until DAY_COUNT } ?: -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val headerHeight = dp(32f)
        val railWidth = (viewWidth * 0.115f).coerceIn(dp(40f), dp(48f))
        val bodyTop = headerHeight
        val rowHeight = (viewHeight - bodyTop) / PERIOD_COUNT

        val visibleDays = 5f
        val columnWidth = (viewWidth - railWidth) / visibleDays
        currentColumnWidth = columnWidth
        val totalDaysWidth = columnWidth * DAY_COUNT
        maxScrollOffset = max(0f, totalDaysWidth - (viewWidth - railWidth))
        horizontalScrollOffset = horizontalScrollOffset.coerceIn(0f, maxScrollOffset)

        val scrollSave = canvas.save()
        canvas.clipRect(railWidth, 0f, viewWidth, viewHeight)
        canvas.translate(-horizontalScrollOffset, 0f)

        drawWeekHeaders(canvas, railWidth, headerHeight, columnWidth)
        drawDaysGrid(canvas, railWidth, bodyTop, rowHeight, columnWidth, viewHeight)
        drawRegularPeriodLines(canvas, railWidth, bodyTop, rowHeight, columnWidth)

        val totalGridWidth = railWidth + columnWidth * DAY_COUNT
        val courseAlphaInt = ((1f - assignmentModeProgress * 0.42f) * 255).roundToInt().coerceIn(0, 255)
        if (courseAlphaInt < 255) {
            val courseLayer = canvas.saveLayerAlpha(
                railWidth, bodyTop, totalGridWidth, viewHeight, courseAlphaInt
            )
            drawCourses(canvas, railWidth, bodyTop, rowHeight, columnWidth)
            canvas.restoreToCount(courseLayer)
        } else {
            drawCourses(canvas, railWidth, bodyTop, rowHeight, columnWidth)
        }

        drawMajorBreakLines(canvas, railWidth, bodyTop, rowHeight, columnWidth)

        if (assignmentModeProgress > 0.01f && assignmentRecords.isNotEmpty()) {
            val assignmentAlpha = (assignmentModeProgress * 255).roundToInt().coerceIn(0, 255)
            val assignmentLayer = if (assignmentAlpha < 255) {
                canvas.saveLayerAlpha(railWidth, bodyTop, totalGridWidth, viewHeight, assignmentAlpha)
            } else -1
            drawAssignments(canvas, railWidth, bodyTop, rowHeight, columnWidth)
            if (assignmentLayer != -1) {
                canvas.restoreToCount(assignmentLayer)
            }
        }

        canvas.restoreToCount(scrollSave)

        drawRail(canvas, railWidth, headerHeight, bodyTop, rowHeight, viewHeight)
        drawRailPeriodLines(canvas, railWidth, bodyTop, rowHeight)

        linePaint.color = Color.rgb(229, 233, 241)
        linePaint.strokeWidth = dp(1f)
        canvas.drawLine(0f, headerHeight, viewWidth, headerHeight, linePaint)
        canvas.drawLine(railWidth, 0f, railWidth, viewHeight, linePaint)
    }

    fun findCourseAt(x: Float, y: Float): ScheduleItem? {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return null

        val headerHeight = dp(32f)
        val railWidth = (viewWidth * 0.115f).coerceIn(dp(40f), dp(48f))
        val bodyTop = headerHeight
        val rowHeight = (viewHeight - bodyTop) / PERIOD_COUNT
        val visibleDays = 5f
        val columnWidth = (viewWidth - railWidth) / visibleDays

        if (x < railWidth || y < bodyTop) return null

        val contentX = x + horizontalScrollOffset
        val contentY = y

        val positioned = positionCourses(courseRecords)
        for (p in positioned) {
            val course = p.course
            val slotLeft = railWidth + (course.day - 1) * columnWidth
            val itemWidth = columnWidth / p.laneCount
            val horizontalInset = if (p.laneCount > 1) dp(1f) else dp(2f)
            val left = slotLeft + p.lane * itemWidth + horizontalInset
            val right = slotLeft + (p.lane + 1) * itemWidth - horizontalInset
            val top = bodyTop + (course.startPeriod - 1) * rowHeight + dp(2f)
            val bottom = bodyTop + (course.startPeriod - 1 + course.duration) * rowHeight - dp(2f)

            if (contentX in left..right && contentY in top..bottom) {
                return course
            }
        }
        return null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        modeAnimator?.cancel()
        modeAnimator = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastTouchX - event.x
                val dy = lastTouchY - event.y
                if (!isDragging && maxScrollOffset > 0f) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isDragging && maxScrollOffset > 0f) {
                    horizontalScrollOffset = (horizontalScrollOffset + dx).coerceIn(0f, maxScrollOffset)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val xVel = velocityTracker?.xVelocity ?: 0f
                    if (Math.abs(xVel) >= minFlingVelocity) {
                        scroller.fling(
                            horizontalScrollOffset.toInt(), 0,
                            (-xVel).toInt(), 0,
                            0, maxScrollOffset.toInt(),
                            0, 0,
                        )
                        postInvalidateOnAnimation()
                    } else {
                        snapToNearestColumn()
                    }
                } else {
                    val clickDx = Math.abs(event.x - downX)
                    val clickDy = Math.abs(event.y - downY)
                    if (clickDx <= touchSlop && clickDy <= touchSlop) {
                        val hitAssignment = findAssignmentAt(event.x, event.y)
                        if (hitAssignment != null) {
                            performClick()
                            onAssignmentClickListener?.invoke(hitAssignment)
                        } else {
                            val hitCourse = findCourseAt(event.x, event.y)
                            if (hitCourse != null) {
                                performClick()
                                onCourseClickListener?.invoke(hitCourse.id)
                            }
                        }
                    }
                }
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    snapToNearestColumn()
                }
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            horizontalScrollOffset = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        } else if (!isDragging && currentColumnWidth > 0f && maxScrollOffset > 0f) {
            snapToNearestColumn()
        }
    }

    private fun snapToNearestColumn() {
        if (currentColumnWidth <= 0f || maxScrollOffset <= 0f || !scroller.isFinished) return
        val target = (horizontalScrollOffset / currentColumnWidth).roundToInt() * currentColumnWidth
        val clamped = target.coerceIn(0f, maxScrollOffset)
        val distance = clamped - horizontalScrollOffset
        if (Math.abs(distance) > 1f) {
            scroller.startScroll(
                horizontalScrollOffset.toInt(), 0,
                distance.toInt(), 0,
                200,
            )
            postInvalidateOnAnimation()
        } else if (distance != 0f) {
            horizontalScrollOffset = clamped
            invalidate()
        }
    }

    private fun drawWeekHeaders(canvas: Canvas, railWidth: Float, headerHeight: Float, columnWidth: Float) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.WHITE
        canvas.drawRect(railWidth, 0f, railWidth + columnWidth * DAY_COUNT, headerHeight, fillPaint)

        if (todayIndex >= 0) {
            val todayLeft = railWidth + todayIndex * columnWidth + dp(3f)
            reusableRect.set(todayLeft, dp(2.5f), todayLeft + columnWidth - dp(6f), headerHeight - dp(2.5f))
            fillPaint.color = Color.rgb(252, 229, 233)
            canvas.drawRoundRect(reusableRect, dp(7f), dp(7f), fillPaint)
        }

        DAY_LABELS.indices.forEach { day ->
            val centerX = railWidth + day * columnWidth + columnWidth / 2f
            val isToday = day == todayIndex
            drawCenteredText(
                canvas, DAY_LABELS[day], centerX, headerHeight * 0.36f, sp(9.5f),
                if (isToday) Color.rgb(189, 82, 103) else Color.rgb(101, 113, 138),
                mediumTypeface,
            )
            drawCenteredText(
                canvas, dayDates[day], centerX, headerHeight * 0.74f, sp(7.5f),
                if (isToday) Color.rgb(189, 82, 103) else Color.rgb(160, 169, 186),
                regularTypeface,
            )
        }
    }

    private fun drawDaysGrid(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
        columnWidth: Float,
        viewHeight: Float,
    ) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.WHITE
        canvas.drawRect(railWidth, bodyTop, railWidth + columnWidth * DAY_COUNT, viewHeight, fillPaint)

        linePaint.color = Color.rgb(238, 241, 246)
        linePaint.strokeWidth = dp(0.75f)
        for (day in 0..DAY_COUNT) {
            val x = railWidth + day * columnWidth
            canvas.drawLine(x, bodyTop, x, viewHeight, linePaint)
        }
    }

    private fun drawRegularPeriodLines(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
        columnWidth: Float,
    ) {
        linePaint.color = Color.rgb(238, 241, 246)
        linePaint.strokeWidth = dp(0.75f)
        val totalWidth = railWidth + columnWidth * DAY_COUNT
        for (boundary in 1 until PERIOD_COUNT) {
            if (boundary != 5 && boundary != 11) {
                val y = bodyTop + boundary * rowHeight
                canvas.drawLine(railWidth, y, totalWidth, y, linePaint)
            }
        }
    }

    private fun drawMajorBreakLines(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
        columnWidth: Float,
    ) {
        linePaint.color = Color.rgb(216, 222, 234)
        linePaint.strokeWidth = dp(1.5f)
        val totalWidth = railWidth + columnWidth * DAY_COUNT
        val breakBoundaries = intArrayOf(5, 11)
        for (boundary in breakBoundaries) {
            val y = bodyTop + boundary * rowHeight
            canvas.drawLine(railWidth, y, totalWidth, y, linePaint)
        }
    }

    private fun drawRailPeriodLines(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
    ) {
        for (boundary in 1 until PERIOD_COUNT) {
            val isMajor = boundary == 5 || boundary == 11
            linePaint.color = if (isMajor) Color.rgb(216, 222, 234) else Color.rgb(238, 241, 246)
            linePaint.strokeWidth = if (isMajor) dp(1.5f) else dp(0.75f)
            val y = bodyTop + boundary * rowHeight
            canvas.drawLine(0f, y, railWidth, y, linePaint)
        }
    }

    private fun drawRail(
        canvas: Canvas,
        railWidth: Float,
        headerHeight: Float,
        bodyTop: Float,
        rowHeight: Float,
        viewHeight: Float,
    ) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.rgb(251, 252, 254)
        canvas.drawRect(0f, 0f, railWidth, headerHeight, fillPaint)
        drawCenteredText(
            canvas, "节次", railWidth / 2f, headerHeight / 2f, sp(8.5f),
            Color.rgb(179, 186, 200), regularTypeface,
        )

        canvas.drawRect(0f, bodyTop, railWidth, viewHeight, fillPaint)

        PERIOD_TIMES.forEachIndexed { index, (startTime, endTime) ->
            val centerY = bodyTop + index * rowHeight + rowHeight / 2f
            drawPeriodLabel(canvas, index + 1, startTime, endTime, railWidth, centerY, rowHeight)
        }
    }

    private fun drawPeriodLabel(
        canvas: Canvas,
        period: Int,
        startTime: String,
        endTime: String,
        railWidth: Float,
        centerY: Float,
        rowHeight: Float,
    ) {
        val centerX = railWidth / 2f
        val rowTop = centerY - rowHeight / 2f

        val numberSize = min(sp(10.5f), rowHeight * 0.28f)
        val timeSize = min(sp(7.5f), rowHeight * 0.19f)

        val numberY = rowTop + rowHeight * 0.28f
        val startY = rowTop + rowHeight * 0.60f
        val endY = rowTop + rowHeight * 0.82f

        drawCenteredText(
            canvas,
            period.toString(),
            centerX,
            numberY,
            numberSize,
            Color.rgb(85, 96, 117),
            mediumTypeface,
        )

        drawCenteredText(
            canvas,
            startTime,
            centerX,
            startY,
            timeSize,
            Color.rgb(156, 163, 175),
            regularTypeface,
        )

        drawCenteredText(
            canvas,
            endTime,
            centerX,
            endY,
            timeSize,
            Color.rgb(156, 163, 175),
            regularTypeface,
        )
    }

    private fun drawCourses(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
        columnWidth: Float,
    ) {
        positionCourses(courseRecords).forEach { positioned ->
            val course = positioned.course
            val slotLeft = railWidth + (course.day - 1) * columnWidth
            val itemWidth = columnWidth / positioned.laneCount
            val horizontalInset = if (positioned.laneCount > 1) dp(1f) else dp(2f)
            val left = slotLeft + positioned.lane * itemWidth + horizontalInset
            val right = slotLeft + (positioned.lane + 1) * itemWidth - horizontalInset
            val top = bodyTop + (course.startPeriod - 1) * rowHeight + dp(2f)
            val bottom = bodyTop + (course.startPeriod - 1 + course.duration) * rowHeight - dp(2f)

            drawCourseCard(canvas, course, left, top, right, bottom, rowHeight)
        }
    }

    private fun positionCourses(records: List<ScheduleItem>): List<PositionedCourse> {
        val result = mutableListOf<PositionedCourse>()
        records.groupBy { it.day }.toSortedMap().values.forEach { dayRecords ->
            val sorted = dayRecords.sortedWith(
                compareBy<ScheduleItem> { it.startPeriod }.thenByDescending { it.duration },
            )
            var component = mutableListOf<ScheduleItem>()
            var componentEnd = -1

            fun flushComponent() {
                if (component.isEmpty()) return
                val laneEnds = mutableListOf<Int>()
                val assignments = mutableListOf<Pair<ScheduleItem, Int>>()
                component.forEach { course ->
                    val freeLane = laneEnds.indexOfFirst { it <= course.startPeriod }
                    val lane = if (freeLane >= 0) freeLane else laneEnds.size.also { laneEnds.add(0) }
                    laneEnds[lane] = course.startPeriod + course.duration
                    assignments += course to lane
                }
                assignments.forEach { (course, lane) ->
                    result += PositionedCourse(course, lane, laneEnds.size)
                }
                component = mutableListOf()
                componentEnd = -1
            }

            sorted.forEach { course ->
                if (component.isNotEmpty() && course.startPeriod >= componentEnd) flushComponent()
                component += course
                componentEnd = max(componentEnd, course.startPeriod + course.duration)
            }
            flushComponent()
        }
        return result
    }

    private fun drawCourseCard(
        canvas: Canvas,
        course: ScheduleItem,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rowHeight: Float,
    ) {
        val palette = TonePalette.forTone(course.tone)
        val radius = dp(8f)
        reusableRect.set(left, top, right, bottom)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = palette.background
        canvas.drawRoundRect(reusableRect, radius, radius, fillPaint)

        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = dp(0.7f)
        fillPaint.color = Color.argb(150, 255, 255, 255)
        canvas.drawRoundRect(reusableRect, radius, radius, fillPaint)
        fillPaint.style = Paint.Style.FILL

        val textSave = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        drawCourseText(canvas, course, palette, left, top, right, bottom, rowHeight)
        canvas.restoreToCount(textSave)
    }

    private fun drawCourseText(
        canvas: Canvas,
        course: ScheduleItem,
        palette: TonePalette,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rowHeight: Float,
    ) {
        val contentLeft = left + dp(5f)
        val contentTop = top + dp(5f)
        val contentWidth = right - contentLeft - dp(5f)
        val contentHeight = bottom - contentTop - dp(4f)
        if (contentWidth < dp(8f) || contentHeight < dp(10f)) return

        val nameSize = (rowHeight * 0.27f).coerceIn(sp(8.5f), sp(10.5f))
        val roomSize = (rowHeight * 0.215f).coerceIn(sp(7f), sp(8.5f))
        val gap = dp(3f)

        courseTextPaint.typeface = mediumTypeface
        courseTextPaint.textSize = nameSize
        courseTextPaint.color = palette.title
        val nameLayout = makeLayout(course.name, contentWidth)
        drawLayout(canvas, nameLayout, contentLeft, contentTop)

        if (course.room.isNotEmpty()) {
            val roomTop = contentTop + nameLayout.height + gap
            courseTextPaint.typeface = regularTypeface
            courseTextPaint.textSize = roomSize
            courseTextPaint.color = palette.detail
            val roomLayout = makeLayout(course.room, contentWidth)
            drawLayout(canvas, roomLayout, contentLeft, roomTop)
        }
    }

    private fun makeLayout(text: String, width: Float): StaticLayout {
        val layoutWidth = max(1, width.toInt())
        return StaticLayout.Builder.obtain(text, 0, text.length, courseTextPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, left: Float, top: Float) {
        val save = canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textSize: Float,
        color: Int,
        typeface: Typeface,
    ) {
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = textSize
        labelPaint.color = color
        labelPaint.setTypeface(typeface)
        canvas.drawText(text, centerX, verticallyCenteredBaseline(labelPaint, centerY), labelPaint)
    }

    private fun verticallyCenteredBaseline(paint: Paint, centerY: Float): Float {
        val metrics = paint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    fun findAssignmentAt(x: Float, y: Float): ScheduleAssignmentItem? {
        if (!showAssignments || assignmentModeProgress < 0.3f || positionedAssignments.isEmpty()) return null
        val contentX = x + horizontalScrollOffset
        val contentY = y
        for (p in positionedAssignments.reversed()) {
            if (p.rect.contains(contentX, contentY)) {
                return p.assignment
            }
        }
        return null
    }

    private fun drawAssignments(
        canvas: Canvas,
        railWidth: Float,
        bodyTop: Float,
        rowHeight: Float,
        columnWidth: Float,
    ) {
        positionedAssignments.clear()
        val ribbonHeight = dp(15f)
        val cornerRadius = dp(5f)
        val anchorRadius = dp(1.8f)

        val grouped = assignmentRecords.groupBy { it.day }
        grouped.forEach { (day, dayAssignments) ->
            if (day in 1..DAY_COUNT) {
                val slotLeft = railWidth + (day - 1) * columnWidth
                val slotRight = slotLeft + columnWidth

                dayAssignments.forEachIndexed { index, item ->
                    val slotIndex = item.deadlineSlot.coerceIn(0, PERIOD_COUNT - 1)
                    val bottomOffset = dp(3.5f) + index * (ribbonHeight + dp(4f))
                    val lineY = bodyTop + (slotIndex + 1) * rowHeight - bottomOffset - ribbonHeight / 2f

                    val lineStartX = slotLeft + dp(1.2f)
                    val anchorX = slotLeft + dp(3.2f)
                    val tagLeft = slotLeft + dp(6.5f)
                    val tagRight = slotRight - dp(2.2f)
                    val tagTop = lineY - ribbonHeight / 2f
                    val tagBottom = lineY + ribbonHeight / 2f
                    val tagCenterY = lineY

                    // Touch hit area (larger for easy clicking, covers full slot width)
                    val hitRect = RectF(slotLeft, lineY - dp(4f), slotRight, tagBottom + dp(4f))
                    positionedAssignments.add(PositionedAssignment(item, hitRect))

                    val palette = when (item.status) {
                        AssignmentStatus.PENDING -> RibbonPalette(
                            solidColor = Color.rgb(249, 115, 22),
                            shadowColor = Color.argb(45, 0, 0, 0),
                            textTypeface = mediumTypeface,
                            isHighEmphasis = true,
                        )
                        AssignmentStatus.SUBMITTED -> RibbonPalette(
                            solidColor = Color.rgb(16, 185, 129),
                            shadowColor = Color.argb(30, 0, 0, 0),
                            textTypeface = regularTypeface,
                            isHighEmphasis = false,
                        )
                        AssignmentStatus.GRADED -> RibbonPalette(
                            solidColor = Color.rgb(139, 92, 246),
                            shadowColor = Color.argb(30, 0, 0, 0),
                            textTypeface = regularTypeface,
                            isHighEmphasis = false,
                        )
                        AssignmentStatus.OVERDUE -> RibbonPalette(
                            solidColor = Color.rgb(100, 116, 139),
                            shadowColor = Color.argb(25, 0, 0, 0),
                            textTypeface = regularTypeface,
                            isHighEmphasis = false,
                        )
                    }

                    val shadowRect = RectF(tagLeft, tagTop + dp(0.8f), tagRight, tagBottom + dp(0.8f))
                    ribbonShadowPaint.color = palette.shadowColor
                    canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, ribbonShadowPaint)

                    val tagRect = RectF(tagLeft, tagTop, tagRight, tagBottom)
                    ribbonBgPaint.color = palette.solidColor
                    canvas.drawRoundRect(tagRect, cornerRadius, cornerRadius, ribbonBgPaint)

                    ribbonTimelinePaint.color = palette.solidColor
                    ribbonTimelinePaint.strokeWidth = if (palette.isHighEmphasis) dp(1.6f) else dp(1.2f)
                    canvas.drawLine(lineStartX, lineY, tagLeft, lineY, ribbonTimelinePaint)

                    ribbonAnchorPaint.color = palette.solidColor
                    canvas.drawCircle(anchorX, lineY, anchorRadius, ribbonAnchorPaint)

                    val textLeft = tagLeft + dp(4.0f)
                    val textRight = tagRight - dp(4.0f)
                    val availableWidth = textRight - textLeft

                    if (availableWidth > dp(10f)) {
                        assignmentTextPaint.typeface = palette.textTypeface
                        assignmentTextPaint.textSize = sp(7.8f)
                        assignmentTextPaint.color = Color.WHITE

                        val rawText = item.deadlineTimeShort + " \u00b7 " + item.courseName
                        val ellipsized = TextUtils.ellipsize(
                            rawText,
                            assignmentTextPaint,
                            availableWidth,
                            TextUtils.TruncateAt.END,
                        ).toString()

                        val textY = verticallyCenteredBaseline(assignmentTextPaint, tagCenterY)
                        canvas.drawText(ellipsized, textLeft, textY, assignmentTextPaint)
                    }
                }
            }
        }
    }

    private data class RibbonPalette(
        val solidColor: Int,
        val shadowColor: Int,
        val textTypeface: Typeface,
        val isHighEmphasis: Boolean,
    )

    private data class PositionedAssignment(
        val assignment: ScheduleAssignmentItem,
        val rect: RectF,
    )

    private data class PositionedCourse(
        val course: ScheduleItem,
        val lane: Int,
        val laneCount: Int,
    )

    private data class TonePalette(
        val background: Int,
        val accent: Int,
        val title: Int,
        val detail: Int,
    ) {
        companion object {
            fun forTone(tone: CourseTone) = when (tone) {
                CourseTone.LILAC -> colors("#EEEAFE", "#8F7CE7", "#554A86", "#776C9D")
                CourseTone.SKY -> colors("#E4F1FD", "#66A7ED", "#3C658F", "#6683A0")
                CourseTone.ROSE -> colors("#FCE8ED", "#EF7894", "#93445A", "#A36C7B")
                CourseTone.MINT -> colors("#E3F7ED", "#55C394", "#39795F", "#668D7D")
                CourseTone.PEACH -> colors("#FCEBE5", "#EE8B6A", "#8B503D", "#9A7468")
                CourseTone.BUTTER -> colors("#FCF3D8", "#E8B846", "#7B612A", "#8F7A4C")
            }

            private fun colors(background: String, accent: String, title: String, detail: String) =
                TonePalette(
                    background = Color.parseColor(background),
                    accent = Color.parseColor(accent),
                    title = Color.parseColor(title),
                    detail = Color.parseColor(detail),
                )
        }
    }

    companion object {
        private const val PERIOD_COUNT = 14
        private const val DAY_COUNT = 7
        private val PERIOD_TIMES = ScheduleLogic.DEFAULT_SLOT_TIMES
        private val DAY_LABELS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
