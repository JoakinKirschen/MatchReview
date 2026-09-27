package be.matchreview.app.domain

import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.data.PlayerParticipation
import be.matchreview.app.data.RecordingSegment
import java.util.Locale

object MatchExportFormatter {
    fun fileName(match: GameMatch): String {
        val opponent = match.opponent
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "opponent" }
        val date = match.matchDate.replace(Regex("[^0-9A-Za-z-]+"), "-").trim('-')
        return "matchreview-${date.ifBlank { "match" }}-$opponent.csv"
    }

    fun toCsv(
        match: GameMatch,
        players: List<Player>,
        events: List<MatchEvent>,
        participations: List<PlayerParticipation>,
        recordings: List<RecordingSegment>
    ): String {
        val names = players.associate { it.id to it.name }
        return buildString {
            appendLine("MatchReview export")
            appendLine("field,value")
            appendLine(row("opponent", match.opponent))
            appendLine(row("date", match.matchDate))
            appendLine(row("venue", match.venue))
            appendLine(row("competition", match.competition))
            appendLine(row("status", match.status.name))
            appendLine(row("score", "${match.ourScore}-${match.opponentScore}"))
            appendLine(row("formation", match.formation))
            appendLine(row("periods", match.periodCount.toString()))
            appendLine(row("minutes_per_period", match.periodDurationMinutes.toString()))
            appendLine()
            appendLine("events")
            appendLine("id,match_time_ms,period,type,player,related_player,sentiment,note,video_segment_id,video_offset_ms")
            events.sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id }).forEach { event ->
                appendLine(
                    listOf(
                        event.id,
                        event.timestampMs,
                        event.periodNumber,
                        event.type,
                        event.playerId?.let(names::get).csvValue(),
                        event.relatedPlayerId?.let(names::get).csvValue(),
                        event.sentiment,
                        event.note,
                        event.recordingSegmentId.csvValue(),
                        event.recordingOffsetMs.csvValue()
                    ).joinToString(",") { csv(it.toString()) }
                )
            }
            appendLine()
            appendLine("participation")
            appendLine("player,start_match_time_ms,end_match_time_ms,entry_reason,exit_reason")
            participations.sortedWith(compareBy<PlayerParticipation> { it.startMatchTimeMs }.thenBy { it.id }).forEach {
                appendLine(
                    listOf(
                        names[it.playerId].csvValue(),
                        it.startMatchTimeMs,
                        it.endMatchTimeMs.csvValue(),
                        it.entryReason.name,
                        it.exitReason?.name.csvValue()
                    ).joinToString(",") { value -> csv(value.toString()) }
                )
            }
            appendLine()
            appendLine("recordings")
            appendLine("id,status,start_match_time_ms,end_match_time_ms,duration_ms,audio,bytes,uri")
            recordings.sortedBy { it.id }.forEach {
                appendLine(
                    listOf(
                        it.id,
                        it.status.name,
                        it.matchClockStartMs,
                        it.matchClockEndMs.csvValue(),
                        it.recordingDurationMs,
                        it.audioEnabled,
                        it.bytesRecorded,
                        it.uri.csvValue()
                    ).joinToString(",") { value -> csv(value.toString()) }
                )
            }
            appendLine()
            appendLine("privacy_note")
            appendLine(row("notice", "The export contains player names and match data. Share and store it only with appropriate permission."))
        }
    }

    private fun row(key: String, value: String) = "${csv(key)},${csv(value)}"

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

    // Named differently from the stdlib String?.orEmpty(), which this helper used to shadow and
    // call recursively, overflowing the stack on every export.
    private fun <T> T?.csvValue(): String = this?.toString() ?: ""
}
