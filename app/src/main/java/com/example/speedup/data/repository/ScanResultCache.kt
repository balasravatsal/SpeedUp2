package com.example.speedup.data.repository

import com.example.speedup.data.model.ExtractedFormField

/**
 * Holds the latest accessibility tree dump and extracted form fields from the most recent scan.
 */
object ScanResultCache {

    @Volatile
    var treeDump: String = ""
        private set

    @Volatile
    var nodeCount: Int = 0
        private set

    @Volatile
    var sourcePackage: String? = null
        private set

    @Volatile
    var formFields: List<ExtractedFormField> = emptyList()
        private set

    @Volatile
    var contentViewportTopPx: Int = 0
        private set

    @Volatile
    var scrollOffsetYAtCapture: Int = 0
        private set

    @Volatile
    var pageNormalizedToTop: Boolean = false
        private set

    fun update(
        dump: String,
        nodes: Int,
        packageName: String?,
        fields: List<ExtractedFormField> = emptyList(),
        contentViewportTop: Int = 0,
        scrollOffsetY: Int = 0,
        normalizedToTop: Boolean = false
    ) {
        treeDump = dump
        nodeCount = nodes
        sourcePackage = packageName
        formFields = fields
        contentViewportTopPx = contentViewportTop
        scrollOffsetYAtCapture = scrollOffsetY
        pageNormalizedToTop = normalizedToTop
    }

    fun clear() {
        treeDump = ""
        nodeCount = 0
        sourcePackage = null
        formFields = emptyList()
        contentViewportTopPx = 0
        scrollOffsetYAtCapture = 0
        pageNormalizedToTop = false
    }
}
