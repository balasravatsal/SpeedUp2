package com.example.speedup.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.engine.WindowScanner.Companion.isBrowserPackage

object FormFieldDetector {

    private val inputClassHints = listOf(
        "edittext", "input", "textarea", "textfield", "autocomplete", "combobox", "spinner"
    )

    private val linkClassHints = listOf(
        "button", "link", "anchor", "tab", "chip"
    )

    private val navigationPhrases = listOf(
        "back to", "return to", "go back", "go to", "back home", "home page",
        "view job", "view all", "see all", "see more", "learn more", "read more",
        "show more", "load more", "similar jobs", "related jobs", "other jobs",
        "share job", "share this", "save job", "saved jobs", "report job",
        "company page", "company website", "view company", "about company",
        "sign in", "log in", "sign up", "register", "create account",
        "skip to", "jump to", "menu", "navigation", "breadcrumb",
        "privacy policy", "terms of", "cookie", "help center", "contact us",
        "follow us", "subscribe", "unsubscribe", "download app",
        "apply now", "easy apply", "quick apply", "submit application"
    )

    private val navigationExact = setOf(
        "home", "back", "close", "cancel", "done", "next", "previous", "prev",
        "share", "save", "apply", "search", "filter", "sort", "more", "less",
        "jobs", "job search", "careers", "login", "logout", "settings"
    )

    fun isBrowserChromeField(
        node: AccessibilityNodeInfo,
        screenHeight: Int,
        packageName: String,
        checkTopBand: Boolean = true
    ): Boolean {
        if (!isBrowserPackage(packageName)) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (checkTopBand && bounds.bottom <= (screenHeight * 0.14f).toInt()) return true
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.contains("url_bar") || viewId.contains("search_box") || viewId.contains("omnibox")) {
            return true
        }
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        return desc.contains("address bar") || desc.contains("search or type web address")
    }

    fun nodeDisplayText(node: AccessibilityNodeInfo): String {
        return listOf(
            node.text?.toString(),
            node.hintText?.toString(),
            node.contentDescription?.toString()
        ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .joinToString(" ")
            .trim()
    }

    fun looksLikeNavigationText(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return false
        if (navigationExact.contains(lower)) return true
        if (navigationPhrases.any { lower.contains(it) }) return true
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
            return true
        }
        return false
    }

    fun isNavigationOrLink(node: AccessibilityNodeInfo): Boolean {
        if (isActualFormInput(node)) return false

        val className = node.className?.toString()?.lowercase().orEmpty()
        if (inputClassHints.any { className.contains(it) }) return false

        val text = nodeDisplayText(node)
        if (looksLikeNavigationText(text)) return true

        if (linkClassHints.any { className.contains(it) }) return true
        if (className.contains("link") || className.contains("anchor")) return true

        val hasSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (node.isClickable && !node.isEditable && !node.isCheckable && !hasSetText) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val compact = bounds.height() in 1..80
            val shortLabel = text.length in 1..55
            if (compact && shortLabel && !className.contains("input")) return true
        }

        val hint = node.hintText?.toString()?.trim().orEmpty()
        if (node.isFocusable && !node.isEditable && hint.isBlank() && text.isNotBlank()) {
            if (node.isClickable && !hasSetText && text.length <= 50) return true
        }

        return false
    }

    /** Strict filter for autofill — visible inputs only, excludes browser chrome band. */
    fun isFormField(node: AccessibilityNodeInfo, packageName: String, screenHeight: Int): Boolean {
        if (!node.isVisibleToUser) return false
        if (isBrowserChromeField(node, screenHeight, packageName)) return false
        if (isNavigationOrLink(node)) return false
        return isActualFormInput(node)
    }

    /**
     * Looser filter for listing fields from the tree. Matches what you see in the dump:
     * editable / checkable / focusable+hint nodes, including off-screen and top-of-form fields.
     */
    fun isListableFormInput(node: AccessibilityNodeInfo, packageName: String, screenHeight: Int): Boolean {
        if (isBrowserChromeField(node, screenHeight, packageName, checkTopBand = false)) return false
        if (isNavigationOrLink(node)) return false
        if (isActualFormInput(node)) return true

        val hint = node.hintText?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (node.isFocusable && (hint.isNotEmpty() || desc.isNotEmpty())) {
            val className = node.className?.toString()?.lowercase().orEmpty()
            if (className.contains("button") && hint.isEmpty()) return false
            val descLooksNav = desc.isNotEmpty() && looksLikeNavigationText(desc)
            if (descLooksNav && hint.isEmpty() && !node.isEditable) return false
            return true
        }
        return false
    }

    fun isActualFormInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString()?.lowercase().orEmpty()
        if (inputClassHints.any { className.contains(it) }) return true
        if (node.isFocusable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            return true
        }
        return node.isCheckable
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }
}
