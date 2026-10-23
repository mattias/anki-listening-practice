package dev.lovkvist.listening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.TextStyle
import dev.lovkvist.listening.data.anki.AnkiText

/**
 * Renders Anki furigana (`漢字[かんじ]`) with the reading above its kanji.
 * Compose has no ruby layout, so each annotated word is a small column, and plain text is
 * split per character so lines can still wrap anywhere, as Japanese text does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyText(
    furigana: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    readingColor: Color = MaterialTheme.colorScheme.primary,
) {
    val units = remember(furigana) { layoutUnits(furigana) }
    val readingStyle = style.copy(fontSize = style.fontSize * READING_SCALE, color = readingColor)

    FlowRow(modifier, horizontalArrangement = Arrangement.Center) {
        for (unit in units) {
            if (unit.base == "\n") {
                Spacer(Modifier.fillMaxWidth())
                continue
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Unannotated text still gets an empty reading line so baselines stay aligned.
                Text(unit.reading.orEmpty(), style = readingStyle, maxLines = 1, softWrap = false)
                Text(unit.base, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun layoutUnits(furigana: String): List<AnkiText.RubySegment> =
    AnkiText.ruby(furigana).flatMap { seg ->
        if (seg.reading != null) {
            listOf(seg)
        } else {
            // Code points, not chars, so characters outside the BMP aren't split in half.
            seg.base.codePoints().toArray().map { AnkiText.RubySegment(String(Character.toChars(it)), null) }
        }
    }

private const val READING_SCALE = 0.5f
