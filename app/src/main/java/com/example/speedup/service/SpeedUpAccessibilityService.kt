package com.example.speedup.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.FieldMappingRepository
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.engine.AutofillExecutor
import com.example.speedup.engine.BrowserLabelFieldScanner
import com.example.speedup.engine.CanonicalField
import com.example.speedup.engine.DetectedFieldSnapshot
import com.example.speedup.engine.FieldFingerprint
import com.example.speedup.engine.FieldMapper
import com.example.speedup.engine.FieldMatch
import com.example.speedup.engine.FieldWidgetType
import com.example.speedup.engine.FillTelemetry
import com.example.speedup.engine.FillValue
import com.example.speedup.engine.FormContext
import com.example.speedup.engine.FormContextBuilder
import com.example.speedup.engine.FormContextMapper
import com.example.speedup.engine.FormFieldDetector
import com.example.speedup.engine.FuzzyMatcher
import com.example.speedup.engine.JobFitAnalyzer
import com.example.speedup.engine.PhoneFieldGrouper
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
    private lateinit var fieldMappingRepository: FieldMappingRepository
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
                        putExtra(AutofillBridge.EXTRA_FORM_FIELDS_DETECTED, result.formFieldsDetected)
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
        FieldMapper.ensureInitialized(applicationContext)
        fieldMappingRepository = FieldMappingRepository(applicationContext)
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

    fun scanFieldsOnScreen(): List<DetectedField> = scanFieldsInternal(applyContextRefinement = true)

    private fun scanFieldsInternal(applyContextRefinement: Boolean): List<DetectedField> {
        val windows = windowScanner.findAllFormWindows()
        if (windows.isEmpty()) {
            Log.w(TAG, "scanFieldsOnScreen: no target window")
            return emptyList()
        }

        val fieldsByBounds = LinkedHashMap<String, DetectedField>()
        val screenHeight = resources.displayMetrics.heightPixels

        for (target in windows) {
            val rawNodes = mutableListOf<AccessibilityNodeInfo>()
            windowScanner.collectFormFields(target.root, target.packageName, rawNodes)
            for (node in rawNodes) {
                try {
                    val field = buildDetectedField(node, target.packageName)
                    val boundsKey = "${field.fingerprint.boundsLeft}:${field.fingerprint.boundsTop}"
                    val existing = fieldsByBounds[boundsKey]
                    if (existing == null) {
                        fieldsByBounds[boundsKey] = field
                    } else {
                        if (field.labelText.isNotBlank() && (existing.labelText.isBlank() || field.match.confidence > existing.match.confidence)) {
                            existing.node.recycle()
                            fieldsByBounds[boundsKey] = field
                        } else {
                            field.node.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error building detected field", e)
                } finally {
                    node.recycle()
                }
            }

            if (WindowScanner.isFormHostPackage(target.packageName)) {
                for (labeled in BrowserLabelFieldScanner.collect(target.root, target.packageName, screenHeight)) {
                    try {
                        val field = buildDetectedField(labeled.input, target.packageName, labeled.label)
                        val boundsKey = "${field.fingerprint.boundsLeft}:${field.fingerprint.boundsTop}"
                        val existing = fieldsByBounds[boundsKey]
                        if (existing == null) {
                            fieldsByBounds[boundsKey] = field
                        } else {
                            existing.node.recycle()
                            fieldsByBounds[boundsKey] = field
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error building labeled field", e)
                    } finally {
                        labeled.input.recycle()
                    }
                }
            }
        }

        val raw = fieldsByBounds.values.toList()

        if (!applyContextRefinement || raw.isEmpty()) {
            Log.d(TAG, "scanFieldsOnScreen: found ${raw.size} fields")
            return raw
        }

        val refined = refineWithFormContext(raw)
        Log.d(TAG, "scanFieldsOnScreen: found ${refined.size} fields (refined)")
        return refined
    }

    private fun refineWithFormContext(fields: List<DetectedField>): List<DetectedField> {
        val snapshots = fields.mapIndexed { index, field ->
            val rect = Rect()
            field.node.getBoundsInScreen(rect)
            DetectedFieldSnapshot(
                canonical = field.match.field,
                label = field.labelText,
                bounds = rect,
                viewId = field.viewId,
                index = index
            )
        }.toMutableList()

        PhoneFieldGrouper.applySpatialHints(snapshots, PhoneFieldGrouper.buildClusters(snapshots))
        val context = FormContextBuilder.fromSnapshots(snapshots)

        return fields.mapIndexed { index, field ->
            val snapshot = snapshots[index]
            val refinedMatch = if (snapshot.canonical != field.match.field && snapshot.canonical != CanonicalField.UNKNOWN) {
                FieldMatch(snapshot.canonical, 0.95f, 0, "spatial-refine")
            } else {
                FormContextMapper.refineSingle(field.match, field.labelText, context, snapshot.canonical)
            }
            if (refinedMatch.field == CanonicalField.FULL_NAME && context.hasSplitName) {
                field.copy(match = FieldMatch(CanonicalField.UNKNOWN, 0f, 0, "split-name-skip"))
            } else {
                field.copy(
                    match = refinedMatch,
                    fingerprint = field.fingerprint.copy(canonical = refinedMatch.field)
                )
            }
        }
    }

    private fun fieldKey(field: DetectedField): String {
        val viewId = field.viewId.takeIf { it.isNotBlank() }
        if (viewId != null) {
            return "$viewId:${field.widgetType.name}"
        }
        val left = quantizeBounds(field.fingerprint.boundsLeft)
        val top = quantizeBounds(field.fingerprint.boundsTop)
        val label = FuzzyMatcher.normalize(field.labelText).take(48)
        return "$left:$top:$label:${field.widgetType.name}"
    }

    private fun quantizeBounds(value: Int, grid: Int = 24): Int = (value / grid) * grid

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
        val widgetType = FormFieldDetector.detectWidgetType(node, packageName)

        val userMatch = fieldMappingRepository.lookup(packageName, id, combinedLabel.ifBlank { labelText })
        val match = if (userMatch != null) {
            FieldMatch(userMatch.first, 0.99f, 6, "user-correction")
        } else {
            FieldMapper.matchField(
                id, hint, contentDesc,
                if (explicitLabel != null) explicitLabel else combinedLabel.ifBlank { labelText },
                inputType, packageName
            )
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return DetectedField(
            match = match,
            node = AccessibilityNodeInfo.obtain(node),
            hint = hint,
            labelText = combinedLabel.ifBlank { id.substringAfterLast("/").replace("_", " ") },
            viewId = id,
            packageName = packageName,
            widgetType = if (userMatch != null) userMatch.second else widgetType,
            fingerprint = FieldFingerprint.from(id, hint, combinedLabel, bounds, match.field)
        )
    }

    fun scanAndBuildFillPlan(repository: ProfileRepository): FillPlan {
        val detected = scanFieldsOnScreen()
        val context = buildFormContext(detected)
        val resolver = ProfileValueResolver(repository)
        val fillable = mutableListOf<FillableField>()
        val unknown = mutableListOf<DetectedField>()

        for (field in detected) {
            if (field.match.field == CanonicalField.UNKNOWN) {
                unknown.add(field)
                continue
            }
            val fillValue = resolver.resolve(field.match.field, context, field.widgetType)
            if (fillValue != null) {
                fillable.add(
                    FillableField(
                        detected = field,
                        fillValue = fillValue,
                        value = fillValue.displayText(),
                        confidence = field.match.confidence
                    )
                )
            } else {
                unknown.add(field)
            }
        }

        Log.d(TAG, "FillPlan: ${fillable.size} fillable, ${unknown.size} unknown/unresolved")
        val plan = FillPlan(fillable, unknown)
        detected.forEach { it.node.recycle() }
        return plan
    }

    fun countFormFieldsOnScreen(): Int {
        val fields = scanFieldsOnScreen()
        val count = fields.size
        fields.forEach { it.node.recycle() }
        return count
    }

    /**
     * Runs autofill on a background thread and delivers [AutofillBridge.ACTION_RESULT]
     * so [FloatingWidgetService] can update UI. Prefer this over sending a broadcast
     * from the overlay service — same-process direct call is reliable on Android 14+.
     */
    fun executeAutoFillRequest(repository: ProfileRepository, showToast: Boolean) {
        Thread {
            val result = performAutoFill(repository)
            sendBroadcast(
                Intent(AutofillBridge.ACTION_RESULT).apply {
                    setPackage(packageName)
                    putExtra(AutofillBridge.EXTRA_SHOW_TOAST, showToast)
                    putExtra(AutofillBridge.EXTRA_FILLED_COUNT, result.filledCount)
                    putExtra(AutofillBridge.EXTRA_TOTAL_DETECTED, result.totalDetected)
                    putExtra(AutofillBridge.EXTRA_FILLABLE_COUNT, result.fillable.size)
                    putExtra(AutofillBridge.EXTRA_FORM_FIELDS_DETECTED, result.formFieldsDetected)
                }
            )
        }.start()
    }

    fun performAutoFill(repository: ProfileRepository): AutoFillResult {
        val windows = windowScanner.findAllFormWindows()
        if (windows.isEmpty()) {
            Log.w(TAG, "performAutoFill: no form window found")
            return AutoFillResult(0, 0, 0, emptyList(), emptyList())
        }

        val detected = scanFieldsOnScreen().sortedBy { it.fingerprint.boundsTop }
        val context = buildFormContext(detected)
        val resolver = ProfileValueResolver(repository)
        val fillable = mutableListOf<FillableField>()
        val unknown = mutableListOf<DetectedField>()
        val filledCanonicals = mutableSetOf<CanonicalField>()
        val filledFingerprints = mutableSetOf<String>()
        var filledCount = 0

        for (field in detected) {
            val canonical = field.match.field
            if (canonical == CanonicalField.UNKNOWN) {
                unknown.add(field)
                continue
            }

            val fillValue = resolver.resolve(canonical, context, field.widgetType)
            if (fillValue == null) {
                unknown.add(field)
                continue
            }

            val display = fillValue.displayText()
            fillable.add(FillableField(field, fillValue, display, field.match.confidence))

            val fpKey = "${field.fingerprint.boundsLeft}:${field.fingerprint.boundsTop}:${canonical.name}"
            if (fpKey in filledFingerprints) {
                field.node.recycle()
                continue
            }
            if (canonical in CanonicalField.singleFillPerPage && canonical in filledCanonicals) {
                field.node.recycle()
                continue
            }

            try {
                val filled = autofillExecutor.fillNode(
                    field.node,
                    fillValue,
                    field.widgetType
                )
                if (filled) {
                    filledCount++
                    filledFingerprints.add(fpKey)
                    filledCanonicals.add(canonical)
                    Log.d(TAG, "Filled $canonical (${field.widgetType}) = ${display.take(20)}")
                    Thread.sleep(if (field.widgetType == FieldWidgetType.DROPDOWN) 400 else 200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error filling ${field.displayLabel()}", e)
            } finally {
                field.node.recycle()
            }
        }

        for (unknownField in unknown) {
            if (!fillable.any { it.detected === unknownField }) {
                unknownField.node.recycle()
            }
        }

        FillTelemetry.recordSession(
            applicationContext,
            filledCount,
            fillable.size - filledCount,
            unknown.map { it.labelText }.filter { it.isNotBlank() }
        )

        Log.d(TAG, "performAutoFill: filled $filledCount / ${fillable.size}")
        return AutoFillResult(
            filledCount = filledCount,
            totalDetected = fillable.size + unknown.size,
            formFieldsDetected = detected.size,
            fillable = fillable,
            unknown = unknown
        )
    }

    private fun buildFormContext(fields: List<DetectedField>): FormContext {
        val snapshots = fields.map { field ->
            val rect = Rect()
            field.node.getBoundsInScreen(rect)
            DetectedFieldSnapshot(field.match.field, field.labelText, rect, field.viewId)
        }
        return FormContextBuilder.fromSnapshots(snapshots)
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

    fun saveFieldCorrection(
        packageName: String,
        viewId: String?,
        label: String,
        canonical: CanonicalField,
        widgetType: FieldWidgetType
    ) {
        fieldMappingRepository.saveCorrection(packageName, viewId, label, canonical, widgetType)
    }
}

data class DetectedField(
    val match: com.example.speedup.engine.FieldMatch,
    val node: AccessibilityNodeInfo,
    val hint: String = "",
    val labelText: String = "",
    val viewId: String = "",
    val packageName: String = "",
    val widgetType: FieldWidgetType = FieldWidgetType.TEXT,
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
    val fillValue: FillValue,
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
    val formFieldsDetected: Int = totalDetected,
    val fillable: List<FillableField>,
    val unknown: List<DetectedField>
)
