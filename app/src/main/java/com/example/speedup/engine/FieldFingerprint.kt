package com.example.speedup.engine

import android.graphics.Rect

/** Identifies a form field for re-finding after overlay dismisses. */
data class FieldFingerprint(
    val viewId: String?,
    val hint: String,
    val label: String,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsWidth: Int,
    val boundsHeight: Int,
    val canonical: CanonicalField
) {
    companion object {
        fun from(
            viewId: String?,
            hint: String,
            label: String,
            bounds: Rect,
            canonical: CanonicalField
        ): FieldFingerprint = FieldFingerprint(
            viewId = viewId?.takeIf { it.isNotBlank() },
            hint = hint.trim(),
            label = label.trim(),
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsWidth = bounds.width(),
            boundsHeight = bounds.height(),
            canonical = canonical
        )
    }

    fun boundsMatch(rect: Rect, tolerance: Int = 8): Boolean {
        if (boundsWidth <= 0 || boundsHeight <= 0) return false
        return kotlin.math.abs(rect.left - boundsLeft) <= tolerance &&
            kotlin.math.abs(rect.top - boundsTop) <= tolerance &&
            kotlin.math.abs(rect.width() - boundsWidth) <= tolerance * 2
    }
}
