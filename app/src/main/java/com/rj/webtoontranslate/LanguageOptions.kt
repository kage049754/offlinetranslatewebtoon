package com.rj.webtoontranslate

import com.google.mlkit.nl.translate.TranslateLanguage

/** Curated list of translation targets, backed by ML Kit's on-device TranslateLanguage codes. */
object LanguageOptions {
    const val DEFAULT_TARGET_CODE = TranslateLanguage.ENGLISH

    data class Entry(val code: String, val label: String)

    val ALL: List<Entry> = listOf(
        Entry(TranslateLanguage.ENGLISH, "English"),
        Entry(TranslateLanguage.SPANISH, "Spanish"),
        Entry(TranslateLanguage.FRENCH, "French"),
        Entry(TranslateLanguage.GERMAN, "German"),
        Entry(TranslateLanguage.PORTUGUESE, "Portuguese"),
        Entry(TranslateLanguage.ITALIAN, "Italian"),
        Entry(TranslateLanguage.INDONESIAN, "Indonesian"),
        Entry(TranslateLanguage.TAGALOG, "Filipino / Tagalog"),
        Entry(TranslateLanguage.VIETNAMESE, "Vietnamese"),
        Entry(TranslateLanguage.THAI, "Thai"),
        Entry(TranslateLanguage.HINDI, "Hindi"),
        Entry(TranslateLanguage.ARABIC, "Arabic"),
        Entry(TranslateLanguage.RUSSIAN, "Russian"),
        Entry(TranslateLanguage.CHINESE, "Chinese"),
        Entry(TranslateLanguage.JAPANESE, "Japanese"),
        Entry(TranslateLanguage.KOREAN, "Korean")
    )

    fun labelFor(code: String): String = ALL.firstOrNull { it.code == code }?.label ?: code
}
