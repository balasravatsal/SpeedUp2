package com.example.speedup.engine

import android.view.accessibility.AccessibilityNodeInfo

class RadioSelector(private val textFiller: TextFiller) {
    fun fill(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        val tokens = when (fillValue) {
            is FillValue.Option -> fillValue.matchTokens
            is FillValue.Text -> listOf(fillValue.value)
            is FillValue.BooleanValue -> listOf(if (fillValue.checked) "Yes" else "No")
            else -> emptyList()
        }
        if (tokens.isEmpty()) return false
        textFiller.tapNode(node)
        Thread.sleep(200)
        return tokens.any { token ->
            findAndTapChild(node, token)
        }
    }

    private fun findAndTapChild(root: AccessibilityNodeInfo, token: String): Boolean {
        val normalized = FuzzyMatcher.normalize(token)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = FuzzyMatcher.normalize(node.text?.toString().orEmpty())
            if (text.isNotBlank() && (text == normalized || FuzzyMatcher.jaroWinkler(text, normalized) > 0.88)) {
                val ok = textFiller.tapNode(node)
                node.recycle()
                while (stack.isNotEmpty()) stack.removeFirst().recycle()
                return ok
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            if (node !== root) node.recycle()
        }
        return false
    }
}
