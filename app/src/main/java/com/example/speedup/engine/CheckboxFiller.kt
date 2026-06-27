package com.example.speedup.engine

import android.view.accessibility.AccessibilityNodeInfo

class CheckboxFiller(private val textFiller: TextFiller) {
    fun fill(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        val shouldCheck = when (fillValue) {
            is FillValue.BooleanValue -> fillValue.checked
            is FillValue.Option -> fillValue.matchTokens.any { it.equals("yes", ignoreCase = true) }
            is FillValue.Text -> fillValue.value.equals("yes", ignoreCase = true)
            else -> true
        }
        val isChecked = node.isChecked
        return if (shouldCheck == isChecked) true else textFiller.tapNode(node)
    }
}
