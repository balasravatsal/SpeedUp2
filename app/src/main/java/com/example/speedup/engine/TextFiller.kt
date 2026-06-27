package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TextFiller(
    private val service: AccessibilityService,
    private val windowScanner: WindowScanner
) {
    companion object {
        private const val TAG = "TextFiller"
        private const val ACTION_DELAY_MS = 120L
        private const val GESTURE_WAIT_MS = 600L
    }

    fun fillText(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        val value = when (fillValue) {
            is FillValue.Text -> fillValue.value
            is FillValue.Option -> fillValue.matchTokens.firstOrNull().orEmpty()
            is FillValue.RangeYears -> "%.1f".format(fillValue.years)
            is FillValue.BooleanValue -> if (fillValue.checked) "Yes" else "No"
            is FillValue.DateValue -> fillValue.isoDate
            is FillValue.FileUri -> return false
        }
        if (value.isBlank()) return false
        return fillNodeLive(node, value)
    }

    fun fillField(packageName: String, fingerprint: FieldFingerprint, value: String): Boolean {
        if (value.isBlank()) return false
        val windows = windowScanner.findAllFormWindows()
            .filter { it.packageName == packageName }
            .ifEmpty { windowScanner.findAllFormWindows() }

        for (target in windows) {
            val node = findNodeByFingerprint(target.root, fingerprint, target.packageName)
            if (node == null) continue
            return try {
                fillNodeLive(node, value)
            } finally {
                node.recycle()
            }
        }
        Log.w(TAG, "fillField: node not found for ${fingerprint.canonical}")
        return false
    }

    private fun fillNodeLive(node: AccessibilityNodeInfo, value: String): Boolean {
        tapNode(node)
        Thread.sleep(ACTION_DELAY_MS)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(ACTION_DELAY_MS)
        trySetText(node, "")
        if (trySetText(node, value)) {
            Log.d(TAG, "Filled via SET_TEXT: ${value.take(24)}")
            return true
        }
        if (tryPasteFromClipboard(node, value)) {
            Log.d(TAG, "Filled via paste: ${value.take(24)}")
            return true
        }
        tapNode(node)
        Thread.sleep(ACTION_DELAY_MS)
        if (tryPasteFromClipboard(node, value)) {
            Log.d(TAG, "Filled via tap+paste: ${value.take(24)}")
            return true
        }
        Log.w(TAG, "Failed to fill (editable=${node.isEditable})")
        return false
    }

    private fun trySetText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tryPasteFromClipboard(node: AccessibilityNodeInfo, value: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("speedup_autofill", value))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(ACTION_DELAY_MS)
        val textLen = node.text?.length ?: 0
        if (textLen > 0) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLen)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            Thread.sleep(50)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        latch.await(GESTURE_WAIT_MS, TimeUnit.MILLISECONDS)
        return ok || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun findNodeByFingerprint(
        root: AccessibilityNodeInfo,
        fp: FieldFingerprint,
        packageName: String
    ): AccessibilityNodeInfo? {
        if (!fp.viewId.isNullOrBlank()) {
            findNodeByViewId(root, fp.viewId)?.let { return it }
        }

        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectFillableNodes(root, packageName, editables)

        fun pickFirst(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            for (node in editables) {
                if (match(node)) {
                    editables.filter { it !== node }.forEach { it.recycle() }
                    return node
                }
            }
            return null
        }

        pickFirst { node ->
            val hint = node.hintText?.toString()?.trim().orEmpty()
            fp.hint.isNotBlank() && hint.equals(fp.hint, ignoreCase = true)
        }?.let { return it }

        pickFirst { node ->
            val label = FieldMapper.findLabelFor(node)
            fp.label.isNotBlank() && label.equals(fp.label, ignoreCase = true)
        }?.let { return it }

        pickFirst { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            fp.boundsMatch(rect)
        }?.let { return it }

        pickFirst { node ->
            val id = node.viewIdResourceName.orEmpty()
            val hint = node.hintText?.toString().orEmpty()
            val label = FieldMapper.findLabelFor(node)
            val match = FieldMapper.matchField(id, hint, node.contentDescription?.toString().orEmpty(), label, node.inputType, packageName)
            match.field == fp.canonical
        }?.let { return it }

        editables.forEach { it.recycle() }
        return null
    }

    private fun collectFillableNodes(
        node: AccessibilityNodeInfo?,
        packageName: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (FormFieldDetector.isFormField(node, packageName) &&
            !FormFieldDetector.isBrowserChromeField(node, service.resources.displayMetrics.heightPixels, packageName)
        ) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectFillableNodes(child, packageName, out)
            child?.recycle()
        }
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == viewId) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByViewId(child, viewId)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
