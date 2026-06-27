package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.util.Log

class FormFillEngine(
    service: AccessibilityService,
    private val windowScanner: WindowScanner
) {
    companion object {
        private const val TAG = "FormFillEngine"
    }

    private val textFiller = TextFiller(service, windowScanner)
    private val dropdownFiller = DropdownFiller(service, textFiller)
    private val radioSelector = RadioSelector(textFiller)
    private val checkboxFiller = CheckboxFiller(textFiller)
    private val dateFiller = DateFiller(textFiller)
    private val fileUploader = FileUploader(textFiller)

    fun fill(
        packageName: String,
        fingerprint: FieldFingerprint,
        fillValue: FillValue,
        widgetType: FieldWidgetType
    ): Boolean {
        val windows = windowScanner.findAllFormWindows()
            .filter { it.packageName == packageName }
            .ifEmpty { windowScanner.findAllFormWindows() }

        for (target in windows) {
            val node = textFiller.findNodeByFingerprint(target.root, fingerprint, target.packageName)
            if (node == null) continue

            return try {
                fillNode(node, fillValue, widgetType)
            } finally {
                node.recycle()
            }
        }

        Log.w(TAG, "fill: node not found for ${fingerprint.canonical}")
        return false
    }

    fun fillNode(node: android.view.accessibility.AccessibilityNodeInfo, fillValue: FillValue, widgetType: FieldWidgetType): Boolean {
        // Never write blank values — leave fields without a real answer untouched.
        if (fillValue is FillValue.Text && fillValue.value.isBlank()) {
            Log.d(TAG, "fillNode: skipping blank text value")
            return false
        }
        return when (widgetType) {
            FieldWidgetType.DROPDOWN -> dropdownFiller.fill(node, fillValue)
            FieldWidgetType.RADIO_GROUP -> radioSelector.fill(node, fillValue)
            FieldWidgetType.CHECKBOX -> checkboxFiller.fill(node, fillValue)
            FieldWidgetType.DATE -> dateFiller.fill(node, fillValue)
            FieldWidgetType.FILE -> fileUploader.fill(node, fillValue)
            FieldWidgetType.TEXTAREA, FieldWidgetType.TEXT -> textFiller.fillText(node, fillValue)
            FieldWidgetType.UNKNOWN -> textFiller.fillText(node, fillValue)
        }
    }
}
