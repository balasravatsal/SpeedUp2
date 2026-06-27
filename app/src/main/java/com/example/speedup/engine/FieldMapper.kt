package com.example.speedup.engine

import android.content.Context
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

object FieldMapper {

    private const val SEMANTIC_THRESHOLD = 0.68f

    private val sortedAliasEntries: List<Pair<CanonicalField, String>> by lazy {
        FieldAliasRepository.allAliasEntries()
            .sortedByDescending { it.second.length }
    }

    fun ensureInitialized(context: Context) {
        FieldAliasRepository.initialize(context)
    }

    fun matchField(
        viewId: String,
        hint: String,
        contentDesc: String,
        labelText: String,
        inputType: Int,
        packageName: String = ""
    ): FieldMatch = matchFieldInternal(viewId, hint, contentDesc, labelText, inputType, packageName, null)

    fun matchFieldWithContext(
        viewId: String,
        hint: String,
        contentDesc: String,
        labelText: String,
        inputType: Int,
        packageName: String,
        formContext: FormContext?
    ): FieldMatch {
        val base = matchFieldInternal(viewId, hint, contentDesc, labelText, inputType, packageName, formContext)
        if (formContext == null) return base
        return FormContextMapper.refineSingle(base, labelText, formContext, null)
    }

    private fun matchFieldInternal(
        viewId: String,
        hint: String,
        contentDesc: String,
        labelText: String,
        inputType: Int,
        packageName: String,
        formContext: FormContext?
    ): FieldMatch {
        val signals = buildSignals(viewId, hint, contentDesc, labelText)
        val normalizedLabel = FuzzyMatcher.normalize(labelText)

        applyNegativeGuards(normalizedLabel, signals)?.let { return it }

        FieldAliasRepository.atsOverride(packageName, viewId)?.let { field ->
            return FieldMatch(field, 0.98f, 1, viewId)
        }
        matchFromAppOverride(packageName, viewId)?.let { field ->
            return FieldMatch(field, 0.98f, 1, viewId)
        }

        matchFromLongestAlias(signals)?.let { return it }

        matchFromFuzzy(signals)?.let { return it }

        if (formContext == null || !formContext.hasSplitName) {
            matchFromInputType(inputType, normalizedLabel)?.let { field ->
                return FieldMatch(field, 0.85f, 3, "inputType")
            }
        } else if (inputType != 0) {
            val inputMatch = matchFromInputType(inputType, normalizedLabel)
            if (inputMatch != null && inputMatch != CanonicalField.FULL_NAME) {
                return FieldMatch(inputMatch, 0.85f, 3, "inputType")
            }
        }

        matchFromSemantic(labelText, hint, contentDesc, viewId)?.let { return it }

        return FieldMatch(CanonicalField.UNKNOWN, 0f, 0)
    }

    private fun applyNegativeGuards(normalizedLabel: String, signals: String): FieldMatch? {
        if (FormContextMapper.isCountryCodeLabel(normalizedLabel) || FormContextMapper.isCountryCodeLabel(signals)) {
            return FieldMatch(CanonicalField.COUNTRY_CODE, 0.96f, 2, "negative-guard-country-code")
        }
        if (normalizedLabel.contains("family name") || normalizedLabel.contains("surname") ||
            signals.contains("family name") || signals.contains("surname")
        ) {
            return FieldMatch(CanonicalField.LAST_NAME, 0.96f, 2, "negative-guard-family-name")
        }
        if (FormContextMapper.isPhoneLabel(normalizedLabel) && !FormContextMapper.isCountryCodeLabel(normalizedLabel)) {
            return FieldMatch(CanonicalField.PHONE, 0.95f, 2, "negative-guard-phone")
        }
        return null
    }

    private fun matchFromLongestAlias(normalizedSignals: String): FieldMatch? {
        for ((field, alias) in sortedAliasEntries) {
            val normalizedAlias = FuzzyMatcher.normalize(alias)
            if (normalizedAlias.isBlank()) continue
            if (containsAsPhrase(normalizedSignals, normalizedAlias)) {
                return FieldMatch(field, 0.92f, 2, alias)
            }
        }
        return null
    }

    private fun containsAsPhrase(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        if (haystack == needle) return true
        if (haystack.contains(" $needle ") || haystack.startsWith("$needle ") || haystack.endsWith(" $needle")) {
            return true
        }
        return haystack.contains(needle) && needle.length >= 4
    }

    private fun buildSignals(viewId: String, hint: String, contentDesc: String, labelText: String): String {
        val idPart = viewId.substringAfterLast("/").replace("_", " ").replace("-", " ")
        val cleanLabel = labelText.replace("*", "").trim()
        return FuzzyMatcher.normalize("$idPart $hint $contentDesc $cleanLabel")
    }

    private fun matchFromInputType(inputType: Int, normalizedLabel: String): CanonicalField? {
        if (FormContextMapper.isCountryCodeLabel(normalizedLabel)) return CanonicalField.COUNTRY_CODE
        val typeClass = inputType and InputType.TYPE_MASK_CLASS
        val typeVariation = inputType and InputType.TYPE_MASK_VARIATION
        return when {
            typeVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> CanonicalField.EMAIL
            typeClass == InputType.TYPE_CLASS_PHONE -> {
                if (FormContextMapper.isCountryCodeLabel(normalizedLabel)) CanonicalField.COUNTRY_CODE
                else CanonicalField.PHONE
            }
            typeVariation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> CanonicalField.FULL_NAME
            typeVariation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> CanonicalField.LOCATION
            typeVariation == InputType.TYPE_TEXT_VARIATION_URI -> CanonicalField.PORTFOLIO
            else -> null
        }
    }

    private fun matchFromAppOverride(packageName: String, viewId: String): CanonicalField? {
        return FieldAliasRepository.atsOverride(packageName, viewId)
    }

    private fun matchFromFuzzy(normalizedSignals: String): FieldMatch? {
        var best: Triple<CanonicalField, String, Double>? = null
        val tokens = normalizedSignals.split(" ").filter { it.length > 2 }

        for ((field, alias) in sortedAliasEntries) {
            val normalizedAlias = FuzzyMatcher.normalize(alias)
            val score = FuzzyMatcher.jaroWinkler(normalizedSignals, normalizedAlias)
            if (score > 0.88 && (best == null || score > best.third)) {
                best = Triple(field, alias, score)
            }
            for (token in tokens) {
                val tokenScore = FuzzyMatcher.jaroWinkler(token, normalizedAlias.replace(" ", ""))
                if (tokenScore > 0.92 && (best == null || tokenScore > best.third)) {
                    best = Triple(field, alias, tokenScore)
                }
            }
        }
        return best?.let { FieldMatch(it.first, it.third.toFloat(), 4, it.second) }
    }

    private fun matchFromSemantic(
        labelText: String,
        hint: String,
        contentDesc: String,
        viewId: String
    ): FieldMatch? {
        var best: FieldMatch? = null
        val candidates = listOf(labelText, hint, contentDesc, viewId.substringAfterLast("/"))
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            SemanticMatcher.match(candidate, SEMANTIC_THRESHOLD)?.let { match ->
                if (best == null || match.confidence > best!!.confidence) {
                    best = match
                }
            }
        }
        return best
    }

    fun findLabelFor(node: AccessibilityNodeInfo): String {
        findAssociatedLabel(node)?.let { return it }
        findPrecedingStaticText(node)?.let { return it }

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val className = sibling.className?.toString() ?: ""
                val siblingText = sibling.text?.toString()?.trim().orEmpty()
                if (sibling != node && siblingText.isNotBlank() && siblingText.length < 80) {
                    val isLabelLike = className.contains("TextView", ignoreCase = true) ||
                        className.contains("Label", ignoreCase = true) ||
                        (!sibling.isEditable && !sibling.isFocusable)
                    if (isLabelLike) {
                        val label = siblingText.replace("*", "").trim()
                        sibling.recycle()
                        parent.recycle()
                        return label
                    }
                }
                sibling.recycle()
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
            depth++
        }
        parent?.recycle()
        return ""
    }

    private fun findPrecedingStaticText(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        var seenTarget = false
        var lastText: String? = null
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child == node) {
                seenTarget = true
                child.recycle()
                break
            }
            val text = child.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text.length < 80 && !child.isEditable) {
                lastText = text.replace("*", "").trim()
            }
            child.recycle()
        }
        parent.recycle()
        return if (seenTarget) lastText else null
    }

    private fun findAssociatedLabel(node: AccessibilityNodeInfo): String? {
        try {
            val labelForId = node.labelFor
            if (labelForId != null) {
                labelForId.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    return it.replace("*", "").trim()
                }
            }
        } catch (_: Exception) {
        }

        val labeledBy = node.labeledBy
        if (labeledBy != null) {
            labeledBy.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it.replace("*", "").trim()
            }
        }
        return null
    }
}
