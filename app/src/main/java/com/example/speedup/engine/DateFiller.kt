package com.example.speedup.engine

import android.view.accessibility.AccessibilityNodeInfo

class DateFiller(private val textFiller: TextFiller) {
    fun fill(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        val value = when (fillValue) {
            is FillValue.DateValue -> fillValue.isoDate
            is FillValue.Text -> fillValue.value
            else -> return false
        }
        return textFiller.fillText(node, FillValue.Text(value))
    }
}
