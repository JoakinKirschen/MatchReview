package be.matchreview.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.data.PlayerParticipation
import be.matchreview.app.data.RecordingSegment
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Creates a compact, printable match summary using Android's built-in PDF support.
 * No network connection or third-party PDF service is involved.
 */
object MatchPdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 42f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2)

    fun fileName(match: GameMatch): String {
        val opponent = match.opponent
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "opponent" }
        val date = match.matchDate.replace(Regex("[^0-9A-Za-z-]+"), "-").trim('-')
        return "matchreview-summary-${date.ifBlank { "match" }}-$opponent.pdf"
    }

    fun write(
        output: OutputStream,
        match: GameMatch,
        players: List<Player>,
        events: List<MatchEvent>,
        participations: List<PlayerParticipation>,
        recordings: List<RecordingSegment>
    ) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            val names = players.associate { it.id to it.name }

            writer.heading("MatchReview summary")
            writer.text(
                "${if (match.isHome) "Home" else "Away"} match vs ${match.opponent}",
                emphasized = true
            )
            writer.text(listOf(match.matchDate, match.competition, match.venue)
                .filter { it.isNotBlank() }
                .joinToString("  |  "))
            writer.spacer(5f)
            writer.labelValue("Score", "${match.ourScore} - ${match.opponentScore}")
            writer.labelValue("Status", match.status.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.titlecase() })
            writer.labelValue("Match size", "${match.playersOnPitch}v${match.playersOnPitch}")
            writer.labelValue("Formation", match.formation)
            writer.labelValue(
                "Timing",
                "${match.periodCount} x ${match.periodDurationMinutes} minutes"
            )
            writer.labelValue(
                "Substitutions",
                if (match.rollingSubstitutions) "Rolling" else "No re-entry"
            )
            if (match.teamRating > 0) writer.labelValue("Team rating", "${match.teamRating}/5")

            writer.section("Timeline")
            if (events.isEmpty()) {
                writer.text("No events recorded.")
            } else {
                events.sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
                    .forEach { event ->
                        val people = listOfNotNull(
                            event.playerId?.let { names[it] },
                            event.relatedPlayerId?.let { names[it] }?.let { "related: $it" }
                        ).joinToString(", ")
                        val detail = listOf(
                            "${formatTime(event.timestampMs)}  P${max(1, event.periodNumber)}",
                            event.type.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.titlecase() },
                            people,
                            event.note
                        ).filter { it.isNotBlank() }.joinToString(" - ")
                        writer.bullet(detail)
                    }
            }

            writer.section("Player participation")
            val minutesByPlayer = participations
                .groupBy { it.playerId }
                .mapValues { (_, intervals) ->
                    intervals.sumOf {
                        ((it.endMatchTimeMs ?: match.accumulatedMatchTimeMs) -
                            it.startMatchTimeMs).coerceAtLeast(0L)
                    }
                }
            if (minutesByPlayer.isEmpty()) {
                writer.text("No participation data recorded.")
            } else {
                minutesByPlayer.entries
                    .sortedBy { names[it.key].orEmpty().lowercase(Locale.ROOT) }
                    .forEach { (playerId, durationMs) ->
                        writer.bullet("${names[playerId] ?: "Unknown player"} - ${formatMinutes(durationMs)}")
                    }
            }

            writer.section("Video")
            val completedRecordings = recordings.count { it.uri != null }
            val durationMs = recordings.sumOf { it.recordingDurationMs }
            writer.text(
                "$completedRecordings saved recording${if (completedRecordings == 1) "" else "s"}" +
                    if (durationMs > 0) " - ${formatTime(durationMs)} total" else ""
            )

            writer.section("Review notes")
            writer.text(match.reviewNotes.ifBlank { "No review notes." })

            writer.spacer(10f)
            writer.text(
                "Privacy notice: this summary contains player names and match data. " +
                    "Share and store it only with appropriate permission.",
                small = true
            )
            writer.finish()
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        return "%d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun formatMinutes(milliseconds: Long): String {
        val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
        val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L) % 60L
        return if (seconds == 0L) "$totalMinutes min" else "$totalMinutes min ${seconds}s"
    }

    private class PdfWriter(private val document: PdfDocument) {
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
        private var y = MARGIN

        init {
            newPage()
        }

        fun heading(value: String) {
            ensureSpace(34f)
            canvas!!.drawText(value, MARGIN, y + title.textSize, title)
            y += 34f
        }

        fun section(value: String) {
            ensureSpace(36f)
            y += 10f
            canvas!!.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, section)
            y += 7f
            canvas!!.drawText(value, MARGIN, y + section.textSize, section)
            y += 24f
        }

        fun labelValue(label: String, value: String) {
            ensureSpace(18f)
            canvas!!.drawText("$label:", MARGIN, y + body.textSize, bold)
            canvas!!.drawText(value, MARGIN + 100f, y + body.textSize, body)
            y += 18f
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
                val lines = wrap(paragraph.ifBlank { " " }, paint, CONTENT_WIDTH - indent)
                lines.forEach { line ->
                    ensureSpace(lineHeight)
                    canvas!!.drawText(line, MARGIN + indent, y + paint.textSize, paint)
                    y += lineHeight
                }
            }
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
            if (y + required > PAGE_HEIGHT - MARGIN) newPage()
        }

        private fun newPage() {
            page?.let(document::finishPage)
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            )
            canvas = page!!.canvas
            y = MARGIN
            canvas!!.drawText("MatchReview", MARGIN, 24f, small)
            canvas!!.drawText("Page $pageNumber", PAGE_WIDTH - MARGIN - 35f, 24f, small)
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
}
