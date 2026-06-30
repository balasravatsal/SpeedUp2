package com.example.speedup.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pairs visible label TextViews with nearby inputs in browser WebViews.
 */
object BrowserLabelFieldScanner {

    data class LabeledInput(
        val input: AccessibilityNodeInfo,
        val label: String
    )

    private val labelKeywords = listOf(
        "first name", "last name", "full name", "legal name", "given name", "family name",
        "email", "e-mail", "phone", "mobile", "cell", "telephone",
        "linkedin", "city", "state", "country", "location", "address", "zip", "postal",
        "website", "portfolio", "github", "resume", "cover letter", "coverletter",
        "school", "university", "college", "degree", "major", "education",
        "company", "employer", "job title", "current title", "position",
        "years of experience", "work authorization", "salary", "start date", "gender"
    )

    fun collect(
        root: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int
    ): List<LabeledInput> {
        val labels = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collectLabelNodes(root, labels)

        val results = mutableListOf<LabeledInput>()
        val usedInputs = mutableSetOf<String>()

        for ((labelNode, labelText) in labels) {
            val input = findNearestInput(labelNode, packageName, screenHeight) ?: continue
            val bounds = Rect()
            input.getBoundsInScreen(bounds)
            val viewId = input.viewIdResourceName
            val key = if (!viewId.isNullOrBlank()) {
                viewId
            } else {
                "${bounds.left / 24}:${bounds.top / 24}"
            }
            if (!usedInputs.add(key)) {
                input.recycle()
                continue
            }
            results.add(LabeledInput(input, labelText))
        }

        for ((node, _) in labels) node.recycle()
        return results
    }

    private fun collectLabelNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, String>>
    ) {
        val className = node.className?.toString()?.lowercase().orEmpty()
        val rawText = node.text?.toString()?.trim().orEmpty()
        val text = rawText.replace("*", "").trim()

        if (text.isNotBlank() && text.length < 80 &&
            !FormFieldDetector.looksLikeNavigationText(text) &&
            looksLikeFormLabel(text)
        ) {
            val isTextNode = className.contains("textview") ||
                className.contains("text") ||
                (!node.isEditable && !node.isFocusable && rawText.isNotBlank())
            if (isTextNode) {
                out.add(AccessibilityNodeInfo.obtain(node) to text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLabelNodes(child, out)
            child.recycle()
        }
    }

    private fun looksLikeFormLabel(text: String): Boolean {
        val lower = text.lowercase()
        if (labelKeywords.any { lower.contains(it) }) return true
        if (lower.endsWith("?") || lower.endsWith(":") || lower.endsWith("*")) return true
        val wordCount = lower.split(Regex("\\s+")).size
        return wordCount in 2..6 && text.length in 8..50
    }

    private fun findNearestInput(
        labelNode: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val labelBounds = Rect()
        labelNode.getBoundsInScreen(labelBounds)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        var parent = labelNode.parent
        var depth = 0
        while (parent != null && depth < 5) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if (!isCandidateInput(child, packageName, screenHeight)) {
                    child.recycle()
                    continue
                }
                val childBounds = Rect()
                child.getBoundsInScreen(childBounds)
                val verticalGap = childBounds.top - labelBounds.bottom
                val score = if (verticalGap >= -20) {
                    kotlin.math.abs(verticalGap) + kotlin.math.abs(childBounds.left - labelBounds.left)
                } else {
                    Int.MAX_VALUE / 2
                }
                if (score < bestScore) {
                    bestScore = score
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(child)
                }
                child.recycle()
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            depth++
        }
        parent?.recycle()
        return best
    }

    private fun isCandidateInput(
        node: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int
    ): Boolean {
        return FormFieldDetector.isListableFormInput(node, packageName, screenHeight)
    }
}
