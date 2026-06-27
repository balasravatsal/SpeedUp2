package com.example.speedup.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Finds form inputs in browser WebViews by pairing visible label TextViews
 * with nearby focusable/editable nodes (Greenhouse, Lever, etc.).
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
        "years of experience", "work authorization", "salary", "start date"
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
        val className = node.className?.toString()?.lowercase() ?: ""
        val rawText = node.text?.toString()?.trim().orEmpty()
        val text = rawText.replace("*", "").trim()

        if (text.isNotBlank() && text.length < 80 && looksLikeFormLabel(text)) {
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
        if (text.endsWith("*")) return true
        val normalized = FuzzyMatcher.normalize(text)
        if (normalized.length < 2) return false
        return labelKeywords.any { normalized.contains(it) }
    }

    private fun findNearestInput(
        labelNode: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val labelBounds = Rect()
        labelNode.getBoundsInScreen(labelBounds)

        val parent = labelNode.parent ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectInputCandidates(parent, packageName, screenHeight, candidates, depth = 0)

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        for (candidate in candidates) {
            val r = Rect()
            candidate.getBoundsInScreen(r)
            if (r.isEmpty) continue
            // Prefer inputs below the label (typical form layout)
            if (r.top < labelBounds.top - 30) continue
            val score = abs(r.top - labelBounds.bottom) + abs(r.left - labelBounds.left) / 4
            if (score < bestScore) {
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(candidate)
                bestScore = score
            }
        }

        candidates.forEach { it.recycle() }
        parent.recycle()
        return best
    }

    private fun collectInputCandidates(
        node: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 6) return
        if (isInputNode(node, packageName, screenHeight)) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInputCandidates(child, packageName, screenHeight, out, depth + 1)
            child.recycle()
        }
    }

    private fun isInputNode(
        node: AccessibilityNodeInfo,
        packageName: String,
        screenHeight: Int
    ): Boolean {
        if (!node.isVisibleToUser) return false
        if (FormFieldDetector.isBrowserChromeField(node, screenHeight, packageName)) return false
        if (FormFieldDetector.isNavigationOrLink(node)) return false
        return FormFieldDetector.isActualFormInput(node, packageName, screenHeight)
    }
}
