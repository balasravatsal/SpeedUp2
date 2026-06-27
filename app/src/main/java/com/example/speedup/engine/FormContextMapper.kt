package com.example.speedup.engine

/**
 * Second-pass refinement using page-level form context.
 */
object FormContextMapper {

    fun refineMatches(
        snapshots: MutableList<DetectedFieldSnapshot>,
        rawMatches: List<FieldMatch>
    ): List<FieldMatch> {
        val adjusted = snapshots.toMutableList()
        PhoneFieldGrouper.applySpatialHints(adjusted, PhoneFieldGrouper.buildClusters(adjusted))
        val context = FormContextBuilder.fromSnapshots(adjusted)

        return rawMatches.mapIndexed { index, match ->
            val snapshot = adjusted.getOrNull(index) ?: snapshots.getOrNull(index)
            val label = snapshot?.label.orEmpty()
            refineSingle(match, label, context, snapshot?.canonical)
        }
    }

    fun refineSingle(
        match: FieldMatch,
        label: String,
        context: FormContext,
        spatialCanonical: CanonicalField?
    ): FieldMatch {
        val normalized = FuzzyMatcher.normalize(label)

        spatialCanonical?.let { spatial ->
            if (spatial != CanonicalField.UNKNOWN && spatial != match.field) {
                return match.copy(field = spatial, confidence = 0.95f, matchedLayer = 0, matchedSignal = "spatial")
            }
        }

        if (context.hasSplitName && match.field == CanonicalField.FULL_NAME) {
            return FieldMatch(CanonicalField.UNKNOWN, 0f, 0, "split-name-downgrade")
        }

        if (normalized.contains("family name") || normalized.contains("surname")) {
            if (match.field == CanonicalField.FULL_NAME) {
                return FieldMatch(CanonicalField.LAST_NAME, 0.94f, 2, "family-name-guard")
            }
        }

        if (isCountryCodeLabel(normalized) && match.field == CanonicalField.PHONE) {
            return FieldMatch(CanonicalField.COUNTRY_CODE, 0.94f, 2, "country-code-guard")
        }

        if (isPhoneLabel(normalized) && match.field == CanonicalField.COUNTRY_CODE) {
            return FieldMatch(CanonicalField.PHONE, 0.94f, 2, "phone-label-guard")
        }

        if (context.hasSplitPhone && match.field == CanonicalField.PHONE && isCountryCodeLabel(normalized)) {
            return FieldMatch(CanonicalField.COUNTRY_CODE, 0.93f, 2, "split-phone-guard")
        }

        return match
    }

    fun isCountryCodeLabel(normalized: String): Boolean =
        normalized.contains("country code") ||
            normalized.contains("dial code") ||
            normalized.contains("phone code") ||
            normalized.contains("isd code") ||
            normalized.contains("calling code") ||
            normalized.contains("international code")

    fun isPhoneLabel(normalized: String): Boolean =
        normalized.contains("phone number") ||
            normalized.contains("mobile number") ||
            normalized.contains("cell number") ||
            normalized.contains("telephone number") ||
            (normalized.contains("phone") && !isCountryCodeLabel(normalized)) ||
            normalized.contains("mobile") ||
            normalized.contains("cell")
}
