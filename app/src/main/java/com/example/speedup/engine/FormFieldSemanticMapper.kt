package com.example.speedup.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speedup.data.model.ExtractedFormField

/**
 * Resolves a human-readable label for a form input node.
 */
object FieldLabelHelper {

    fun findLabelFor(node: AccessibilityNodeInfo): String {
        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val siblingText = sibling.text?.toString()?.trim().orEmpty()
                if (sibling !== node && siblingText.isNotEmpty() && siblingText.length < 80 && !sibling.isEditable) {
                    sibling.recycle()
                    parent.recycle()
                    return siblingText.replace("*", "").trim()
                }
                sibling.recycle()
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            depth++
        }
        parent?.recycle()

        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrBlank()) {
            return viewId.substringAfterLast('/').replace('_', ' ').replace('-', ' ').trim()
        }
        return ""
    }
}

/**
 * Maps labels / hints to canonical field types using keywords and [SemanticMatcher].
 */
object FormFieldSemanticMapper {

    private val keywordRules: List<Pair<List<String>, CanonicalField>> = listOf(
        listOf("first name", "given name", "fname", "legal first") to CanonicalField.FIRST_NAME,
        listOf("middle name", "middle initial") to CanonicalField.MIDDLE_NAME,
        listOf("last name", "family name", "surname", "lname") to CanonicalField.LAST_NAME,
        listOf("full name", "your name", "candidate name") to CanonicalField.FULL_NAME,
        listOf("preferred name") to CanonicalField.PREFERRED_NAME,
        listOf("email", "e-mail", "email address") to CanonicalField.EMAIL,
        listOf("phone", "mobile", "cell", "telephone") to CanonicalField.PHONE,
        listOf("country code", "dial code") to CanonicalField.COUNTRY_CODE,
        listOf("linkedin") to CanonicalField.LINKEDIN,
        listOf("portfolio", "personal website", "website") to CanonicalField.PORTFOLIO,
        listOf("github") to CanonicalField.GITHUB,
        listOf("city") to CanonicalField.CITY,
        listOf("state", "province", "region") to CanonicalField.STATE,
        listOf("country") to CanonicalField.COUNTRY,
        listOf("location", "address") to CanonicalField.LOCATION,
        listOf("current title", "job title", "position") to CanonicalField.CURRENT_TITLE,
        listOf("company", "employer") to CanonicalField.COMPANY,
        listOf("years of experience", "experience years") to CanonicalField.YEARS_OF_EXPERIENCE,
        listOf("school", "university", "college") to CanonicalField.SCHOOL,
        listOf("degree", "qualification") to CanonicalField.DEGREE,
        listOf("education") to CanonicalField.EDUCATION,
        listOf("cover letter") to CanonicalField.COVER_LETTER,
        listOf("about", "summary", "bio") to CanonicalField.ABOUT,
        listOf("work authorization", "legally authorized", "visa") to CanonicalField.WORK_AUTHORIZATION,
        listOf("salary", "compensation") to CanonicalField.SALARY_EXPECTATION,
        listOf("gender") to CanonicalField.GENDER,
        listOf("veteran") to CanonicalField.VETERAN_STATUS,
        listOf("relocate", "relocation") to CanonicalField.WILLING_TO_RELOCATE,
        listOf("notice period") to CanonicalField.NOTICE_PERIOD,
        listOf("how did you hear", "referral") to CanonicalField.REFERRAL_SOURCE
    )

    fun classify(
        label: String,
        hint: String,
        contentDesc: String,
        viewId: String,
        inputType: Int
    ): Pair<String, String> {
        val normalizedLabel = label.trim()
        val candidate = buildString {
            append(normalizedLabel)
            if (hint.isNotBlank()) append(' ').append(hint)
            if (contentDesc.isNotBlank()) append(' ').append(contentDesc)
            if (viewId.isNotBlank()) append(' ').append(viewId.replace('_', ' ').replace('-', ' '))
        }.trim()

        matchFromInputType(inputType, normalizedLabel)?.let {
            return it.displayName to "inputType"
        }

        for ((keywords, field) in keywordRules) {
            if (keywords.any { candidate.lowercase().contains(it) }) {
                return field.displayName to "keyword"
            }
        }

        if (SemanticMatcher.isReady()) {
            SemanticMatcher.match(candidate)?.let { match ->
                if (match.field != CanonicalField.UNKNOWN) {
                    return match.field.displayName to "semantic"
                }
            }
        }

        return "Unknown" to "none"
    }

    private fun matchFromInputType(inputType: Int, label: String): CanonicalField? {
        if (inputType == 0) return null
        val typeClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        val typeVariation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when {
            typeClass == android.text.InputType.TYPE_CLASS_PHONE -> CanonicalField.PHONE
            typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> CanonicalField.EMAIL
            typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> CanonicalField.FULL_NAME
            typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> CanonicalField.LOCATION
            typeVariation == android.text.InputType.TYPE_TEXT_VARIATION_URI -> CanonicalField.PORTFOLIO
            else -> null
        }
    }

    fun widgetFallback(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString()?.lowercase().orEmpty()
        return when {
            node.isCheckable -> "Checkbox / Radio"
            className.contains("spinner") -> "Dropdown"
            node.isEditable -> "Text Input"
            else -> "Input"
        }
    }

    fun dedupeKey(viewId: String, label: String, node: AccessibilityNodeInfo): String {
        if (viewId.isNotBlank()) return "id:$viewId"
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "pos:${label.lowercase()}:${bounds.top / 32}:${bounds.left / 32}"
    }
}
