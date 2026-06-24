package com.example.speedup.engine

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

object FieldMapper {

    private val aliasMap: Map<CanonicalField, List<String>> = mapOf(
        CanonicalField.FIRST_NAME to listOf(
            "first name", "first_name", "firstname", "given name", "givenname",
            "fname", "forename", "legal first name"
        ),
        CanonicalField.LAST_NAME to listOf(
            "last name", "last_name", "lastname", "surname", "family name",
            "lname", "legal last name"
        ),
        CanonicalField.FULL_NAME to listOf(
            "full name", "fullname", "your name", "legal name",
            "complete name", "applicant name", "candidate name"
        ),
        CanonicalField.EMAIL to listOf(
            "email", "e mail", "email address", "work email", "personal email",
            "contact email", "mail id"
        ),
        CanonicalField.PHONE to listOf(
            "phone", "mobile", "phone number", "mobile number", "contact number",
            "cell", "telephone", "contact phone", "whatsapp"
        ),
        CanonicalField.COUNTRY_CODE to listOf(
            "country code", "dial code", "phone code", "isd code", "area code"
        ),
        CanonicalField.CITY to listOf(
            "city", "current city", "city of residence", "town"
        ),
        CanonicalField.STATE to listOf(
            "state", "state province", "province", "region"
        ),
        CanonicalField.COUNTRY to listOf(
            "country", "country of residence", "nationality country"
        ),
        CanonicalField.LOCATION to listOf(
            "location", "current location", "address", "where do you live"
        ),
        CanonicalField.LINKEDIN to listOf(
            "linkedin", "linkedin url", "linkedin profile", "linkedin link"
        ),
        CanonicalField.PORTFOLIO to listOf(
            "portfolio", "website", "personal website", "github", "github url",
            "portfolio url", "personal url"
        ),
        CanonicalField.CURRENT_TITLE to listOf(
            "current role", "current position", "job title", "current title",
            "designation", "your role", "professional title"
        ),
        CanonicalField.YEARS_OF_EXPERIENCE to listOf(
            "years of experience", "total experience", "work experience years",
            "experience years", "years exp"
        ),
        CanonicalField.COMPANY to listOf(
            "company", "employer", "organization", "current company",
            "company name", "organisation"
        ),
        CanonicalField.EDUCATION to listOf(
            "education", "qualification", "academic background"
        ),
        CanonicalField.DEGREE to listOf(
            "degree", "qualification", "field of study", "major"
        ),
        CanonicalField.SCHOOL to listOf(
            "school", "university", "college", "institution", "institute"
        ),
        CanonicalField.COVER_LETTER to listOf(
            "cover letter", "why do you want", "why this role", "motivation",
            "why join", "additional information"
        ),
        CanonicalField.ABOUT to listOf(
            "about", "about you", "tell us about yourself", "bio", "summary",
            "professional summary", "describe yourself"
        )
    )

    private val appOverrides: Map<String, Map<String, CanonicalField>> = mapOf(
        "com.linkedin.android" to mapOf(
            "first_name" to CanonicalField.FIRST_NAME,
            "last_name" to CanonicalField.LAST_NAME,
            "email_address" to CanonicalField.EMAIL,
            "phone_number" to CanonicalField.PHONE,
            "phone" to CanonicalField.PHONE,
            "email" to CanonicalField.EMAIL
        ),
        "com.android.chrome" to webFormOverrides(),
        "com.chrome.beta" to webFormOverrides(),
        "com.chrome.dev" to webFormOverrides(),
        "org.mozilla.firefox" to webFormOverrides(),
        "com.microsoft.emmx" to webFormOverrides()
    )

    private fun webFormOverrides(): Map<String, CanonicalField> = mapOf(
        "fname" to CanonicalField.FIRST_NAME,
        "firstname" to CanonicalField.FIRST_NAME,
        "first-name" to CanonicalField.FIRST_NAME,
        "first_name" to CanonicalField.FIRST_NAME,
        "lname" to CanonicalField.LAST_NAME,
        "lastname" to CanonicalField.LAST_NAME,
        "last-name" to CanonicalField.LAST_NAME,
        "last_name" to CanonicalField.LAST_NAME,
        "email" to CanonicalField.EMAIL,
        "e-mail" to CanonicalField.EMAIL,
        "phone" to CanonicalField.PHONE,
        "tel" to CanonicalField.PHONE,
        "mobile" to CanonicalField.PHONE,
        "linkedin" to CanonicalField.LINKEDIN,
        "website" to CanonicalField.PORTFOLIO,
        "portfolio" to CanonicalField.PORTFOLIO,
        "city" to CanonicalField.CITY,
        "state" to CanonicalField.STATE,
        "country" to CanonicalField.COUNTRY,
        "location" to CanonicalField.LOCATION,
        "resume" to CanonicalField.ABOUT,
        "cover-letter" to CanonicalField.COVER_LETTER,
        "coverletter" to CanonicalField.COVER_LETTER
    )

    fun matchField(
        viewId: String,
        hint: String,
        contentDesc: String,
        labelText: String,
        inputType: Int,
        packageName: String = ""
    ): FieldMatch {
        val signals = buildSignals(viewId, hint, contentDesc, labelText)

        matchFromInputType(inputType)?.let { field ->
            return FieldMatch(field, 0.95f, 1, "inputType")
        }

        matchFromAppOverride(packageName, viewId)?.let { field ->
            return FieldMatch(field, 0.98f, 2, viewId)
        }

        matchFromKeywords(signals)?.let { (field, alias) ->
            return FieldMatch(field, 0.92f, 3, alias)
        }

        matchFromFuzzy(signals)?.let { (field, alias, score) ->
            return FieldMatch(field, score.toFloat(), 4, alias)
        }

        // Layer 5: Semantic Embedding Matching
        var bestSemanticMatch: FieldMatch? = null
        val candidates = listOf(
            labelText,
            hint,
            contentDesc,
            viewId.substringAfterLast("/").replace("_", " ")
        ).filter { it.isNotBlank() }.distinct()

        for (candidate in candidates) {
            SemanticMatcher.match(candidate)?.let { match ->
                if (bestSemanticMatch == null || match.confidence > bestSemanticMatch!!.confidence) {
                    bestSemanticMatch = match
                }
            }
        }

        if (bestSemanticMatch != null) {
            return bestSemanticMatch!!
        }

        return FieldMatch(CanonicalField.UNKNOWN, 0f, 0)
    }

    private fun buildSignals(viewId: String, hint: String, contentDesc: String, labelText: String): String {
        val idPart = viewId.substringAfterLast("/").replace("_", " ")
        return FuzzyMatcher.normalize("$idPart $hint $contentDesc $labelText")
    }

    private fun matchFromInputType(inputType: Int): CanonicalField? {
        val typeClass = inputType and InputType.TYPE_MASK_CLASS
        val typeVariation = inputType and InputType.TYPE_MASK_VARIATION
        return when {
            typeVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> CanonicalField.EMAIL
            typeClass == InputType.TYPE_CLASS_PHONE -> CanonicalField.PHONE
            typeVariation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> CanonicalField.FULL_NAME
            typeVariation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> CanonicalField.LOCATION
            typeVariation == InputType.TYPE_TEXT_VARIATION_URI -> CanonicalField.PORTFOLIO
            else -> null
        }
    }

    private fun matchFromAppOverride(packageName: String, viewId: String): CanonicalField? {
        val overrides = appOverrides[packageName] ?: return null
        val idKey = viewId.substringAfterLast("/").lowercase()
        overrides[idKey]?.let { return it }
        // HTML name/id attributes often appear as partial resource names
        for ((key, field) in overrides) {
            if (idKey.contains(key)) return field
        }
        return null
    }

    private fun matchFromKeywords(normalizedSignals: String): Pair<CanonicalField, String>? {
        for ((field, aliases) in aliasMap) {
            for (alias in aliases) {
                val normalizedAlias = FuzzyMatcher.normalize(alias)
                if (normalizedSignals.contains(normalizedAlias)) {
                    return field to alias
                }
            }
        }
        return null
    }

    private fun matchFromFuzzy(normalizedSignals: String): Triple<CanonicalField, String, Double>? {
        var best: Triple<CanonicalField, String, Double>? = null
        val tokens = normalizedSignals.split(" ").filter { it.length > 2 }

        for ((field, aliases) in aliasMap) {
            for (alias in aliases) {
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
        }
        return best
    }

    fun findLabelFor(node: AccessibilityNodeInfo): String {
        findAssociatedLabel(node)?.let { return it }

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val className = sibling.className?.toString() ?: ""
                if (sibling != node && className.contains("TextView", ignoreCase = true)) {
                    sibling.text?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length < 80 }?.let {
                        sibling.recycle()
                        parent.recycle()
                        return it
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

    private fun findAssociatedLabel(node: AccessibilityNodeInfo): String? {
        try {
            val labelForId = node.labelFor
            if (labelForId != null) {
                labelForId.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    return it
                }
            }
        } catch (_: Exception) {
            // labelFor deprecated on newer APIs
        }

        val labeledBy = node.labeledBy
        if (labeledBy != null) {
            labeledBy.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return null
    }
}
