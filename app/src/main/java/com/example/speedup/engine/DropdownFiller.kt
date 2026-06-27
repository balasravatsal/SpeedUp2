package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class DropdownFiller(
    private val service: AccessibilityService,
    private val textFiller: TextFiller
) {
    companion object {
        private const val TAG = "DropdownFiller"
        private const val OPEN_WAIT_MS = 350L
        private const val SELECT_WAIT_MS = 200L
    }

    fun fill(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        textFiller.tapNode(node)
        Thread.sleep(OPEN_WAIT_MS)

        val options = collectOptions(service.rootInActiveWindow)
        if (options.isEmpty()) {
            Log.w(TAG, "No dropdown options found")
            return false
        }

        val optionTexts = options.map { it.text }
        val best = OptionMatcher.bestOption(optionTexts, fillValue)
        if (best == null) {
            options.forEach { it.node.recycle() }
            return false
        }

        val target = options.firstOrNull { it.text == best }?.node
        if (target == null) {
            options.forEach { it.node.recycle() }
            return false
        }

        val ok = textFiller.tapNode(target)
        options.filter { it.node !== target }.forEach { it.node.recycle() }
        target.recycle()
        Thread.sleep(SELECT_WAIT_MS)
        Log.d(TAG, "Selected dropdown option: $best")
        return ok
    }

    private data class OptionNode(val text: String, val node: AccessibilityNodeInfo)

    private fun collectOptions(root: AccessibilityNodeInfo?): List<OptionNode> {
        if (root == null) return emptyList()
        val out = mutableListOf<OptionNode>()
        collectOptionsRecursive(root, out, 0)
        root.recycle()
        return out
    }

    private fun collectOptionsRecursive(
        node: AccessibilityNodeInfo?,
        out: MutableList<OptionNode>,
        depth: Int
    ) {
        if (node == null || depth > 12 || out.size > 80) return

        val className = node.className?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val isClickable = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        val isOptionLike = className.contains("textview") ||
            className.contains("button") ||
            className.contains("item") ||
            className.contains("option") ||
            className.contains("checked")

        if (text.isNotBlank() && text.length < 80 && isClickable && isOptionLike &&
            !text.equals("select", ignoreCase = true) && !text.contains("search", ignoreCase = true)
        ) {
            out.add(OptionNode(text, AccessibilityNodeInfo.obtain(node)))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectOptionsRecursive(child, out, depth + 1)
            child?.recycle()
        }
    }
}
