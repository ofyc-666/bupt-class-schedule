package com.bupt.schedule.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.bupt.schedule.R
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import java.util.Calendar
import java.util.Locale

object CustomAssignmentPickers {

    private fun createBottomSheetDialog(activity: Activity, layoutResId: Int): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutResId)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        return dialog
    }

    fun showCoursePicker(
        activity: Activity,
        courseNames: List<String>,
        currentCourse: String,
        onSelect: (String) -> Unit
    ) {
        val dialog = createBottomSheetDialog(activity, R.layout.dialog_custom_course_picker)

        val emptyView = dialog.findViewById<TextView>(R.id.course_picker_empty)
        val listContainer = dialog.findViewById<LinearLayout>(R.id.course_picker_list)
        dialog.findViewById<View>(R.id.course_picker_close).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.course_picker_cancel).setOnClickListener { dialog.dismiss() }

        if (courseNames.isEmpty()) {
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
            val inflater = activity.layoutInflater
            val density = activity.resources.displayMetrics.density
            val dividerMargin = (14 * density).toInt()
            val dividerHeight = (1 * density).toInt().coerceAtLeast(1)

            courseNames.forEachIndexed { index, name ->
                val itemView = inflater.inflate(R.layout.item_custom_course_picker, listContainer, false)
                val nameView = itemView.findViewById<TextView>(R.id.course_item_name)
                val checkView = itemView.findViewById<ImageView>(R.id.course_item_check)
                nameView.text = name
                val isSelected = name == currentCourse.trim()
                checkView.visibility = if (isSelected) View.VISIBLE else View.GONE
                if (isSelected) {
                    nameView.setTextColor(Color.parseColor("#6574C8"))
                }
                itemView.setOnClickListener {
                    onSelect(name)
                    dialog.dismiss()
                }
                listContainer.addView(itemView)

                if (index < courseNames.size - 1) {
                    val divider = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dividerHeight
                        ).apply {
                            marginStart = dividerMargin
                            marginEnd = dividerMargin
                        }
                        setBackgroundColor(Color.parseColor("#F0F2F6"))
                    }
                    listContainer.addView(divider)
                }
            }
        }
        dialog.show()
    }

    fun showDatePicker(
        activity: Activity,
        deadline: Calendar,
        onConfirmed: () -> Unit
    ) {
        val dialog = createBottomSheetDialog(activity, R.layout.dialog_custom_date_picker)

        val yearPicker = dialog.findViewById<NumberPicker>(R.id.picker_year)
        val monthPicker = dialog.findViewById<NumberPicker>(R.id.picker_month)
        val dayPicker = dialog.findViewById<NumberPicker>(R.id.picker_day)

        val currentYear = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).get(Calendar.YEAR)
        val startYear = currentYear - 1
        val endYear = currentYear + 3

        yearPicker.minValue = startYear
        yearPicker.maxValue = endYear
        yearPicker.displayedValues = (startYear..endYear).map { "${it}\u5e74" }.toTypedArray()
        yearPicker.wrapSelectorWheel = false

        monthPicker.minValue = 1
        monthPicker.maxValue = 12
        monthPicker.displayedValues = (1..12).map { "${it}\u6708" }.toTypedArray()
        monthPicker.wrapSelectorWheel = true

        fun updateDays(targetDay: Int? = null) {
            val tempCal = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).apply {
                set(Calendar.YEAR, yearPicker.value)
                set(Calendar.MONTH, monthPicker.value - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val maxDays = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val selectedDay = (targetDay ?: dayPicker.value).coerceIn(1, maxDays)
            dayPicker.displayedValues = null
            dayPicker.value = 1
            dayPicker.minValue = 1
            dayPicker.maxValue = maxDays
            dayPicker.displayedValues = (1..maxDays).map { "${it}\u65e5" }.toTypedArray()
            dayPicker.value = selectedDay
            dayPicker.wrapSelectorWheel = true
        }

        yearPicker.value = deadline.get(Calendar.YEAR).coerceIn(startYear, endYear)
        monthPicker.value = deadline.get(Calendar.MONTH) + 1
        updateDays(deadline.get(Calendar.DAY_OF_MONTH))

        val changeListener = NumberPicker.OnValueChangeListener { _, _, _ -> updateDays() }
        yearPicker.setOnValueChangedListener(changeListener)
        monthPicker.setOnValueChangedListener(changeListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            yearPicker.selectionDividerHeight = 0
            monthPicker.selectionDividerHeight = 0
            dayPicker.selectionDividerHeight = 0
        }

        fun applyDate(cal: Calendar) {
            val y = cal.get(Calendar.YEAR).coerceIn(startYear, endYear)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            yearPicker.value = y
            monthPicker.value = m
            updateDays(d)
        }

        dialog.findViewById<View>(R.id.date_chip_today).setOnClickListener {
            applyDate(Calendar.getInstance(UCloudAssignmentParser.SHANGHAI))
        }
        dialog.findViewById<View>(R.id.date_chip_tomorrow).setOnClickListener {
            applyDate(Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).apply {
                add(Calendar.DAY_OF_MONTH, 1)
            })
        }
        dialog.findViewById<View>(R.id.date_chip_this_sunday).setOnClickListener {
            val cal = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI)
            val chinaDay = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK) - 1
            cal.add(Calendar.DAY_OF_MONTH, 7 - chinaDay)
            applyDate(cal)
        }
        dialog.findViewById<View>(R.id.date_chip_next_sunday).setOnClickListener {
            val cal = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI)
            val chinaDay = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK) - 1
            cal.add(Calendar.DAY_OF_MONTH, 7 - chinaDay + 7)
            applyDate(cal)
        }

        dialog.findViewById<View>(R.id.date_picker_close).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.date_picker_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.date_picker_confirm).setOnClickListener {
            deadline.set(Calendar.YEAR, yearPicker.value)
            deadline.set(Calendar.MONTH, monthPicker.value - 1)
            deadline.set(Calendar.DAY_OF_MONTH, dayPicker.value)
            onConfirmed()
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showTimePicker(
        activity: Activity,
        deadline: Calendar,
        onConfirmed: () -> Unit
    ) {
        val dialog = createBottomSheetDialog(activity, R.layout.dialog_custom_time_picker)

        val hourPicker = dialog.findViewById<NumberPicker>(R.id.picker_hour)
        val minutePicker = dialog.findViewById<NumberPicker>(R.id.picker_minute)

        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        hourPicker.displayedValues = (0..23).map { String.format(Locale.CHINA, "%02d", it) }.toTypedArray()
        hourPicker.value = deadline.get(Calendar.HOUR_OF_DAY)
        hourPicker.wrapSelectorWheel = true

        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.displayedValues = (0..59).map { String.format(Locale.CHINA, "%02d", it) }.toTypedArray()
        minutePicker.value = deadline.get(Calendar.MINUTE)
        minutePicker.wrapSelectorWheel = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hourPicker.selectionDividerHeight = 0
            minutePicker.selectionDividerHeight = 0
        }

        fun applyTime(h: Int, m: Int) {
            hourPicker.value = h
            minutePicker.value = m
        }

        dialog.findViewById<View>(R.id.time_chip_2359).setOnClickListener { applyTime(23, 59) }
        dialog.findViewById<View>(R.id.time_chip_2200).setOnClickListener { applyTime(22, 0) }
        dialog.findViewById<View>(R.id.time_chip_1800).setOnClickListener { applyTime(18, 0) }
        dialog.findViewById<View>(R.id.time_chip_1200).setOnClickListener { applyTime(12, 0) }

        dialog.findViewById<View>(R.id.time_picker_close).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.time_picker_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.time_picker_confirm).setOnClickListener {
            deadline.set(Calendar.HOUR_OF_DAY, hourPicker.value)
            deadline.set(Calendar.MINUTE, minutePicker.value)
            onConfirmed()
            dialog.dismiss()
        }

        dialog.show()
    }
}
