package be.matchreview.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.matchreview.app.data.AppDatabase
import be.matchreview.app.data.AvailabilityStatus
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchDao
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.MatchRepository
import be.matchreview.app.data.MatchSquadPlayer
import be.matchreview.app.data.MatchStatus
import be.matchreview.app.data.Player
import be.matchreview.app.data.PlayerMatchState
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.data.Team
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatchDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MatchDao

    private data class Fixture(val teamId: Long, val matchId: Long, val playerIds: List<Long>)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.matchDao()
    }

    @After
    fun tearDown() = db.close()

    /** Four selected players, two starters, kicked off at [KICK_OFF_MONOTONIC]. */
    private fun liveMatch(): Fixture = runBlocking {
        val teamId = dao.insertTeam(Team(name = "U12"))
        val playerIds = (1..4).map {
            dao.insertPlayer(Player(teamId = teamId, name = "Player $it", shirtNumber = it))
        }
        val matchId = dao.insertMatch(
            GameMatch(
                teamId = teamId,
                opponent = "Rivals",
                matchDate = "2026-09-26",
                playersOnPitch = 2,
                status = MatchStatus.LINEUP_READY
            )
        )
        dao.upsertMatchSquadPlayers(playerIds.map {
            MatchSquadPlayer(matchId, it, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.BENCH)
        })
        dao.upsertLineupPlacements(playerIds.mapIndexed { index, id ->
            MatchLineupPlacement(matchId, id, onPitch = index < 2)
        })
        dao.kickOffMatch(matchId, KICK_OFF_MONOTONIC, KICK_OFF_WALL)
        Fixture(teamId, matchId, playerIds)
    }

    private fun MatchDao.latestGoal(matchId: Long): MatchEvent = runBlocking {
        getScoringEventsNewestFirst(matchId).first()
    }

    @Test
    fun deletingGoalFromTimelineUpdatesScore() = runBlocking {
        val fixture = liveMatch()
        dao.recordOurGoal(fixture.matchId, fixture.playerIds[0], null, KICK_OFF_MONOTONIC + 60_000, KICK_OFF_WALL + 60_000)
        assertEquals(1, dao.getMatchOnce(fixture.matchId)!!.ourScore)

        dao.deleteTimelineEvent(dao.latestGoal(fixture.matchId).id)

        assertEquals(0, dao.getMatchOnce(fixture.matchId)!!.ourScore)
    }

    @Test
    fun deletingOtherEventKeepsManuallyEnteredLegacyScore() = runBlocking {
        val teamId = dao.insertTeam(Team(name = "U12"))
        val matchId = dao.insertMatch(
            GameMatch(teamId = teamId, opponent = "Legacy", matchDate = "2025-01-01", ourScore = 3, opponentScore = 1)
        )
        val tagId = dao.insertEvent(MatchEvent(matchId = matchId, timestampMs = 5_000, type = "Chance"))

        dao.deleteTimelineEvent(tagId)

        val match = dao.getMatchOnce(matchId)!!
        assertEquals(3, match.ourScore)
        assertEquals(1, match.opponentScore)
    }

    @Test
    fun editingGoalTimeShiftsWallClockAnchorInsteadOfDroppingIt() = runBlocking {
        val fixture = liveMatch()
        dao.recordOurGoal(fixture.matchId, fixture.playerIds[0], null, KICK_OFF_MONOTONIC + 120_000, KICK_OFF_WALL + 120_000)
        val goal = dao.latestGoal(fixture.matchId)

        dao.updateOurGoal(goal.id, fixture.playerIds[1], null, goal.timestampMs)
        assertEquals(KICK_OFF_WALL + 120_000, dao.getEventOnce(goal.id)!!.occurredAtEpochMs)

        dao.updateOurGoal(goal.id, fixture.playerIds[1], null, goal.timestampMs - 60_000)
        val edited = dao.getEventOnce(goal.id)!!
        assertEquals(60_000L, edited.timestampMs)
        assertEquals(KICK_OFF_WALL + 60_000, edited.occurredAtEpochMs)
        assertEquals(fixture.playerIds[1], edited.playerId)
    }

    @Test
    fun archivingPlayerKeepsMatchHistoryButHidesThemFromTheTeam() = runBlocking {
        val fixture = liveMatch()
        val starter = fixture.playerIds[0]

        dao.archivePlayer(starter)

        assertTrue(dao.observeParticipations(fixture.matchId).first().any { it.playerId == starter })
        assertTrue(dao.getMatchSquadOnce(fixture.matchId).any { it.playerId == starter })
        assertFalse(dao.getPlayersForTeam(fixture.teamId).any { it.id == starter })
        assertTrue(dao.observeAllPlayers().first().first { it.id == starter }.archived)
    }

    @Test
    fun archivingPlayerRemovesThemFromUpcomingMatches() = runBlocking {
        val fixture = liveMatch()
        val player = fixture.playerIds[3]
        val upcoming = dao.insertMatch(GameMatch(teamId = fixture.teamId, opponent = "Next", matchDate = "2026-10-03"))
        dao.upsertMatchSquadPlayer(MatchSquadPlayer(upcoming, player, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.BENCH))
        dao.upsertLineupPlacement(MatchLineupPlacement(upcoming, player))

        dao.archivePlayer(player)

        assertTrue(dao.getMatchSquadOnce(upcoming).isEmpty())
        assertTrue(dao.getLineupOnce(upcoming).isEmpty())
        assertTrue(dao.getMatchSquadOnce(fixture.matchId).any { it.playerId == player })
    }

    @Test
    fun permanentPlayerDeletionUnlinksAssists() = runBlocking {
        val fixture = liveMatch()
        val (scorer, assist) = fixture.playerIds
        dao.recordOurGoal(fixture.matchId, scorer, assist, KICK_OFF_MONOTONIC + 30_000, KICK_OFF_WALL + 30_000)
        val goal = dao.latestGoal(fixture.matchId)

        dao.deletePlayerPermanently(dao.getPlayersForTeam(fixture.teamId).first { it.id == assist })

        val event = dao.getEventOnce(goal.id)!!
        assertEquals(scorer, event.playerId)
        assertNull(event.relatedPlayerId)
    }

    @Test
    fun reviewAndLineupUpdatesDoNotOverwriteLiveMatchState() = runBlocking {
        val fixture = liveMatch()

        dao.updateReview(fixture.matchId, 7, "Pressed well")
        dao.markLineupReady(fixture.matchId)
        dao.setVideoUri(fixture.matchId, "content://imported/1")

        val match = dao.getMatchOnce(fixture.matchId)!!
        assertEquals(MatchStatus.LIVE, match.status)
        assertTrue(match.clockRunning)
        assertEquals(7, match.teamRating)
        assertEquals("Pressed well", match.reviewNotes)
        assertEquals("content://imported/1", match.videoUri)
    }

    @Test
    fun pauseAfterRebootUsesElapsedWallClockTime() = runBlocking {
        val teamId = dao.insertTeam(Team(name = "U12"))
        val matchId = dao.insertMatch(
            GameMatch(teamId = teamId, opponent = "Rivals", matchDate = "2026-09-26", status = MatchStatus.LINEUP_READY)
        )
        // Kick-off 5 minutes after boot; after a restart the device has been up 10 minutes
        // while 40 minutes passed.
        dao.kickOffMatch(matchId, 5 * 60_000L, KICK_OFF_WALL)
        dao.pauseMatchClock(matchId, 10 * 60_000L, KICK_OFF_WALL + 40 * 60_000L)

        assertEquals(40 * 60_000L, dao.getMatchOnce(matchId)!!.accumulatedMatchTimeMs)
    }

    @Test
    fun failedRecordingKeepsItsFileReferenceForDeletion() = runBlocking {
        val fixture = liveMatch()
        val repository = MatchRepository(dao)
        val id = repository.beginRecordingSegment(fixture.matchId, 0L, 0, false)

        repository.failRecordingSegment(id, RecordingStatus.FAILED, 1_000L, 1_000L, "Encoder failed", "content://media/9")

        val segment = dao.getRecordingOnce(id)!!
        assertEquals(RecordingStatus.FAILED, segment.status)
        assertEquals("content://media/9", segment.uri)
    }

    @Test
    fun completedRecordingWithWarningStaysPlayable() = runBlocking {
        val fixture = liveMatch()
        val repository = MatchRepository(dao)
        val id = repository.beginRecordingSegment(fixture.matchId, 0L, 0, false)

        repository.completeRecordingSegment(id, "content://media/10", 90_000L, 90_000L, 4_000_000_000L, "File-size limit reached")

        val segment = dao.getRecordingOnce(id)!!
        assertEquals(RecordingStatus.COMPLETED, segment.status)
        assertEquals("content://media/10", segment.uri)
        assertEquals("File-size limit reached", segment.errorMessage)
    }

    private companion object {
        const val KICK_OFF_MONOTONIC = 3_600_000L
        const val KICK_OFF_WALL = 1_790_000_000_000L
    }
}
