package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/** @deprecated Use [FormFillEngine] — kept for legacy call sites. */
class AutofillExecutor(
    service: AccessibilityService,
    windowScanner: WindowScanner
) {
    private val formFillEngine = FormFillEngine(service, windowScanner)
    private val textFiller = TextFiller(service, windowScanner)

    fun fillField(packageName: String, fingerprint: FieldFingerprint, value: String): Boolean =
        textFiller.fillField(packageName, fingerprint, value)

    fun fillField(
        packageName: String,
        fingerprint: FieldFingerprint,
        fillValue: FillValue,
        widgetType: FieldWidgetType
    ): Boolean = formFillEngine.fill(packageName, fingerprint, fillValue, widgetType)

    fun fillNode(node: AccessibilityNodeInfo, value: String): Boolean =
        textFiller.fillText(node, FillValue.Text(value))

    fun fillNode(
        node: AccessibilityNodeInfo,
        fillValue: FillValue,
        widgetType: FieldWidgetType
    ): Boolean = formFillEngine.fillNode(node, fillValue, widgetType)
}
