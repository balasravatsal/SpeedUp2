package com.example.speedup.engine

import android.graphics.Rect

object PhoneFieldGrouper {

    fun buildClusters(fields: List<DetectedFieldSnapshot>): List<PhoneCluster> {
        val phoneRelated = fields.filter {
            it.canonical == CanonicalField.PHONE || it.canonical == CanonicalField.COUNTRY_CODE
        }
        if (phoneRelated.size < 2) return emptyList()

        val clusters = mutableListOf<PhoneCluster>()
        val used = mutableSetOf<Int>()

        for (i in phoneRelated.indices) {
            if (i in used) continue
            val a = phoneRelated[i]
            for (j in i + 1 until phoneRelated.size) {
                if (j in used) continue
                val b = phoneRelated[j]
                if (!areHorizontallyAdjacent(a.bounds, b.bounds)) continue

                val left = if (a.bounds.left <= b.bounds.left) a else b
                val right = if (left === a) b else a
                val leftIsCode = left.bounds.width() < right.bounds.width() ||
                    left.canonical == CanonicalField.COUNTRY_CODE

                val codeBounds = if (leftIsCode) left.bounds else right.bounds
                val phoneBounds = if (leftIsCode) right.bounds else left.bounds
                clusters.add(PhoneCluster(codeBounds, phoneBounds))
                used.add(i)
                used.add(j)
                break
            }
        }
        return clusters
    }

    fun applySpatialHints(
        fields: MutableList<DetectedFieldSnapshot>,
        clusters: List<PhoneCluster>
    ) {
        for (cluster in clusters) {
            for (i in fields.indices) {
                val f = fields[i]
                if (cluster.countryCodeBounds != null && boundsOverlap(f.bounds, cluster.countryCodeBounds)) {
                    fields[i] = f.copy(canonical = CanonicalField.COUNTRY_CODE)
                } else if (cluster.phoneBounds != null && boundsOverlap(f.bounds, cluster.phoneBounds)) {
                    fields[i] = f.copy(canonical = CanonicalField.PHONE)
                }
            }
        }
    }

    private fun areHorizontallyAdjacent(a: Rect, b: Rect): Boolean {
        val verticalOverlap = a.top < b.bottom && b.top < a.bottom
        if (!verticalOverlap) return false
        val hGap = if (a.right <= b.left) b.left - a.right else a.left - b.right
        return hGap in 0..120
    }

    private fun boundsOverlap(a: Rect, b: Rect): Boolean {
        return a.left <= b.right && b.left <= a.right &&
            a.top <= b.bottom && b.top <= a.bottom
    }
}

/** Lightweight field snapshot for form-context passes (no AccessibilityNodeInfo). */
data class DetectedFieldSnapshot(
    val canonical: CanonicalField,
    val label: String,
    val bounds: Rect,
    val viewId: String = "",
    val index: Int = 0
)

object FormContextBuilder {
    fun fromSnapshots(snapshots: List<DetectedFieldSnapshot>): FormContext {
        val clusters = PhoneFieldGrouper.buildClusters(snapshots)
        return FormContext(
            hasFirstName = snapshots.any { it.canonical == CanonicalField.FIRST_NAME },
            hasLastName = snapshots.any { it.canonical == CanonicalField.LAST_NAME },
            hasMiddleName = snapshots.any { it.canonical == CanonicalField.MIDDLE_NAME },
            hasFullName = snapshots.any { it.canonical == CanonicalField.FULL_NAME },
            hasCountryCode = snapshots.any { it.canonical == CanonicalField.COUNTRY_CODE },
            hasPhone = snapshots.any { it.canonical == CanonicalField.PHONE },
            phoneClusters = clusters
        )
    }
}
