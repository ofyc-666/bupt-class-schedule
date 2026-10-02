package com.bupt.schedule

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bupt.schedule.data.DefaultScheduleRepository
import com.bupt.schedule.data.local.RefreshMetadataStore
import com.bupt.schedule.data.local.ScheduleStore
import com.bupt.schedule.data.local.SecureCredentialStore
import com.bupt.schedule.data.remote.SjdScheduleClient
import com.bupt.schedule.domain.logic.ScheduleLogic
import com.bupt.schedule.domain.logic.SemesterLogic
import com.bupt.schedule.domain.model.Course
import com.bupt.schedule.domain.model.Credentials
import com.bupt.schedule.domain.model.ScheduleException
import com.bupt.schedule.domain.model.ScheduleFailureKind
import com.bupt.schedule.domain.model.ScheduleSnapshot
import com.bupt.schedule.domain.repository.ScheduleRepository
import com.bupt.schedule.domain.usecase.CredentialSaveMode
import com.bupt.schedule.domain.usecase.RefreshSchedule
import com.bupt.schedule.domain.usecase.UCloudPasswordManager
import com.bupt.schedule.domain.usecase.AssignmentRefreshGate
import com.bupt.schedule.ui.ScheduleUiMapper
import com.bupt.schedule.ui.ScheduleView
import com.bupt.schedule.ui.ToggleSwitch
import android.graphics.Typeface
import com.bupt.schedule.data.DefaultAssignmentRepository
import com.bupt.schedule.data.local.AssignmentStore
import com.bupt.schedule.domain.repository.AssignmentRepository
import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.domain.model.TaskType
import com.bupt.schedule.ui.model.ScheduleAssignmentItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.bupt.schedule.data.remote.UCloudAssignmentClient


enum class AssignmentSyncState {
    IDLE,
    LOADING,
    SUCCESS,
    FAILED_WITH_CACHE,
    FAILED_NO_CACHE,
}

/** Activity 只编排登录/缓存/刷新与普通 View 状态，不包含接口或解析细节。 */
class MainActivity : Activity() {
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val assignmentWorker = Executors.newSingleThreadExecutor()
    private val refreshInFlight = AtomicBoolean(false)
    private val assignmentRefreshInFlight = AssignmentRefreshGate()
    private var isSpinning = false
    private var currentSnapshot: ScheduleSnapshot? = null
    private var selectedWeek: Int = 1
    private var revertRefreshStatusRunnable: Runnable? = null

    private lateinit var credentialStore: SecureCredentialStore
    private lateinit var refreshMetadataStore: RefreshMetadataStore
    private lateinit var repository: ScheduleRepository
    private lateinit var assignmentRepository: AssignmentRepository
    private lateinit var ucloudPasswordManager: UCloudPasswordManager
    private lateinit var refreshSchedule: RefreshSchedule
    private lateinit var loginPage: View
    private lateinit var schedulePage: View
    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var teachingCloudPasswordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginError: TextView
    private lateinit var semesterLabel: TextView
    private lateinit var weekBadge: View
    private lateinit var weekBadgeText: TextView
    private lateinit var weekRange: TextView
    private lateinit var refreshButton: View
    private lateinit var refreshIcon: ImageView
    private lateinit var refreshLabel: TextView
    private lateinit var scheduleProgress: ProgressBar
    private lateinit var scheduleStatus: TextView
    private lateinit var scheduleView: ScheduleView
    private lateinit var mainContainer: View
    private lateinit var tabSchedule: View
    private lateinit var tabScheduleIcon: ImageView
    private lateinit var tabScheduleText: TextView
    private lateinit var tabAssignment: View
    private lateinit var tabAssignmentIcon: ImageView
    private lateinit var tabAssignmentText: TextView
    private lateinit var assignmentPage: View
    private lateinit var assignmentSwitchContainer: View
    private lateinit var assignmentToggleSwitch: ToggleSwitch
    private lateinit var assignmentStatsBadge: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipPending: TextView
    private lateinit var chipOverdue: TextView
    private lateinit var assignmentListContainer: ViewGroup
    private lateinit var assignmentEmptyView: View
    private lateinit var assignmentEmptyTitle: TextView
    private lateinit var assignmentEmptySubtitle: TextView
    private lateinit var assignmentEmptyAction: TextView
    private var assignmentSyncState: AssignmentSyncState = AssignmentSyncState.IDLE
    private var currentAssignments: List<Assignment> = emptyList()
    private var selectedAssignmentFilter: AssignmentStatus? = null
    private var currentTabIsSchedule: Boolean = true
    private var isAssignmentSwitchOn: Boolean = false
    private lateinit var assignmentRefreshBtn: View
    private lateinit var assignmentRefreshIcon: ImageView
    private lateinit var assignmentCloudStatus: TextView
    private lateinit var assignmentReauthBtn: TextView
    private lateinit var assignmentUnbindBtn: TextView
    private var isAssignmentSpinning: Boolean = false

    private enum class PendingUCloudAction {
        OPEN_TAB,
        ENABLE_SWITCH,
        MANUAL_REFRESH,
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureSystemBars()
        configureContentInsets()
        bindViews()

        credentialStore = SecureCredentialStore(applicationContext)
        refreshMetadataStore = RefreshMetadataStore(applicationContext)
        repository = DefaultScheduleRepository(
            client = SjdScheduleClient(),
            store = ScheduleStore(applicationContext),
        )
        assignmentRepository = DefaultAssignmentRepository(
            client = UCloudAssignmentClient(),
            store = AssignmentStore(applicationContext),
        )
        ucloudPasswordManager = UCloudPasswordManager(
            verify = assignmentRepository::verifyCredentials,
            save = credentialStore::save,
            isCurrent = { credentialStore.load() == it },
        )
        refreshSchedule = RefreshSchedule(
            repository = repository,
            saveCredentials = credentialStore::save,
            onRefreshSuccess = refreshMetadataStore::saveLastSuccessfulRefreshTime,
        )
        loginButton.setOnClickListener { submitLogin() }
        bootstrap()
    }

    private fun bindViews() {
        loginPage = findViewById(R.id.login_page)
        schedulePage = findViewById(R.id.schedule_page)
        accountInput = findViewById(R.id.account_input)
        passwordInput = findViewById(R.id.password_input)
        teachingCloudPasswordInput = findViewById(R.id.teaching_cloud_password_input)
        loginButton = findViewById(R.id.login_button)
        loginProgress = findViewById(R.id.login_progress)
        loginError = findViewById(R.id.login_error)
        semesterLabel = findViewById(R.id.semester_label)
        weekBadge = findViewById(R.id.week_badge)
        weekBadgeText = findViewById(R.id.week_badge_text)
        weekRange = findViewById(R.id.week_range)
        refreshButton = findViewById(R.id.refresh_button)
        refreshIcon = findViewById(R.id.refresh_icon)
        refreshLabel = findViewById(R.id.refresh_label)
        scheduleProgress = findViewById(R.id.schedule_progress)
        scheduleStatus = findViewById(R.id.schedule_status)
        scheduleView = findViewById(R.id.schedule_view)
        mainContainer = findViewById(R.id.main_container)
        tabSchedule = findViewById(R.id.tab_schedule)
        tabScheduleIcon = findViewById(R.id.tab_schedule_icon)
        tabScheduleText = findViewById(R.id.tab_schedule_text)
        tabAssignment = findViewById(R.id.tab_assignment)
        tabAssignmentIcon = findViewById(R.id.tab_assignment_icon)
        tabAssignmentText = findViewById(R.id.tab_assignment_text)
        assignmentPage = findViewById(R.id.assignment_page)
        assignmentSwitchContainer = findViewById(R.id.assignment_switch_container)
        assignmentToggleSwitch = findViewById(R.id.assignment_toggle_switch)
        assignmentStatsBadge = findViewById(R.id.assignment_stats_badge)
        chipAll = findViewById(R.id.chip_all)
        chipPending = findViewById(R.id.chip_pending)
        chipOverdue = findViewById(R.id.chip_overdue)
        assignmentListContainer = findViewById(R.id.assignment_list_container)
        assignmentEmptyView = findViewById(R.id.assignment_empty_view)
        assignmentEmptyTitle = findViewById(R.id.assignment_empty_title)
        assignmentEmptySubtitle = findViewById(R.id.assignment_empty_subtitle)
        assignmentEmptyAction = findViewById(R.id.assignment_empty_action)

        assignmentRefreshBtn = findViewById(R.id.assignment_refresh_btn)
        assignmentRefreshIcon = findViewById(R.id.assignment_refresh_icon)
        assignmentCloudStatus = findViewById(R.id.assignment_cloud_status)
        assignmentCloudStatus.setOnClickListener {
            val isFailed = assignmentSyncState == AssignmentSyncState.FAILED_NO_CACHE || assignmentSyncState == AssignmentSyncState.FAILED_WITH_CACHE
            if (isFailed) {
                triggerRetryIfFailed()
            }
        }
        assignmentReauthBtn = findViewById(R.id.assignment_reauth_btn)
        assignmentUnbindBtn = findViewById(R.id.assignment_unbind_btn)

        assignmentRefreshBtn.setOnClickListener { triggerManualAssignmentRefresh() }
        assignmentReauthBtn.setOnClickListener { showConfigureUCloudDialog(PendingUCloudAction.MANUAL_REFRESH) }
        assignmentUnbindBtn.setOnClickListener {
            val account = credentialStore.load()?.account.orEmpty()
            confirmClearUCloudPassword(account)
        }

        assignmentEmptyView.setOnClickListener { triggerRetryIfFailed() }
        assignmentEmptyAction.setOnClickListener { triggerRetryIfFailed() }
        assignmentStatsBadge.setOnClickListener { triggerRetryIfFailed() }

        tabSchedule.setOnClickListener { selectTab(isSchedule = true) }
        tabAssignment.setOnClickListener {
            if (!currentTabIsSchedule) return@setOnClickListener
            val credentials = credentialStore.load()
            if (credentials?.teachingCloudPassword.isNullOrBlank()) {
                showConfigureUCloudDialog(PendingUCloudAction.OPEN_TAB)
            } else {
                openAssignmentTab(credentials!!)
            }
        }
        assignmentToggleSwitch.setChecked(isAssignmentSwitchOn, animate = false)
        assignmentToggleSwitch.onCheckedChangeListener = { isChecked ->
            if (isChecked) {
                if (credentialStore.load()?.teachingCloudPassword.isNullOrBlank()) {
                    assignmentToggleSwitch.setChecked(false, animate = false)
                    showConfigureUCloudDialog(PendingUCloudAction.ENABLE_SWITCH)
                } else {
                    isAssignmentSwitchOn = true
                    scheduleView.setShowAssignments(true)
                }
            } else {
                isAssignmentSwitchOn = false
                scheduleView.setShowAssignments(false)
            }
        }
        assignmentSwitchContainer.setOnClickListener {
            if (!isAssignmentSwitchOn) {
                if (credentialStore.load()?.teachingCloudPassword.isNullOrBlank()) {
                    showConfigureUCloudDialog(PendingUCloudAction.ENABLE_SWITCH)
                } else {
                    assignmentToggleSwitch.toggle(animate = true)
                }
            } else {
                assignmentToggleSwitch.toggle(animate = true)
            }
        }
        chipAll.setOnClickListener { filterAssignments(null) }
        chipPending.setOnClickListener { filterAssignments(AssignmentStatus.PENDING) }
        chipOverdue.setOnClickListener { filterAssignments(AssignmentStatus.OVERDUE) }
        scheduleView.onAssignmentClickListener = { item -> showAssignmentDetailDialog(item.original) }

        refreshButton.setOnClickListener { triggerManualRefresh() }
        weekBadge.setOnClickListener { showWeekPickerDialog() }
        scheduleView.onCourseClickListener = { courseId ->
            val snapshot = currentSnapshot
            if (snapshot != null) {
                val course = snapshot.courses.firstOrNull { it.id == courseId }
                if (course != null) {
                    showCourseDetailDialog(course)
                }
            }
        }
    }

    private fun bootstrap() {
        val cached = repository.loadCached()
        val credentials = credentialStore.load()?.takeIf {
            it.account.isNotBlank() && it.password.isNotEmpty()
        }
        currentAssignments = assignmentRepository.loadCached(credentials?.account)
        when {
            cached != null -> {
                val now = Calendar.getInstance(shanghai)
                selectedWeek = ScheduleLogic.resolveSelectedWeek(null, cached, now)
                currentSnapshot = cached
                val lastRefresh = refreshMetadataStore.getLastSuccessfulRefreshTime()
                val initialRefreshText = if (lastRefresh != null) {
                    ScheduleLogic.formatRefreshTime(lastRefresh, now)
                } else {
                    "\u663e\u793a\u7f13\u5b58"
                }
                renderSchedule(cached, selectedWeek, initialRefreshText)
                if (credentials != null) {
                    if (ScheduleLogic.shouldAutoRefreshToday(lastRefresh, now)) {
                        refreshLabel.text = "\u6b63\u5728\u5237\u65b0\u2026"
                        refresh(
                            credentials,
                            hadCache = true,
                            credentialSaveMode = CredentialSaveMode.KEEP_EXISTING,
                        )
                    }
                } else {
                    showScheduleError("账号信息不可用，点此重新登录。") {
                        showLogin(account = "")
                    }
                }
            }
            credentials != null -> {
                showScheduleLoading()
                refresh(
                    credentials,
                    hadCache = false,
                    credentialSaveMode = CredentialSaveMode.KEEP_EXISTING,
                )
            }
            else -> showLogin()
        }
    }

    private fun submitLogin() {
        val account = accountInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        val teachingPassword = teachingCloudPasswordInput.text?.toString().orEmpty().ifEmpty { null }
        val validationMessage = when {
            account.isEmpty() -> "\u8bf7\u8f93\u5165\u5b66\u53f7\u3002"
            password.isEmpty() -> "\u8bf7\u8f93\u5165\u6559\u52a1\u5bc6\u7801\u3002"
            else -> null
        }
        if (validationMessage != null) {
            showLoginError(validationMessage)
            return
        }

        val previousAccount = credentialStore.load()?.account
        if (previousAccount != null && previousAccount != account) {
            assignmentRepository.clearSession()
            currentAssignments = emptyList()
            assignmentSyncState = AssignmentSyncState.IDLE
            isAssignmentSwitchOn = false
            assignmentToggleSwitch.setChecked(false, animate = false)
            scheduleView.setShowAssignments(false)
            selectTab(isSchedule = true)
        }

        val credentials = Credentials(account, password, teachingPassword)
        val teachingCredentials = credentials.forTeachingLogin()
        hideKeyboard()
        setLoginLoading(true)
        refresh(
            teachingCredentials,
            hadCache = false,
            credentialSaveMode = CredentialSaveMode.SAVE_AFTER_REFRESH_SUCCESS,
            onSuccess = { snapshot ->
                currentAssignments = assignmentRepository.loadCached(account)
                updateAssignmentUiState()
                if (!teachingPassword.isNullOrBlank()) {
                    assignmentWorker.execute {
                        val result = runCatching {
                            ucloudPasswordManager.configure(teachingCredentials, teachingPassword)
                        }
                        mainHandler.post {
                            if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@post
                            if (credentialStore.load()?.account != account) return@post
                            result.onSuccess { updated ->
                                updateAssignmentUiState()
                                triggerAssignmentRefresh(updated, snapshot.termStartDate)
                            }
                                .onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "云邮配置失败，可稍后在作业页重新配置",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }
                }
            },
        )
    }

    private fun triggerManualRefresh() {
        if (refreshInFlight.get()) return
        cancelRevertRefreshStatus()
        val credentials = credentialStore.load()?.takeIf {
            it.account.isNotBlank() && it.password.isNotEmpty()
        }
        if (credentials != null) {
            refreshLabel.text = "\u6b63\u5728\u5237\u65b0\u2026"
            refresh(
                credentials,
                hadCache = currentSnapshot != null,
                credentialSaveMode = CredentialSaveMode.KEEP_EXISTING,
            )
        } else {
            showScheduleError("\u8d26\u53f7\u4fe1\u606f\u4e0d\u53ef\u7528\uff0c\u70b9\u6b64\u91cd\u65b0\u767b\u5f55\u3002") {
                showLogin(account = "")
            }
        }
    }

    private fun startRefreshAnimation() {
        if (isSpinning) return
        isSpinning = true
        spinRefreshIcon()
    }

    private fun spinRefreshIcon() {
        if (!isSpinning) return
        refreshIcon.animate()
            .rotationBy(360f)
            .setDuration(600)
            .withEndAction {
                if (isSpinning) {
                    refreshIcon.rotation = 0f
                    spinRefreshIcon()
                }
            }
            .start()
    }

    private fun stopRefreshAnimation() {
        isSpinning = false
        refreshIcon.animate().cancel()
        refreshIcon.rotation = 0f
    }

    private fun refresh(
        credentials: Credentials,
        hadCache: Boolean,
        credentialSaveMode: CredentialSaveMode,
        onSuccess: ((ScheduleSnapshot) -> Unit)? = null,
    ) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        cancelRevertRefreshStatus()
        startRefreshAnimation()
        worker.execute {
            val result = runCatching {
                refreshSchedule.execute(credentials, credentialSaveMode)
            }
            mainHandler.post {
                refreshInFlight.set(false)
                stopRefreshAnimation()
                if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@post
                result.onSuccess { snapshot ->
                    passwordInput.text?.clear()
                    teachingCloudPasswordInput.text?.clear()
                    setLoginLoading(false)

                    onSuccess?.invoke(snapshot)

                    scheduleProgress.visibility = View.GONE
                    val now = Calendar.getInstance(shanghai)
                    val oldSnapshot = currentSnapshot
                    val hasChanged = !ScheduleLogic.hasSameBusinessContent(oldSnapshot, snapshot)
                    if (hasChanged) {
                        selectedWeek = ScheduleLogic.resolveRefreshWeek(
                            hasExistingSnapshot = oldSnapshot != null,
                            currentSelectedWeek = selectedWeek,
                            newSnapshot = snapshot,
                            now = now,
                        )
                        currentSnapshot = snapshot
                        renderSchedule(snapshot, selectedWeek, "\u5df2\u66f4\u65b0")
                    } else {
                        currentSnapshot = snapshot
                        scheduleStatus.visibility = View.GONE
                        scheduleStatus.setOnClickListener(null)
                        refreshLabel.text = "\u5df2\u662f\u6700\u65b0"
                    }
                    scheduleRevertRefreshStatus(delayMs = 1800L)
                }.onFailure { error ->
                    setLoginLoading(false)
                    scheduleProgress.visibility = View.GONE
                    val message = userMessage(error)
                    if (hadCache) {
                        refreshLabel.text = "\u5237\u65b0\u5931\u8d25"
                        val relogin = error is ScheduleException && error.kind in setOf(
                            ScheduleFailureKind.LOGIN_FAILED,
                            ScheduleFailureKind.MISSING_TOKEN,
                        )
                        showScheduleError(
                            if (relogin) "$message 点此重新登录。" else message,
                            if (relogin) ({ showLogin(account = credentials.account) }) else null,
                        )
                        scheduleRevertRefreshStatus(delayMs = 3000L)
                    } else {
                        showLogin(message, credentials.account)
                    }
                }
            }
        }
    }

    private fun renderSchedule(snapshot: ScheduleSnapshot, week: Int, refreshText: String?) {
        loginPage.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
        schedulePage.visibility = if (currentTabIsSchedule) View.VISIBLE else View.GONE
        assignmentPage.visibility = if (currentTabIsSchedule) View.GONE else View.VISIBLE
        scheduleStatus.visibility = View.GONE
        scheduleStatus.setOnClickListener(null)
        if (refreshText != null) {
            refreshLabel.text = refreshText
        }

        val courses = ScheduleLogic.coursesForWeek(snapshot, week)
        scheduleView.setCourseRecords(ScheduleUiMapper.map(courses))

        val now = Calendar.getInstance(shanghai)
        val monday = ScheduleLogic.mondayOfWeek(snapshot.termStartDate, week)
            ?: ScheduleLogic.mondayOfCurrentWeek(now)
        val dates = ScheduleLogic.daysOfWeek(monday)
        val todayIndex = ScheduleLogic.todayIndexInWeek(dates, now)

        val dateFormatter = SimpleDateFormat("M/d", Locale.CHINA).apply { timeZone = shanghai }
        val rangeFormatter = SimpleDateFormat("M\u6708d\u65e5", Locale.CHINA).apply { timeZone = shanghai }

        scheduleView.setWeekHeader(
            dates.map { dateFormatter.format(it.time) },
            todayIndex,
        )
        semesterLabel.text = formatTerm(snapshot.termID)
        weekBadgeText.text = "\u7b2c $week \u5468"
        weekRange.text = "${rangeFormatter.format(dates.first().time)} \u2014 ${rangeFormatter.format(dates.last().time)}"
        refreshAssignments(snapshot.courses, week)
    }

    private fun showCourseDetailDialog(course: Course) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_course_detail)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        val nameView = dialog.findViewById<TextView>(R.id.course_detail_name)
        val teacherView = dialog.findViewById<TextView>(R.id.course_detail_teacher)
        val teachingClassView = dialog.findViewById<TextView>(R.id.course_detail_teaching_class)
        val roomView = dialog.findViewById<TextView>(R.id.course_detail_room)
        val clockTimeView = dialog.findViewById<TextView>(R.id.course_detail_clock_time)
        val timePeriodView = dialog.findViewById<TextView>(R.id.course_detail_time_period)
        val closeBtn = dialog.findViewById<View>(R.id.course_detail_close)
        val confirmBtn = dialog.findViewById<View>(R.id.course_detail_confirm_button)

        nameView.text = course.name.ifBlank { "未命名课程" }
        teacherView.text = course.teacher.ifBlank { "暂无教师信息" }
        teachingClassView.text = ScheduleLogic.formatTeachingClass(course.teachingClass)
        roomView.text = course.room.ifBlank { "地点待定" }

        val weekdayText = when (course.weekday) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "星期" + course.weekday
        }
        val startPeriod = course.startSlot + 1
        val endPeriod = course.endSlot + 1
        val slotText = if (startPeriod == endPeriod) {
            "第 " + startPeriod + " 节"
        } else {
            "第 " + startPeriod + "–" + endPeriod + " 节"
        }
        timePeriodView.text = weekdayText + " " + slotText

        val fallbackRange = ScheduleLogic.defaultSlotTimeRange(course.startSlot, course.endSlot)
        val startTime = course.startTime.ifBlank { fallbackRange?.first.orEmpty() }
        val endTime = course.endTime.ifBlank { fallbackRange?.second.orEmpty() }
        val clockTime = when {
            startTime.isNotBlank() && endTime.isNotBlank() -> startTime + " — " + endTime
            startTime.isNotBlank() -> startTime
            endTime.isNotBlank() -> endTime
            else -> "暂无详细时间"
        }
        clockTimeView.text = clockTime

        closeBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showWeekPickerDialog() {
        val snapshot = currentSnapshot ?: return
        val maxWeek = ScheduleLogic.maxWeek(snapshot)
        if (maxWeek <= 0) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_week_picker)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        val picker = dialog.findViewById<NumberPicker>(R.id.week_number_picker)
        picker.minValue = 1
        picker.maxValue = maxWeek
        picker.displayedValues = (1..maxWeek).map { "\u7b2c $it \u5468" }.toTypedArray()
        picker.value = selectedWeek.coerceIn(1, maxWeek)
        picker.wrapSelectorWheel = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.selectionDividerHeight = 0
        }

        dialog.findViewById<View>(R.id.dialog_cancel_button).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.dialog_confirm_button).setOnClickListener {
            val newWeek = picker.value
            if (newWeek != selectedWeek) {
                selectedWeek = newWeek
                currentSnapshot?.let { current ->
                    renderSchedule(current, selectedWeek, refreshText = null)
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showScheduleLoading() {
        loginPage.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
        schedulePage.visibility = View.VISIBLE
        assignmentPage.visibility = View.GONE
        scheduleProgress.visibility = View.VISIBLE
        scheduleStatus.visibility = View.GONE
        scheduleView.setCourseRecords(emptyList())
        val now = Calendar.getInstance(shanghai)
        val monday = ScheduleLogic.mondayOfCurrentWeek(now)
        val dates = ScheduleLogic.daysOfWeek(monday)
        val dateFormatter = SimpleDateFormat("M/d", Locale.CHINA).apply { timeZone = shanghai }
        val rangeFormatter = SimpleDateFormat("M\u6708d\u65e5", Locale.CHINA).apply { timeZone = shanghai }
        scheduleView.setWeekHeader(
            dates.map { dateFormatter.format(it.time) },
            ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7),
        )
        semesterLabel.text = formatTerm(SemesterLogic.suggest(now).termID)
        weekBadgeText.text = "\u52a0\u8f7d\u4e2d"
        weekRange.text = "${rangeFormatter.format(dates.first().time)} \u2014 ${rangeFormatter.format(dates.last().time)}"
        refreshLabel.text = "\u6b63\u5728\u5237\u65b0\u2026"
    }

    private fun showLogin(message: String? = null, account: String = "") {
        if (::mainContainer.isInitialized) {
            mainContainer.visibility = View.GONE
        }
        schedulePage.visibility = View.GONE
        loginPage.visibility = View.VISIBLE
        scheduleProgress.visibility = View.GONE
        if (account.isNotBlank()) accountInput.setText(account)
        passwordInput.text?.clear()
        setLoginLoading(false)
        if (message == null) {
            loginError.visibility = View.GONE
        } else {
            showLoginError(message)
        }
    }

    private fun showLoginError(message: String) {
        loginError.text = message
        loginError.visibility = View.VISIBLE
    }

    private fun showScheduleError(message: String, onClick: (() -> Unit)? = null) {
        scheduleStatus.text = message
        scheduleStatus.visibility = View.VISIBLE
        scheduleStatus.isClickable = onClick != null
        scheduleStatus.setOnClickListener(if (onClick == null) null else View.OnClickListener { onClick() })
    }

    private fun setLoginLoading(loading: Boolean) {
        loginButton.isEnabled = !loading
        accountInput.isEnabled = !loading
        passwordInput.isEnabled = !loading
        loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) loginError.visibility = View.GONE
    }

    private fun userMessage(error: Throwable): String =
        (error as? ScheduleException)?.message ?: "获取课表失败，请稍后重试。"

    private fun ucloudMessage(error: Throwable): String =
        (error as? ScheduleException)?.message
            ?: "云邮连接失败，请检查网络或密码后重试。"

    private fun formatTerm(termID: String): String {
        val parts = termID.split('-')
        if (parts.size != 3) return termID.ifBlank { "当前学期" } + " 课程表"
        val season = if (parts[2] == "1") "秋季学期" else "春季学期"
        return "${parts[0]}–${parts[1]} $season 课程表"
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(passwordInput.windowToken, 0)
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.statusBarColor = getColor(R.color.schedule_surface)
        window.navigationBarColor = getColor(R.color.schedule_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun configureContentInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val root = findViewById<View>(R.id.app_root)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            // Apply the union once at the root, including the keyboard on login.
            val safeArea = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
            view.setPadding(
                initialLeft + safeArea.left,
                initialTop + safeArea.top,
                initialRight + safeArea.right,
                initialBottom + safeArea.bottom,
            )
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    private fun scheduleRevertRefreshStatus(delayMs: Long) {
        cancelRevertRefreshStatus()
        val runnable = Runnable {
            revertRefreshStatusRunnable = null
            if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@Runnable
            val lastRefresh = refreshMetadataStore.getLastSuccessfulRefreshTime()
            val text = if (lastRefresh != null) {
                ScheduleLogic.formatRefreshTime(lastRefresh, Calendar.getInstance(shanghai))
            } else if (currentSnapshot != null) {
                "\u663e\u793a\u7f13\u5b58"
            } else {
                return@Runnable
            }
            refreshLabel.text = text
        }
        revertRefreshStatusRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelRevertRefreshStatus() {
        revertRefreshStatusRunnable?.let(mainHandler::removeCallbacks)
        revertRefreshStatusRunnable = null
    }

    private val refreshTickerRunnable = object : Runnable {
        override fun run() {
            updateRefreshTimeLabelIfIdle()
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private fun startRefreshTicker() {
        stopRefreshTicker()
        updateRefreshTimeLabelIfIdle()
        mainHandler.postDelayed(refreshTickerRunnable, 60_000L)
    }

    private fun stopRefreshTicker() {
        mainHandler.removeCallbacks(refreshTickerRunnable)
    }

    private fun updateRefreshTimeLabelIfIdle() {
        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return
        if (schedulePage.visibility != View.VISIBLE) return
        if (refreshInFlight.get()) return
        if (revertRefreshStatusRunnable != null) return

        val lastRefresh = refreshMetadataStore.getLastSuccessfulRefreshTime() ?: return
        refreshLabel.text = ScheduleLogic.formatRefreshTime(lastRefresh, Calendar.getInstance(shanghai))
    }

    override fun onStart() {
        super.onStart()
        startRefreshTicker()
        checkAutoRefreshOnForeground()
    }

    private fun checkAutoRefreshOnForeground() {
        val credentials = credentialStore.load()?.takeIf {
            it.account.isNotBlank() && it.password.isNotEmpty()
        }
        val lastRefresh = refreshMetadataStore.getLastSuccessfulRefreshTime()
        val now = Calendar.getInstance(shanghai)
        val shouldRefresh = ScheduleLogic.shouldAutoRefreshOnForeground(
            isScheduleVisible = schedulePage.visibility == View.VISIBLE,
            hasSnapshot = currentSnapshot != null,
            hasValidCredentials = credentials != null,
            isRefreshInFlight = refreshInFlight.get(),
            lastSuccessfulRefreshAt = lastRefresh,
            now = now,
        )
        if (shouldRefresh && credentials != null) {
            refreshLabel.text = "\u6b63\u5728\u5237\u65b0\u2026"
            refresh(
                credentials,
                hadCache = true,
                credentialSaveMode = CredentialSaveMode.KEEP_EXISTING,
            )
        }
    }

    private fun selectTab(isSchedule: Boolean) {
        currentTabIsSchedule = isSchedule
        schedulePage.visibility = if (isSchedule) View.VISIBLE else View.GONE
        assignmentPage.visibility = if (isSchedule) View.GONE else View.VISIBLE

        val activeColor = Color.parseColor("#6172D5")
        val inactiveColor = Color.parseColor("#8E9AA8")

        if (isSchedule) {
            tabScheduleIcon.setColorFilter(activeColor)
            tabScheduleText.setTextColor(activeColor)
            tabScheduleText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

            tabAssignmentIcon.setColorFilter(inactiveColor)
            tabAssignmentText.setTextColor(inactiveColor)
            tabAssignmentText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        } else {
            tabScheduleIcon.setColorFilter(inactiveColor)
            tabScheduleText.setTextColor(inactiveColor)
            tabScheduleText.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

            tabAssignmentIcon.setColorFilter(activeColor)
            tabAssignmentText.setTextColor(activeColor)
            tabAssignmentText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private fun refreshAssignments(courses: List<Course>, week: Int) {
        val weekAssignments = currentAssignments
            .filter { it.week == week && !it.status.isFinished }
            .map { a ->
                ScheduleAssignmentItem(
                    id = a.id,
                    assignmentId = a.id,
                    courseName = a.courseName,
                    title = a.title,
                    deadlineTimeShort = a.deadlineTimeShort,
                    day = a.weekday,
                    deadlineSlot = a.deadlineSlot,
                    status = a.status,
                    tone = a.tone,
                    original = a,
                )
            }
        scheduleView.setAssignmentRecords(weekAssignments)
        updateAssignmentUiState()
    }

    private fun triggerRetryIfFailed() {
        triggerManualAssignmentRefresh()
    }

    private fun showConfigureUCloudDialog(pendingAction: PendingUCloudAction) {
        val credentials = credentialStore.load()?.takeIf {
            it.account.isNotBlank() && it.password.isNotEmpty()
        } ?: return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bind_ucloud)
        dialog.setCancelable(false)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        val accountView = dialog.findViewById<TextView>(R.id.bind_ucloud_account)
        val passwordInput = dialog.findViewById<EditText>(R.id.bind_ucloud_password_input)
        val errorView = dialog.findViewById<TextView>(R.id.bind_ucloud_error)
        val progressView = dialog.findViewById<ProgressBar>(R.id.bind_ucloud_progress)
        val cancelBtn = dialog.findViewById<Button>(R.id.bind_ucloud_cancel_btn)
        val submitBtn = dialog.findViewById<Button>(R.id.bind_ucloud_submit_btn)
        val closeBtn = dialog.findViewById<View>(R.id.bind_ucloud_close)
        accountView.text = "学号：${credentials.account}"
        closeBtn.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        submitBtn.setOnClickListener {
            val password = passwordInput.text?.toString().orEmpty()
            if (password.isBlank()) {
                errorView.text = "请输入云邮密码"
                errorView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorView.visibility = View.GONE
            progressView.visibility = View.VISIBLE
            submitBtn.isEnabled = false
            cancelBtn.isEnabled = false
            closeBtn.isEnabled = false
            assignmentWorker.execute {
                val result = runCatching { ucloudPasswordManager.configure(credentials, password) }
                mainHandler.post {
                    if (!dialog.isShowing) return@post
                    progressView.visibility = View.GONE
                    submitBtn.isEnabled = true
                    cancelBtn.isEnabled = true
                    closeBtn.isEnabled = true
                    result.onSuccess { updated ->
                        if (credentialStore.load()?.account != credentials.account) {
                            dialog.dismiss()
                            return@onSuccess
                        }
                        assignmentRepository.clearSession()
                        dialog.dismiss()
                        updateAssignmentUiState()
                        when (pendingAction) {
                            PendingUCloudAction.OPEN_TAB -> openAssignmentTab(updated)
                            PendingUCloudAction.ENABLE_SWITCH -> {
                                isAssignmentSwitchOn = false
                                assignmentToggleSwitch.setChecked(false, animate = false)
                                scheduleView.setShowAssignments(false)
                                triggerAssignmentRefresh(updated, currentSnapshot?.termStartDate)
                                Toast.makeText(
                                    this@MainActivity,
                                    "云邮已配置，可再次开启作业显示",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            PendingUCloudAction.MANUAL_REFRESH -> triggerAssignmentRefresh(updated, currentSnapshot?.termStartDate)
                        }
                    }.onFailure { error ->
                        errorView.text = ucloudMessage(error)
                        errorView.visibility = View.VISIBLE
                    }
                }
            }
        }
        dialog.show()
    }

    private fun openAssignmentTab(credentials: Credentials) {
        if (!currentTabIsSchedule) return
        currentAssignments = assignmentRepository.loadCached(credentials.account)
        updateAssignmentUiState()
        selectTab(isSchedule = false)
        triggerAssignmentRefresh(credentials, currentSnapshot?.termStartDate)
    }

    private fun startAssignmentRefreshAnimation() {
        if (isAssignmentSpinning) return
        isAssignmentSpinning = true
        spinAssignmentRefreshIcon()
    }

    private fun spinAssignmentRefreshIcon() {
        if (!isAssignmentSpinning) return
        assignmentRefreshIcon.animate()
            .rotationBy(360f)
            .setDuration(600)
            .withEndAction {
                if (isAssignmentSpinning) {
                    assignmentRefreshIcon.rotation = 0f
                    spinAssignmentRefreshIcon()
                }
            }
            .start()
    }

    private fun stopAssignmentRefreshAnimation() {
        isAssignmentSpinning = false
        assignmentRefreshIcon.animate().cancel()
        assignmentRefreshIcon.rotation = 0f
    }

    private fun triggerManualAssignmentRefresh() {
        val credentials = credentialStore.load() ?: return
        if (credentials.teachingCloudPassword.isNullOrBlank()) {
            showConfigureUCloudDialog(PendingUCloudAction.MANUAL_REFRESH)
        } else {
            triggerAssignmentRefresh(credentials, currentSnapshot?.termStartDate)
        }
    }

    private fun updateAssignmentUiState() {
        assignmentStatsBadge.text = "共 ${currentAssignments.size} 项"
        val configured = !credentialStore.load()?.teachingCloudPassword.isNullOrBlank()
        val failed = assignmentSyncState == AssignmentSyncState.FAILED_WITH_CACHE ||
            assignmentSyncState == AssignmentSyncState.FAILED_NO_CACHE
        assignmentReauthBtn.visibility = if (configured && !failed) View.GONE else View.VISIBLE
        assignmentReauthBtn.text = if (configured) "重新验证云邮密码" else "配置云邮"
        assignmentUnbindBtn.visibility = if (configured) View.VISIBLE else View.GONE
        assignmentUnbindBtn.text = "清除云邮密码"
        assignmentCloudStatus.text = when {
            !configured -> "云邮未配置"
            assignmentSyncState == AssignmentSyncState.LOADING -> "正在更新任务…"
            assignmentSyncState == AssignmentSyncState.SUCCESS -> "更新完成"
            assignmentSyncState == AssignmentSyncState.FAILED_WITH_CACHE ||
                assignmentSyncState == AssignmentSyncState.FAILED_NO_CACHE -> "同步失败"
            else -> "云邮已配置"
        }
        assignmentCloudStatus.setTextColor(Color.parseColor(if (failed) "#BD5267" else "#8E9AA8"))
        renderAssignmentList()
    }

    private fun triggerAssignmentRefresh(credentials: Credentials, termStartDate: String?) {
        if (credentials.teachingCloudPassword.isNullOrBlank()) return
        if (!assignmentRefreshInFlight.begin()) return
        assignmentSyncState = AssignmentSyncState.LOADING
        startAssignmentRefreshAnimation()
        updateAssignmentUiState()
        assignmentWorker.execute {
            val result = runCatching { assignmentRepository.refresh(credentials, termStartDate) }
            mainHandler.post {
                assignmentRefreshInFlight.end()
                stopAssignmentRefreshAnimation()
                if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@post
                val activeCredentials = credentialStore.load()
                if (activeCredentials?.account != credentials.account ||
                    activeCredentials.teachingCloudPassword != credentials.teachingCloudPassword) {
                    if (!currentTabIsSchedule && !activeCredentials?.teachingCloudPassword.isNullOrBlank()) {
                        triggerAssignmentRefresh(activeCredentials!!, currentSnapshot?.termStartDate)
                    }
                    return@post
                }
                result.onSuccess { assignments ->
                    currentAssignments = assignments
                    assignmentSyncState = AssignmentSyncState.SUCCESS
                }.onFailure { error ->
                    assignmentSyncState = if (currentAssignments.isEmpty())
                        AssignmentSyncState.FAILED_NO_CACHE else AssignmentSyncState.FAILED_WITH_CACHE
                    Toast.makeText(this@MainActivity, "同步失败：${ucloudMessage(error)}", Toast.LENGTH_SHORT).show()
                }
                currentSnapshot?.let { refreshAssignments(it.courses, selectedWeek) } ?: updateAssignmentUiState()
            }
        }
    }

    private fun filterAssignments(status: AssignmentStatus?) {
        selectedAssignmentFilter = status

        chipAll.setBackgroundResource(if (status == null) R.drawable.bg_filter_chip_selected else R.drawable.bg_filter_chip_unselected)
        chipAll.setTextColor(if (status == null) Color.WHITE else Color.parseColor("#5E6980"))

        chipPending.setBackgroundResource(if (status == AssignmentStatus.PENDING) R.drawable.bg_filter_chip_selected else R.drawable.bg_filter_chip_unselected)
        chipPending.setTextColor(if (status == AssignmentStatus.PENDING) Color.WHITE else Color.parseColor("#5E6980"))

        chipOverdue.setBackgroundResource(if (status == AssignmentStatus.OVERDUE) R.drawable.bg_filter_chip_selected else R.drawable.bg_filter_chip_unselected)
        chipOverdue.setTextColor(if (status == AssignmentStatus.OVERDUE) Color.WHITE else Color.parseColor("#5E6980"))

        renderAssignmentList()
    }

    private fun renderAssignmentList() {
        assignmentListContainer.removeAllViews()
        val filtered = if (selectedAssignmentFilter == null) {
            currentAssignments
        } else {
            currentAssignments.filter { it.status == selectedAssignmentFilter }
        }

        if (filtered.isEmpty()) {
            assignmentEmptyView.visibility = View.VISIBLE
            val configured = !credentialStore.load()?.teachingCloudPassword.isNullOrBlank()
            assignmentEmptyAction.visibility = View.VISIBLE
            when {
                !configured -> {
                    assignmentEmptyTitle.text = "云邮未配置"
                    assignmentEmptySubtitle.text = "配置云邮密码后可读取未提交的作业和测验"
                    assignmentEmptyAction.text = "配置云邮"
                }
                assignmentSyncState == AssignmentSyncState.FAILED_NO_CACHE ||
                    assignmentSyncState == AssignmentSyncState.FAILED_WITH_CACHE -> {
                    assignmentEmptyTitle.text = "同步失败"
                    assignmentEmptySubtitle.text = "请检查网络或云邮密码，点击重试"
                    assignmentEmptyAction.text = "重试"
                }
                assignmentSyncState == AssignmentSyncState.LOADING -> {
                    assignmentEmptyTitle.text = "正在更新任务…"
                    assignmentEmptySubtitle.text = "请稍候"
                    assignmentEmptyAction.visibility = View.GONE
                }
                else -> {
                    assignmentEmptyTitle.text = "暂无任务"
                    assignmentEmptySubtitle.text = "当前没有未提交的作业或测验"
                    assignmentEmptyAction.visibility = View.GONE
                }
            }
            return
        }

        assignmentEmptyView.visibility = View.GONE
        val inflater = layoutInflater

        for (assignment in filtered) {
            val cardView = inflater.inflate(R.layout.item_assignment_card, assignmentListContainer, false)

            val courseNameView = cardView.findViewById<TextView>(R.id.card_course_name)
            val statusBadgeView = cardView.findViewById<TextView>(R.id.card_status_badge)
            val titleView = cardView.findViewById<TextView>(R.id.card_assignment_title)
            val subtitleView = cardView.findViewById<TextView>(R.id.card_assignment_subtitle)
            val deadlineView = cardView.findViewById<TextView>(R.id.card_deadline_text)
            val remainingView = cardView.findViewById<TextView>(R.id.card_remaining_text)

            courseNameView.text = if (assignment.taskType == TaskType.QUIZ) "测验 · ${assignment.courseName}" else assignment.courseName
            statusBadgeView.text = assignment.status.label

            when (assignment.status) {
                AssignmentStatus.PENDING -> {
                    statusBadgeView.setBackgroundResource(R.drawable.bg_tag_pending)
                    statusBadgeView.setTextColor(Color.parseColor("#BD5267"))
                    deadlineView.setTextColor(Color.parseColor("#BD5267"))
                }
                AssignmentStatus.SUBMITTED -> {
                    statusBadgeView.setBackgroundResource(R.drawable.bg_tag_submitted)
                    statusBadgeView.setTextColor(Color.parseColor("#2E8555"))
                    deadlineView.setTextColor(Color.parseColor("#5E6980"))
                }
                AssignmentStatus.GRADED -> {
                    statusBadgeView.setBackgroundResource(R.drawable.bg_tag_graded)
                    statusBadgeView.setTextColor(Color.parseColor("#6E5EC9"))
                    deadlineView.setTextColor(Color.parseColor("#5E6980"))
                }
                AssignmentStatus.OVERDUE -> {
                    statusBadgeView.setBackgroundResource(R.drawable.bg_tag_pending)
                    statusBadgeView.setTextColor(Color.parseColor("#8E9AA8"))
                    deadlineView.setTextColor(Color.parseColor("#8E9AA8"))
                }
            }

            titleView.text = assignment.title
            subtitleView.text = assignment.chapterName?.trim()?.takeIf(String::isNotEmpty)
                ?: if (assignment.taskType == TaskType.QUIZ) "测验信息见详情" else "作业说明见详情"
            deadlineView.text = "${assignment.deadlineText} 截止"
            remainingView.text = if (assignment.remainingDaysText.isNotBlank()) " · ${assignment.remainingDaysText}" else ""

            cardView.setOnClickListener {
                showAssignmentDetailDialog(assignment)
            }

            assignmentListContainer.addView(cardView)
        }
    }

    private fun showAssignmentDetailDialog(assignment: Assignment) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_assignment_detail)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        val badgeView = dialog.findViewById<TextView>(R.id.assignment_detail_badge)
        val titleView = dialog.findViewById<TextView>(R.id.assignment_detail_title)
        val courseView = dialog.findViewById<TextView>(R.id.assignment_detail_course)
        val deadlineView = dialog.findViewById<TextView>(R.id.assignment_detail_deadline)
        val statusView = dialog.findViewById<TextView>(R.id.assignment_detail_status)
        val remainingView = dialog.findViewById<TextView>(R.id.assignment_detail_remaining)
        val descView = dialog.findViewById<TextView>(R.id.assignment_detail_description)
        val scoreRow = dialog.findViewById<View>(R.id.assignment_detail_score_row)
        val scoreView = dialog.findViewById<TextView>(R.id.assignment_detail_score)
        val closeBtn = dialog.findViewById<View>(R.id.assignment_detail_close)
        val confirmBtn = dialog.findViewById<View>(R.id.assignment_detail_confirm_button)

        badgeView.text = assignment.status.label
        titleView.text = assignment.title
        courseView.text = if (assignment.taskType == TaskType.QUIZ) "测验 · ${assignment.courseName}" else assignment.courseName
        deadlineView.text = "${assignment.deadlineText} 截止"
        statusView.text = assignment.status.label
        if (assignment.remainingDaysText.isNotBlank()) {
            remainingView.visibility = View.VISIBLE
            remainingView.text = "(${assignment.remainingDaysText})"
        } else {
            remainingView.visibility = View.GONE
            remainingView.text = ""
        }
        if (assignment.taskType == TaskType.QUIZ) {
            descView.text = "云课堂测验"
        } else if (assignment.description.isNotBlank()) {
            descView.text = assignment.description
        } else {
            descView.text = "\u6b63\u5728\u52a0\u8f7d\u4f5c\u4e1a\u8bf4\u660e\u2026"
            assignmentWorker.execute {
                val detail = runCatching { assignmentRepository.fetchDetail(assignment.id) { credentialStore.load() } }.getOrNull()
                mainHandler.post {
                    if (dialog.isShowing) {
                        descView.text = detail?.ifBlank { "\u6682\u65e0\u4f5c\u4e1a\u8bf4\u660e" } ?: "\u8be6\u60c5\u52a0\u8f7d\u5931\u8d25"
                    }
                }
            }
        }

        when (assignment.status) {
            AssignmentStatus.PENDING -> {
                badgeView.setBackgroundResource(R.drawable.bg_tag_pending)
                badgeView.setTextColor(Color.parseColor("#BD5267"))
                statusView.setTextColor(Color.parseColor("#BD5267"))
            }
            AssignmentStatus.SUBMITTED -> {
                badgeView.setBackgroundResource(R.drawable.bg_tag_submitted)
                badgeView.setTextColor(Color.parseColor("#2E8555"))
                statusView.setTextColor(Color.parseColor("#2E8555"))
            }
            AssignmentStatus.GRADED -> {
                badgeView.setBackgroundResource(R.drawable.bg_tag_graded)
                badgeView.setTextColor(Color.parseColor("#6E5EC9"))
                statusView.setTextColor(Color.parseColor("#6E5EC9"))
            }
            AssignmentStatus.OVERDUE -> {
                badgeView.setBackgroundResource(R.drawable.bg_tag_pending)
                badgeView.setTextColor(Color.parseColor("#8E9AA8"))
                statusView.setTextColor(Color.parseColor("#8E9AA8"))
            }
        }

        if (assignment.score != null) {
            scoreRow.visibility = View.VISIBLE
            scoreView.text = assignment.score
        } else {
            scoreRow.visibility = View.GONE
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    override fun onStop() {
        stopRefreshTicker()
        super.onStop()
    }

    override fun onDestroy() {
        stopRefreshTicker()
        cancelRevertRefreshStatus()
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        assignmentWorker.shutdownNow()
        super.onDestroy()
    }

    private fun confirmClearUCloudPassword(account: String) {
        if (account.isBlank()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("清除云邮密码")
            .setMessage("只清除本机保存的云邮密码；教务账号、密码和课表会保留。")
            .setPositiveButton("清除") { _, _ ->
                val current = credentialStore.load()
                if (current?.account == account) {
                    runCatching {
                        ucloudPasswordManager.clear(current)
                        assignmentRepository.clearSession()
                        assignmentSyncState = AssignmentSyncState.IDLE
                        isAssignmentSwitchOn = false
                        assignmentToggleSwitch.setChecked(false, animate = false)
                        scheduleView.setShowAssignments(false)
                        updateAssignmentUiState()
                    }.onFailure { error ->
                        Toast.makeText(this, userMessage(error), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

}
