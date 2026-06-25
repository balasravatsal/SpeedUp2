package com.example.speedup.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.engine.AutofillExecutor
import com.example.speedup.engine.BrowserLabelFieldScanner
import com.example.speedup.engine.CanonicalField
import com.example.speedup.engine.FieldMapper
import com.example.speedup.engine.FieldFingerprint
import com.example.speedup.engine.JobFitAnalyzer
import com.example.speedup.engine.ProfileValueResolver
import com.example.speedup.engine.ScreenTextCollector
import com.example.speedup.engine.SemanticMatcher
import com.example.speedup.engine.WindowScanner
import com.example.speedup.util.AutofillBridge

class SpeedUpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SpeedUpAccessibility"
        private const val REFRESH_DEBOUNCE_MS = 2000L
        var instance: SpeedUpAccessibilityService? = null
            private set
    }

    private lateinit var windowScanner: WindowScanner
    private lateinit var autofillExecutor: AutofillExecutor
    private lateinit var screenTextCollector: ScreenTextCollector
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { notifyWidgetRefresh() }

    private val autofillRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AutofillBridge.ACTION_REQUEST) return
            val showToast = intent.getBooleanExtra(AutofillBridge.EXTRA_SHOW_TOAST, true)
            Thread {
                val repo = ProfileRepository(applicationContext)
                val result = performAutoFill(repo)
                sendBroadcast(
                    Intent(AutofillBridge.ACTION_RESULT).apply {
                        setPackage(packageName)
                        putExtra(AutofillBridge.EXTRA_SHOW_TOAST, showToast)
                        putExtra(AutofillBridge.EXTRA_FILLED_COUNT, result.filledCount)
                        putExtra(AutofillBridge.EXTRA_TOTAL_DETECTED, result.totalDetected)
                        putExtra(AutofillBridge.EXTRA_FILLABLE_COUNT, result.fillable.size)
                    }
                )
            }.start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        instance = this
        val filter = IntentFilter(AutofillBridge.ACTION_REQUEST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autofillRequestReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(autofillRequestReceiver, filter)
        }
        windowScanner = WindowScanner(this)
        autofillExecutor = AutofillExecutor(this, windowScanner)
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
        // Skip our own package to avoid feedback loops from the overlay
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

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
        try {
            unregisterReceiver(autofillRequestReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun scanFieldsOnScreen(): List<DetectedField> {
        val windows = windowScanner.findAllFormWindows()
        if (windows.isEmpty()) {
            Log.w(TAG, "scanFieldsOnScreen: no target window")
            return emptyList()
        }

        val fields = mutableListOf<DetectedField>()
        val seen = mutableSetOf<String>()
        val screenHeight = resources.displayMetrics.heightPixels

        for (target in windows) {
            val rawNodes = mutableListOf<AccessibilityNodeInfo>()
            windowScanner.collectFormFields(target.root, target.packageName, rawNodes)
            for (node in rawNodes) {
                try {
                    val field = buildDetectedField(node, target.packageName)
                    val key = fieldKey(field)
                    if (!seen.add(key)) continue
                    fields.add(field)
                } finally {
                    node.recycle()
                }
            }

            if (WindowScanner.isFormHostPackage(target.packageName)) {
                for (labeled in BrowserLabelFieldScanner.collect(target.root, target.packageName, screenHeight)) {
                    try {
                        val field = buildDetectedField(labeled.input, target.packageName, labeled.label)
                        val key = fieldKey(field)
                        if (!seen.add(key)) continue
                        fields.add(field)
                    } finally {
                        labeled.input.recycle()
                    }
                }
            }
        }

        Log.d(TAG, "scanFieldsOnScreen: found ${fields.size} fields across ${windows.size} window(s)")
        return fields
    }

    private fun fieldKey(field: DetectedField): String =
        "${field.fingerprint.boundsLeft}:${field.fingerprint.boundsTop}:" +
            "${field.fingerprint.label}:${field.match.field.name}"

    private fun buildDetectedField(
        node: AccessibilityNodeInfo,
        packageName: String,
        explicitLabel: String? = null
    ): DetectedField {
        val id = node.viewIdResourceName ?: ""
        val hint = node.hintText?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val stateDesc = try {
            node.stateDescription?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
        val labelText = explicitLabel ?: FieldMapper.findLabelFor(node)
        val combinedLabel = listOf(labelText, hint, contentDesc, stateDesc)
            .firstOrNull { it.isNotBlank() }?.replace("*", "")?.trim() ?: ""
        val inputType = node.inputType

        val match = FieldMapper.matchField(
            id, hint, contentDesc,
            if (explicitLabel != null) explicitLabel else combinedLabel.ifBlank { labelText },
            inputType, packageName
        )
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return DetectedField(
            match = match,
            node = AccessibilityNodeInfo.obtain(node),
            hint = hint,
            labelText = combinedLabel.ifBlank { id.substringAfterLast("/").replace("_", " ") },
            viewId = id,
            packageName = packageName,
            fingerprint = FieldFingerprint.from(id, hint, combinedLabel, bounds, match.field)
        )
    }

    fun scanAndBuildFillPlan(repository: ProfileRepository): FillPlan {
        val detected = scanFieldsOnScreen()
        val resolver = ProfileValueResolver(repository)
        val fillable = mutableListOf<FillableField>()
        val unknown = mutableListOf<DetectedField>()

        for (field in detected) {
            if (field.match.field == CanonicalField.UNKNOWN) {
                unknown.add(field)
                continue
            }
            val value = resolver.resolve(field.match.field)
            if (value != null) {
                fillable.add(
                    FillableField(
                        detected = field,
                        value = value,
                        confidence = field.match.confidence
                    )
                )
            } else {
                unknown.add(field)
            }
        }

        Log.d(TAG, "FillPlan: ${fillable.size} fillable, ${unknown.size} unknown/unresolved")
        return FillPlan(fillable, unknown)
    }

    fun performAutoFill(repository: ProfileRepository): AutoFillResult {
        val windows = windowScanner.findAllFormWindows()
        if (windows.isEmpty()) {
            Log.w(TAG, "performAutoFill: no form window found")
            return AutoFillResult(0, 0, emptyList(), emptyList())
        }

        // NOTE: Do NOT scroll the form here. Scrolling forward pushes the
        // top-of-form fields (name, email, phone) out of the viewport before
        // they're scanned, so they'd never be detected or filled. We fill what
        // the user currently has on screen.
        val detected = scanFieldsOnScreen()
            .sortedBy { it.fingerprint.boundsTop }
        val resolver = ProfileValueResolver(repository)
        val fillable = mutableListOf<FillableField>()
        val unknown = mutableListOf<DetectedField>()
        val filledCanonicals = mutableSetOf<CanonicalField>()
        var filledCount = 0

        for (field in detected) {
            val canonical = field.match.field
            if (canonical == CanonicalField.UNKNOWN) {
                unknown.add(field)
                continue
            }
            val value = resolver.resolve(canonical)
            if (value == null) {
                unknown.add(field)
                continue
            }

            fillable.add(FillableField(field, value, field.match.confidence))

            if (canonical in filledCanonicals) {
                field.node.recycle()
                continue
            }

            try {
                val filled = autofillExecutor.fillField(field.packageName, field.fingerprint, value)
                if (filled) {
                    filledCount++
                    filledCanonicals.add(canonical)
                    Log.d(TAG, "Filled $canonical = ${value.take(20)}")
                    // Give WebView / Chrome time to process the fill before the next field
                    Thread.sleep(200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error filling ${field.displayLabel()}", e)
            } finally {
                field.node.recycle()
            }
        }

        for (unknownField in unknown) {
            unknownField.node.recycle()
        }

        Log.d(TAG, "performAutoFill: filled $filledCount / ${fillable.size}")
        return AutoFillResult(
            filledCount = filledCount,
            totalDetected = fillable.size + unknown.size,
            fillable = fillable,
            unknown = unknown
        )
    }

    fun performAutoFill(fieldMap: Map<String, String>): Boolean {
        val fields = scanFieldsOnScreen()
        var filledAny = false

        for (field in fields) {
            val value = fieldMap[field.match.field.key] ?: fieldMap[field.canonicalKeyLegacy()]
            if (value != null) {
                if (autofillExecutor.fillNode(field.node, value)) {
                    filledAny = true
                }
            }
            field.node.recycle()
        }
        return filledAny
    }

    fun scanAndCompareJob(repository: ProfileRepository): JobPosting {
        val collection = screenTextCollector.collect()
        Log.d(TAG, "JD scan: ${collection.texts.size} blocks from ${collection.sourcePackage}")
        return JobFitAnalyzer.analyze(collection.texts, repository)
    }
}

data class DetectedField(
    val match: com.example.speedup.engine.FieldMatch,
    val node: AccessibilityNodeInfo,
    val hint: String = "",
    val labelText: String = "",
    val viewId: String = "",
    val packageName: String = "",
    val fingerprint: FieldFingerprint = FieldFingerprint(
        null, "", "", 0, 0, 0, 0, CanonicalField.UNKNOWN
    )
) {
    fun displayLabel(): String =
        if (labelText.isNotBlank()) labelText else match.field.displayName

    fun canonicalKeyLegacy(): String = match.field.key
}

data class FillableField(
    val detected: DetectedField,
    val value: String,
    val confidence: Float
)

data class FillPlan(
    val fillable: List<FillableField>,
    val unknown: List<DetectedField>
)

data class AutoFillResult(
    val filledCount: Int,
    val totalDetected: Int,
    val fillable: List<FillableField>,
    val unknown: List<DetectedField>
)
