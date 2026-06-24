package com.example.speedup.engine

class WordPieceTokenizer(private val vocab: Map<String, Int>) {
    companion object {
        const val UNK_TOKEN = "[UNK]"
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val PAD_TOKEN = "[PAD]"
        
        fun load(vocabLines: List<String>): WordPieceTokenizer {
            val vocabMap = HashMap<String, Int>(vocabLines.size)
            vocabLines.forEachIndexed { index, line ->
                vocabMap[line.trim()] = index
            }
            return WordPieceTokenizer(vocabMap)
        }
    }

    private val unkId = vocab[UNK_TOKEN] ?: 100
    private val clsId = vocab[CLS_TOKEN] ?: 101
    private val sepId = vocab[SEP_TOKEN] ?: 102

    fun tokenize(text: String): List<Int> {
        val cleanText = text.lowercase().trim()
        if (cleanText.isEmpty()) {
            return listOf(clsId, sepId)
        }
        
        // Basic punctuation splitting: add spaces around punctuation to ensure they are tokenized correctly
        val spacedText = cleanText.replace(Regex("([!\"\\#\\$%&'\\(\\)\\*\\+,\\-\\./:;<=>\\?@\\[\\\\\\]\\^_`\\{\\|\\}\\~])"), " $1 ")
        val words = spacedText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        
        val tokenIds = mutableListOf<Int>()
        tokenIds.add(clsId)

        for (word in words) {
            val subTokens = tokenizeWord(word)
            for (subToken in subTokens) {
                tokenIds.add(vocab[subToken] ?: unkId)
            }
        }
        
        tokenIds.add(sepId)
        return tokenIds
    }

    private fun tokenizeWord(word: String): List<String> {
        if (vocab.containsKey(word)) {
            return listOf(word)
        }
        
        val result = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var curSubword = ""
            while (start < end) {
                var substr = word.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                if (vocab.containsKey(substr)) {
                    curSubword = substr
                    break
                }
                end--
            }
            if (curSubword.isEmpty()) {
                return listOf(UNK_TOKEN)
            }
            result.add(curSubword)
            start = end
        }
        return result
    }
}
