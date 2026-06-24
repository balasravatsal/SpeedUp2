package com.example.speedup.engine

/**
 * Extracts structured job metadata and requirements from accessibility text blocks.
 * Designed for Greenhouse, Workday, LinkedIn, and generic job boards in browsers.
 */
object JdExtractor {

    data class ParsedJob(
        val title: String?,
        val company: String?,
        val location: String?,
        val requirements: List<String>,
        val requiredExperienceYears: Double,
        val isJobPage: Boolean,
        val confidence: DetectionConfidence
    )

    enum class DetectionConfidence {
        HIGH,      // Title + requirements found
        MEDIUM,    // Job page signals + title or company
        LOW,       // Job board URL / apply page but little JD visible
        NONE
    }

    private val jobPageSignals = listOf(
        "job application for", "apply for this job", "back to jobs",
        "key responsibilities", "about you", "qualifications", "requirements",
        "years of experience", "we are looking", "about the role",
        "what you'll do", "what you will do", "submit application",
        "indicates a required field", "powered by greenhouse", "quick apply",
        "pay transparency", "total rewards", "compensation", "salary range",
        "greenhouse.io", "job-boards", "workday", "lever.co", "ashbyhq.com"
    )

    private val titleRoleWords = listOf(
        "engineer", "developer", "designer", "manager", "director", "lead",
        "architect", "analyst", "specialist", "consultant", "intern",
        "accountant", "accounting", "president", "vice president", "vp",
        "coordinator", "associate", "executive", "officer", "recruiter",
        "scientist", "researcher", "administrator", "technician", "senior"
    )

    private val skipTitlePhrases = listOf(
        "apply", "back to", "create a job", "quick apply", "submit",
        "indicates a required", "powered by", "voluntary", "select...",
        "attach", "dropbox", "google drive", "first name", "last name",
        "pay transparency", "pay range", "total rewards", "the pay range"
    )

    fun parse(texts: List<String>): ParsedJob {
        val combined = texts.joinToString("\n")
        val combinedLower = combined.lowercase()

        val greenhouse = parseGreenhouseApplicationTitle(combined)
        val urlMeta = parseUrlMetadata(texts)
        val title = greenhouse?.first
            ?: parseHeadingTitle(texts)
            ?: urlMeta?.second
        val company = greenhouse?.second
            ?: parseCompanyName(texts, title, combinedLower)
            ?: urlMeta?.first
        val location = parseLocation(texts, combinedLower)
            ?: parseLocationFromPayTransparency(texts)
        val reqExperience = parseRequiredExperience(texts, combinedLower)
        val requirements = extractRequirements(texts, combined, combinedLower)
        val isJobPage = detectJobPage(texts, combinedLower)
        val confidence = assessConfidence(isJobPage, title, company, requirements, combinedLower)

        return ParsedJob(
            title = title,
            company = company,
            location = location,
            requirements = requirements,
            requiredExperienceYears = reqExperience,
            isJobPage = isJobPage,
            confidence = confidence
        )
    }

    private fun detectJobPage(texts: List<String>, combinedLower: String): Boolean {
        if (texts.isEmpty()) return false

        val platformSignals = listOf(
            "greenhouse.io", "job-boards.greenhouse", "workday", "lever.co",
            "jobs.lever", "linkedin.com", "indeed.com", "ashbyhq.com", "myworkdayjobs"
        )
        if (platformSignals.any { combinedLower.contains(it) }) return true

        if (jobPageSignals.any { combinedLower.contains(it) }) return true

        // Salary / compensation visible on page
        if (Regex("""\$\d{1,3}[,.]?\d{3}""").containsMatchIn(combinedLower)) return true

        if (combinedLower.contains("apply") && texts.size >= 3) return true

        return texts.size >= 5 && (
            combinedLower.contains("experience") ||
                combinedLower.contains("responsibilities") ||
                combinedLower.contains("about you")
            )
    }

    private fun assessConfidence(
        isJobPage: Boolean,
        title: String?,
        company: String?,
        requirements: List<String>,
        combinedLower: String
    ): DetectionConfidence {
        if (!isJobPage) return DetectionConfidence.NONE
        if (title != null && requirements.size >= 2) return DetectionConfidence.HIGH
        if (title != null && company != null) return DetectionConfidence.MEDIUM
        if (company != null || combinedLower.contains("greenhouse")) return DetectionConfidence.LOW
        return DetectionConfidence.LOW
    }

    /** greenhouse.io/crunchyroll/jobs/ → Crunchyroll */
    private fun parseUrlMetadata(texts: List<String>): Pair<String, String?>? {
        for (text in texts) {
            val lower = text.lowercase()
            val ghMatch = """greenhouse\.io/([a-z0-9_-]+)/jobs""".toRegex().find(lower)
            if (ghMatch != null) {
                val company = ghMatch.groupValues[1]
                    .replace("-", " ")
                    .split(" ")
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                return company to "Role at $company"
            }
        }
        return null
    }

    private fun parseLocationFromPayTransparency(texts: List<String>): String? {
        for (text in texts) {
            val match = """pay\s+transparency\s*[-–]\s*(.+)""".toRegex(RegexOption.IGNORE_CASE).find(text.trim())
            if (match != null) {
                val loc = match.groupValues[1].trim()
                if (loc.length in 3..80) return loc
            }
        }
        return null
    }

    /** "Job Application for Senior Vice President, Accounting at Crunchyroll, LLC" */
    private fun parseGreenhouseApplicationTitle(combined: String): Pair<String, String>? {
        val patterns = listOf(
            """job\s+application\s+for\s+(.+?)\s+at\s+(.+?)(?:\s*$|\n|\.|$)""",
            """for\s+(.+?)\s+at\s+(.+?)\s*-\s*""".toRegex(RegexOption.IGNORE_CASE).pattern
        )
        for (pattern in patterns) {
            val match = pattern.toRegex(RegexOption.IGNORE_CASE).find(combined) ?: continue
            val title = match.groupValues[1].trim().removeSuffix(".")
            val company = match.groupValues[2].trim().removeSuffix(".")
            if (title.length in 5..120 && company.length in 2..80) {
                return title to company
            }
        }
        return null
    }

    private fun parseHeadingTitle(texts: List<String>): String? {
        val candidates = texts
            .map { it.trim() }
            .filter { text ->
                val lower = text.lowercase()
                text.length in 10..100 &&
                    titleRoleWords.any { lower.contains(it) } &&
                    skipTitlePhrases.none { lower.contains(it) } &&
                    !lower.startsWith("about crunch") &&
                    !lower.startsWith("about the") &&
                    !lower.contains("key responsib") &&
                    !lower.contains("founded by")
            }
            .distinct()

        return candidates.maxByOrNull { scoreTitleCandidate(it) }
    }

    private fun scoreTitleCandidate(text: String): Int {
        var score = 0
        val lower = text.lowercase()
        if (lower.contains("senior") || lower.contains("vice president") || lower.contains("lead")) score += 3
        if (text.contains(",")) score += 2
        if (titleRoleWords.count { lower.contains(it) } == 1) score += 2
        score += (100 - text.length) / 10
        return score
    }

    private fun parseCompanyName(
        texts: List<String>,
        title: String?,
        combinedLower: String
    ): String? {
        for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.startsWith("About ", ignoreCase = true) && trimmed.length in 8..70) {
                val name = trimmed.removePrefix("About ").removePrefix("about ").trim()
                if (!name.equals("the role", ignoreCase = true) &&
                    !name.equals("you", ignoreCase = true)
                ) {
                    return name
                }
            }
        }

        for (text in texts) {
            if (text.matches(Regex("""[A-Z][\w\s]+,?\s*(LLC|Inc\.?|Corp\.?|Ltd\.?|LLP)"""))) {
                return text.trim()
            }
        }
        return null
    }

    private fun parseLocation(texts: List<String>, combinedLower: String): String? {
        for (text in texts) {
            val trimmed = text.trim()
            val lower = trimmed.lowercase()
            if (lower == "remote" || lower.contains("remote,") || lower.contains("hybrid")) {
                return trimmed
            }
            if (trimmed.contains(",") && trimmed.length in 8..80 &&
                (lower.contains("united states") || lower.contains("india") ||
                    lower.contains("california") || lower.contains("gujarat") ||
                    Regex("""\b[A-Z][a-z]+,\s*[A-Z][a-z]+""").containsMatchIn(trimmed))
            ) {
                return trimmed
            }
        }
        return null
    }

    fun parseRequiredExperience(texts: List<String>, combinedLower: String): Double {
        val patterns = listOf(
            """(\d+(?:\.\d+)?)\+?\s*(?:years|yrs)\s+of\s+(?:progressive\s+)?(?:\w+\s+){0,3}(?:experience|exp)"""
                .toRegex(RegexOption.IGNORE_CASE),
            """(\d+(?:\.\d+)?)\+?\s*(?:years|yrs)\s+(?:in\s+)?(?:a\s+)?(?:senior\s+)?(?:leadership\s+)?role"""
                .toRegex(RegexOption.IGNORE_CASE),
            """(\d+(?:\.\d+)?)\+?\s*(?:years|yrs|year)\s+""".toRegex(RegexOption.IGNORE_CASE)
        )
        var maxYears = 0.0
        val source = texts.joinToString(" ") + " " + combinedLower
        for (pattern in patterns) {
            for (match in pattern.findAll(source)) {
                val years = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
                if (years > maxYears) maxYears = years
            }
        }
        return maxYears
    }

    private fun extractRequirements(
        texts: List<String>,
        combined: String,
        combinedLower: String
    ): List<String> {
        val requirements = linkedSetOf<String>()

        for (skill in SkillMatcher.jdSkillCatalog) {
            if (SkillMatcher.findInText(combined, skill)) {
                requirements.add(skill)
            }
        }

        for (cert in listOf("CPA", "CFA", "PMP", "CISSP", "AWS Certified", "MBA")) {
            if (SkillMatcher.findInText(combined, cert)) {
                requirements.add(cert)
            }
        }

        val eduPatterns = listOf(
            "master's degree", "masters degree", "bachelor's degree", "bachelors degree",
            "phd", "doctorate", "mba"
        )
        for (pattern in eduPatterns) {
            if (combinedLower.contains(pattern)) {
                requirements.add(
                    pattern.split(" ").joinToString(" ") { w ->
                        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                )
            }
        }

        val sectionHeaders = listOf(
            "about you", "key responsibilities", "qualifications",
            "requirements", "must have", "nice to have", "what you'll need"
        )
        var inSection = false
        var sectionDepth = 0
        for (text in texts) {
            val lower = text.lowercase().trim()
            if (sectionHeaders.any { lower == it || lower.startsWith(it) }) {
                inSection = true
                sectionDepth = 0
                continue
            }
            if (inSection) {
                sectionDepth++
                if (sectionDepth > 25) inSection = false
                val bullet = text.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim()
                if (bullet.length in 15..200 && !isFormLabel(bullet)) {
                    condenseBulletToRequirement(bullet)?.let { requirements.add(it) }
                }
            }
        }

        val expYears = parseRequiredExperience(texts, combinedLower)
        if (expYears >= 1) {
            requirements.add("%.0f+ years experience".format(expYears))
        }

        return requirements.distinct().take(25)
    }

    private fun isFormLabel(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("first name") || lower.contains("last name") ||
            lower.contains("indicates a required") || lower.contains("attach") ||
            lower.contains("select...") || lower.contains("dropbox") ||
            lower.contains("voluntary self")
    }

    private fun condenseBulletToRequirement(bullet: String): String? {
        val lower = bullet.lowercase()
        val qualSignals = listOf(
            "experience", "required", "preferred", "degree", "certification",
            "cpa", "skills", "proficiency", "expertise", "knowledge", "understanding",
            "proven", "ability", "must", "years"
        )
        if (qualSignals.none { lower.contains(it) }) return null
        return if (bullet.length <= 80) bullet else bullet.take(77) + "…"
    }
}
