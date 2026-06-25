package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class WindowScanner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WindowScanner"
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

        fun isFormHostPackage(packageName: String): Boolean =
            isBrowserPackage(packageName) ||
                packageName in JOB_APP_PACKAGES ||
                packageName.contains("workday", ignoreCase = true) ||
                packageName.contains("linkedin", ignoreCase = true) ||
                packageName.contains("greenhouse", ignoreCase = true) ||
                packageName.contains("lever", ignoreCase = true) ||
                packageName.contains("indeed", ignoreCase = true)
    }

    data class WindowTarget(
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val fieldCount: Int,
        val textCount: Int
    )

    /** Windows likely containing job posting content (browsers first, then job apps). */
    fun findJobContentWindows(): List<WindowTarget> {
        val ownPackage = service.packageName
        val candidates = mutableListOf<WindowTarget>()
        collectAllWindows(ownPackage, candidates, countFields = false)

        if (candidates.isEmpty()) return emptyList()

        val browsers = candidates.filter { isBrowserPackage(it.packageName) }
            .sortedByDescending { it.textCount }
        val jobApps = candidates.filter { it.packageName in JOB_APP_PACKAGES }
            .sortedByDescending { it.textCount }
        val others = candidates.filter {
            it.packageName !in browsers.map { b -> b.packageName }.toSet() &&
                it.packageName !in JOB_APP_PACKAGES
        }.sortedByDescending { it.textCount }

        // Merge browser windows first — Chrome may split toolbar vs page
        val result = mutableListOf<WindowTarget>()
        result.addAll(browsers)
        result.addAll(jobApps)
        if (result.isEmpty()) result.addAll(others.take(3))
        return result
    }

    /** Best window for reading job description content. */
    fun findBestContentWindowRoot(): WindowTarget? {
        val windows = findJobContentWindows()
        return windows.maxByOrNull { it.textCount }
            ?: run {
                val ownPackage = service.packageName
                val candidates = mutableListOf<WindowTarget>()
                collectAllWindows(ownPackage, candidates, countFields = false)
                candidates.maxByOrNull { it.textCount }
            }
    }

    /** Best window for auto-filling forms (native or browser). */
    fun findBestFormWindowRoot(): WindowTarget? {
        val ownPackage = service.packageName
        val candidates = mutableListOf<WindowTarget>()
        collectAllWindows(ownPackage, candidates, countFields = true)

        val withFields = candidates.filter { it.fieldCount > 0 }
        val best = withFields.maxWithOrNull(
            compareByDescending<WindowTarget> { if (isBrowserPackage(it.packageName)) 1 else 0 }
                .thenByDescending { windowArea(it.root) }
                .thenByDescending { it.fieldCount }
        )
        if (best != null) {
            Log.d(TAG, "Best form window: ${best.packageName} (${best.fieldCount} fields)")
            return best
        }

        // Fallback: largest browser window (page content vs toolbar) when field count is 0
        val browserFallback = candidates
            .filter { isBrowserPackage(it.packageName) }
            .maxByOrNull { windowArea(it.root) }
        if (browserFallback != null) {
            Log.w(TAG, "No counted fields; using largest browser window (${browserFallback.packageName})")
            return browserFallback
        }

        Log.w(TAG, "No form window found among ${candidates.size} candidates")
        return null
    }

    private fun windowArea(root: AccessibilityNodeInfo): Int {
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        return rect.width() * rect.height()
    }

    /** All windows that may contain form fields — content-rich windows first. */
    fun findAllFormWindows(): List<WindowTarget> {
        val ownPackage = service.packageName
        val candidates = mutableListOf<WindowTarget>()
        collectAllWindows(ownPackage, candidates, countFields = true)

        val formHosts = candidates
            .filter {
                isFormHostPackage(it.packageName) &&
                    (it.fieldCount > 0 || it.textCount >= 5)
            }
            .sortedWith(
                compareByDescending<WindowTarget> { windowArea(it.root) }
                    .thenByDescending { it.fieldCount }
                    .thenByDescending { it.textCount }
            )
        if (formHosts.isNotEmpty()) return formHosts

        return candidates
            .filter { it.fieldCount > 0 || it.textCount >= 8 }
            .sortedWith(
                compareByDescending<WindowTarget> { it.fieldCount }
                    .thenByDescending { windowArea(it.root) }
            )
    }

    private fun collectAllWindows(
        ownPackage: String,
        candidates: MutableList<WindowTarget>,
        countFields: Boolean
    ) {
        val windows = service.windows
        if (windows != null) {
            for (window in windows) {
                collectCandidate(window, ownPackage, candidates, countFields)
            }
        }

        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (pkg != null && pkg != ownPackage) {
                val fieldCount = countFormFields(activeRoot, pkg)
                val textCount = countTextNodes(activeRoot)
                if (fieldCount > 0 || textCount >= 5) {
                    candidates.add(WindowTarget(activeRoot, pkg, fieldCount, textCount))
                }
            }
        }
    }

    private fun collectCandidate(
        window: AccessibilityWindowInfo,
        ownPackage: String,
        candidates: MutableList<WindowTarget>,
        countFields: Boolean
    ) {
        val type = window.type
        val isAppWindow = type == AccessibilityWindowInfo.TYPE_APPLICATION
        val isOverlayWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            type == WINDOW_TYPE_WINDOW
        if (!isAppWindow && !isOverlayWindow) {
            return
        }

        val root = window.root ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == ownPackage) return

        val fieldCount = countFormFields(root, pkg)
        val textCount = countTextNodes(root)

        if (countFields) {
            if (fieldCount > 0) {
                candidates.add(WindowTarget(root, pkg, fieldCount, textCount))
            } else if (isFormHostPackage(pkg) && textCount >= 5) {
                candidates.add(WindowTarget(root, pkg, fieldCount, textCount))
            }
        } else if (textCount >= 1) {
            candidates.add(WindowTarget(root, pkg, fieldCount, textCount))
        }
    }

    fun countFormFields(node: AccessibilityNodeInfo?, packageName: String = ""): Int {
        if (node == null) return 0
        var count = 0
        if (FormFieldDetector.isFormField(node, packageName) &&
            !FormFieldDetector.isBrowserChromeField(node, screenHeight, packageName)
        ) count++
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            count += countFormFields(child, packageName)
            child?.recycle()
        }
        return count
    }

    private val screenHeight: Int
        get() = service.resources.displayMetrics.heightPixels

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

    fun collectFormFields(
        node: AccessibilityNodeInfo?,
        packageName: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (FormFieldDetector.isFormField(node, packageName) &&
            !FormFieldDetector.isBrowserChromeField(
                node,
                service.resources.displayMetrics.heightPixels,
                packageName
            )
        ) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectFormFields(child, packageName, out)
            child?.recycle()
        }
    }
}

object FormFieldDetector {

    private val editableClassPatterns = listOf(
        "edittext",
        "autocomplete",
        "multiline",
        "textinput",
        "input",
        "textarea",
        "textfield"
    )

    fun isFormField(node: AccessibilityNodeInfo, packageName: String = ""): Boolean {
        val isFormHost = WindowScanner.isFormHostPackage(packageName)

        if (node.isEditable) return true

        val className = node.className?.toString()?.lowercase() ?: ""
        if (editableClassPatterns.any { className.contains(it) }) {
            return node.isVisibleToUser || node.isFocusable || isFormHost
        }

        val hasTextAction = node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                action.id == AccessibilityNodeInfo.ACTION_PASTE
        }

        if (hasTextAction && (node.isFocusable || node.isEditable)) return true

        if (isFormHost || className.contains("webview")) {
            val hint = node.hintText?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if ((hint.isNotEmpty() || desc.isNotEmpty()) && node.isFocusable) return true

            if (node.isFocusable && node.isVisibleToUser) {
                val label = FieldMapper.findLabelFor(node)
                if (label.isNotBlank()) return true
            }
        }

        return false
    }

    /** Chrome URL bar and top toolbar inputs — browser chrome only, not app forms. */
    fun isBrowserChromeField(node: AccessibilityNodeInfo, screenHeight: Int, packageName: String = ""): Boolean {
        if (!WindowScanner.isBrowserPackage(packageName)) return false
        val id = node.viewIdResourceName.orEmpty().lowercase()
        if (id.contains("url") || id.contains("omnibox") || id.contains("search_box") ||
            id.contains("loc_bar") || id.contains("toolbar")
        ) {
            return true
        }
        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (hint.contains("search") || desc.contains("search or type web address")) return true

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (screenHeight > 0 && rect.bottom <= screenHeight * 0.14) return true
        return false
    }
}
