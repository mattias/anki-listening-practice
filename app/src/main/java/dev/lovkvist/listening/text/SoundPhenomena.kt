package dev.lovkvist.listening.text

/**
 * One morpheme of a Japanese sentence: how it is written, how it is read (hiragana), its part of
 * speech (level 1 and 2, as in IPADIC: 助詞 / 格助詞) and dictionary form.
 */
data class Morph(val surface: String, val reading: String, val pos: String, val pos2: String, val base: String)

/**
 * Ways spoken Japanese runs together or changes, so that what is heard differs from the written form.
 * Each has an [example] of the written form and what is heard. Detecting one in a sentence says where
 * it can happen: how much a speaker really runs the sounds together varies.
 */
enum class Phenomenon(val id: String, val example: String) {
    /** A word ending in an い-row sound before を: い, き, し, ち, に, ひ, み, り and their voiced forms. */
    I_WO("i_wo", "薬を → くすりょ"),

    /** ん before を, は, に or が runs into the particle's vowel. */
    N_PARTICLE("n_particle", "本を → ほんの"),

    /** これ/それ/あれ/どれ, て, で or に before は contract. */
    CONTRACTED_WA("wa", "これは → こりゃ"),

    /** て/で before いる, いく, おく, しまう. */
    TE_AUX("te_aux", "食べている → 食べてる"),

    /** のだ, のです, ので. */
    NO_DA("no_da", "のだ → んだ"),

    /** なければ, なくては. */
    NAKEREBA("nakereba", "なければ → なきゃ"),

    /** という. */
    TOIU("toiu", "という → って"),

    /** The す of です/ます at the end of a sentence is hardly voiced. */
    DEVOICE_FINAL("devoice_final", "です → des"),

    /** き く し す ち つ ひ ふ before a voiceless consonant lose their vowel's voice. */
    DEVOICE_MID("devoice_mid", "しました → shmashta"),

    /** が inside a sentence is often nasal, [ŋ]. */
    NASAL_G("nasal_g", "学校が → がっこうŋa"),

    /** Words that are often said shorter. */
    SHORTENED("shortened", "本当 → ほんと"),
}

/** Where a [phenomenon] was found: the [text] it covers. */
data class PhenomenonMatch(val phenomenon: Phenomenon, val text: String)

object SoundPhenomena {
    private val I_ROW = setOf('い', 'き', 'し', 'ち', 'に', 'ひ', 'み', 'り', 'ぎ', 'じ', 'ぢ', 'び', 'ぴ')
    private val WA_CONTRACTING_WORDS = setOf("これ", "それ", "あれ", "どれ")
    private val WA_CONTRACTING_PARTICLES = setOf("て", "で", "に")
    private val TE_HELPERS = setOf("いる", "いく", "おく", "しまう")
    private val DEVOICING = setOf('き', 'く', 'し', 'す', 'ち', 'つ', 'ひ', 'ふ')
    private val VOICELESS_START = "かきくけこさしすせそたちつてとはひふへほぱぴぷぺぽ".toSet()
    private val NASAL_G_KANA = setOf('が', 'ぎ', 'ぐ', 'げ', 'ご')
    private val FINAL_FILLERS = setOf("ね", "よ", "か", "な", "わ", "ぞ", "さ")
    // Words that start with ほんとう (ほんとうに) and the like, and words that count only as themselves (ところ, not ところで).
    private val SHORTENABLE_PREFIXES = listOf("ほんとう", "ありがとう", "だいじょうぶ")
    private val SHORTENABLE_WORDS = setOf("ところ")

    /** Every phenomenon [morphs] can show, in sentence order. */
    fun find(morphs: List<Morph>): List<PhenomenonMatch> {
        val found = ArrayList<PhenomenonMatch>()
        fun add(p: Phenomenon, vararg parts: Morph) = found.add(PhenomenonMatch(p, parts.joinToString("") { it.surface }))

        for (i in morphs.indices) {
            val m = morphs[i]
            val prev = morphs.getOrNull(i - 1)
            val next = morphs.getOrNull(i + 1)
            if (prev != null && prev.pos != "助詞" && prev.pos != "記号") {
                // A word ending in an い-row sound before を.
                if (isParticle(m, "を") && prev.reading.lastOrNull() in I_ROW) add(Phenomenon.I_WO, prev, m)
                if (isParticle(m, "を") || isParticle(m, "は") || isParticle(m, "に") || isParticle(m, "が")) {
                    if (prev.reading.lastOrNull() == 'ん') add(Phenomenon.N_PARTICLE, prev, m)
                }
            }
            if (prev != null && isParticle(m, "は") &&
                (prev.reading in WA_CONTRACTING_WORDS || (prev.pos == "助詞" && prev.surface in WA_CONTRACTING_PARTICLES))
            ) {
                add(Phenomenon.CONTRACTED_WA, prev, m)
            }
            if (prev != null && next != null && isParticle(m, "て", "で") && next.base in TE_HELPERS) {
                // 食べている: the verb before the て is part of what is heard.
                add(Phenomenon.TE_AUX, prev, m, next)
            }
            if (next != null && m.surface == "の" && m.pos == "名詞" && next.surface in setOf("だ", "です", "で", "だった", "でした")) {
                add(Phenomenon.NO_DA, m, next)
            }
            if (m.surface == "ので" && m.pos == "助詞") add(Phenomenon.NO_DA, m)
            // IPADIC has という as one word.
            if (isParticle(m, "という") || (next != null && isParticle(m, "と") && next.base == "いう")) {
                add(Phenomenon.TOIU, m)
            }
            if (m.reading in SHORTENABLE_WORDS || SHORTENABLE_PREFIXES.any { m.reading.startsWith(it) }) add(Phenomenon.SHORTENED, m)
            if (isParticle(m, "が") && prev != null && prev.pos != "助詞") {
                add(Phenomenon.NASAL_G, prev, m)
            } else if (m.reading.drop(1).any { it in NASAL_G_KANA } && m.pos != "助詞") {
                add(Phenomenon.NASAL_G, m)
            }
        }

        val text = morphs.joinToString("") { it.surface }
        for (hit in Regex("なければ|なくては").findAll(text)) found.add(PhenomenonMatch(Phenomenon.NAKEREBA, hit.value))

        // です/ます at the end, with only a sentence-final particle or punctuation after it.
        val tail = morphs.dropLastWhile { it.pos == "記号" || (it.pos == "助詞" && it.surface in FINAL_FILLERS) }
        tail.lastOrNull()?.let { last ->
            if (last.surface == "です" || last.surface == "ます") found.add(PhenomenonMatch(Phenomenon.DEVOICE_FINAL, last.surface))
        }

        // A voiceless vowel inside the reading: き く し す ち つ ひ ふ before か-row, さ-row, た-row, は-row or ぱ-row.
        val reading = morphs.joinToString("") { it.reading }
        for (i in 0 until reading.length - 1) {
            if (reading[i] in DEVOICING && reading[i + 1] in VOICELESS_START) {
                found.add(PhenomenonMatch(Phenomenon.DEVOICE_MID, reading.substring(i, i + 2)))
                break
            }
        }
        return found
    }

    /** Which phenomena [morphs] show. */
    fun phenomena(morphs: List<Morph>): Set<Phenomenon> = find(morphs).mapTo(LinkedHashSet()) { it.phenomenon }

    private fun isParticle(m: Morph, vararg surfaces: String) = m.pos == "助詞" && m.surface in surfaces
}
