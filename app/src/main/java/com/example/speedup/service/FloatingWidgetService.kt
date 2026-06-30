package com.example.speedup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
import androidx.core.app.NotificationCompat
import androidx.core.widget.NestedScrollView
import com.example.speedup.R
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.ui.analysis.JobFitAnalysisActivity
import java.util.ArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 9001
        private const val REFRESH_DEBOUNCE_MS = 2500L
        private const val SCAN_CACHE_MS = 4000L

        var instance: FloatingWidgetService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ProfileRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val refreshRunnable = Runnable { doRefreshJobAnalysis() }

    private var widgetView: View? = null
    private var panelView: View? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    private var isDetected = false
    private var isExpanded = false
    private var accessibilityOff = false
    private var isScanning = false
    private var currentJobPosting: JobPosting? = null
    private var cachedJob: JobPosting? = null
    private var cacheTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ProfileRepository(this)
        startNotification()
        setupWidget()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        scanExecutor.shutdownNow()
        widgetView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        widgetView = null
        panelView = null
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
            val accessibility = SpeedUpAccessibilityService.instance ?: return@execute
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
            .setContentText("Tap the floating button to analyze job fit")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupWidget() {
        val li = LayoutInflater.from(this)
        widgetView = li.inflate(R.layout.overlay_floating_widget, null)

        widgetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        windowManager.addView(widgetView, widgetParams)

        widgetView?.findViewById<FrameLayout>(R.id.widget_button)?.setOnTouchListener(object : View.OnTouchListener {
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
            bindPanelContent(cachedJob!!)
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
            cachedJob = job
            cacheTime = System.currentTimeMillis()
            mainHandler.post {
                isScanning = false
                if (isExpanded) bindPanelContent(job)
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
                if (isExpanded) bindPanelContent(job)
                currentJobPosting = job
            }
        }
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

    private fun bindPanelContent(job: JobPosting) {
        currentJobPosting = job
        val li = LayoutInflater.from(this)
        val panel = panelView ?: return

        val showJd = job.jdDetected && !accessibilityOff

        panel.findViewById<View>(R.id.panel_jd_section)?.visibility =
            if (showJd) View.VISIBLE else View.GONE
        panel.findViewById<LinearLayout>(R.id.panel_analysis_container)?.visibility =
            if (showJd) View.VISIBLE else View.GONE

        panel.findViewById<TextView>(R.id.panel_job_title)?.text = job.title
        panel.findViewById<TextView>(R.id.panel_job_subtitle)?.text =
            buildString {
                append(job.company)
                if (job.location.isNotBlank() && job.location != "—") {
                    append(" • ").append(job.location)
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

        wirePanelActions(job)
    }

    private fun applyPanelMaxHeight() {
        val scroll = panelView?.findViewById<NestedScrollView>(R.id.panel_scroll) ?: return
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = maxScrollHeight
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

    private fun wirePanelActions(job: JobPosting?) {
        panelView?.findViewById<ImageButton>(R.id.btn_panel_close)?.setOnClickListener { collapsePanel() }
        panelView?.findViewById<Button>(R.id.btn_skip)?.setOnClickListener { collapsePanel() }

        if (accessibilityOff) {
            panelView?.findViewById<Button>(R.id.btn_view_analysis)?.apply {
                text = "Enable Accessibility Service"
                isEnabled = true
                setOnClickListener { openAccessibilitySettings() }
            }
        } else {
            panelView?.findViewById<Button>(R.id.btn_view_analysis)?.apply {
                text = "View Full Analysis"
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
        } catch (_: Exception) {
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
            try { windowManager.removeView(it) } catch (_: Exception) {}
            panelView = null
        }
        isExpanded = false
        isScanning = false
        widgetView?.visibility = View.VISIBLE
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}
