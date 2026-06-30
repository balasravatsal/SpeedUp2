package com.example.speedup.data.model

data class ExtractedFormField(
    val label: String,
    val fieldType: String,
    val hint: String = "",
    val matchSource: String = "",
    /** Screen Y from [android.view.accessibility.AccessibilityNodeInfo.getBoundsInScreen] at capture time. */
    val topPx: Int = -1,
    val leftPx: Int = -1,
    val heightPx: Int = 0,
    /** Stable Y within the full page content (survives scrolling). */
    val documentTopPx: Int = -1,
    val documentLeftPx: Int = -1
)
