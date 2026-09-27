package be.matchreview.app

import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.domain.MatchExportFormatter
import org.junit.Assert.*
import org.junit.Test

class MatchExportFormatterTest {
    @Test fun csvEscapesNamesAndNotes() {
        val match = GameMatch(teamId = 1, opponent = "Rivals, FC", matchDate = "2026-09-26")
        val player = Player(id = 7, teamId = 1, name = "Doe, Jane")
        val event = MatchEvent(
            id = 9, matchId = 1, playerId = 7, timestampMs = 12_000,
            type = "SHOT", note = "Near post, \"saved\""
        )
        val csv = MatchExportFormatter.toCsv(match, listOf(player), listOf(event), emptyList(), emptyList())
        assertTrue(csv.contains("\"Rivals, FC\""))
        assertTrue(csv.contains("\"Doe, Jane\""))
        assertTrue(csv.contains("\"Near post, \"\"saved\"\"\""))
    }

    @Test fun filenameIsFilesystemSafe() {
        val name = MatchExportFormatter.fileName(
            GameMatch(teamId = 1, opponent = "A/B United", matchDate = "26/09/2026")
        )
        assertFalse(name.contains("/"))
        assertTrue(name.endsWith(".csv"))
    }
}
