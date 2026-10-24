package dev.lovkvist.listening.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.lovkvist.listening.data.anki.AnkiText

/**
 * Renders Anki furigana (`漢字[かんじ]`) with the reading above its kanji.
 * Compose has no ruby layout, so each annotated word is a small column, and plain text is
 * split per character so lines can still wrap anywhere, as Japanese text does.
 * [highlights] are character ranges in the base text (the text without readings) to mark,
 * reading included, e.g. unknown words. [onTap] gets the position of a tapped character.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyText(
    furigana: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    readingColor: Color = MaterialTheme.colorScheme.primary,
    highlights: List<IntRange> = emptyList(),
    highlightColor: Color = MaterialTheme.colorScheme.tertiary,
    onTap: ((Int) -> Unit)? = null,
) {
    val groups = remember(furigana) { keepTogether(layoutUnits(furigana)) }
    // Line boxes about as tall as the glyphs: the reading right on its kanji, and lines close together.
    val (baseStyle, readingStyle) = rubyStyles(style, readingColor)
    val markedStyle = baseStyle.copy(color = highlightColor, textDecoration = TextDecoration.Underline)
    val markedReadingStyle = readingStyle.copy(color = highlightColor)

    FlowRow(modifier, horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (group in groups) {
            if (group.first().segment.base == "\n") {
                Spacer(Modifier.fillMaxWidth())
                continue
            }
            // Units line up on the base text's baseline, so a character from a fallback font (?, !) sits
            // on the same line as the rest.
            Row(Modifier.alignBy(LastBaseline)) {
                for (unit in group) {
                    val end = unit.start + unit.segment.base.length - 1
                    val marked = highlights.any { it.first <= end && unit.start <= it.last }
                    val tap = if (onTap != null) Modifier.clickable { onTap(unit.start) } else Modifier
                    Column(tap.alignBy(LastBaseline), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Unannotated text still gets an empty reading line so baselines stay aligned.
                        Text(
                            unit.segment.reading.orEmpty(),
                            style = if (marked) markedReadingStyle else readingStyle,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.trimVertical(0.15f),
                        )
                        Text(unit.segment.base, style = if (marked) markedStyle else baseStyle, maxLines = 1, softWrap = false, modifier = Modifier.trimVertical(0.12f))
                    }
                }
            }
        }
    }
}

/**
 * Takes [fraction] of the height off the top and off the bottom: the empty space fonts keep above
 * and below their glyphs, so readings sit on their kanji and lines stay close. Nothing is clipped.
 */
internal fun Modifier.trimVertical(fraction: Float) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val trim = (p.height * fraction).toInt()
    layout(p.width, p.height - 2 * trim) { p.place(0, -trim) }
}

/** A piece of ruby text and where its base text starts in the whole base text. */
private data class RubyUnit(val segment: AnkiText.RubySegment, val start: Int)

private fun layoutUnits(furigana: String): List<RubyUnit> {
    var offset = 0
    return AnkiText.ruby(furigana).flatMap { seg ->
        if (seg.reading != null) {
            listOf(seg)
        } else {
            // Code points, not chars, so characters outside the BMP aren't split in half.
            seg.base.codePoints().toArray().map { AnkiText.RubySegment(String(Character.toChars(it)), null) }
        }
    }.map { seg -> RubyUnit(seg, offset).also { offset += seg.base.length } }
}

/**
 * Groups units that must not be split by a line break: punctuation like 、。」 never starts a
 * line, and opening brackets like 「 never end one (Japanese kinsoku rules).
 */
private fun keepTogether(units: List<RubyUnit>): List<List<RubyUnit>> {
    val groups = mutableListOf<MutableList<RubyUnit>>()
    var openBracket = false
    for (unit in units) {
        val seg = unit.segment
        val previous = groups.lastOrNull()
        val glue = seg.reading == null && seg.base.isNotEmpty() && seg.base[0] in NO_LINE_START
        if (previous != null && seg.base != "\n" && previous.first().segment.base != "\n" && (glue || openBracket)) {
            previous += unit
        } else {
            groups += mutableListOf(unit)
        }
        openBracket = seg.reading == null && seg.base.isNotEmpty() && seg.base[0] in NO_LINE_END
    }
    return groups
}

private const val NO_LINE_START = "、。，．,.!?！？)）]］}｝」』】〉》〕ー・：；:;～…ゃゅょっぁぃぅぇぉャュョッァィゥェォ"
private const val NO_LINE_END = "(（[［{｛「『【〈《〔"

internal const val READING_SCALE = 0.5f
