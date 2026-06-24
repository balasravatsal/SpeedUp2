package com.example.speedup.engine

/**
 * Matches JD-required skills against the user's declared profile skills.
 * Uses alias lookup, fuzzy matching, then ONNX embeddings when available.
 */
object SkillMatcher {

    private const val EXACT_THRESHOLD = 0.95
    private const val STRONG_THRESHOLD = 0.82
    private const val PARTIAL_THRESHOLD = 0.68

    data class SkillMatchResult(
        val jdSkill: String,
        val profileSkill: String?,
        val confidence: Float,
        val matchType: MatchType
    )

    enum class MatchType {
        EXACT, STRONG, PARTIAL, NONE
    }

    private val skillAliases: Map<String, List<String>> = mapOf(
        "java" to listOf("java", "java ee", "javaee", "j2ee", "jdk"),
        "spring boot" to listOf("spring boot", "springboot", "spring framework", "spring"),
        "spring" to listOf("spring", "spring framework", "spring mvc"),
        "microservices" to listOf("microservices", "micro services", "microservice"),
        "react" to listOf("react", "reactjs", "react.js", "react native", "reactnative"),
        "typescript" to listOf("typescript", "ts"),
        "javascript" to listOf("javascript", "js", "ecmascript"),
        "node.js" to listOf("node.js", "nodejs", "node"),
        "kotlin" to listOf("kotlin", "kt"),
        "python" to listOf("python", "py"),
        "aws" to listOf("aws", "amazon web services", "ec2", "s3", "lambda"),
        "docker" to listOf("docker", "containers", "containerization"),
        "kubernetes" to listOf("kubernetes", "k8s"),
        "sql" to listOf("sql", "mysql", "postgresql", "postgres", "sqlite"),
        "mongodb" to listOf("mongodb", "mongo"),
        "git" to listOf("git", "github", "gitlab", "bitbucket"),
        "android" to listOf("android", "android sdk"),
        "machine learning" to listOf("machine learning", "ml", "deep learning", "ai"),
        "tensorflow" to listOf("tensorflow", "tf"),
        "pytorch" to listOf("pytorch", "torch"),
        "figma" to listOf("figma"),
        "ux research" to listOf("ux research", "user research", "usability testing"),
        "ui design" to listOf("ui design", "user interface", "visual design"),
        "rest api" to listOf("rest", "rest api", "restful", "api development"),
        "graphql" to listOf("graphql", "gql"),
        "redis" to listOf("redis"),
        "kafka" to listOf("kafka", "apache kafka"),
        "ci/cd" to listOf("ci/cd", "cicd", "continuous integration", "continuous deployment"),
        "agile" to listOf("agile", "scrum", "kanban"),
        "html" to listOf("html", "html5"),
        "css" to listOf("css", "css3", "scss", "sass"),
        "angular" to listOf("angular", "angularjs"),
        "vue" to listOf("vue", "vue.js", "vuejs"),
        "flutter" to listOf("flutter", "dart"),
        "swift" to listOf("swift", "swiftui"),
        "ios" to listOf("ios", "iphone"),
        "devops" to listOf("devops", "sre", "site reliability"),
        "terraform" to listOf("terraform", "iac", "infrastructure as code"),
        "elasticsearch" to listOf("elasticsearch", "elastic search", "elk"),
        "rabbitmq" to listOf("rabbitmq", "message queue", "mq"),
        "hibernate" to listOf("hibernate", "jpa"),
        "maven" to listOf("maven", "gradle"),
        "c++" to listOf("c++", "cpp"),
        "c#" to listOf("c#", "csharp", ".net", "dotnet"),
        "go" to listOf("go", "golang"),
        "rust" to listOf("rust"),
        "scala" to listOf("scala"),
        "spark" to listOf("spark", "apache spark"),
        "hadoop" to listOf("hadoop"),
        "tableau" to listOf("tableau", "power bi", "powerbi"),
        "jira" to listOf("jira", "confluence"),
        "selenium" to listOf("selenium", "cypress", "playwright"),
        "junit" to listOf("junit", "mockito", "testing"),
        "oauth" to listOf("oauth", "oauth2", "jwt", "authentication"),
        "linux" to listOf("linux", "unix", "bash"),
        "excel" to listOf("excel", "spreadsheet"),
        "communication" to listOf("communication", "verbal", "written"),
        "leadership" to listOf("leadership", "team lead", "mentoring", "executive leadership"),
        "accounting" to listOf("accounting", "accountant", "bookkeeping"),
        "finance" to listOf("finance", "financial", "fp&a", "financial planning"),
        "gaap" to listOf("gaap", "generally accepted accounting"),
        "ifrs" to listOf("ifrs", "international financial reporting"),
        "cpa" to listOf("cpa", "certified public accountant"),
        "erp" to listOf("erp", "enterprise resource planning", "sap", "oracle erp"),
        "auditing" to listOf("auditing", "audit", "external audit", "internal audit")
    )

    /** Finance, accounting, and cross-domain terms for JD scanning. */
    val domainCatalog: List<String> = listOf(
        "Accounting", "Finance", "GAAP", "IFRS", "CPA", "ERP", "Financial Reporting",
        "Auditing", "Audit", "Budgeting", "Forecasting", "Tax", "Compliance",
        "Leadership", "Management", "Strategic Planning", "Mergers And Acquisitions",
        "Due Diligence", "Investor Relations", "Risk Management", "Internal Controls",
        "Excel", "SAP", "Oracle", "QuickBooks", "Salesforce", "Communication",
        "Project Management", "Data Analysis", "Business Intelligence"
    )

    /** Canonical tech skills used to scan JD text for requirements. */
    val jdSkillCatalog: List<String> = (skillAliases.keys.map { canonical ->
        canonical.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    } + domainCatalog).distinct()

    fun normalizeSkill(skill: String): String =
        FuzzyMatcher.normalize(skill).replace(" ", "")

    fun findInText(text: String, skill: String): Boolean {
        val lower = text.lowercase()
        val canonical = skill.lowercase()
        if ("""\b${Regex.escape(canonical)}\b""".toRegex().containsMatchIn(lower)) return true
        val aliases = skillAliases[canonical] ?: listOf(canonical)
        return aliases.any { alias ->
            """\b${Regex.escape(alias)}\b""".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    fun extractSkillsFromJd(texts: List<String>): List<String> {
        val combined = texts.joinToString(" ")
        val found = linkedSetOf<String>()

        for (skill in jdSkillCatalog) {
            if (findInText(combined, skill)) {
                found.add(skill)
            }
        }
        return found.toList()
    }

    /**
     * Match a single JD skill against user profile skills.
     * Returns NONE if the user has no related skill in their profile (out of scope).
     */
    fun matchJdSkillToProfile(jdSkill: String, profileSkills: List<String>): SkillMatchResult {
        if (profileSkills.isEmpty()) {
            return SkillMatchResult(jdSkill, null, 0f, MatchType.NONE)
        }

        var bestProfileSkill: String? = null
        var bestScore = 0f

        for (profileSkill in profileSkills) {
            val score = similarity(jdSkill, profileSkill)
            if (score > bestScore) {
                bestScore = score
                bestProfileSkill = profileSkill
            }
        }

        val matchType = when {
            bestScore >= EXACT_THRESHOLD -> MatchType.EXACT
            bestScore >= STRONG_THRESHOLD -> MatchType.STRONG
            bestScore >= PARTIAL_THRESHOLD -> MatchType.PARTIAL
            else -> MatchType.NONE
        }

        return SkillMatchResult(
            jdSkill = jdSkill,
            profileSkill = if (matchType != MatchType.NONE) bestProfileSkill else null,
            confidence = bestScore,
            matchType = matchType
        )
    }

    fun isInProfileScope(jdSkill: String, profileSkills: List<String>): Boolean {
        return matchJdSkillToProfile(jdSkill, profileSkills).matchType != MatchType.NONE
    }

    fun similarity(skillA: String, skillB: String): Float {
        val normA = FuzzyMatcher.normalize(skillA)
        val normB = FuzzyMatcher.normalize(skillB)
        if (normA == normB) return 1f
        if (normA.replace(" ", "") == normB.replace(" ", "")) return 0.98f

        val aliasesA = expandAliases(normA)
        val aliasesB = expandAliases(normB)
        for (a in aliasesA) {
            for (b in aliasesB) {
                if (a == b) return 0.96f
                if (a.contains(b) || b.contains(a)) {
                    val shorter = minOf(a.length, b.length)
                    val longer = maxOf(a.length, b.length)
                    if (shorter.toFloat() / longer > 0.6f) return 0.88f
                }
            }
        }

        val fuzzy = FuzzyMatcher.jaroWinkler(normA.replace(" ", ""), normB.replace(" ", ""))
        if (fuzzy > 0.90) return fuzzy.toFloat()

        if (SemanticMatcher.isReady()) {
            val embA = SemanticMatcher.getEmbedding(skillA)
            val embB = SemanticMatcher.getEmbedding(skillB)
            if (embA != null && embB != null) {
                val semantic = SemanticMatcher.cosineSimilarity(embA, embB)
                if (semantic > fuzzy) return semantic
            }
        }

        return fuzzy.toFloat()
    }

    private fun expandAliases(normalizedSkill: String): Set<String> {
        val result = mutableSetOf(normalizedSkill)
        for ((canonical, aliases) in skillAliases) {
            val allForms = (aliases + canonical).map { FuzzyMatcher.normalize(it) }
            if (allForms.any { it == normalizedSkill || normalizedSkill.contains(it) || it.contains(normalizedSkill) }) {
                result.addAll(allForms)
            }
        }
        return result
    }
}
