package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Collects text from all relevant foreground windows (especially browsers).
 * Avoids relying on rootInActiveWindow alone — overlays can steal focus.
 */
class ScreenTextCollector(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ScreenTextCollector"
        private const val MAX_NODE_TEXT = 4000
    }

    data class CollectionResult(
        val texts: List<String>,
        val sourcePackage: String?,
        val windowCount: Int
    )

    fun collect(): CollectionResult {
        val ownPackage = service.packageName
        val merged = LinkedHashSet<String>()
        var bestPackage: String? = null
        var windowCount = 0

        val targets = WindowScanner(service).findJobContentWindows()
        windowCount = targets.size

        for (target in targets) {
            if (target.packageName == ownPackage) continue
            collectFromNode(target.root, merged)
            if (bestPackage == null || target.textCount > 0) {
                bestPackage = target.packageName
            }
        }

        // Last resort: any content-rich window
        if (merged.size < 4) {
            WindowScanner(service).findBestContentWindowRoot()?.let { target ->
                if (target.packageName != ownPackage) {
                    collectFromNode(target.root, merged)
                    bestPackage = target.packageName
                    windowCount++
                }
            }
        }

        Log.d(TAG, "Collected ${merged.size} text blocks from $windowCount windows (pkg=$bestPackage)")
        return CollectionResult(merged.toList(), bestPackage, windowCount)
    }

    private fun collectFromNode(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return

        fun addFragment(raw: String) {
            val text = raw.trim()
            if (text.isEmpty()) return
            if (text.length <= MAX_NODE_TEXT) {
                out.add(text)
            }
            // Split long JD paragraphs into lines for section parsing
            if (text.length > 80) {
                text.split("\n", "•", "·", ". ").forEach { part ->
                    val line = part.trim()
                    if (line.length in 4..MAX_NODE_TEXT) out.add(line)
                }
            }
        }

        node.text?.toString()?.let { addFragment(it) }
        node.hintText?.toString()?.let { addFragment(it) }
        node.contentDescription?.toString()?.let { addFragment(it) }
        try {
            node.stateDescription?.toString()?.let { addFragment(it) }
        } catch (_: Exception) { }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectFromNode(child, out)
            child?.recycle()
        }
    }
}
