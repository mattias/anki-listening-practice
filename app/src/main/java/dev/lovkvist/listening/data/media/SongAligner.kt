package dev.lovkvist.listening.data.media

import kotlin.math.abs

/**
 * Finds how far a song's lyrics are off against its audio by comparing them with captions that were
 * made from the audio itself (YouTube's automatic ones: rough for singing, but with the right times).
 * Each lyric line is compared with each caption; a pair that is alike says "this line is sung when
 * that caption starts", and the true shift is the one most pairs agree on. A chorus that repeats gives
 * pairs at other shifts too, but only the true one gets a vote from every line.
 */
object SongAligner {
    /** How alike two texts must be (0 to 1) to count as the same line. */
    private const val MIN_SIMILARITY = 0.45

    /** Votes within this distance of each other agree on one shift. */
    private const val AGREE_MS = 1_500L

    /** Shifts further than this aren't believed. */
    private const val MAX_SHIFT_MS = 120_000L

    /** Lines with fewer letters than this say too little to match. */
    private const val MIN_LETTERS = 4

    private const val MIN_VOTE_WEIGHT = 2.0

    /** The shift the agreeing votes must have of all votes, so scattered matches don't count. */
    private const val MIN_SHARE = 0.4

    /**
     * The time in ms to add to the [lyrics]' times so they match the audio the [captions] come from,
     * or null if too few lines match to tell.
     */
    fun offsetMs(lyrics: List<SubtitleCue>, captions: List<SubtitleCue>): Long? {
        val captionGrams = captions.map { it to bigrams(it.text) }.filter { it.second.isNotEmpty() }
        val votes = ArrayList<Pair<Long, Double>>() // shift, weight
        for (line in lyrics) {
            if (letters(line.text).length < MIN_LETTERS) continue
            val grams = bigrams(line.text)
            if (grams.isEmpty()) continue
            for ((caption, captionGram) in captionGrams) {
                val similarity = dice(grams, captionGram)
                if (similarity < MIN_SIMILARITY) continue
                val shift = caption.startMs - line.startMs
                if (abs(shift) <= MAX_SHIFT_MS) votes += shift to similarity
            }
        }
        if (votes.isEmpty()) return null
        val total = votes.sumOf { it.second }
        // The shift with the most weight within AGREE_MS of it.
        val peak = votes.maxByOrNull { (shift, _) -> votes.sumOf { if (abs(it.first - shift) <= AGREE_MS) it.second else 0.0 } }!!.first
        val agreeing = votes.filter { abs(it.first - peak) <= AGREE_MS }
        val weight = agreeing.sumOf { it.second }
        if (weight < MIN_VOTE_WEIGHT || weight < total * MIN_SHARE) return null
        return weightedMedian(agreeing).let { Math.round(it / 100.0) * 100 }
    }

    /**
     * For captions in any other language (a translation, or speech recognition that found nothing to
     * compare): when the first caption starts is when the first line is sung. The shift is the
     * difference between the first starts, trusted if the last starts differ by about the same, or if
     * lines and captions start together at that shift often enough.
     */
    fun offsetByTiming(lyrics: List<SubtitleCue>, captions: List<SubtitleCue>): Long? {
        val spoken = captions.filter { letters(it.text.replace(NOT_SPOKEN, " ")).isNotEmpty() }.sortedBy { it.startMs }
        val lines = lyrics.sortedBy { it.startMs }
        if (spoken.size < 3 || lines.size < 3) return null
        val shift = spoken.first().startMs - lines.first().startMs
        if (abs(shift) > MAX_SHIFT_MS) return null
        val lastShift = spoken.last().startMs - lines.last().startMs
        val endsAgree = abs(lastShift - shift) <= END_AGREE_MS
        val together = lines.count { line -> spoken.any { abs(it.startMs - (line.startMs + shift)) <= START_NEAR_MS } }
        val share = together.toDouble() / minOf(lines.size, spoken.size)
        if (!endsAgree && share < MIN_START_SHARE) return null
        return Math.round(shift / 100.0) * 100
    }

    /** Sound labels such as [Music] or ♪ that say nothing about when singing starts. */
    private val NOT_SPOKEN = Regex("""[\[(（【][^\])）】]*[\])）】]|[♪♫♬]""")
    private const val END_AGREE_MS = 8_000L
    private const val START_NEAR_MS = 2_500L
    private const val MIN_START_SHARE = 0.5

    private fun weightedMedian(votes: List<Pair<Long, Double>>): Long {
        val sorted = votes.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var seen = 0.0
        for ((shift, weight) in sorted) {
            seen += weight
            if (seen >= half) return shift
        }
        return sorted.last().first
    }

    private fun letters(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    /** Pairs of neighboring letters: what two spellings of a line have in common, in any language. */
    private fun bigrams(text: String): Set<String> {
        val s = letters(text)
        return if (s.length < 2) emptySet() else (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return 2.0 * common / (a.size + b.size)
    }
}
