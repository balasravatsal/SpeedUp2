package com.example.speedup.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.engine.JobFitAnalyzer
import com.example.speedup.engine.ScreenTextCollector
import com.example.speedup.engine.SemanticMatcher

class SpeedUpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SpeedUpAccessibility"
        private const val REFRESH_DEBOUNCE_MS = 2000L
        var instance: SpeedUpAccessibilityService? = null
            private set
    }

    private lateinit var screenTextCollector: ScreenTextCollector
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { notifyWidgetRefresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        instance = this
        screenTextCollector = ScreenTextCollector(this)
        Thread {
            SemanticMatcher.initialize(applicationContext)
            Log.d(TAG, "SemanticMatcher ready=${SemanticMatcher.isReady()}")
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }
        if (event.packageName?.toString() == packageName) return

        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun notifyWidgetRefresh() {
        FloatingWidgetService.instance?.onScreenContentChanged()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun scanAndCompareJob(repository: ProfileRepository): JobPosting {
        val collection = screenTextCollector.collect()
        Log.d(TAG, "JD scan: ${collection.texts.size} blocks from ${collection.sourcePackage}")
        return JobFitAnalyzer.analyze(collection.texts, repository)
    }
}
