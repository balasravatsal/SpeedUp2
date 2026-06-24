package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class AutofillExecutor(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "AutofillExecutor"
        private const val FOCUS_DELAY_MS = 80L
    }

    fun fillNode(node: AccessibilityNodeInfo, value: String): Boolean {
        if (value.isBlank()) return false

        val refreshed = refreshNode(node) ?: node

        // Long-click sometimes needed for WebView fields
        refreshed.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(FOCUS_DELAY_MS)
        refreshed.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(FOCUS_DELAY_MS)

        if (trySetText(refreshed, value)) {
            Log.d(TAG, "Filled via ACTION_SET_TEXT: ${value.take(20)}")
            return true
        }

        val latest = refreshNode(refreshed) ?: refreshed
        if (trySetText(latest, value)) {
            Log.d(TAG, "Filled via ACTION_SET_TEXT (retry): ${value.take(20)}")
            return true
        }

        if (tryPasteFromClipboard(latest, value)) {
            Log.d(TAG, "Filled via clipboard paste: ${value.take(20)}")
            return true
        }

        // WebView fallback: select all + paste
        latest.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val selectArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
        }
        latest.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
        Thread.sleep(FOCUS_DELAY_MS)
        if (latest.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Log.d(TAG, "Filled via select-all + paste")
            return true
        }

        Log.w(TAG, "Failed to fill field (actions=${latest.actionList.map { it.id }})")
        return false
    }

    private fun trySetText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tryPasteFromClipboard(node: AccessibilityNodeInfo, value: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("speedup_autofill", value))
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(FOCUS_DELAY_MS)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        // Some fields accept paste without listing the action
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun refreshNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName ?: return null
        val root = WindowScanner(service).findBestFormWindowRoot()?.root ?: return null
        return findNodeByViewId(root, viewId)
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
