package com.example.speedup.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.engine.AutofillExecutor
import com.example.speedup.engine.CanonicalField
import com.example.speedup.engine.FieldMapper
import com.example.speedup.engine.FormFieldDetector
import com.example.speedup.engine.JobFitAnalyzer
import com.example.speedup.engine.ProfileValueResolver
import com.example.speedup.engine.ScreenTextCollector
import com.example.speedup.engine.SemanticMatcher
import com.example.speedup.engine.WindowScanner

class SpeedUpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SpeedUpAccessibility"
        var instance: SpeedUpAccessibilityService? = null
            private set
    }

    private lateinit var windowScanner: WindowScanner
    private lateinit var autofillExecutor: AutofillExecutor
    private lateinit var screenTextCollector: ScreenTextCollector

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        instance = this
        windowScanner = WindowScanner(this)
        autofillExecutor = AutofillExecutor(this)
        screenTextCollector = ScreenTextCollector(this)
        Thread {
            SemanticMatcher.initialize(applicationContext)
            Log.d(TAG, "SemanticMatcher ready=${SemanticMatcher.isReady()}")
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            FloatingWidgetService.instance?.onScreenContentChanged()
        }
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

    fun scanFieldsOnScreen(): List<DetectedField> {
        val target = windowScanner.findBestFormWindowRoot()
        if (target == null) {
            Log.w(TAG, "scanFieldsOnScreen: no target window")
            return emptyList()
        }

        val rawNodes = mutableListOf<AccessibilityNodeInfo>()
        windowScanner.collectFormFields(target.root, target.packageName, rawNodes)

        val fields = mutableListOf<DetectedField>()
        for (node in rawNodes) {
            try {
                fields.add(buildDetectedField(node, target.packageName))
            } finally {
                node.recycle()
            }
        }

        Log.d(TAG, "scanFieldsOnScreen: found ${fields.size} fields in ${target.packageName}")
        return fields
    }

    private fun buildDetectedField(node: AccessibilityNodeInfo, packageName: String): DetectedField {
        val id = node.viewIdResourceName ?: ""
        val hint = node.hintText?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val stateDesc = try {
            node.stateDescription?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
        val labelText = FieldMapper.findLabelFor(node)
        val combinedLabel = listOf(labelText, hint, contentDesc, stateDesc)
            .firstOrNull { it.isNotBlank() } ?: ""
        val inputType = node.inputType

        val match = FieldMapper.matchField(id, hint, contentDesc, combinedLabel, inputType, packageName)
        return DetectedField(
            match = match,
            node = AccessibilityNodeInfo.obtain(node),
            hint = hint,
            labelText = combinedLabel.ifBlank { id.substringAfterLast("/").replace("_", " ") },
            viewId = id
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
        val plan = scanAndBuildFillPlan(repository)
        var filledCount = 0

        for (item in plan.fillable) {
            try {
                if (autofillExecutor.fillNode(item.detected.node, item.value)) {
                    filledCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error filling ${item.detected.displayLabel()}", e)
            } finally {
                item.detected.node.recycle()
            }
        }

        for (unknown in plan.unknown) {
            unknown.node.recycle()
        }

        Log.d(TAG, "performAutoFill: filled $filledCount / ${plan.fillable.size}")
        return AutoFillResult(
            filledCount = filledCount,
            totalDetected = plan.fillable.size + plan.unknown.size,
            fillable = plan.fillable,
            unknown = plan.unknown
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
    val viewId: String = ""
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
