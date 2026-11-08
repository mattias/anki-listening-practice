package dev.lovkvist.listening.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPhenomenaTest {
    private val tokenizer = JapaneseTokenizer()

    private fun found(sentence: String): Set<Phenomenon> = SoundPhenomena.phenomena(tokenizer.morphs(sentence))

    @Test
    fun iSoundBeforeWo() {
        assertTrue(Phenomenon.I_WO in found("薬を飲んだ。"))
        assertTrue(Phenomenon.I_WO in found("息を吸った。"))
        // 水 ends in ず, a 本 in ん: not this one.
        assertFalse(Phenomenon.I_WO in found("水を飲んだ。"))
        assertFalse(Phenomenon.I_WO in found("本を読んだ。"))
        assertEquals("薬を", SoundPhenomena.find(tokenizer.morphs("薬を飲んだ。")).first { it.phenomenon == Phenomenon.I_WO }.text)
    }

    @Test
    fun nBeforeParticle() {
        assertTrue(Phenomenon.N_PARTICLE in found("本を読んだ。"))
        assertTrue(Phenomenon.N_PARTICLE in found("パンは好きです。"))
        assertFalse(Phenomenon.N_PARTICLE in found("薬を飲んだ。"))
    }

    @Test
    fun contractionsWithWa() {
        assertTrue(Phenomenon.CONTRACTED_WA in found("これは本です。"))
        assertTrue(Phenomenon.CONTRACTED_WA in found("それは違います。"))
        assertTrue(Phenomenon.CONTRACTED_WA in found("食べては駄目です。"))
        assertTrue(Phenomenon.CONTRACTED_WA in found("ここでは静かにして。"))
        // A は after an ordinary noun does not contract.
        assertFalse(Phenomenon.CONTRACTED_WA in found("私は学生です。"))
    }

    @Test
    fun teWithHelperVerbs() {
        assertTrue(Phenomenon.TE_AUX in found("今、食べている。"))
        assertTrue(Phenomenon.TE_AUX in found("先に買っておく。"))
        assertTrue(Phenomenon.TE_AUX in found("全部飲んでしまった。"))
        assertFalse(Phenomenon.TE_AUX in found("食べた。"))
    }

    @Test
    fun noDaToiuAndNakereba() {
        assertTrue(Phenomenon.NO_DA in found("行くのです。"))
        assertTrue(Phenomenon.NO_DA in found("遅れたので、走った。"))
        assertTrue(Phenomenon.TOIU in found("来ないという話だ。"))
        assertTrue(Phenomenon.NAKEREBA in found("早く行かなければならない。"))
        assertTrue(Phenomenon.NAKEREBA in found("急がなくてはいけない。"))
    }

    @Test
    fun devoicing() {
        assertTrue(Phenomenon.DEVOICE_FINAL in found("これは本です。"))
        assertTrue(Phenomenon.DEVOICE_FINAL in found("明日行きますね。"))
        assertFalse(Phenomenon.DEVOICE_FINAL in found("ですから行く。"))
        // き before た is devoiced; し before the particle は (said わ) is not.
        assertTrue(Phenomenon.DEVOICE_MID in found("北へ行く。"))
        assertFalse(Phenomenon.DEVOICE_MID in found("私は笑う。"))
    }

    @Test
    fun nothingInAPlainSentence() {
        assertEquals(emptySet<Phenomenon>(), found("犬が走る。").minus(setOf(Phenomenon.NASAL_G)))
    }
}
