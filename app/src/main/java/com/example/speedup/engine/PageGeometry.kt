package com.example.speedup.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Converts screen bounds to stable document-Y coordinates inside the scrollable page content.
 */
object PageGeometry {

    data class PageContext(
        /** Top edge of the scrollable content viewport on screen. */
        val contentViewportTopPx: Int,
        val contentViewportLeftPx: Int,
        /** Estimated page scroll offset at capture time (0 when normalized to top). */
        val scrollOffsetYAtCapture: Int,
        /** True when the page was scrolled to (or already near) the top before measuring. */
        val normalizedToTop: Boolean
    )

    fun findMainScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        walk(root) { node ->
            if (!node.isScrollable) return@walk
            val bounds = boundsOf(node)
            val area = bounds.width() * bounds.height()
            if (area > bestArea) {
                bestArea = area
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(node)
            }
        }
        return best
    }

    fun buildContext(root: AccessibilityNodeInfo, screenHeight: Int, packageName: String): PageContext {
        val scrollRoot = findMainScrollable(root)
        if (scrollRoot != null) {
            val viewport = boundsOf(scrollRoot)
            val offset = estimateScrollOffsetY(scrollRoot)
            val normalized = offset < 40
            scrollRoot.recycle()
            return PageContext(
                contentViewportTopPx = viewport.top,
                contentViewportLeftPx = viewport.left,
                scrollOffsetYAtCapture = if (normalized) 0 else offset,
                normalizedToTop = normalized
            )
        }

        val fallbackTop = if (WindowScanner.isBrowserPackage(packageName)) {
            (screenHeight * 0.14f).toInt()
        } else {
            0
        }
        return PageContext(
            contentViewportTopPx = fallbackTop,
            contentViewportLeftPx = 0,
            scrollOffsetYAtCapture = 0,
            normalizedToTop = false
        )
    }

    /**
     * Document Y stays constant as the user scrolls:
     * documentTop = screenTop - contentViewportTop + scrollOffsetY
     */
    fun toDocumentY(screenTop: Int, ctx: PageContext): Int {
        return screenTop - ctx.contentViewportTopPx + ctx.scrollOffsetYAtCapture
    }

    fun toDocumentX(screenLeft: Int, ctx: PageContext): Int {
        return screenLeft - ctx.contentViewportLeftPx
    }

    fun estimateScrollOffsetY(scrollRoot: AccessibilityNodeInfo): Int {
        val viewport = boundsOf(scrollRoot)
        var minTop = Int.MAX_VALUE
        walk(scrollRoot) { node ->
            val bounds = boundsOf(node)
            if (bounds.height() < 12 || bounds.width() < 40) return@walk
            minTop = minOf(minTop, bounds.top)
        }
        if (minTop == Int.MAX_VALUE) return 0
        return (viewport.top - minTop).coerceAtLeast(0)
    }

    fun minContentTop(scrollRoot: AccessibilityNodeInfo): Int {
        var minTop = Int.MAX_VALUE
        walk(scrollRoot) { node ->
            val bounds = boundsOf(node)
            if (bounds.height() < 12 || bounds.width() < 40) return@walk
            minTop = minOf(minTop, bounds.top)
        }
        return if (minTop == Int.MAX_VALUE) boundsOf(scrollRoot).top else minTop
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visit)
            child.recycle()
        }
    }
}
