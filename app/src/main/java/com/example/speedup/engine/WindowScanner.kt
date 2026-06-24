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
    }

    data class WindowTarget(
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val fieldCount: Int,
        val textCount: Int
    )

    fun isBrowserPackage(packageName: String): Boolean =
        packageName in BROWSER_PACKAGES ||
            packageName.contains("chrome", ignoreCase = true) ||
            packageName.contains("browser", ignoreCase = true)

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

        val best = candidates.maxByOrNull { it.fieldCount }
        if (best != null && best.fieldCount > 0) {
            Log.d(TAG, "Best form window: ${best.packageName} (${best.fieldCount} fields)")
            return best
        }

        Log.w(TAG, "No form window found among ${candidates.size} candidates")
        return null
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
                val fieldCount = countFormFields(activeRoot)
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

        val fieldCount = countFormFields(root)
        val textCount = countTextNodes(root)

        if (countFields) {
            if (fieldCount > 0) {
                candidates.add(WindowTarget(root, pkg, fieldCount, textCount))
            }
        } else if (textCount >= 1) {
            candidates.add(WindowTarget(root, pkg, fieldCount, textCount))
        }
    }

    fun countFormFields(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        var count = 0
        if (FormFieldDetector.isFormField(node)) count++
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            count += countFormFields(child)
            child?.recycle()
        }
        return count
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

    fun collectFormFields(
        node: AccessibilityNodeInfo?,
        packageName: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (FormFieldDetector.isFormField(node, packageName)) {
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
        if (!node.isVisibleToUser && !node.isFocusable) return false

        if (node.isEditable) return true

        val className = node.className?.toString()?.lowercase() ?: ""
        if (editableClassPatterns.any { className.contains(it) }) {
            return true
        }

        val hasTextAction = node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                action.id == AccessibilityNodeInfo.ACTION_PASTE
        }
        if (hasTextAction && node.isFocusable) return true

        // Chrome / WebView: contenteditable-like nodes expose hint or placeholder
        if (WindowScanner.BROWSER_PACKAGES.contains(packageName) ||
            packageName.contains("chrome", ignoreCase = true) ||
            packageName.contains("browser", ignoreCase = true) ||
            className.contains("webview")
        ) {
            val hint = node.hintText?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (node.isFocusable && (hint.isNotEmpty() || desc.isNotEmpty()) && hasTextAction) {
                return true
            }
        }

        return false
    }
}
