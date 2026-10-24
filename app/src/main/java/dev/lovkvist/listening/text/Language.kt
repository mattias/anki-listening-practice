package dev.lovkvist.listening.text

/** How a language's sentence readings are written above the text, if it has any. */
enum class Reading(val label: String) {
    FURIGANA("Furigana"),
    PINYIN("Pinyin"),
    JYUTPING("Jyutping"),
    TAILO("Tâi-lô"),
}

/**
 * The study languages: Anki Miner's set and codes, plus Tagalog and Taiwanese, which Anki Miner
 * doesn't mine.
 */
enum class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    /** YouTube subtitle codes in order of preference. */
    val captionCodes: List<String>,
    /** Legacy subtitle encoding to try when a file isn't UTF-8. */
    val legacyCharset: String,
    /** Sentence readings shown above the text; null if the language doesn't use any. */
    val reading: Reading? = null,
    /**
     * Values of Anki Miner Note's Language field that mean this language (prefix match on "-"),
     * used to tell the language of decks imported before decks had one.
     */
    val noteTags: List<String> = listOf(code),
    /** Written without spaces between words, so words are found by dictionary segmentation. */
    val unspaced: Boolean = false,
    /** Combining marks to ignore when matching words, like Arabic vowel marks or Russian stress. */
    val ignoredMarks: Regex? = null,
    /** Anki Miner mines this language, so its word lists and note tags apply. */
    val inAnkiMiner: Boolean = true,
    /** Locale for drawing text: picks the right Han glyph variants (Japanese, Taiwanese, Hong Kong). */
    val textLocale: String = code,
) {
    JAPANESE("ja", "Japanese", "日本語", listOf("ja"), "Shift_JIS", reading = Reading.FURIGANA, unspaced = true),
    KOREAN("ko", "Korean", "한국어", listOf("ko"), "EUC-KR"),
    CHINESE(
        "zh", "Chinese (Mandarin)", "中文", listOf("zh-Hans", "zh-CN", "zh-Hant", "zh-TW", "zh"), "GB18030",
        reading = Reading.PINYIN, unspaced = true,
    ),
    ENGLISH("en", "English", "English", listOf("en", "en-US", "en-GB", "en-CA", "en-AU"), "windows-1252"),
    CATALAN("ca", "Catalan", "Català", listOf("ca", "ca-ES"), "windows-1252"),
    GERMAN("de", "German", "Deutsch", listOf("de", "de-AT", "de-CH"), "windows-1252"),
    PORTUGUESE("pt", "Portuguese", "Português", listOf("pt", "pt-BR", "pt-PT"), "windows-1252"),
    FRENCH("fr", "French", "Français", listOf("fr", "fr-CA", "fr-FR"), "windows-1252"),
    SPANISH("es", "Spanish", "Español", listOf("es", "es-419", "es-ES", "es-US"), "windows-1252"),
    ITALIAN("it", "Italian", "Italiano", listOf("it"), "windows-1252"),
    DUTCH("nl", "Dutch", "Nederlands", listOf("nl"), "windows-1252"),
    NORWEGIAN("nb", "Norwegian (Bokmål)", "Norsk bokmål", listOf("no", "nb"), "windows-1252", noteTags = listOf("nb", "no")),
    ROMANIAN("ro", "Romanian", "Română", listOf("ro"), "windows-1250"),
    GREEK("el", "Greek", "Ελληνικά", listOf("el"), "windows-1253"),
    FINNISH("fi", "Finnish", "Suomi", listOf("fi"), "windows-1252"),
    HUNGARIAN("hu", "Hungarian", "Magyar", listOf("hu"), "windows-1250"),
    CROATIAN("hr", "Croatian", "Hrvatski", listOf("hr"), "windows-1250"),
    SWEDISH("sv", "Swedish", "Svenska", listOf("sv"), "windows-1252"),
    POLISH("pl", "Polish", "Polski", listOf("pl"), "windows-1250"),
    LITHUANIAN("lt", "Lithuanian", "Lietuvių", listOf("lt"), "windows-1257"),
    DANISH("da", "Danish", "Dansk", listOf("da"), "windows-1252"),
    TURKISH("tr", "Turkish", "Türkçe", listOf("tr"), "windows-1254"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", listOf("id"), "windows-1252"),
    RUSSIAN("ru", "Russian", "Русский", listOf("ru"), "windows-1251", ignoredMarks = Regex("[̀́]")),
    ARABIC("ar", "Arabic", "العربية", listOf("ar"), "windows-1256", ignoredMarks = Regex("[\\p{Mn}ـ]")),
    THAI("th", "Thai", "ไทย", listOf("th"), "TIS-620", unspaced = true),
    PERSIAN("fa", "Persian", "فارسی", listOf("fa"), "windows-1256", ignoredMarks = Regex("[\\p{Mn}ـ]")),
    SLOVENIAN("sl", "Slovenian", "Slovenščina", listOf("sl"), "windows-1250"),
    UKRAINIAN("uk", "Ukrainian", "Українська", listOf("uk"), "windows-1251", ignoredMarks = Regex("[̀́]")),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", listOf("vi"), "windows-1258"),
    CANTONESE(
        "yue", "Cantonese", "廣東話", listOf("yue", "zh-HK", "zh-Hant-HK", "zh-Hant"), "Big5-HKSCS",
        reading = Reading.JYUTPING,
        // Anki Miner tags Cantonese cards with the script ("zh-Hant") rather than "yue".
        noteTags = listOf("yue", "zh-Hant", "zh-HK"),
        unspaced = true,
        textLocale = "zh-HK",
    ),
    HEBREW("he", "Hebrew", "עברית", listOf("iw", "he"), "windows-1255", noteTags = listOf("he", "iw"), ignoredMarks = Regex("\\p{Mn}")),
    // YouTube labels Tagalog subtitles as Filipino ("fil").
    TAGALOG("tl", "Tagalog", "Tagalog", listOf("fil", "tl"), "windows-1252", noteTags = listOf("tl", "fil"), inAnkiMiner = false),
    // Taiwanese Hokkien (Taigi), "nan" for Min Nan. Written in Han characters, in Tâi-lô/POJ
    // romanization (syllables joined by hyphens) or mixed; Han text is segmented like Chinese.
    TAIWANESE(
        "nan", "Taiwanese", "台語", listOf("nan-TW", "nan"), "Big5",
        reading = Reading.TAILO,
        noteTags = listOf("nan"),
        unspaced = true,
        inAnkiMiner = false,
        textLocale = "zh-TW",
    ),
    ;

    /** True if a note's Language field value (e.g. "zh-Hans") is this language. */
    fun matchesTag(tag: String): Boolean {
        val t = tag.trim()
        return noteTags.any { t.equals(it, ignoreCase = true) || t.startsWith("$it-", ignoreCase = true) }
    }

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: JAPANESE
    }
}
