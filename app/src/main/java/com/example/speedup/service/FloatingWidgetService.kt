package com.example.speedup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.core.app.NotificationCompat
import com.example.speedup.R
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.ui.analysis.JobFitAnalysisActivity
import com.example.speedup.engine.CanonicalField
import com.example.speedup.engine.FieldWidgetType
import com.example.speedup.util.AutofillBridge
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 9001
        private const val REFRESH_DEBOUNCE_MS = 2500L
        private const val SCAN_CACHE_MS = 4000L
        private const val FORM_SCAN_CACHE_MS = 8000L
        private const val AUTOFILL_SCAN_TIMEOUT_MS = 20_000L

        var instance: FloatingWidgetService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ProfileRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    /** Separate queue so Auto Fill is not blocked behind JD analysis scans. */
    private val autofillScanExecutor = Executors.newSingleThreadExecutor()
    private val refreshRunnable = Runnable { doRefreshJobAnalysis() }

    private var widgetView: View? = null
    private var panelView: View? = null
    private var previewView: View? = null
    private var scanLoadingView: View? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    private var isDetected = false
    private var isExpanded = false
    private var accessibilityOff = false
    private var isScanning = false
    private var currentJobPosting: JobPosting? = null
    private var currentFillPlan: FillPlan? = null
    private var cachedJob: JobPosting? = null
    private var cacheTime = 0L
    private var cachedFormFieldCount = 0
    private var formCacheTime = 0L
    private var autofillRestoreRunnable: Runnable? = null
    private var isAutofillScanning = false
    private var autoFillClickCount = 0

    private val autofillResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AutofillBridge.ACTION_RESULT) return
            autofillRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
            autofillRestoreRunnable = null
            val filled = intent.getIntExtra(AutofillBridge.EXTRA_FILLED_COUNT, 0)
            val total = intent.getIntExtra(AutofillBridge.EXTRA_TOTAL_DETECTED, 0)
            val fillable = intent.getIntExtra(AutofillBridge.EXTRA_FILLABLE_COUNT, 0)
            val formFields = intent.getIntExtra(AutofillBridge.EXTRA_FORM_FIELDS_DETECTED, total)
            val showToast = intent.getBooleanExtra(AutofillBridge.EXTRA_SHOW_TOAST, true)
            mainHandler.post {
                restoreWidgetAfterFill()
                if (showToast) {
                    showAutofillToast(filled, total, fillable, formFields)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ProfileRepository(this)
        val filter = IntentFilter(AutofillBridge.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autofillResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(autofillResultReceiver, filter)
        }
        startNotification()
        setupWidget()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(autofillResultReceiver)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(refreshRunnable)
        scanExecutor.shutdownNow()
        autofillScanExecutor.shutdownNow()
        widgetView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        widgetAttached = false
        panelView?.let { windowManager.removeView(it) }
        previewView?.let { windowManager.removeView(it) }
        hideScanLoading()
        widgetView = null
        panelView = null
        previewView = null
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleRefreshJobAnalysis()
        return START_STICKY
    }

    fun onScreenContentChanged() {
        if (isExpanded) return
        scheduleRefreshJobAnalysis()
    }

    private fun scheduleRefreshJobAnalysis() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun doRefreshJobAnalysis() {
        if (!repository.isProfileSetupCompleted() || isExpanded) return
        val accessibility = SpeedUpAccessibilityService.instance ?: return
        scanExecutor.execute {
            val job = accessibility.scanAndCompareJob(repository)
            cachedJob = job
            cacheTime = System.currentTimeMillis()
            mainHandler.post {
                currentJobPosting = job
                setJobDetectedState(job.jdDetected)
            }
        }
    }

    private fun startNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speed Up Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Up is active")
            .setContentText("Tap the floating button to analyze and auto-fill")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupWidget() {
        val li = LayoutInflater.from(this)
        widgetView = li.inflate(R.layout.overlay_floating_widget, null)

        val layoutType = overlayLayoutType()

        widgetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        windowManager.addView(widgetView, widgetParams)
        widgetAttached = true

        val button = widgetView?.findViewById<FrameLayout>(R.id.widget_button)
        button?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = 0L

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null || widgetParams == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = widgetParams!!.x
                        initialY = widgetParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        initialTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        widgetParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        widgetParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(widgetView, widgetParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - initialTime
                        val distanceX = abs(event.rawX - initialTouchX)
                        val distanceY = abs(event.rawY - initialTouchY)
                        if (duration < 200 && distanceX < 10 && distanceY < 10) {
                            expandPanel()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    fun setJobDetectedState(detected: Boolean) {
        isDetected = detected
        widgetView?.post {
            val badge = widgetView?.findViewById<TextView>(R.id.widget_badge)
            val pulse = widgetView?.findViewById<View>(R.id.widget_pulse)
            if (detected) {
                val score = currentJobPosting?.fitScore ?: 0
                badge?.text = score.toString()
                badge?.visibility = View.VISIBLE
                pulse?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.alpha(0.3f)?.setDuration(600)?.withEndAction {
                    pulse?.scaleX = 1.0f
                    pulse?.scaleY = 1.0f
                    pulse?.alpha = 0.0f
                }?.start()
            } else {
                badge?.visibility = View.GONE
                pulse?.animate()?.cancel()
                pulse?.alpha = 0f
            }
        }
    }

    private fun expandPanel() {
        if (isExpanded || isScanning) return
        isExpanded = true
        isScanning = true
        widgetView?.visibility = View.GONE
        accessibilityOff = SpeedUpAccessibilityService.instance == null

        showPanelShell()
        val useCache = cachedJob != null &&
            System.currentTimeMillis() - cacheTime < SCAN_CACHE_MS

        if (useCache && cachedJob != null) {
            isScanning = false
            bindPanelContent(cachedJob!!, cachedFormFieldCount)
            refreshJobInBackground()
            return
        }

        scanExecutor.execute {
            val accessibility = SpeedUpAccessibilityService.instance
            val job = if (accessibility != null) {
                accessibility.scanAndCompareJob(repository)
            } else {
                buildAccessibilityOffPosting()
            }
            mainHandler.post {
                if (isExpanded) {
                    bindPanelContent(job, cachedFormFieldCount)
                    showScanLoading("Counting form inputs…")
                }
            }
            val formCount = scanFormFieldCount(forceRefresh = true)
            cachedJob = job
            cacheTime = System.currentTimeMillis()
            mainHandler.post {
                hideScanLoading()
                isScanning = false
                if (isExpanded) bindPanelContent(job, formCount)
            }
        }
    }

    private fun refreshJobInBackground() {
        scanExecutor.execute {
            val accessibility = SpeedUpAccessibilityService.instance ?: return@execute
            val job = accessibility.scanAndCompareJob(repository)
            cachedJob = job
            cacheTime = System.currentTimeMillis()
            mainHandler.post {
                if (isExpanded) bindPanelContent(job, cachedFormFieldCount)
                currentJobPosting = job
            }
        }
    }

    /** Cached form-field count so the panel does not flicker between scans. */
    private fun scanFormFieldCount(forceRefresh: Boolean): Int {
        val accessibility = SpeedUpAccessibilityService.instance ?: return 0
        if (!forceRefresh && System.currentTimeMillis() - formCacheTime < FORM_SCAN_CACHE_MS) {
            return cachedFormFieldCount
        }
        cachedFormFieldCount = accessibility.countFormFieldsOnScreen()
        formCacheTime = System.currentTimeMillis()
        return cachedFormFieldCount
    }

    private fun showPanelShell() {
        val li = LayoutInflater.from(this)
        panelView = li.inflate(R.layout.overlay_widget_panel, null)

        panelView?.findViewById<TextView>(R.id.panel_job_title)?.text = "Analyzing job…"
        panelView?.findViewById<TextView>(R.id.panel_job_subtitle)?.text = "Reading screen content"
        panelView?.findViewById<TextView>(R.id.panel_score_text)?.text = "…"
        panelView?.findViewById<ProgressBar>(R.id.panel_score_progress)?.isIndeterminate = true

        applyPanelMaxHeight()
        attachPanelToWindow()
        wirePanelActions(null)
    }

    private fun bindPanelContent(job: JobPosting, formFieldCount: Int = cachedFormFieldCount) {
        currentJobPosting = job
        val li = LayoutInflater.from(this)
        val panel = panelView ?: return

        val showJd = job.jdDetected && !accessibilityOff
        val showForm = formFieldCount > 0 && !accessibilityOff

        panel.findViewById<View>(R.id.panel_jd_section)?.visibility =
            if (showJd) View.VISIBLE else View.GONE
        panel.findViewById<LinearLayout>(R.id.panel_analysis_container)?.visibility =
            if (showJd) View.VISIBLE else View.GONE

        panel.findViewById<LinearLayout>(R.id.panel_form_section)?.apply {
            visibility = if (showForm) View.VISIBLE else View.GONE
            if (showForm) {
                findViewById<TextView>(R.id.panel_form_title)?.text = "Application form detected"
                findViewById<TextView>(R.id.panel_form_subtitle)?.text =
                    "$formFieldCount input field(s) on this page"
            }
        }

        if (showForm && !showJd) {
            panel.findViewById<TextView>(R.id.panel_job_title)?.text = "Job application form"
            panel.findViewById<TextView>(R.id.panel_job_subtitle)?.text =
                "Tap Auto Fill to review and fill from your profile"
        } else {
            panel.findViewById<TextView>(R.id.panel_job_title)?.text = job.title
            panel.findViewById<TextView>(R.id.panel_job_subtitle)?.text =
                buildString {
                    append(job.company)
                    if (job.location.isNotBlank() && job.location != "—") {
                        append(" • ").append(job.location)
                    }
                }
        }

        if (showJd) {
            panel.findViewById<ProgressBar>(R.id.panel_score_progress)?.apply {
                isIndeterminate = false
                progress = job.fitScore
            }
            panel.findViewById<TextView>(R.id.panel_score_text)?.text = "${job.fitScore}%"

            val fitLabel = panel.findViewById<TextView>(R.id.panel_fit_label)
            val fitSubtitle = panel.findViewById<TextView>(R.id.panel_fit_subtitle)
            fitLabel?.text = job.fitLabel.ifBlank { fitLabelForScore(job.fitScore) }
            fitSubtitle?.text = job.fitSubtitle.ifBlank { fitSubtitleForScore(job) }
            fitLabel?.setTextColor(fitLabelColor(job.fitScore))
            populateAnalysisPoints(li, job)
        }

        wirePanelActions(job, formFieldCount)
    }

    private fun applyPanelMaxHeight() {
        val scroll = panelView?.findViewById<NestedScrollView>(R.id.panel_scroll) ?: return
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = maxScrollHeight
        }
    }

    /**
     * Cap the preview field list height so the "Fill" buttons below the scroll
     * area always stay on screen, no matter how many fields were detected.
     * Short lists keep wrap_content; long lists are clamped and become scrollable.
     */
    private fun applyPreviewMaxHeight() {
        val scroll = previewView?.findViewById<NestedScrollView>(R.id.preview_scroll) ?: return
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        scroll.post {
            val contentHeight = (scroll.getChildAt(0)?.height ?: 0)
            val target = if (contentHeight in 1 until maxScrollHeight) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                maxScrollHeight
            }
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = target
            }
            scroll.requestLayout()
        }
    }

    private fun attachPanelToWindow() {
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(panelView, panelParams)
        setupDismissOnOutsideTouch(panelView, R.id.panel_card) { collapsePanel() }
    }

    /** Dismiss only when tapping the dimmed area outside the card — not on card content. */
    private fun setupDismissOnOutsideTouch(root: View?, cardId: Int, onDismiss: () -> Unit) {
        root ?: return
        root.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
            val card = root.findViewById<View>(cardId) ?: return@setOnTouchListener false
            val rect = Rect()
            card.getGlobalVisibleRect(rect)
            if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                onDismiss()
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun wirePanelActions(job: JobPosting?, formFieldCount: Int = cachedFormFieldCount) {
        panelView?.findViewById<ImageButton>(R.id.btn_panel_close)?.setOnClickListener { collapsePanel() }
        panelView?.findViewById<Button>(R.id.btn_skip)?.setOnClickListener { collapsePanel() }

        val autoFillBtn = panelView?.findViewById<Button>(R.id.btn_auto_fill)
        if (accessibilityOff) {
            autoFillBtn?.text = "Enable Accessibility Service"
            autoFillBtn?.setOnClickListener { openAccessibilitySettings() }
        } else {
            val baseText = if (formFieldCount > 0) "⚡ Auto Fill ($formFieldCount fields)" else "⚡ Auto Fill Form"
            autoFillBtn?.text = "$baseText ($autoFillClickCount)"
            autoFillBtn?.isEnabled = !isScanning && !isAutofillScanning
            autoFillBtn?.setOnClickListener {
                autoFillClickCount++
                val newBaseText = if (formFieldCount > 0) "⚡ Auto Fill ($formFieldCount fields)" else "⚡ Auto Fill Form"
                autoFillBtn.text = "$newBaseText ($autoFillClickCount)"
                showFillPreview()
            }
        }

        panelView?.findViewById<Button>(R.id.btn_view_analysis)?.apply {
            isEnabled = job != null && !isScanning
            setOnClickListener {
                if (job == null) return@setOnClickListener
                collapsePanel()
                startActivity(
                    Intent(this@FloatingWidgetService, JobFitAnalysisActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("JOB_TITLE", job.title)
                        putExtra("JOB_COMPANY", job.company)
                        putExtra("JOB_FIT_SCORE", job.fitScore)
                        putExtra("FIT_LABEL", job.fitLabel)
                        putExtra("FIT_SUBTITLE", job.fitSubtitle)
                        putStringArrayListExtra("SKILLS_MATCHED", ArrayList(job.skillsMatched))
                        putStringArrayListExtra("SKILLS_MISSING", ArrayList(job.skillsMissing))
                        putStringArrayListExtra("SKILLS_PARTIAL", ArrayList(job.skillsPartial))
                    }
                )
            }
        }
    }

    private fun buildAccessibilityOffPosting(): JobPosting = JobPosting(
        title = "Accessibility Service is OFF",
        company = "Speed Up can't read the screen",
        location = "",
        fitScore = 0,
        skillsMatched = emptyList(),
        skillsMissing = emptyList(),
        skillsPartial = emptyList(),
        jdDetected = false,
        fitLabel = "Action Needed",
        fitSubtitle = "Enable the Speed Up accessibility service to analyze jobs"
    )

    private fun openAccessibilitySettings() {
        collapsePanel()
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            Toast.makeText(
                this,
                "Find \"Speed Up\" in the list and turn it ON",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings → Accessibility → Speed Up", Toast.LENGTH_LONG).show()
        }
    }

    private fun populateAnalysisPoints(li: LayoutInflater, job: JobPosting) {
        val container = panelView?.findViewById<LinearLayout>(R.id.panel_analysis_container)
        container?.removeAllViews()

        if (accessibilityOff) {
            addPoint(container, li, "The Speed Up accessibility service is turned off", R.drawable.ic_error, R.color.color_danger)
            addPoint(container, li, "Tap the button below to open Accessibility settings", R.drawable.ic_warning, R.color.color_warning)
            addPoint(container, li, "Turn ON \"Speed Up\", then come back and tap the widget", R.drawable.ic_warning, R.color.color_warning)
            return
        }

        if (!job.jdDetected) {
            addPoint(container, li, "Open a job page in Chrome, then tap the widget again", R.drawable.ic_warning, R.color.color_warning)
            return
        }

        if (job.fitLabel == "JD Partially Loaded") {
            addPoint(container, li, job.fitSubtitle, R.drawable.ic_warning, R.color.color_warning)
            if (job.skillsMissing.isNotEmpty()) {
                addPoint(container, li, "— You lack (visible so far) —", R.drawable.ic_error, R.color.color_danger, bold = true)
                for (skill in job.skillsMissing) {
                    addPoint(container, li, skill, R.drawable.ic_error, R.color.color_danger)
                }
            }
            return
        }

        val userOverallExp = repository.getTotalYearsOfExperience()
        val reqExp = job.requiredExperience

        if (reqExp > 0) {
            val met = userOverallExp >= reqExp
            addPoint(
                container, li,
                "Experience: %.0f+ yrs required · You have %.1f yrs".format(reqExp, userOverallExp),
                if (met) R.drawable.ic_check else R.drawable.ic_error,
                if (met) R.color.color_success else R.color.color_danger
            )
        }

        if (job.skillsMatched.isNotEmpty()) {
            addPoint(container, li, "— You have —", R.drawable.ic_check, R.color.color_success, bold = true)
            for (skill in job.skillsMatched.take(4)) {
                addPoint(container, li, skill, R.drawable.ic_check, R.color.color_success)
            }
        }

        if (job.skillsPartial.isNotEmpty()) {
            addPoint(container, li, "— Partial match —", R.drawable.ic_warning, R.color.color_warning, bold = true)
            for (skill in job.skillsPartial.take(2)) {
                addPoint(container, li, "$skill (weak overlap)", R.drawable.ic_warning, R.color.color_warning)
            }
        }

        if (job.skillsMissing.isNotEmpty()) {
            addPoint(container, li, "— You lack —", R.drawable.ic_error, R.color.color_danger, bold = true)
            for (skill in job.skillsMissing.take(5)) {
                addPoint(container, li, skill, R.drawable.ic_error, R.color.color_danger)
            }
        }

        if (job.skillsMatched.isEmpty() && job.skillsMissing.isEmpty() && job.skillsPartial.isEmpty()) {
            addPoint(container, li, "No specific requirements extracted — scroll the JD into view", R.drawable.ic_warning, R.color.color_warning)
        }
    }

    private fun addPoint(
        container: LinearLayout?,
        li: LayoutInflater,
        text: String,
        iconRes: Int,
        colorRes: Int,
        bold: Boolean = false
    ) {
        val row = li.inflate(R.layout.item_analysis_point, container, false)
        val tv = row.findViewById<TextView>(R.id.tv_point_text)
        tv.text = text
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        row.findViewById<ImageView>(R.id.img_point_icon).apply {
            setImageResource(iconRes)
            setColorFilter(getColor(colorRes))
        }
        container?.addView(row)
    }

    private fun fitLabelForScore(score: Int): String = when {
        score >= 80 -> "Strong Match"
        score >= 50 -> "Partial Match"
        score >= 25 -> "Weak Match"
        else -> "Poor Match"
    }

    private fun fitSubtitleForScore(job: JobPosting): String {
        val matched = job.skillsMatched.size
        val missing = job.skillsMissing.size
        return when {
            !job.jdDetected -> "Scroll the job description into view"
            missing > 0 -> "$matched matched · $missing gaps in your profile"
            matched > 0 -> "You meet the key requirements"
            else -> "Review requirements before applying"
        }
    }

    private fun fitLabelColor(score: Int): Int = when {
        score >= 80 -> getColor(R.color.color_success)
        score >= 50 -> getColor(R.color.color_warning)
        score >= 25 -> getColor(R.color.color_warning)
        else -> getColor(R.color.color_danger)
    }

    private fun collapsePanel() {
        panelView?.let {
            windowManager.removeView(it)
            panelView = null
        }
        previewView?.let {
            windowManager.removeView(it)
            previewView = null
        }
        hideScanLoading()
        isExpanded = false
        isScanning = false
        isAutofillScanning = false
        widgetView?.visibility = View.VISIBLE
    }

    private fun showScanLoading(message: String) {
        hideScanLoading()
        val li = LayoutInflater.from(this)
        scanLoadingView = li.inflate(R.layout.overlay_scan_loading, null)
        scanLoadingView?.findViewById<TextView>(R.id.tv_scan_loading_message)?.text = message
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(scanLoadingView, params)
    }

    private fun hideScanLoading() {
        scanLoadingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            scanLoadingView = null
        }
    }

    private fun setAutoFillButtonLoading(loading: Boolean, message: String? = null) {
        val fillBtn = panelView?.findViewById<Button>(R.id.btn_auto_fill)
        val spinner = panelView?.findViewById<ProgressBar>(R.id.progress_auto_fill)
        if (loading) {
            fillBtn?.isEnabled = false
            fillBtn?.text = message ?: "Scanning form fields…"
            spinner?.visibility = View.VISIBLE
        } else {
            fillBtn?.isEnabled = !isScanning
            val baseText = if (cachedFormFieldCount > 0) {
                "⚡ Auto Fill ($cachedFormFieldCount fields)"
            } else {
                "⚡ Auto Fill Form"
            }
            fillBtn?.text = "$baseText ($autoFillClickCount)"
            spinner?.visibility = View.GONE
        }
    }

    private fun showFillPreview() {
        val accessibility = SpeedUpAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Enable Accessibility Service in Settings to auto-fill", Toast.LENGTH_LONG).show()
            return
        }
        if (isAutofillScanning) {
            Toast.makeText(this, "Already scanning form fields…", Toast.LENGTH_SHORT).show()
            return
        }

        isAutofillScanning = true
        setAutoFillButtonLoading(true)
        showScanLoading("Reading form inputs…")

        autofillScanExecutor.execute {
            var plan: FillPlan? = null
            var timedOut = false
            val scanWorker = Executors.newSingleThreadExecutor()
            try {
                val future = scanWorker.submit<FillPlan> {
                    accessibility.scanAndBuildFillPlan(repository)
                }
                plan = future.get(AUTOFILL_SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                timedOut = true
                android.util.Log.w("FloatingWidget", "scanAndBuildFillPlan timed out")
            } catch (e: Exception) {
                android.util.Log.e("FloatingWidget", "scanAndBuildFillPlan failed", e)
            } finally {
                scanWorker.shutdownNow()
            }
            mainHandler.post {
                isAutofillScanning = false
                hideScanLoading()
                setAutoFillButtonLoading(false)
                if (!isExpanded || panelView == null) return@post
                when {
                    timedOut -> Toast.makeText(
                        this,
                        "Scan took too long. Scroll the form into view and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    plan == null -> Toast.makeText(
                        this,
                        "Couldn't read the form. Scroll to the application fields and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    else -> showFillPreviewUi(plan!!)
                }
            }
        }
    }

    private fun showFillPreviewUi(plan: FillPlan) {
        currentFillPlan = plan
        panelView?.visibility = View.GONE

        val li = LayoutInflater.from(this)
        previewView = li.inflate(R.layout.overlay_fill_preview, null)

        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(previewView, previewParams)
        setupDismissOnOutsideTouch(previewView, R.id.preview_card) { dismissPreview() }
        applyPreviewMaxHeight()

        previewView?.findViewById<ImageButton>(R.id.btn_preview_close)?.setOnClickListener { dismissPreview() }

        val fieldsContainer = previewView?.findViewById<LinearLayout>(R.id.preview_fields_container)
        val missingContainer = previewView?.findViewById<LinearLayout>(R.id.preview_missing_container)
        val missingLabel = previewView?.findViewById<TextView>(R.id.tv_missing_label)
        fieldsContainer?.removeAllViews()
        missingContainer?.removeAllViews()

        val inputCount = plan.fillable.size + plan.unknown.size
        previewView?.findViewById<TextView>(R.id.tv_preview_subtitle)?.text =
            "${plan.fillable.size} of $inputCount inputs matched • long-press to remap"

        for (item in plan.fillable) {
            val row = li.inflate(R.layout.item_fill_preview_field, fieldsContainer, false)
            val label = item.detected.displayLabel()
            row.findViewById<TextView>(R.id.tv_field_label).text =
                "$label (${item.detected.widgetType.name.lowercase()})"
            row.findViewById<TextView>(R.id.tv_field_value).text = truncateValue(item.value)
            val statusDot = row.findViewById<View>(R.id.view_field_status)
            statusDot.setBackgroundResource(
                if (item.confidence >= 0.85f) R.drawable.bg_badge_success else R.drawable.bg_badge_warning
            )
            row.setOnLongClickListener {
                showCanonicalPicker(label, item.detected)
                true
            }
            fieldsContainer?.addView(row)
        }

        if (plan.fillable.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No matching fields found on screen. Open a job application form first."
                setTextColor(getColor(R.color.text_muted))
                textSize = 14f
            }
            fieldsContainer?.addView(emptyView)
        }

        if (plan.unknown.isNotEmpty()) {
            missingLabel?.visibility = View.VISIBLE
            missingContainer?.visibility = View.VISIBLE
            for (unknown in plan.unknown.take(5)) {
                val row = li.inflate(R.layout.item_fill_preview_field, missingContainer, false)
                val label = unknown.labelText.ifBlank { "Unknown field" }
                row.findViewById<TextView>(R.id.tv_field_label).text = label
                row.findViewById<TextView>(R.id.tv_field_value).text = "Could not map — long-press to teach"
                row.findViewById<View>(R.id.view_field_status)
                    .setBackgroundResource(R.drawable.bg_badge_warning)
                row.setOnLongClickListener {
                    showCanonicalPicker(label, unknown)
                    true
                }
                missingContainer?.addView(row)
            }
        }

        val fillAllButton = previewView?.findViewById<Button>(R.id.btn_fill_all)
        fillAllButton?.setOnClickListener { executeAutoFill(showToast = true) }
        previewView?.findViewById<Button>(R.id.btn_fill_selected)?.setOnClickListener {
            executeAutoFill(showToast = true)
        }
    }

    private fun executeAutoFill(showToast: Boolean) {
        previewView?.let {
            windowManager.removeView(it)
            previewView = null
        }
        panelView?.let {
            windowManager.removeView(it)
            panelView = null
        }
        isExpanded = false
        widgetView?.let { w ->
            try {
                windowManager.removeView(w)
                widgetAttached = false
            } catch (_: Exception) { /* already removed */ }
        }

        val accessibility = SpeedUpAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Enable Accessibility Service in Settings to auto-fill", Toast.LENGTH_LONG).show()
            restoreWidgetAfterFill()
            return
        }

        if (showToast) {
            Toast.makeText(this, "Filling form…", Toast.LENGTH_SHORT).show()
        }

        autofillRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        autofillRestoreRunnable = null

        scanExecutor.execute {
            // Brief pause so overlay windows finish dismissing before the a11y tree scan
            Thread.sleep(400)
            accessibility.executeAutoFillRequest(repository, showToast)
            mainHandler.post {
                val restoreFallback = Runnable {
                    restoreWidgetAfterFill()
                    autofillRestoreRunnable = null
                }
                autofillRestoreRunnable = restoreFallback
                mainHandler.postDelayed(restoreFallback, 12_000L)
            }
        }
    }

    private var widgetAttached = false

    private fun restoreWidgetAfterFill() {
        autofillRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        autofillRestoreRunnable = null
        widgetView?.let { w ->
            widgetParams?.let { params ->
                if (!widgetAttached) {
                    try {
                        windowManager.addView(w, params)
                        widgetAttached = true
                    } catch (_: Exception) { /* already attached */ }
                }
            }
            w.visibility = View.VISIBLE
        }
    }

    private fun showAutofillToast(filledCount: Int, totalDetected: Int, fillableCount: Int, formFieldsDetected: Int) {
        val message = when {
            formFieldsDetected == 0 ->
                "No fillable fields visible. Scroll to show all fields or try again."
            filledCount > 0 && filledCount < fillableCount ->
                "Filled $filledCount of $fillableCount fields on this page. " +
                    "${fillableCount - filledCount} need dropdown/manual attention — tap Preview."
            filledCount > 0 ->
                "Filled $filledCount field(s) on this page. Go to the next step and tap Auto Fill again."
            fillableCount > 0 ->
                "Found $fillableCount fields but couldn't fill them. Tap a field in the form and retry."
            totalDetected > 0 ->
                "No fields matched your profile. Add more info in the Profile tab or long-press to remap."
            else ->
                "No form fields found. Scroll to the application form and try again."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showCanonicalPicker(label: String, detected: DetectedField) {
        val accessibility = SpeedUpAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Accessibility service required to save mapping", Toast.LENGTH_SHORT).show()
            return
        }
        val options = CanonicalField.entries.filter { it != CanonicalField.UNKNOWN }
        val names = options.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Map \"$label\" to")
            .setItems(names) { _, which ->
                val canonical = options[which]
                accessibility.saveFieldCorrection(
                    packageName = detected.packageName,
                    viewId = detected.viewId.takeIf { it.isNotBlank() },
                    label = detected.labelText.ifBlank { label },
                    canonical = canonical,
                    widgetType = detected.widgetType
                )
                Toast.makeText(
                    this,
                    "Saved as ${canonical.displayName} for this site",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun dismissPreview() {
        previewView?.let {
            windowManager.removeView(it)
            previewView = null
        }
        panelView?.visibility = View.VISIBLE
    }

    private fun truncateValue(value: String, maxLen: Int = 80): String {
        return if (value.length <= maxLen) value else value.take(maxLen) + "…"
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun buildFallbackJobPosting(repository: ProfileRepository): JobPosting {
        return JobPosting(
            title = "Open a job posting",
            company = "—",
            location = "—",
            fitScore = 0,
            skillsMatched = emptyList(),
            skillsMissing = emptyList(),
            skillsPartial = emptyList(),
            jdDetected = false,
            fitLabel = "No JD Detected",
            fitSubtitle = "Navigate to a job description and tap again"
        )
    }
}
