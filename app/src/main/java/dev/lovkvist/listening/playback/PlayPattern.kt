package dev.lovkvist.listening.playback

/** [plays] plays of a sentence at [percent] % of the normal speed (50 to 150). */
data class PlayStep(val plays: Int, val percent: Int) {
    val speed: Float get() = percent / 100f
}

/**
 * A pattern of plays for each sentence: for example 3 plays at 0.85×, then 3 at 0.90×, then 5 at 1.00×. When the last
 * play is done the sentence moves on (Crystal Clear or skip, as chosen). Speeds are whole percents, so they never
 * drift the way repeated float arithmetic would.
 */
data class PlayPattern(val steps: List<PlayStep>) {
    val totalPlays: Int get() = steps.sumOf { it.plays }

    /** The speed for the play with this index (0 = the first play of a sentence); the last step's speed after the end. */
    fun speedFor(playIndex: Int): Float {
        var left = playIndex.coerceAtLeast(0)
        for (step in steps) {
            if (left < step.plays) return step.speed
            left -= step.plays
        }
        return steps.lastOrNull()?.speed ?: 1f
    }

    /** A form that can be stored: "3x85,3x90,5x100". */
    fun format(): String = steps.joinToString(",") { "${it.plays}x${it.percent}" }

    fun withStep(index: Int, step: PlayStep) = PlayPattern(steps.mapIndexed { i, s -> if (i == index) step.clamped() else s })

    fun withoutStep(index: Int) = if (steps.size <= 1) this else PlayPattern(steps.filterIndexed { i, _ -> i != index })

    /** One more step, like the last one but a little faster (or a little slower if that one is already the fastest). */
    fun withNewStep(): PlayPattern {
        if (steps.size >= MAX_STEPS) return this
        val last = steps.lastOrNull() ?: return DEFAULT
        val percent = if (last.percent + STEP_PERCENT <= MAX_PERCENT) last.percent + STEP_PERCENT else last.percent
        return PlayPattern(steps + PlayStep(last.plays, percent))
    }

    companion object {
        const val MAX_STEPS = 6
        const val MAX_PLAYS = 20
        const val MIN_PERCENT = 50
        const val MAX_PERCENT = 150
        const val STEP_PERCENT = 5

        val DEFAULT = PlayPattern(listOf(PlayStep(3, 85), PlayStep(3, 90), PlayStep(5, 100)))

        /** The pattern in [text] (see [format]); null when it is empty or not a pattern. Out-of-range numbers are clamped. */
        fun parse(text: String?): PlayPattern? {
            if (text.isNullOrBlank()) return null
            val steps = text.split(',').take(MAX_STEPS).map { part ->
                val (plays, percent) = part.trim().split('x').takeIf { it.size == 2 }?.map { it.trim().toIntOrNull() ?: return null } ?: return null
                PlayStep(plays, percent).clamped()
            }
            return steps.takeIf { it.isNotEmpty() }?.let(::PlayPattern)
        }

        private fun PlayStep.clamped() = PlayStep(plays.coerceIn(1, MAX_PLAYS), percent.coerceIn(MIN_PERCENT, MAX_PERCENT))
    }
}
