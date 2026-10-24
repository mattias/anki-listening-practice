package dev.lovkvist.listening.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.json.JSONArray
import org.json.JSONObject

/** A ruby base and its reading; it sits in the text as inline content with id "ruby<index>". */
data class Ruby(val base: String, val reading: String)

/** Glossary text; ruby is inline content (see [Ruby]). */
class Glossary(val text: AnnotatedString, val rubies: List<Ruby>)

/**
 * Turns a Yomitan glossary (a JSON array of strings, {type: text} and {type: structured-content})
 * into styled text: block tags start new lines, list items get bullets or numbers, ruby becomes
 * inline content drawn by [GlossaryText], tag-like spans are tinted, and images are left out.
 */
fun glossary(json: String, tagColor: Color): Glossary {
    val rubies = mutableListOf<Ruby>()
    val text = buildAnnotatedString { render(json, GlossaryRenderer(this, tagColor, rubies)) }
    return Glossary(text, rubies)
}

/**
 * A glossary with real furigana: each ruby is a placeholder as wide as its base or reading,
 * with the reading drawn just above the base and the base on the line's baseline.
 */
@Composable
fun GlossaryText(json: String, style: TextStyle, tagColor: Color, readingColor: Color, modifier: Modifier = Modifier) {
    val g = remember(json, tagColor) { glossary(json, tagColor) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // A fixed line height would keep lines with ruby from growing, so the reading would overlap the line above.
    val baseStyle = LocalTextStyle.current.merge(style).let { if (g.rubies.isEmpty()) it else it.copy(lineHeight = TextUnit.Unspecified) }
    val (rubyBase, readingStyle) = rubyStyles(baseStyle, readingColor)
    val inline = remember(g, baseStyle, readingStyle, density) {
        g.rubies.withIndex().associate { (i, ruby) ->
            val base = measurer.measure(ruby.base, rubyBase, maxLines = 1, softWrap = false).size
            val reading = measurer.measure(ruby.reading, readingStyle, maxLines = 1, softWrap = false).size
            val width = with(density) { maxOf(base.width, reading.width).toSp() }
            val height = with(density) { (base.height + reading.height).toSp() }
            "ruby$i" to InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextBottom)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(ruby.reading, style = readingStyle, maxLines = 1, softWrap = false)
                    Text(ruby.base, style = rubyBase, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    Text(g.text, style = baseStyle, inlineContent = inline, modifier = modifier)
}

private fun render(json: String, renderer: GlossaryRenderer) {
    val items = runCatching { JSONArray(json) }.getOrNull() ?: return
    for (i in 0 until items.length()) {
        when (val item = items.get(i)) {
            is String -> renderer.block { renderer.put(item) }
            is JSONObject -> when (item.optString("type")) {
                "text" -> renderer.block { renderer.put(item.optString("text")) }
                "structured-content" -> renderer.block { renderer.node(item.opt("content")) }
            }
            // An inflected form pointing to its lemma: ["dricka", ["present active indicative"]].
            is JSONArray -> renderer.block {
                val forms = item.optJSONArray(1)?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
                renderer.put("→ ${item.optString(0)}" + if (forms.isNotEmpty()) " ($forms)" else "")
            }
        }
    }
}

/** Writes glossary nodes to [out]; ruby becomes inline content, collected in [rubies]. */
private class GlossaryRenderer(private val out: AnnotatedString.Builder, private val tagColor: Color, private val rubies: MutableList<Ruby>) {
    private var last = '\n'
    // Right after a list bullet, a block inside the item starts on the bullet's line.
    private var atBullet = false

    fun put(text: CharSequence) {
        if (text.isEmpty()) return
        out.append(text)
        last = text.last()
        atBullet = false
    }

    fun newline() {
        if (last != '\n' && !atBullet) put("\n")
    }

    fun block(body: () -> Unit) {
        newline()
        body()
        newline()
    }

    fun node(node: Any?) {
        when (node) {
            null, JSONObject.NULL -> {}
            is String -> put(node)
            is JSONArray -> for (i in 0 until node.length()) {
                // Dictionaries style sibling spans as separate boxes (On / カン, Rank 585 / Grade 4); keep them apart.
                if (i > 0 && isSpan(node.get(i - 1)) && isSpan(node.get(i)) && !last.isWhitespace()) put(" ")
                node(node.get(i))
            }
            is JSONObject -> element(node)
            else -> put(node.toString())
        }
    }

    private fun element(e: JSONObject) {
        val content = e.opt("content")
        when (e.optString("tag")) {
            "br" -> put("\n")
            "img", "image" -> {}
            "rt", "rp" -> {}
            "ruby" -> ruby(content)
            "ul" -> list(content, numbered = false)
            "ol" -> list(content, numbered = true)
            "summary" -> block { out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node(content) } }
            "div", "p", "li", "details", "table", "tr", "thead", "tbody" -> block { styled(e) { node(content) } }
            "td", "th" -> {
                styled(e) { node(content) }
                put("  ")
            }
            else -> styled(e) { node(content) }
        }
    }

    /** Ruby as inline content; ruby without a reading is just its base text. */
    private fun ruby(content: Any?) {
        val parts = (content as? JSONArray)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: listOf(content)
        val isRt = { p: Any? -> p is JSONObject && p.optString("tag") == "rt" }
        val isRp = { p: Any? -> p is JSONObject && p.optString("tag") == "rp" }
        val readings = parts.filter(isRt).joinToString("") { plain((it as JSONObject).opt("content")) }
        val bases = parts.filter { !isRt(it) && !isRp(it) }
        if (readings.isNotEmpty()) {
            val base = bases.joinToString("") { plain(it) }
            out.appendInlineContent("ruby${rubies.size}", base)
            rubies += Ruby(base, readings)
            last = base.lastOrNull() ?: last
            atBullet = false
            return
        }
        bases.forEach(::node)
    }

    private fun list(content: Any?, numbered: Boolean) {
        newline()
        val items = (content as? JSONArray)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: listOf(content)
        var n = 0
        for (item in items) {
            if (item is JSONObject && item.optString("tag") == "li") {
                newline()
                put(if (numbered) "${++n}. " else "• ")
                atBullet = true
                styled(item) { node(item.opt("content")) }
                newline()
            } else {
                node(item)
            }
        }
        newline()
    }

    /** Applies bold/italic from the element's style, and tints tag-like spans (part of speech, labels). */
    private fun styled(e: JSONObject, body: () -> Unit) {
        val style = e.optJSONObject("style")
        val data = e.optJSONObject("data")
        val tagLike = data?.optString("content").orEmpty().let { "tag" in it || "part-of-speech" in it || "misc" in it } ||
            style?.has("backgroundColor") == true
        var span = SpanStyle()
        if (style?.optString("fontWeight") == "bold") span = span.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if (style?.optString("fontStyle") == "italic") span = span.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (tagLike) span = span.merge(SpanStyle(color = tagColor, fontSize = 0.85.em))
        out.withStyle(span) { body() }
        if (tagLike) put(" ")
    }

    private fun isSpan(node: Any?) = node is JSONObject && node.optString("tag") == "span"

    private fun plain(node: Any?): String = when (node) {
        null, JSONObject.NULL -> ""
        is String -> node
        is JSONArray -> (0 until node.length()).joinToString("") { plain(node.get(it)) }
        is JSONObject -> plain(node.opt("content"))
        else -> node.toString()
    }
}

/**
 * A dictionary headword with its reading as furigana over the part that differs, so kana the two
 * share stay plain: 成る / なる → な over 成, then る.
 */
@Composable
fun Headword(expression: String, reading: String, style: TextStyle, readingColor: Color, modifier: Modifier = Modifier) {
    val (wordStyle, readingStyle) = rubyStyles(style, readingColor)
    if (reading.isEmpty() || reading == expression) {
        Text(expression, style = wordStyle, modifier = modifier)
        return
    }
    var prefix = expression.commonPrefixWith(reading).length
    var suffix = expression.drop(prefix).commonSuffixWith(reading.drop(prefix)).length
    // Nothing left over on one side: put the whole reading over the whole word.
    if (prefix + suffix >= expression.length || prefix + suffix >= reading.length) {
        prefix = 0
        suffix = 0
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        if (prefix > 0) Text(expression.take(prefix), style = wordStyle)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(reading.substring(prefix, reading.length - suffix), style = readingStyle, maxLines = 1, softWrap = false)
            Text(expression.substring(prefix, expression.length - suffix), style = wordStyle)
        }
        if (suffix > 0) Text(expression.takeLast(suffix), style = wordStyle)
    }
}

/**
 * Styles for a ruby base and its reading with line boxes about as tall as the glyphs, so the
 * reading sits right on top of the base instead of a line's spacing above it.
 */
internal fun rubyStyles(style: TextStyle, readingColor: Color): Pair<TextStyle, TextStyle> {
    val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val base = style.copy(lineHeight = style.fontSize * 1.1f, lineHeightStyle = tight)
    val reading = style.copy(
        fontSize = style.fontSize * READING_SCALE,
        lineHeight = style.fontSize * READING_SCALE * 1.1f,
        lineHeightStyle = tight,
        color = readingColor,
    )
    return base to reading
}
