package com.example.speedup

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
import com.example.speedup.engine.WordPieceTokenizer
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun tokenizer_isCorrect() {
        val vocabLines = listOf(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]",
            "first", "name", "given", "phone", "email",
            "##name", "##phone", "##ing", "look", "##s"
        )
        val tokenizer = WordPieceTokenizer.load(vocabLines)
        
        // Test basic tokenization of exact words
        val tokens1 = tokenizer.tokenize("first name")
        // CLS (2) + first (4) + name (5) + SEP (3)
        assertEquals(listOf(2, 4, 5, 3), tokens1)

        // Test tokenization of subwords
        val tokens2 = tokenizer.tokenize("looking")
        // CLS (2) + look (12) + ##ing (11) + SEP (3)
        assertEquals(listOf(2, 12, 11, 3), tokens2)

        // Test unknown words fallback to [UNK] (1)
        val tokens3 = tokenizer.tokenize("xyz")
        assertEquals(listOf(2, 1, 3), tokens3)
    }
}