package com.example.speedup.engine

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.data.model.ExtractedFormField

/**
 * Captures the accessibility node tree as an indented text dump and extracts form inputs.
 */
class AccessibilityTreeCollector(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AccessibilityTreeCollector"
        private const val MAX_NODES = 1200
        private const val MAX_TEXT_LEN = 80
    }

    data class TreeCaptureResult(
        val dump: String,
        val nodeCount: Int,
        val sourcePackage: String?,
        val formFields: List<ExtractedFormField>
    )

    private var nodesVisited = 0
    private val extractedFields = LinkedHashMap<String, ExtractedFormField>()
    private var packageName = ""
    private var screenHeight = 0

    fun capture(): TreeCaptureResult {
        nodesVisited = 0
        extractedFields.clear()
        val ownPackage = service.packageName
        screenHeight = service.resources.displayMetrics.heightPixels
        val sb = StringBuilder()

        val targets = WindowScanner(service).findJobContentWindows()
            .filter { it.packageName != ownPackage }

        val target = targets.firstOrNull()
            ?: WindowScanner(service).findBestContentWindowRoot()?.takeIf { it.packageName != ownPackage }

        if (target == null) {
            return TreeCaptureResult(
                dump = "No foreground content window found.",
                nodeCount = 0,
                sourcePackage = null,
                formFields = emptyList()
            )
        }

        packageName = target.packageName

        // Web forms: pair label TextViews with nearby inputs first
        val labeled = BrowserLabelFieldScanner.collect(target.root, packageName, screenHeight)
        for (item in labeled) {
            addExtractedField(item.input, item.label)
            item.input.recycle()
        }

        sb.append("package: ").append(target.packageName).append('\n')
        sb.append("nodes: (building…)\n\n")
        appendNode(target.root, 0, sb)

        val dump = sb.toString().replace(
            "nodes: (building…)",
            "nodes: $nodesVisited"
        )
        val fields = extractedFields.values
            .sortedWith(compareBy({ if (it.topPx < 0) Int.MAX_VALUE else it.topPx }, { it.leftPx }))
        Log.d(TAG, "Captured $nodesVisited nodes, ${fields.size} inputs from $packageName")
        return TreeCaptureResult(dump, nodesVisited, target.packageName, fields)
    }

    private fun appendNode(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || nodesVisited >= MAX_NODES) return
        nodesVisited++

        addExtractedField(node)

        val indent = "  ".repeat(depth.coerceAtMost(24))
        val className = node.className?.toString()?.substringAfterLast('.') ?: "Node"
        val attrs = mutableListOf<String>()

        truncate(node.text?.toString())?.let { attrs.add("text=\"$it\"") }
        truncate(node.hintText?.toString())?.let { attrs.add("hint=\"$it\"") }
        truncate(node.contentDescription?.toString())?.let { attrs.add("desc=\"$it\"") }
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { attrs.add("id=$it") }
        if (node.isEditable) attrs.add("editable")
        if (node.isCheckable) attrs.add("checkable")
        if (node.isFocusable) attrs.add("focusable")
        if (node.isScrollable) attrs.add("scrollable")
        if (!node.isVisibleToUser) attrs.add("not-visible")

        sb.append(indent)
            .append('[').append(depth).append("] ")
            .append(className)
        if (attrs.isNotEmpty()) {
            sb.append("  ").append(attrs.joinToString("  "))
        }
        sb.append('\n')

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            appendNode(child, depth + 1, sb)
            child?.recycle()
        }
    }

    private fun addExtractedField(node: AccessibilityNodeInfo, explicitLabel: String? = null) {
        if (!FormFieldDetector.isListableFormInput(node, packageName, screenHeight)) return

        val hint = node.hintText?.toString()?.trim().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val label = (explicitLabel ?: FieldLabelHelper.findLabelFor(node)).replace("*", "").trim()

        val (canonicalType, matchSource) = FormFieldSemanticMapper.classify(
            label = label,
            hint = hint,
            contentDesc = contentDesc,
            viewId = viewId,
            inputType = node.inputType
        )

        val displayType = if (canonicalType != "Unknown") {
            canonicalType
        } else {
            FormFieldSemanticMapper.widgetFallback(node)
        }

        val displayLabel = label.ifBlank {
            hint.ifBlank {
                contentDesc.ifBlank {
                    viewId.substringAfterLast('/').replace('_', ' ').ifBlank { "Input field" }
                }
            }
        }

        if (FormFieldDetector.looksLikeNavigationText(displayLabel) && !node.isEditable && !node.isCheckable) {
            return
        }

        val bounds = FormFieldDetector.boundsOf(node)
        val key = FormFieldSemanticMapper.dedupeKey(viewId, displayLabel, node)
        extractedFields[key] = ExtractedFormField(
            label = displayLabel,
            fieldType = displayType,
            hint = hint,
            matchSource = matchSource,
            topPx = bounds.top,
            leftPx = bounds.left,
            heightPx = bounds.height()
        )
    }

    private fun truncate(raw: String?): String? {
        val text = raw?.trim()?.replace("\n", " ") ?: return null
        if (text.isEmpty()) return null
        return if (text.length <= MAX_TEXT_LEN) text else text.take(MAX_TEXT_LEN) + "…"
    }
}
