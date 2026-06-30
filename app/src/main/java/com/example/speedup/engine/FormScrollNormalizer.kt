package com.example.speedup.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Scrolls page content toward the top so document-Y coordinates align with layout position.
 */
object FormScrollNormalizer {

    private const val TAG = "FormScrollNormalizer"
    private const val MAX_SCROLL_STEPS = 16
    private const val SETTLE_MS = 220L
    private const val TOP_TOLERANCE_PX = 36

    fun scrollToTop(root: AccessibilityNodeInfo): Boolean {
        val scrollRoot = PageGeometry.findMainScrollable(root) ?: return false
        return try {
            var stagnantRounds = 0
            var lastMinTop = Int.MIN_VALUE

            repeat(MAX_SCROLL_STEPS) { step ->
                val offset = PageGeometry.estimateScrollOffsetY(scrollRoot)
                if (offset < TOP_TOLERANCE_PX) {
                    Log.d(TAG, "Near top after $step scrolls (offset=$offset)")
                    return@repeat
                }

                val minTop = PageGeometry.minContentTop(scrollRoot)
                if (minTop == lastMinTop) {
                    stagnantRounds++
                } else {
                    stagnantRounds = 0
                    lastMinTop = minTop
                }
                if (stagnantRounds >= 2) {
                    Log.d(TAG, "Scroll stagnant at step $step (offset=$offset)")
                    return@repeat
                }

                val scrolled = scrollRoot.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (!scrolled) {
                    scrollNestedBackward(scrollRoot)
                }
                Thread.sleep(SETTLE_MS)
            }

            val finalOffset = PageGeometry.estimateScrollOffsetY(scrollRoot)
            Log.d(TAG, "Finished scroll-to-top; offset=$finalOffset")
            finalOffset < TOP_TOLERANCE_PX * 2
        } finally {
            scrollRoot.recycle()
        }
    }

    private fun scrollNestedBackward(scrollRoot: AccessibilityNodeInfo) {
        val nested = mutableListOf<AccessibilityNodeInfo>()
        collectScrollables(scrollRoot, nested)
        for (node in nested) {
            if (node !== scrollRoot) {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
        }
        nested.forEach { it.recycle() }
    }

    private fun collectScrollables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScrollables(child, out)
            child.recycle()
        }
    }
}
