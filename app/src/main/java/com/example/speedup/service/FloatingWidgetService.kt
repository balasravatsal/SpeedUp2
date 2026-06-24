package com.example.speedup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import androidx.core.app.NotificationCompat
import com.example.speedup.R
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.ui.analysis.JobFitAnalysisActivity
import com.example.speedup.engine.SemanticMatcher
import java.util.ArrayList
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 9001

        var instance: FloatingWidgetService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ProfileRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    private var widgetView: View? = null
    private var panelView: View? = null
    private var previewView: View? = null
    private var widgetParams: WindowManager.LayoutParams? = null

    private var isDetected = false
    private var isExpanded = false
    private var accessibilityOff = false
    private var currentJobPosting: JobPosting? = null
    private var currentFillPlan: FillPlan? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ProfileRepository(this)
        startNotification()
        setupWidget()
        Thread {
            SemanticMatcher.initialize(applicationContext)
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshJobAnalysis()
        return START_STICKY
    }

    fun onScreenContentChanged() {
        mainHandler.post { refreshJobAnalysis() }
    }

    private fun refreshJobAnalysis() {
        if (!repository.isProfileSetupCompleted()) return
        val accessibility = SpeedUpAccessibilityService.instance ?: return
        val job = accessibility.scanAndCompareJob(repository)
        currentJobPosting = job
        setJobDetectedState(true)
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
                pulse?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.alpha(0.4f)?.setDuration(1000)?.withEndAction {
                    pulse.scaleX = 1.0f
                    pulse.scaleY = 1.0f
                    pulse.alpha = 0.0f
                    if (isDetected) setJobDetectedState(true)
                }?.start()
            } else {
                badge?.visibility = View.GONE
                pulse?.animate()?.cancel()
                pulse?.alpha = 0f
            }
        }
    }

    private fun expandPanel() {
        if (isExpanded) return
        isExpanded = true
        widgetView?.visibility = View.GONE

        // Brief delay so Chrome regains accessibility focus after widget hides
        mainHandler.postDelayed({
            val accessibility = SpeedUpAccessibilityService.instance
            accessibilityOff = accessibility == null
            currentJobPosting = if (accessibility != null) {
                accessibility.scanAndCompareJob(repository)
            } else {
                buildAccessibilityOffPosting()
            }
            showExpandedPanel(currentJobPosting!!)
        }, 120)
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

    private fun showExpandedPanel(job: JobPosting) {
        val li = LayoutInflater.from(this)
        panelView = li.inflate(R.layout.overlay_widget_panel, null)

        panelView?.findViewById<TextView>(R.id.panel_job_title)?.text = job.title
        panelView?.findViewById<TextView>(R.id.panel_job_subtitle)?.text = "${job.company} • ${job.location}"
        panelView?.findViewById<TextView>(R.id.panel_score_text)?.text = "${job.fitScore}%"
        panelView?.findViewById<ProgressBar>(R.id.panel_score_progress)?.progress = job.fitScore

        val fitLabel = panelView?.findViewById<TextView>(R.id.panel_fit_label)
        val fitSubtitle = panelView?.findViewById<TextView>(R.id.panel_fit_subtitle)
        fitLabel?.text = job.fitLabel.ifBlank { fitLabelForScore(job.fitScore) }
        fitSubtitle?.text = job.fitSubtitle.ifBlank { fitSubtitleForScore(job) }
        fitLabel?.setTextColor(fitLabelColor(job.fitScore))

        populateAnalysisPoints(li, job)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(panelView, panelParams)

        panelView?.findViewById<View>(R.id.panel_dismiss_area)?.setOnClickListener { collapsePanel() }
        panelView?.findViewById<ImageButton>(R.id.btn_panel_close)?.setOnClickListener { collapsePanel() }
        panelView?.findViewById<Button>(R.id.btn_skip)?.setOnClickListener { collapsePanel() }

        val autoFillBtn = panelView?.findViewById<Button>(R.id.btn_auto_fill)
        if (accessibilityOff) {
            autoFillBtn?.text = "Enable Accessibility Service"
            autoFillBtn?.setOnClickListener { openAccessibilitySettings() }
        } else {
            autoFillBtn?.text = "⚡ Auto Fill Form"
            autoFillBtn?.setOnClickListener { showFillPreview() }
        }
        panelView?.findViewById<Button>(R.id.btn_view_analysis)?.setOnClickListener {
            collapsePanel()
            startActivity(
                Intent(this, JobFitAnalysisActivity::class.java).apply {
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
        isExpanded = false
        widgetView?.visibility = View.VISIBLE
    }

    private fun showFillPreview() {
        val accessibility = SpeedUpAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Enable Accessibility Service in Settings to auto-fill", Toast.LENGTH_LONG).show()
            return
        }

        // Minimize overlay interference while scanning
        panelView?.visibility = View.GONE
        val plan = accessibility.scanAndBuildFillPlan(repository)
        currentFillPlan = plan

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

        previewView?.findViewById<View>(R.id.preview_dismiss_area)?.setOnClickListener { dismissPreview() }
        previewView?.findViewById<ImageButton>(R.id.btn_preview_close)?.setOnClickListener { dismissPreview() }

        val fieldsContainer = previewView?.findViewById<LinearLayout>(R.id.preview_fields_container)
        val missingContainer = previewView?.findViewById<LinearLayout>(R.id.preview_missing_container)
        val missingLabel = previewView?.findViewById<TextView>(R.id.tv_missing_label)
        fieldsContainer?.removeAllViews()
        missingContainer?.removeAllViews()

        previewView?.findViewById<TextView>(R.id.tv_preview_subtitle)?.text =
            "${plan.fillable.size} fields matched from your profile"

        for (item in plan.fillable) {
            val row = li.inflate(R.layout.item_fill_preview_field, fieldsContainer, false)
            row.findViewById<TextView>(R.id.tv_field_label).text = item.detected.displayLabel()
            row.findViewById<TextView>(R.id.tv_field_value).text = truncateValue(item.value)
            val statusDot = row.findViewById<View>(R.id.view_field_status)
            statusDot.setBackgroundResource(
                if (item.confidence >= 0.85f) R.drawable.bg_badge_success else R.drawable.bg_badge_warning
            )
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
            for (unknown in plan.unknown.take(3)) {
                val row = li.inflate(R.layout.item_fill_preview_field, missingContainer, false)
                row.findViewById<TextView>(R.id.tv_field_label).text =
                    unknown.labelText.ifBlank { "Unknown field" }
                row.findViewById<TextView>(R.id.tv_field_value).text = "Could not map to profile"
                row.findViewById<View>(R.id.view_field_status)
                    .setBackgroundResource(R.drawable.bg_badge_warning)
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
        val accessibility = SpeedUpAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Enable Accessibility Service in Settings", Toast.LENGTH_LONG).show()
            return
        }

        // Hide overlays first so the job app regains focus and accessibility can see its fields
        dismissPreview()
        collapsePanel()

        mainHandler.postDelayed({
            val result = accessibility.performAutoFill(repository)
            if (showToast) {
                val message = when {
                    result.filledCount > 0 ->
                        "Auto-filled ${result.filledCount} field(s) from your profile"
                    result.totalDetected == 0 ->
                        "No form fields found. Open a job application form first, then tap Auto Fill."
                    result.fillable.isNotEmpty() ->
                        "Found ${result.fillable.size} fields but couldn't fill them. Tap each field in the form and retry."
                    else ->
                        "No fields matched your profile. Add more info in the Profile tab."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }, 450)
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

    override fun onDestroy() {
        super.onDestroy()
        widgetView?.let { windowManager.removeView(it) }
        panelView?.let { windowManager.removeView(it) }
        previewView?.let { windowManager.removeView(it) }
        widgetView = null
        panelView = null
        previewView = null
        if (instance == this) instance = null
    }
}
