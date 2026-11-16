package dev.lovkvist.listening.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayPatternTest {
    private val pattern = PlayPattern.DEFAULT // 3 x 0.85, 3 x 0.90, 5 x 1.00

    @Test fun totalsAndSpeedsPerPlay() {
        assertEquals(11, pattern.totalPlays)
        val speeds = (0..10).map { pattern.speedFor(it) }
        assertEquals(listOf(0.85f, 0.85f, 0.85f, 0.90f, 0.90f, 0.90f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f), speeds)
    }

    @Test fun staysOnTheLastSpeedAfterTheEnd() {
        assertEquals(1.0f, pattern.speedFor(11), 0f)
        assertEquals(1.0f, pattern.speedFor(500), 0f)
        assertEquals(0.85f, pattern.speedFor(-3), 0f)
    }

    @Test fun formatsAndParsesBack() {
        assertEquals("3x85,3x90,5x100", pattern.format())
        assertEquals(pattern, PlayPattern.parse("3x85,3x90,5x100"))
        assertEquals(pattern, PlayPattern.parse(" 3x85 , 3x90 , 5x100 "))
    }

    @Test fun rejectsWhatIsNotAPattern() {
        assertNull(PlayPattern.parse(null))
        assertNull(PlayPattern.parse(""))
        assertNull(PlayPattern.parse("abc"))
        assertNull(PlayPattern.parse("3x85,oops"))
        assertNull(PlayPattern.parse("3-85"))
    }

    @Test fun clampsOutOfRangeNumbers() {
        val p = PlayPattern.parse("0x10,99x500")!!
        assertEquals(listOf(PlayStep(1, 50), PlayStep(20, 150)), p.steps)
    }

    @Test fun editing() {
        assertEquals(PlayStep(4, 85), pattern.withStep(0, PlayStep(4, 85)).steps[0])
        assertEquals(PlayStep(20, 150), pattern.withStep(1, PlayStep(99, 999)).steps[1])
        assertEquals(2, pattern.withoutStep(1).steps.size)
        assertEquals(1, PlayPattern(listOf(PlayStep(2, 100))).withoutStep(0).steps.size)
        assertEquals(PlayStep(5, 105), pattern.withNewStep().steps.last())
        // The fastest speed stays; the number of steps is limited.
        assertEquals(PlayStep(2, 150), PlayPattern(listOf(PlayStep(2, 150))).withNewStep().steps.last())
        var p = pattern
        repeat(10) { p = p.withNewStep() }
        assertEquals(PlayPattern.MAX_STEPS, p.steps.size)
    }
}
