package app.sada.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordFileTest {
    private val vocab = setOf("▁HE", "Y", "▁SA", "DA", "▁CON", "TIN", "UE", "▁SARA", "H")
    private val good = listOf(
        "▁HE Y ▁SA DA :2.0 #0.25 @HEY_SADA",
        "▁HE Y ▁SARA H :1.5 #0.30 @HEY_SADA_ALT",
        "▁CON TIN UE :1.5 #0.25 @CONTINUE",
    )

    @Test fun parsesTokensOptionsAndLabel() {
        val e = KeywordFile.parseLine(good[0])!!
        assertEquals(listOf("▁HE", "Y", "▁SA", "DA"), e.tokens)
        assertEquals(2.0, e.score!!, 0.0)
        assertEquals(0.25, e.threshold!!, 0.0)
        assertEquals("HEY_SADA", e.label)
    }

    @Test fun validFileHasNoIssues() {
        assertEquals(emptyList<String>(), KeywordFile.validate(good, vocab, listOf("HEY_SADA", "CONTINUE")))
    }

    @Test fun catchesOptionsTurnedIntoTokens() {
        val bad = listOf("▁HE Y ▁: 2 . 0 ▁# 0 . 25 @HEY_SADA", good[2])
        val issues = KeywordFile.validate(bad, vocab + setOf("▁:", "2", ".", "0", "▁#", "25"), listOf("HEY_SADA", "CONTINUE"))
        assertTrue(issues.toString(), issues.any { it.contains("options were turned into tokens") })
    }

    @Test fun catchesUnknownTokensMissingLabelAndMissingKeyword() {
        val issues = KeywordFile.validate(listOf("▁HE Y ▁SADDA :2.0 #0.25"), vocab, listOf("HEY_SADA", "CONTINUE"))
        assertTrue(issues.toString(), issues.any { it.contains("not in the model vocabulary: ▁SADDA") })
        assertTrue(issues.toString(), issues.any { it.contains("no @label") })
        assertTrue(issues.toString(), issues.any { it.contains("CONTINUE is missing") })
    }

    @Test fun catchesBadThreshold() {
        val issues = KeywordFile.validate(listOf("▁CON TIN UE :1.5 #1.5 @CONTINUE"), vocab, listOf("CONTINUE"))
        assertTrue(issues.toString(), issues.any { it.contains("outside 0..1") })
    }

    @Test fun vocabularyFromTokensTxt() {
        assertEquals(setOf("<blk>", "▁HE", "Y"), KeywordFile.vocabulary(listOf("<blk> 0", "▁HE 1", "Y 2", "")))
    }

    @Test fun nearMissThresholdRewrite() {
        val out = KeywordFile.withThreshold(listOf(good[0], "▁CON TIN UE :1.5 @CONTINUE"), 0.08)
        assertEquals("▁HE Y ▁SA DA :2.0 #0.08 @HEY_SADA", out[0])
        assertEquals("▁CON TIN UE :1.5 #0.08 @CONTINUE", out[1])
    }
}
