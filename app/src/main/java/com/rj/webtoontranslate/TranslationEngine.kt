package com.rj.webtoontranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * A single detected block of text on screen, with the box it occupies and,
 * once processed, the translation that should be drawn in that same box.
 */
data class DetectedBlock(
    val originalText: String,
    val boundingBoxLeft: Int,
    val boundingBoxTop: Int,
    val boundingBoxRight: Int,
    val boundingBoxBottom: Int,
    var translatedText: String? = null,
    var backgroundColor: Int = Color.WHITE
)

/**
 * Auto-detects the script/language present in a captured frame and translates
 * every text block to the user's chosen target language -- entirely on-device.
 */
class TranslationEngine {

    private val recognizerOptionsByScript = mapOf(
        "latin" to TextRecognizerOptions.DEFAULT_OPTIONS,
        "chinese" to ChineseTextRecognizerOptions.Builder().build(),
        "japanese" to JapaneseTextRecognizerOptions.Builder().build(),
        "korean" to KoreanTextRecognizerOptions.Builder().build(),
        "devanagari" to DevanagariTextRecognizerOptions.Builder().build()
    )

    private val scriptToLanguageHint = mapOf(
        "chinese" to TranslateLanguage.CHINESE,
        "japanese" to TranslateLanguage.JAPANESE,
        "korean" to TranslateLanguage.KOREAN,
        "devanagari" to TranslateLanguage.HINDI
    )

    private var cachedTranslator: Translator? = null
    private var cachedSource: String? = null
    private var cachedTarget: String? = null

    /**
     * Runs every script recognizer against the frame and keeps whichever one
     * found the most text -- this is how "auto detect" works across Latin,
     * CJK and Devanagari scripts without the user having to pick a source.
     */
    suspend fun recognizeText(bitmap: Bitmap): Pair<List<Text.TextBlock>, String?> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val deferredResults = recognizerOptionsByScript.map { (script, options) ->
            async {
                val text = runCatching {
                    TextRecognition.getClient(options).process(image).await()
                }.getOrNull()
                script to text
            }
        }
        val results = deferredResults.map { it.await() }
        val best = results.maxByOrNull { (_, text) ->
            text?.textBlocks?.sumOf { it.text.length } ?: 0
        }
        val bestBlocks = best?.second?.textBlocks?.filter { it.text.isNotBlank() } ?: emptyList()
        val scriptHint = best?.first?.let { scriptToLanguageHint[it] }
        bestBlocks to scriptHint
    }

    /** Returns a BCP-47-ish language code (e.g. "en", "ko"), or null if unclear. */
    suspend fun identifyLanguage(sampleText: String): String? {
        if (sampleText.isBlank()) return null
        val code = LanguageIdentification.getClient().identifyLanguage(sampleText).await()
        return if (code == "und") null else code
    }

    /**
     * Translates each block's text into [targetCode]. [sourceHint] is the language
     * guessed either from LanguageIdentification or from which script recognizer won.
     */
    suspend fun translateBlocks(
        blocks: List<Text.TextBlock>,
        sourceCode: String,
        targetCode: String,
        sourceBitmap: Bitmap
    ): List<DetectedBlock> {
        val resolvedSource = TranslateLanguage.fromLanguageTag(sourceCode) ?: TranslateLanguage.ENGLISH
        val translator = getOrCreateTranslator(resolvedSource, targetCode)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

        return blocks.map { block ->
            val box = block.boundingBox ?: Rect()
            val translated = runCatching { translator.translate(block.text).await() }
                .getOrElse { block.text }
            DetectedBlock(
                originalText = block.text,
                boundingBoxLeft = box.left,
                boundingBoxTop = box.top,
                boundingBoxRight = box.right,
                boundingBoxBottom = box.bottom,
                translatedText = translated,
                backgroundColor = sampleBackgroundColor(sourceBitmap, box)
            )
        }
    }

    /**
     * Samples pixels just outside the text glyphs (the box's border) rather than
     * its center, so we pick up the panel/bubble color instead of ink color --
     * that's what lets the translated text sit "exactly where the original was"
     * without leaving a mismatched-color patch behind.
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
        if (box.width() <= 0 || box.height() <= 0) return Color.WHITE
        val margin = 3
        val samplePoints = listOf(
            box.left - margin to box.top - margin,
            box.right + margin to box.top - margin,
            box.left - margin to box.bottom + margin,
            box.right + margin to box.bottom + margin
        )
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        for ((x, y) in samplePoints) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                val color = bitmap.getPixel(x, y)
                r += Color.red(color); g += Color.green(color); b += Color.blue(color)
                count++
            }
        }
        if (count == 0) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun getOrCreateTranslator(sourceCode: String, targetCode: String): Translator {
        val existing = cachedTranslator
        if (existing != null && cachedSource == sourceCode && cachedTarget == targetCode) {
            return existing
        }
        existing?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        cachedTranslator = translator
        cachedSource = sourceCode
        cachedTarget = targetCode
        return translator
    }

    fun close() {
        cachedTranslator?.close()
        cachedTranslator = null
    }
}
