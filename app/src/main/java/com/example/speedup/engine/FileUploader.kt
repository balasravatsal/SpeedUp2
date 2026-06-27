package com.example.speedup.engine

import android.view.accessibility.AccessibilityNodeInfo

class FileUploader(private val textFiller: TextFiller) {
    fun fill(node: AccessibilityNodeInfo, fillValue: FillValue): Boolean {
        if (fillValue !is FillValue.FileUri || fillValue.contentUri.isBlank()) return false
        textFiller.tapNode(node)
        Thread.sleep(300)
        // Full SAF file-picker navigation is platform-dependent; tap opens picker for user.
        return true
    }
}
