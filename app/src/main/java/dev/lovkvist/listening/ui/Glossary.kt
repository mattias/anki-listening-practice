package dev.lovkvist.listening.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a Yomitan glossary (a JSON array of strings, {type: text} and {type: structured-content})
 * into styled text: block tags start new lines, list items get bullets or numbers, ruby
 * readings go in parentheses, tag-like spans are tinted, and images are left out.
 */
fun glossaryText(json: String, tagColor: Color): AnnotatedString = buildAnnotatedString {
    val items = runCatching { JSONArray(json) }.getOrNull() ?: return@buildAnnotatedString
    val renderer = GlossaryRenderer(this, tagColor)
    for (i in 0 until items.length()) {
        when (val item = items.get(i)) {
            is String -> renderer.block { renderer.put(item) }
            is JSONObject -> when (item.optString("type")) {
                "text" -> renderer.block { renderer.put(item.optString("text")) }
                "structured-content" -> renderer.block { renderer.node(item.opt("content")) }
            }
            // Arrays are deinflection hints (format 3), not definitions.
        }
    }
}

private class GlossaryRenderer(private val out: AnnotatedString.Builder, private val tagColor: Color) {
    private var last = '\n'

    fun put(text: CharSequence) {
        if (text.isEmpty()) return
        out.append(text)
        last = text.last()
    }

    fun newline() {
        if (last != '\n') put("\n")
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

    /** Base text, then its readings in parentheses: 漢字(かんじ). */
    private fun ruby(content: Any?) {
        val parts = (content as? JSONArray)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: listOf(content)
        val readings = StringBuilder()
        for (p in parts) {
            if (p is JSONObject && p.optString("tag") == "rt") readings.append(plain(p.opt("content")))
            else if (!(p is JSONObject && p.optString("tag") == "rp")) node(p)
        }
        if (readings.isNotEmpty()) out.withStyle(SpanStyle(fontSize = 0.8.em, color = tagColor)) { put("($readings)") }
    }

    private fun list(content: Any?, numbered: Boolean) {
        newline()
        val items = (content as? JSONArray)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: listOf(content)
        var n = 0
        for (item in items) {
            if (item is JSONObject && item.optString("tag") == "li") {
                newline()
                put(if (numbered) "${++n}. " else "• ")
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
