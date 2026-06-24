package com.example.speedup.engine

import com.example.speedup.data.model.JobPosting
import com.example.speedup.data.repository.ProfileRepository

/**
 * Compares JD requirements against the user's profile.
 * JD requirements are the source of truth — profile is checked against them.
 */
object JobFitAnalyzer {

    data class FitResult(
        val skillsMatched: List<String>,
        val skillsMissing: List<String>,
        val skillsPartial: List<String>,
        val requiredExperience: Double,
        val fitScore: Int,
        val fitLabel: String,
        val fitSubtitle: String
    )

    fun analyze(texts: List<String>, repository: ProfileRepository): JobPosting {
        val parsed = JdExtractor.parse(texts)

        if (!parsed.isJobPage) {
            return buildNoJdResult(parsed)
        }

        val profileSkills = collectProfileSkills(repository)
        val profile = repository.getProfile()
        val userOverallExp = repository.getTotalYearsOfExperience()

        val requirements = parsed.requirements

        // Job page detected but requirements not in viewport — still show job info
        if (requirements.isEmpty()) {
            return buildPartialJdResult(parsed, repository)
        }

        val fit = computeFit(
            jdRequirements = requirements,
            profileSkills = profileSkills,
            profileBio = profile.bio + " " + profile.title,
            reqExperience = parsed.requiredExperienceYears,
            userOverallExp = userOverallExp,
            repository = repository
        )

        return JobPosting(
            title = parsed.title ?: "Job Opening",
            company = parsed.company ?: "—",
            location = parsed.location ?: "—",
            fitScore = fit.fitScore,
            skillsMatched = fit.skillsMatched,
            skillsMissing = fit.skillsMissing,
            skillsPartial = fit.skillsPartial,
            skillsIgnored = emptyList(),
            requiredExperience = fit.requiredExperience,
            jdDetected = true,
            fitLabel = fit.fitLabel,
            fitSubtitle = fit.fitSubtitle
        )
    }

    private fun buildNoJdResult(parsed: JdExtractor.ParsedJob): JobPosting {
        return JobPosting(
            title = parsed.title ?: "Open a job posting",
            company = parsed.company ?: "—",
            location = parsed.location ?: "—",
            fitScore = 0,
            skillsMatched = emptyList(),
            skillsMissing = emptyList(),
            skillsPartial = emptyList(),
            jdDetected = false,
            fitLabel = "No JD Detected",
            fitSubtitle = "Open a job page in Chrome (Greenhouse, LinkedIn, etc.) and tap again"
        )
    }

    private fun buildPartialJdResult(
        parsed: JdExtractor.ParsedJob,
        repository: ProfileRepository
    ): JobPosting {
        val userExp = repository.getTotalYearsOfExperience()
        val expGap = if (parsed.requiredExperienceYears > 0 && userExp < parsed.requiredExperienceYears) {
            listOf("%.0f+ years experience".format(parsed.requiredExperienceYears))
        } else {
            emptyList()
        }

        return JobPosting(
            title = parsed.title ?: "Job Opening",
            company = parsed.company ?: "—",
            location = parsed.location ?: "—",
            fitScore = if (expGap.isNotEmpty()) 15 else 25,
            skillsMatched = emptyList(),
            skillsMissing = expGap,
            skillsPartial = emptyList(),
            jdDetected = true,
            requiredExperience = parsed.requiredExperienceYears,
            fitLabel = "JD Partially Loaded",
            fitSubtitle = "Scroll to \"About you\" / requirements for full match analysis"
        )
    }

    fun collectProfileSkills(repository: ProfileRepository): List<String> {
        val profile = repository.getProfile()
        val fromExperience = repository.getExperiences().flatMap { it.skillsUsed }
        val fromBio = profile.title.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        return (profile.skills + fromExperience + fromBio)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    fun collectProfileContext(repository: ProfileRepository): String {
        val profile = repository.getProfile()
        val experiences = repository.getExperiences()
        val education = repository.getEducation()
        return buildString {
            append(profile.bio).append(" ")
            append(profile.title).append(" ")
            append(profile.skills.joinToString(" ")).append(" ")
            experiences.forEach { exp ->
                append(exp.title).append(" ")
                append(exp.description).append(" ")
                append(exp.skillsUsed.joinToString(" ")).append(" ")
            }
            education.forEach { edu ->
                append(edu.degree).append(" ").append(edu.school).append(" ")
            }
        }.lowercase()
    }

    fun computeFit(
        jdRequirements: List<String>,
        profileSkills: List<String>,
        profileBio: String,
        reqExperience: Double,
        userOverallExp: Double,
        repository: ProfileRepository
    ): FitResult {
        val matched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val partial = mutableListOf<String>()
        val profileContext = collectProfileContext(repository).lowercase()

        for (requirement in jdRequirements) {
            when (matchRequirementToProfile(requirement, profileSkills, profileContext, repository)) {
                MatchLevel.STRONG -> matched.add(requirement)
                MatchLevel.PARTIAL -> partial.add(requirement)
                MatchLevel.NONE -> missing.add(requirement)
            }
        }

        val totalReqs = jdRequirements.size.coerceAtLeast(1)
        var score = 0
        score += ((matched.size.toDouble() / totalReqs) * 50).toInt()
        score += ((partial.size.toDouble() / totalReqs) * 10).toInt()

        if (reqExperience > 0) {
            score += when {
                userOverallExp >= reqExperience -> 25
                else -> ((userOverallExp / reqExperience).coerceIn(0.0, 1.0) * 25).toInt()
            }
        } else {
            score += 15
        }

        if (matched.isNotEmpty()) {
            val withExp = matched.count { req ->
                repository.getYearsOfExperienceForSkill(req) > 0.0 ||
                    profileContext.contains(req.lowercase().take(20))
            }
            score += ((withExp.toDouble() / matched.size) * 15).toInt()
        }

        val finalScore = score.coerceIn(0, 100)
        val (label, subtitle) = scoreToLabel(finalScore, matched.size, missing.size)

        return FitResult(
            skillsMatched = matched,
            skillsMissing = missing,
            skillsPartial = partial,
            requiredExperience = reqExperience,
            fitScore = finalScore,
            fitLabel = label,
            fitSubtitle = subtitle
        )
    }

    private enum class MatchLevel { STRONG, PARTIAL, NONE }

    private fun matchRequirementToProfile(
        requirement: String,
        profileSkills: List<String>,
        profileContext: String,
        repository: ProfileRepository
    ): MatchLevel {
        val reqLower = requirement.lowercase()

        if (reqLower.contains("years experience") || reqLower.contains("years of experience")) {
            val years = Regex("""(\d+)""").find(reqLower)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return when {
                years <= 0 -> MatchLevel.STRONG
                repository.getTotalYearsOfExperience() >= years -> MatchLevel.STRONG
                repository.getTotalYearsOfExperience() >= years * 0.7 -> MatchLevel.PARTIAL
                else -> MatchLevel.NONE
            }
        }

        if (reqLower.contains("degree") || reqLower.contains("mba") || reqLower.contains("phd")) {
            val hasEdu = repository.getEducation().any { edu ->
                val deg = edu.degree.lowercase()
                reqLower.split(" ").any { word -> word.length > 3 && deg.contains(word) }
            }
            return if (hasEdu) MatchLevel.STRONG
            else if (profileContext.contains("bachelor") || profileContext.contains("master")) MatchLevel.PARTIAL
            else MatchLevel.NONE
        }

        if (profileContext.contains(reqLower.take(20))) return MatchLevel.STRONG

        return when (SkillMatcher.matchJdSkillToProfile(requirement, profileSkills).matchType) {
            SkillMatcher.MatchType.EXACT, SkillMatcher.MatchType.STRONG -> MatchLevel.STRONG
            SkillMatcher.MatchType.PARTIAL -> MatchLevel.PARTIAL
            SkillMatcher.MatchType.NONE -> MatchLevel.NONE
        }
    }

    private fun scoreToLabel(score: Int, matched: Int, missing: Int): Pair<String, String> {
        return when {
            score >= 80 -> "Strong Match" to "You meet most JD requirements"
            score >= 50 -> "Partial Match" to "$matched matched · $missing gaps in your profile"
            score >= 25 -> "Weak Match" to "$missing requirements you may lack"
            else -> "Poor Match" to "This role may not fit your current profile"
        }
    }
}
