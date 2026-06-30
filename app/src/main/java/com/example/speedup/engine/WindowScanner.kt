package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class WindowScanner(private val service: AccessibilityService) {

    companion object {
        /** AccessibilityWindowInfo.TYPE_WINDOW (API 33+) */
        private const val WINDOW_TYPE_WINDOW = 6

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.vivaldi.browser"
        )

        val JOB_APP_PACKAGES = setOf(
            "com.linkedin.android",
            "com.indeed.android.jobsearch",
            "com.glassdoor.app",
            "com.workday.workdroidapp",
            "com.greenhouse.app",
            "com.lever.Lever"
        )

        fun isBrowserPackage(packageName: String): Boolean =
            packageName in BROWSER_PACKAGES ||
                packageName.contains("chrome", ignoreCase = true) ||
                packageName.contains("browser", ignoreCase = true)
    }

    data class WindowTarget(
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val textCount: Int
    )

    /** Windows likely containing job posting content (browsers first, then job apps). */
    fun findJobContentWindows(): List<WindowTarget> {
        val ownPackage = service.packageName
        val candidates = mutableListOf<WindowTarget>()
        collectAllWindows(ownPackage, candidates)

        if (candidates.isEmpty()) return emptyList()

        val browsers = candidates.filter { isBrowserPackage(it.packageName) }
            .sortedByDescending { it.textCount }
        val jobApps = candidates.filter { it.packageName in JOB_APP_PACKAGES }
            .sortedByDescending { it.textCount }
        val others = candidates.filter {
            it.packageName !in browsers.map { b -> b.packageName }.toSet() &&
                it.packageName !in JOB_APP_PACKAGES
        }.sortedByDescending { it.textCount }

        val result = mutableListOf<WindowTarget>()
        result.addAll(browsers)
        result.addAll(jobApps)
        if (result.isEmpty()) result.addAll(others.take(3))
        return result
    }

    fun findBestContentWindowRoot(): WindowTarget? {
        val windows = findJobContentWindows()
        return windows.maxByOrNull { it.textCount }
            ?: run {
                val ownPackage = service.packageName
                val candidates = mutableListOf<WindowTarget>()
                collectAllWindows(ownPackage, candidates)
                candidates.maxByOrNull { it.textCount }
            }
    }

    private fun collectAllWindows(ownPackage: String, candidates: MutableList<WindowTarget>) {
        val windows = service.windows
        if (windows != null) {
            for (window in windows) {
                collectCandidate(window, ownPackage, candidates)
            }
        }

        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (pkg != null && pkg != ownPackage) {
                val textCount = countTextNodes(activeRoot)
                if (textCount >= 1) {
                    candidates.add(WindowTarget(activeRoot, pkg, textCount))
                }
            }
        }
    }

    private fun collectCandidate(
        window: AccessibilityWindowInfo,
        ownPackage: String,
        candidates: MutableList<WindowTarget>
    ) {
        val type = window.type
        val isAppWindow = type == AccessibilityWindowInfo.TYPE_APPLICATION
        val isOverlayWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            type == WINDOW_TYPE_WINDOW
        if (!isAppWindow && !isOverlayWindow) return

        val root = window.root ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == ownPackage) return

        val textCount = countTextNodes(root)
        if (textCount >= 1) {
            candidates.add(WindowTarget(root, pkg, textCount))
        }
    }

    fun countTextNodes(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        var count = 0
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty() && text.length < 500) count++
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            count += countTextNodes(child)
            child?.recycle()
        }
        return count
    }
}
