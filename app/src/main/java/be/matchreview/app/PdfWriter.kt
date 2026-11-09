package be.matchreview.app

import be.matchreview.app.i18n.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

/** A4 page measurements in PDF points. */
internal object PdfLayout {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 42f
    const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2)
}

/** Writes text, tables and drawings top to bottom, starting new pages as needed. */
internal class PdfWriter(private val document: PdfDocument) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 42, 52)
        textSize = 11f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val bold = Paint(body).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val title = Paint(bold).apply { textSize = 22f }
    private val section = Paint(bold).apply {
        textSize = 15f
        color = Color.rgb(8, 127, 91)
    }
    private val small = Paint(body).apply {
        textSize = 9f
        color = Color.DKGRAY
    }

    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = PdfLayout.MARGIN

    init {
        newPage()
    }

    /** Draws the team logo in the top-right corner of the current page. */
    fun logo(bitmap: Bitmap) {
        val size = 58f
        val scale = size / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN - width
        canvas!!.drawBitmap(
            bitmap,
            null,
            RectF(left, y, left + width, y + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
    }

    /** Reserves [height] points on one page and lets [draw] paint into it. */
    fun block(height: Float, draw: (Canvas, Float) -> Unit) {
        ensureSpace(height)
        draw(canvas!!, y)
        y += height
    }

    fun heading(value: String) {
        ensureSpace(34f)
        canvas!!.drawText(value, PdfLayout.MARGIN, y + title.textSize, title)
        y += 34f
    }

    fun section(value: String) {
        ensureSpace(36f)
        y += 10f
        canvas!!.drawLine(PdfLayout.MARGIN, y, PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN, y, section)
        y += 7f
        canvas!!.drawText(value, PdfLayout.MARGIN, y + section.textSize, section)
        y += 24f
    }

    fun labelValue(label: String, value: String) {
        val lines = wrap(value, body, PdfLayout.CONTENT_WIDTH - 100f)
        ensureSpace(18f * lines.size)
        canvas!!.drawText("$label:", PdfLayout.MARGIN, y + body.textSize, bold)
        lines.forEach { line ->
            canvas!!.drawText(line, PdfLayout.MARGIN + 100f, y + body.textSize, body)
            y += 18f
        }
    }

    fun bullet(value: String) {
        text("• $value", indent = 12f)
    }

    fun text(
        value: String,
        emphasized: Boolean = false,
        small: Boolean = false,
        indent: Float = 0f
    ) {
        val paint = when {
            emphasized -> bold
            small -> this.small
            else -> body
        }
        val lineHeight = paint.textSize + 5f
        val paragraphs = value.replace("\r", "").split("\n")
        paragraphs.forEach { paragraph ->
            val lines = wrap(paragraph.ifBlank { " " }, paint, PdfLayout.CONTENT_WIDTH - indent)
            lines.forEach { line ->
                ensureSpace(lineHeight)
                canvas!!.drawText(line, PdfLayout.MARGIN + indent, y + paint.textSize, paint)
                y += lineHeight
            }
        }
    }

    /**
     * A simple table. [weights] share the content width between the columns; the first
     * column is left aligned and the others right aligned. The header repeats on new pages.
     */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>) {
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        val widths = weights.map { PdfLayout.CONTENT_WIDTH * it / total }
        val rowHeight = 16f
        val shade = Paint().apply { color = Color.rgb(232, 240, 236) }
        val stripe = Paint().apply { color = Color.rgb(246, 248, 247) }
        fun drawRow(cells: List<String>, paint: Paint, background: Paint?) {
            background?.let {
                canvas!!.drawRect(PdfLayout.MARGIN, y, PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN, y + rowHeight, it)
            }
            var left = PdfLayout.MARGIN
            cells.forEachIndexed { index, cell ->
                val width = widths.getOrElse(index) { 0f }
                val text = fit(cell, paint, width - 8f)
                val x = if (index == 0) left + 4f else left + width - 4f - paint.measureText(text)
                canvas!!.drawText(text, x, y + rowHeight - 4.5f, paint)
                left += width
            }
            y += rowHeight
        }
        ensureSpace(rowHeight * 2)
        drawRow(headers, bold, shade)
        rows.forEachIndexed { index, row ->
            if (y + rowHeight > PdfLayout.PAGE_HEIGHT - PdfLayout.MARGIN) {
                newPage()
                drawRow(headers, bold, shade)
            }
            drawRow(row, body, if (index % 2 == 1) stripe else null)
        }
        y += 4f
    }

    private fun fit(value: String, paint: Paint, width: Float): String {
        if (paint.measureText(value) <= width) return value
        var end = paint.breakText(value, true, width - paint.measureText("…"), null).coerceAtLeast(0)
        if (end > value.length) end = value.length
        return value.substring(0, end).trimEnd() + "…"
    }

    fun spacer(height: Float) {
        ensureSpace(height)
        y += height
    }

    fun finish() {
        page?.let(document::finishPage)
        page = null
        canvas = null
    }

    private fun ensureSpace(required: Float) {
        if (y + required > PdfLayout.PAGE_HEIGHT - PdfLayout.MARGIN) newPage()
    }

    private fun newPage() {
        page?.let(document::finishPage)
        pageNumber++
        page = document.startPage(
            PdfDocument.PageInfo.Builder(PdfLayout.PAGE_WIDTH, PdfLayout.PAGE_HEIGHT, pageNumber).create()
        )
        canvas = page!!.canvas
        y = PdfLayout.MARGIN
        canvas!!.drawText("MatchReview", PdfLayout.MARGIN, 24f, small)
        canvas!!.drawText(tr("Page {pageNumber}", "pageNumber" to pageNumber), PdfLayout.PAGE_WIDTH - PdfLayout.MARGIN - 35f, 24f, small)
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var remaining = value.trim()
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val breakAt = remaining.lastIndexOf(' ', count - 1)
                if (breakAt > 0) count = breakAt
            }
            result += remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trimStart()
        }
        return result
    }
}
