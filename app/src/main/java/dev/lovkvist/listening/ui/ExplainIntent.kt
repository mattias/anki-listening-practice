package dev.lovkvist.listening.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import dev.lovkvist.listening.data.Sentence
import dev.lovkvist.listening.l10n.AppLanguage
import dev.lovkvist.listening.l10n.displayName
import dev.lovkvist.listening.text.Language
import java.util.Locale

private const val GEMINI_PACKAGE = "com.google.android.apps.bard"

/**
 * The question sent to an AI assistant: the sentence, what language it is in, and what to explain.
 * It is written in English, which gives the steadiest results, and asks for the answer in
 * [answerIn], the app's language.
 */
fun explainPrompt(sentence: Sentence, language: Language, answerIn: String): String = buildString {
    appendLine("Explain this ${language.displayName(Locale.ENGLISH)} sentence to a learner. Answer entirely in $answerIn.")
    appendLine()
    appendLine(sentence.text)
    if (sentence.furigana.isNotBlank() && sentence.furigana != sentence.text) {
        appendLine("(with readings: ${sentence.furigana})")
    }
    appendLine()
    appendLine("1. Give a natural translation of the whole sentence.")
    appendLine("2. Break it down chunk by chunk: for each chunk, the reading (if the language has one), its meaning and the role it plays.")
    appendLine("3. Explain every piece of grammar in it, broken down: the form, how it is built, and when it is used. Give one short example for each.")
    append("Keep it clear and compact.")
}

/**
 * Opens the Gemini app with the question ready (send it from there), or the share menu if
 * Gemini isn't installed, so another AI app can be picked.
 */
fun explainInGemini(context: Context, sentence: Sentence, language: Language) {
    val answerIn = AppLanguage.locale().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, explainPrompt(sentence, language, answerIn))
    }
    try {
        context.startActivity(Intent(send).setPackage(GEMINI_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
