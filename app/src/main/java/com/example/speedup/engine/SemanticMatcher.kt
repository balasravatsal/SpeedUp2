package com.example.speedup.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException
import kotlin.math.sqrt

object SemanticMatcher {
    private const val TAG = "SemanticMatcher"
    
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    
    // Cache for descriptor embeddings
    private val descriptorEmbeddings = LinkedHashMap<CanonicalField, List<FloatArray>>()
    
    private var isInitialized = false

    fun isReady(): Boolean = isInitialized

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            Log.d(TAG, "Initializing SemanticMatcher...")
            env = OrtEnvironment.getEnvironment()
            
            // Load vocabulary
            val vocabLines = context.assets.open("vocab.txt").bufferedReader().use { it.readLines() }
            tokenizer = WordPieceTokenizer.load(vocabLines)
            Log.d(TAG, "Vocabulary loaded: ${vocabLines.size} tokens")
            
            // Load ONNX Model
            val modelBytes = context.assets.open("model_quantized.onnx").use { it.readBytes() }
            session = env?.createSession(modelBytes)
            Log.d(TAG, "ONNX model loaded successfully")
            
            // Precompute embeddings for all canonical fields and their representative descriptors
            precomputeDescriptors()
            
            isInitialized = true
            Log.d(TAG, "SemanticMatcher initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SemanticMatcher", e)
        }
    }

    private fun precomputeDescriptors() {
        val descriptorsMap = mapOf(
            CanonicalField.FIRST_NAME to listOf("first name", "firstname", "given name", "fname", "forename"),
            CanonicalField.LAST_NAME to listOf("last name", "lastname", "surname", "family name", "lname"),
            CanonicalField.FULL_NAME to listOf("full name", "fullname", "your name", "complete name"),
            CanonicalField.EMAIL to listOf("email", "email address", "e-mail", "work email", "personal email"),
            CanonicalField.PHONE to listOf("phone", "mobile", "phone number", "mobile number", "contact number", "telephone"),
            CanonicalField.COUNTRY_CODE to listOf("country code", "dial code", "phone code", "area code"),
            CanonicalField.CITY to listOf("city", "current city", "city of residence", "town"),
            CanonicalField.STATE to listOf("state", "state province", "province", "region"),
            CanonicalField.COUNTRY to listOf("country", "country of residence", "nationality"),
            CanonicalField.LOCATION to listOf("location", "current location", "address", "where do you live"),
            CanonicalField.LINKEDIN to listOf("linkedin", "linkedin url", "linkedin profile"),
            CanonicalField.PORTFOLIO to listOf("portfolio", "website", "personal website", "github", "portfolio url"),
            CanonicalField.CURRENT_TITLE to listOf("current role", "current position", "job title", "current title", "designation"),
            CanonicalField.YEARS_OF_EXPERIENCE to listOf("years of experience", "total experience", "work experience years", "years exp"),
            CanonicalField.COMPANY to listOf("company", "employer", "organization", "current company", "company name"),
            CanonicalField.EDUCATION to listOf("education", "qualification", "academic background"),
            CanonicalField.DEGREE to listOf("degree", "field of study", "major"),
            CanonicalField.SCHOOL to listOf("school", "university", "college", "institution"),
            CanonicalField.COVER_LETTER to listOf("cover letter", "motivation letter", "why join", "why this role"),
            CanonicalField.ABOUT to listOf("about", "about you", "bio", "summary", "professional summary")
        )

        for ((field, descriptors) in descriptorsMap) {
            val list = mutableListOf<FloatArray>()
            for (desc in descriptors) {
                getEmbeddingDirect(desc)?.let {
                    list.add(it)
                }
            }
            descriptorEmbeddings[field] = list
        }
        Log.d(TAG, "Precomputed descriptors for ${descriptorEmbeddings.size} fields")
    }

    private fun getEmbeddingDirect(text: String): FloatArray? {
        val currentSession = session ?: return null
        val currentEnv = env ?: return null
        val currentTokenizer = tokenizer ?: return null

        var inputIdsTensor: OnnxTensor? = null
        var attentionMaskTensor: OnnxTensor? = null
        var tokenTypeIdsTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            val tokens = currentTokenizer.tokenize(text)
            if (tokens.isEmpty()) return null
            
            val inputIds = LongArray(tokens.size) { tokens[it].toLong() }
            val attentionMask = LongArray(tokens.size) { 1L }
            val tokenTypeIds = LongArray(tokens.size) { 0L }

            inputIdsTensor = OnnxTensor.createTensor(currentEnv, arrayOf(inputIds))
            attentionMaskTensor = OnnxTensor.createTensor(currentEnv, arrayOf(attentionMask))
            tokenTypeIdsTensor = OnnxTensor.createTensor(currentEnv, arrayOf(tokenTypeIds))

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor
            )

            results = currentSession.run(inputs)
            if (results.count() == 0) return null
            
            @Suppress("UNCHECKED_CAST")
            val outputTensor = results.get(0).value as Array<Array<FloatArray>>
            
            // Perform Mean Pooling
            val seqLen = outputTensor[0].size
            val hiddenSize = 384
            val meanVector = FloatArray(hiddenSize) { 0f }
            var sumMask = 0f

            for (i in 0 until seqLen) {
                val maskVal = attentionMask[i].toFloat()
                val tokenEmbedding = outputTensor[0][i]
                for (j in 0 until hiddenSize) {
                    meanVector[j] += tokenEmbedding[j] * maskVal
                }
                sumMask += maskVal
            }

            if (sumMask > 0f) {
                for (j in 0 until hiddenSize) {
                    meanVector[j] /= sumMask
                }
            }

            // L2 Normalize
            var normSum = 0f
            for (v in meanVector) {
                normSum += v * v
            }
            val norm = sqrt(normSum)
            if (norm > 0f) {
                for (j in 0 until hiddenSize) {
                    meanVector[j] /= norm
                }
            }

            return meanVector
        } catch (e: Exception) {
            Log.e(TAG, "Embedding generation failed for: '$text'", e)
            return null
        } finally {
            results?.close()
            inputIdsTensor?.close()
            attentionMaskTensor?.close()
            tokenTypeIdsTensor?.close()
        }
    }

    fun getEmbedding(text: String): FloatArray? {
        if (!isInitialized) return null
        return getEmbeddingDirect(text)
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
        }
        return dotProduct
    }

    fun match(text: String, threshold: Float = 0.72f): FieldMatch? {
        if (!isInitialized) return null
        
        val normalizedText = text.lowercase().trim()
        if (normalizedText.isEmpty()) return null
        
        val queryEmbedding = getEmbeddingDirect(normalizedText) ?: return null
        
        var bestField: CanonicalField? = null
        var maxSim = 0f

        for ((field, embeddings) in descriptorEmbeddings) {
            for (emb in embeddings) {
                val sim = cosineSimilarity(queryEmbedding, emb)
                if (sim > maxSim) {
                    maxSim = sim
                    bestField = field
                }
            }
        }

        if (bestField != null && maxSim >= threshold) {
            return FieldMatch(
                field = bestField,
                confidence = maxSim,
                matchedLayer = 5,
                matchedSignal = "semantic (sim: %.2f)".format(maxSim)
            )
        }
        
        return null
    }
}
