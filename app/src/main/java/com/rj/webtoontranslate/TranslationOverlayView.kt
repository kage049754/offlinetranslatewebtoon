package com.rj.webtoontranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Draws each translated block directly on top of the spot the original text
 * occupied: a solid patch (color-sampled from that area of the captured frame)
 * followed by the translated string sized to fit inside the same box.
 *
 * This view is added to WindowManager with FLAG_NOT_TOUCHABLE, so none of this
 * ever intercepts touches -- scrolling/tapping the app underneath keeps working
 * the instant a translation is drawn.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private var blocks: List<DetectedBlock> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun show(newBlocks: List<DetectedBlock>) {
        blocks = newBlocks
        invalidate()
    }

    fun clear() {
        blocks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (block in blocks) {
            val text = block.translatedText ?: continue
            val rect = RectF(
                block.boundingBoxLeft.toFloat(),
                block.boundingBoxTop.toFloat(),
                block.boundingBoxRight.toFloat(),
                block.boundingBoxBottom.toFloat()
            )
            if (rect.width() <= 0f || rect.height() <= 0f) continue

            boxPaint.color = block.backgroundColor
            canvas.drawRoundRect(rect, 10f, 10f, boxPaint)

            textPaint.color = if (isLight(block.backgroundColor)) Color.BLACK else Color.WHITE
            textPaint.textSize = fitTextSize(text, rect.width(), rect.height())
            drawWrappedText(canvas, text, rect)
        }
    }

    /** Binary-searches a text size so the (possibly multi-line) string fits the box. */
    private fun fitTextSize(text: String, maxWidth: Float, maxHeight: Float): Float {
        var lo = 8f
        var hi = maxHeight.coerceAtMost(64f)
        var best = lo
        repeat(8) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid
            val lines = wrapLines(text, maxWidth)
            val totalHeight = lines.size * textPaint.fontSpacing
            if (totalHeight <= maxHeight) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    private fun wrapLines(text: String, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (textPaint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun drawWrappedText(canvas: Canvas, text: String, rect: RectF) {
        val lines = wrapLines(text, rect.width())
        val totalHeight = lines.size * textPaint.fontSpacing
        var y = rect.centerY() - totalHeight / 2f + textPaint.fontSpacing * 0.8f
        for (line in lines) {
            canvas.drawText(line, rect.centerX(), y, textPaint)
            y += textPaint.fontSpacing
        }
    }

    private fun isLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance > 150
    }
}
