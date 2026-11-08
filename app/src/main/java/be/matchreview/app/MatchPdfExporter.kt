package be.matchreview.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.data.PlayerParticipation
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.Team
import be.matchreview.app.domain.GoalMouthGeometry
import be.matchreview.app.domain.GoalSummaryRules
import be.matchreview.app.domain.MatchActions
import be.matchreview.app.domain.MatchStatsRules
import be.matchreview.app.domain.StartingLineupRules
import be.matchreview.app.domain.TimelineGrouping
import be.matchreview.app.domain.TimelineItem
import be.matchreview.app.domain.VideoEventRules
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Creates a compact, printable match summary using Android's built-in PDF support.
 * No network connection or third-party PDF service is involved.
 */
object MatchPdfExporter {
    private const val MARGIN = PdfLayout.MARGIN

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
        team: Team?,
        players: List<Player>,
        events: List<MatchEvent>,
        participations: List<PlayerParticipation>,
        recordings: List<RecordingSegment>,
        lineup: List<MatchLineupPlacement> = emptyList()
    ) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            val names = players.associate { it.id to it.name }
            val playersById = players.associateBy { it.id }
            val teamName = team?.name ?: "Our team"

            TeamLogoCodec.decode(team?.logoPng)?.let { writer.logo(it) }
            writer.heading(
                if (match.isHome) "$teamName vs ${match.opponent}" else "${match.opponent} vs $teamName"
            )
            writer.text(
                "${if (match.isHome) "Home" else "Away"} match • match summary",
                emphasized = true
            )
            writer.text(listOf(match.matchDate, match.competition, match.venue)
                .filter { it.isNotBlank() }
                .joinToString("  |  "))
            writer.spacer(5f)
            writer.labelValue("Score", "${match.ourScore} - ${match.opponentScore}")
            val goalLines = GoalSummaryRules.lines(events, names, teamName, match.opponent)
            if (goalLines.isNotEmpty()) writer.labelValue("Goals", goalLines.joinToString(", "))
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
            if (match.teamRating > 0) writer.labelValue("Team rating", "${match.teamRating}/10")

            val stats = MatchStatsRules.compute(events)
            if (stats.hasDetail) {
                writer.section("Match stats")
                writer.table(
                    headers = listOf("", teamName, match.opponent),
                    rows = MatchStatsRules.rows(stats).map { (label, ours, theirs) ->
                        listOf(label, ours.toString(), theirs.toString())
                    },
                    weights = listOf(2f, 1.5f, 1.5f)
                )
            }

            val (videoTags, matchEvents) = events
                .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
                .partition(VideoEventRules::isImportedVideoTag)
            // The lineup pictures start with the starting lineup, rebuilt for older matches.
            val lineupEvents = StartingLineupRules.withStartingLineup(match.id, matchEvents, participations, lineup)
                .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
            writer.section("Timeline")
            if (matchEvents.isEmpty()) {
                writer.text("No events recorded.")
            } else {
                matchEvents
                    // Marks only help find moments in the video; they are not match facts.
                    .filterNot { it.type == MatchActions.MARK }
                    .forEach { event ->
                        val people = listOfNotNull(
                            event.playerId?.let { names[it] },
                            event.relatedPlayerId?.let { names[it] }?.let { "related: $it" }
                        ).joinToString(", ")
                        val detail = listOf(
                            "${formatTime(event.timestampMs)}  P${max(1, event.periodNumber)}",
                            MatchActions.label(event.type)
                                ?.let { if (MatchActions.isOpponent(event.type)) "${match.opponent}: ${it.lowercase()}" else it }
                                ?: event.type.replace('_', ' ').lowercase()
                                    .replaceFirstChar { it.titlecase() },
                            people,
                            event.note,
                            GoalMouthGeometry.describe(event.goalX, event.goalY)
                                ?.let { "goal position: $it" }.orEmpty()
                        ).filter { it.isNotBlank() }.joinToString(" - ")
                        writer.bullet(detail)
                    }
            }

            if (videoTags.isNotEmpty()) {
                writer.section("Imported video tags")
                writer.text("Times are positions in the imported video, not match time.", small = true)
                videoTags.forEach { tag ->
                    writer.bullet(
                        listOf(
                            formatTime(tag.timestampMs),
                            tag.type,
                            tag.playerId?.let { names[it] }.orEmpty(),
                            tag.sentiment,
                            tag.note
                        ).filter { it.isNotBlank() }.joinToString(" - ")
                    )
                }
            }

            val lineupChanges = TimelineGrouping
                .group(lineupEvents)
                .filterIsInstance<TimelineItem.LineupChange>()
            if (lineupChanges.isNotEmpty()) {
                writer.section("Starting lineup and changes")
                writer.text("Highlighted players came on at that moment.", small = true)
                lineupChanges.chunked(3).forEach { row ->
                    writer.block(PITCH_HEIGHT + 30f) { canvas, top ->
                        row.forEachIndexed { index, change ->
                            val left = MARGIN + index * (PITCH_WIDTH + 24f)
                            drawPitch(canvas, change, playersById, left, top)
                        }
                    }
                }
            }

            writer.section("Goalkeeping")
            val saves = events.filter { it.type == "KEEPER_SAVE" }
            writer.text("${saves.size} save(s), ${match.opponentScore} goal(s) conceded")
            saves.groupBy { it.playerId }.forEach { (keeperId, keeperSaves) ->
                writer.bullet("${keeperId?.let { names[it] } ?: "Keeper not assigned"} - ${keeperSaves.size} save(s)")
            }

            val placedGoals = events.filter {
                (it.type == "OUR_GOAL" || it.type == "OPPONENT_GOAL") && it.goalX != null && it.goalY != null
            }
            if (placedGoals.isNotEmpty()) {
                writer.section("Goal map")
                writer.text("Green: $teamName goals. Red: ${match.opponent} goals.", small = true)
                writer.block(GOAL_MAP_HEIGHT + 8f) { canvas, top ->
                    drawGoalMap(canvas, placedGoals, MARGIN, top)
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

    private const val PITCH_WIDTH = 154f
    private const val PITCH_HEIGHT = 210f
    private const val GOAL_MAP_WIDTH = 360f
    private const val GOAL_MAP_HEIGHT = GOAL_MAP_WIDTH / GoalMouthGeometry.ASPECT_RATIO

    private fun drawPitch(
        canvas: Canvas,
        change: TimelineItem.LineupChange,
        playersById: Map<Long, Player>,
        left: Float,
        top: Float
    ) {
        val grass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 166, 59) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 42, 52)
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val pitch = RectF(left, top + 14f, left + PITCH_WIDTH, top + 14f + PITCH_HEIGHT)
        val label = when {
            change.isStartingLineup && change.isApproximate -> "Start (approximate)"
            change.isStartingLineup -> "Start"
            else -> formatTime(change.timestampMs)
        }
        canvas.drawText(label, left, top + 10f, caption)
        canvas.drawRect(pitch, grass)
        canvas.drawRect(pitch, line)
        canvas.drawLine(pitch.left, pitch.centerY(), pitch.right, pitch.centerY(), line)
        canvas.drawCircle(pitch.centerX(), pitch.centerY(), PITCH_WIDTH * 0.13f, line)
        val boxWidth = PITCH_WIDTH * 0.58f
        val boxHeight = PITCH_HEIGHT * 0.16f
        canvas.drawRect(pitch.centerX() - boxWidth / 2, pitch.top, pitch.centerX() + boxWidth / 2, pitch.top + boxHeight, line)
        canvas.drawRect(pitch.centerX() - boxWidth / 2, pitch.bottom - boxHeight, pitch.centerX() + boxWidth / 2, pitch.bottom, line)

        val incoming = change.incomingPlayerIds.toSet()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 61, 110)
            textSize = 7.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 6.5f
            textAlign = Paint.Align.CENTER
        }
        change.snapshot.forEach { position ->
            val player = playersById[position.playerId]
            val x = pitch.left + PITCH_WIDTH * position.normalizedX.coerceIn(0.07f, 0.93f)
            val y = pitch.top + PITCH_HEIGHT * position.normalizedY.coerceIn(0.06f, 0.9f)
            dot.color = if (position.playerId in incoming) Color.rgb(255, 213, 79) else Color.rgb(245, 247, 250)
            canvas.drawCircle(x, y, 7f, dot)
            canvas.drawText(
                player?.shirtNumber?.takeIf { it > 0 }?.toString() ?: "",
                x, y + 2.7f, number
            )
            canvas.drawText(player?.name?.substringBefore(" ")?.take(10) ?: "", x, y + 15f, name)
        }
    }

    private fun drawGoalMap(canvas: Canvas, goals: List<MatchEvent>, left: Float, top: Float) {
        val frame = GoalMouthGeometry.frame(GOAL_MAP_WIDTH, GOAL_MAP_HEIGHT)
        val net = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 243, 246) }
        val netLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(184, 196, 204)
            strokeWidth = 0.6f
        }
        val post = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 71, 79)
            strokeWidth = 5f
        }
        canvas.drawRect(left + frame.left, top + frame.top, left + frame.right, top + frame.bottom, net)
        for (i in 1 until 12) {
            val x = left + frame.left + (frame.right - frame.left) * i / 12f
            canvas.drawLine(x, top + frame.top, x, top + frame.bottom, netLine)
        }
        for (i in 1 until 5) {
            val y = top + frame.top + (frame.bottom - frame.top) * i / 5f
            canvas.drawLine(left + frame.left, y, left + frame.right, y, netLine)
        }
        canvas.drawLine(left + frame.left, top + frame.bottom, left + frame.left, top + frame.top, post)
        canvas.drawLine(left + frame.right, top + frame.bottom, left + frame.right, top + frame.top, post)
        canvas.drawLine(left + frame.left - 2.5f, top + frame.top, left + frame.right + 2.5f, top + frame.top, post)

        val ball = Paint(Paint.ANTI_ALIAS_FLAG)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        goals.forEach { goal ->
            val (x, y) = GoalMouthGeometry.toCanvas(goal.goalX!!, goal.goalY!!, GOAL_MAP_WIDTH, GOAL_MAP_HEIGHT)
            ball.color = if (goal.type == "OUR_GOAL") Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40)
            canvas.drawCircle(left + x, top + y, 6f, outline)
            canvas.drawCircle(left + x, top + y, 4.5f, ball)
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
}
