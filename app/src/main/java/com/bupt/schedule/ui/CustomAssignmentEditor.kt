package com.bupt.schedule.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.TextView
import com.bupt.schedule.R
import com.bupt.schedule.data.remote.UCloudAssignmentParser
import com.bupt.schedule.domain.model.CustomAssignment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object CustomAssignmentEditor {
    fun show(activity: Activity, existing: CustomAssignment?, courseNames: List<String>, save: (CustomAssignment) -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_custom_assignment)
        val title = dialog.findViewById<EditText>(R.id.custom_title)
        val course = dialog.findViewById<AutoCompleteTextView>(R.id.custom_course)
        val description = dialog.findViewById<EditText>(R.id.custom_description)
        val dateButton = dialog.findViewById<Button>(R.id.custom_date)
        val timeButton = dialog.findViewById<Button>(R.id.custom_time)
        val error = dialog.findViewById<TextView>(R.id.custom_error)
        val deadline = Calendar.getInstance(UCloudAssignmentParser.SHANGHAI).apply {
            if (existing != null) timeInMillis = existing.deadlineMillis
            else { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val names = courseNames.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        val density = activity.resources.displayMetrics.density
        val courseRow = dialog.findViewById<View>(R.id.custom_course_row) ?: course

        val updateDropdownHeight = { count: Int ->
            val itemHeight = (44 * density).toInt()
            val popupPadding = (8 * density).toInt()
            val safetyMargin = (10 * density).toInt()
            val spaceBelow = CourseDropdownHelper.calculateSpaceBelow(courseRow, safetyMargin)
            course.dropDownHeight = CourseDropdownHelper.computeDropDownHeight(
                count = count,
                itemHeight = itemHeight,
                popupPadding = popupPadding,
                spaceBelow = spaceBelow
            )
        }

        course.setAdapter(CourseDropdownAdapter(activity, names) { count ->
            updateDropdownHeight(count)
        })
        course.threshold = 1
        course.setDropDownBackgroundResource(R.drawable.bg_course_dropdown_popup)
        course.setDropDownAnchor(R.id.custom_course_row)
        course.dropDownVerticalOffset = (4 * density).toInt()

        title.setText(existing?.title.orEmpty())
        course.setText(existing?.courseName.orEmpty(), false)
        description.setText(existing?.description.orEmpty())
        dialog.findViewById<TextView>(R.id.custom_editor_heading).text = if (existing == null) "\u81ea\u5b9a\u4e49\u4f5c\u4e1a" else "\u7f16\u8f91\u4f5c\u4e1a"
        dialog.findViewById<TextView>(R.id.custom_editor_badge).text = if (existing == null) "\u81ea\u5b9a\u4e49" else "\u7f16\u8f91"
        fun updateDeadline() {
            dateButton.text = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
                timeZone = UCloudAssignmentParser.SHANGHAI
            }.format(deadline.time)
            timeButton.text = UCloudAssignmentParser.formatTimeShort(deadline)
        }
        updateDeadline()
        dateButton.setOnClickListener {
            CustomAssignmentPickers.showDatePicker(activity, deadline) { updateDeadline() }
        }
        timeButton.setOnClickListener {
            CustomAssignmentPickers.showTimePicker(activity, deadline) { updateDeadline() }
        }
        dialog.findViewById<TextView>(R.id.custom_pick_course).setOnClickListener {
            CustomAssignmentPickers.showCoursePicker(activity, names, course.text.toString()) { selected ->
                course.setText(selected, false)
            }
        }
        dialog.findViewById<View>(R.id.custom_cancel_close).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.custom_cancel).setOnClickListener { dialog.dismiss() }
        val showError = { msg: String ->
            error.text = msg
            error.visibility = View.VISIBLE
            dialog.findViewById<View>(R.id.custom_editor_scroll)?.let { scroll ->
                scroll.post {
                    (scroll as? DetailScrollView)?.smoothScrollTo(0, error.bottom)
                }
            }
        }
        val hideError = {
            if (error.visibility != View.GONE) {
                error.visibility = View.GONE
            }
        }

        title.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { hideError() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        course.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { hideError() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.findViewById<Button>(R.id.custom_save).setOnClickListener {
            val name = title.text.toString().trim()
            val courseName = course.text.toString().trim()
            if (name.isEmpty()) {
                showError("\u8bf7\u586b\u5199\u4f5c\u4e1a\u540d\u79f0")
                title.requestFocus()
                return@setOnClickListener
            }
            if (courseName.isEmpty()) {
                showError("\u8bf7\u586b\u5199\u6240\u5c5e\u8bfe\u7a0b")
                course.requestFocus()
                return@setOnClickListener
            }
            runCatching {
                val task = existing?.copy(title = name, courseName = courseName, deadlineMillis = deadline.timeInMillis,
                    description = description.text.toString().trim())
                    ?: CustomAssignment(title = name, courseName = courseName, deadlineMillis = deadline.timeInMillis,
                        description = description.text.toString().trim())
                save(task)
            }.onSuccess { dialog.dismiss() }.onFailure {
                showError("\u4fdd\u5b58\u5931\u8d25\uff0c\u672c\u5730\u539f\u6709\u6570\u636e\u5df2\u4fdd\u7559\uff0c\u8bf7\u91cd\u8bd5")
            }
        }
        dialog.window?.let { window ->
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }

    private class CourseDropdownAdapter(
        context: Context,
        private val allCourses: List<String>,
        private val onFilterResult: (Int) -> Unit
    ) : ArrayAdapter<String>(context, R.layout.item_course_dropdown, R.id.dropdown_course_text, ArrayList(allCourses)) {

        private val lock = Any()
        private var filteredList: List<String> = allCourses

        override fun getCount(): Int = filteredList.size
        override fun getItem(position: Int): String? = filteredList.getOrNull(position)

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    if (constraint.isNullOrBlank()) {
                        synchronized(lock) {
                            results.values = allCourses
                            results.count = allCourses.size
                        }
                    } else {
                        val query = constraint.toString().trim().lowercase()
                        val matches = allCourses.filter { it.lowercase().contains(query) }
                        results.values = matches
                        results.count = matches.size
                    }
                    return results
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    filteredList = (results?.values as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    onFilterResult(filteredList.size)
                    if (results != null && results.count > 0) {
                        notifyDataSetChanged()
                    } else {
                        notifyDataSetInvalidated()
                    }
                }
            }
        }
    }
}
