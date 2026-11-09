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
import be.matchreview.app.data.ParticipationReason
import be.matchreview.app.data.PlayerMatchState
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.data.Team
import be.matchreview.app.domain.LineupSnapshot
import be.matchreview.app.domain.MatchActions
import be.matchreview.app.domain.MatchStatsRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun recordOurGoalReturnsInsertedEventIdForImmediateEditing() = runBlocking {
        val fixture = liveMatch()

        val eventId = dao.recordOurGoal(
            fixture.matchId,
            null,
            null,
            KICK_OFF_MONOTONIC + 15_000,
            KICK_OFF_WALL + 15_000
        )

        assertNotNull(eventId)
        assertEquals(eventId, dao.latestGoal(fixture.matchId).id)
        assertEquals("Scorer not assigned", dao.latestGoal(fixture.matchId).note)
    }

    @Test
    fun renamingTeamKeepsItsLogoPlayersAndMatches() = runBlocking {
        val fixture = liveMatch()
        val repository = MatchRepository(dao)
        repository.setTeamLogo(fixture.teamId, "logo")
        val before = dao.observeTeams().first().single { it.id == fixture.teamId }

        repository.updateTeam(before.copy(name = "  U13 Lions ", club = "FC New ", ageGroup = "U13", season = "2027/28"))

        val after = dao.observeTeams().first().single { it.id == fixture.teamId }
        assertEquals("U13 Lions", after.name)
        assertEquals("FC New", after.club)
        assertEquals("U13", after.ageGroup)
        assertEquals("2027/28", after.season)
        assertEquals("logo", after.logoPng)
        assertEquals(fixture.playerIds.size, dao.getPlayersForTeam(fixture.teamId).size)
        assertEquals(fixture.teamId, dao.getMatchOnce(fixture.matchId)!!.teamId)
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

    @Test
    fun substitutionRoundKeepsMinutesRunningUntilItIsConfirmed() = runBlocking {
        val fixture = liveMatch()
        val (starterA, starterB, benchC, _) = fixture.playerIds
        val lineup = dao.getLineupOnce(fixture.matchId)
        // Bench player C replaces starter A; the round is confirmed two minutes in.
        val plan = lineup.map {
            when (it.playerId) {
                starterA -> it.copy(onPitch = false)
                benchC -> it.copy(onPitch = true, normalizedX = 0.4f, normalizedY = 0.4f)
                else -> it
            }
        }

        val applied = dao.applySubstitutionRound(
            fixture.matchId, plan, KICK_OFF_MONOTONIC + 120_000, KICK_OFF_WALL + 120_000
        )

        assertTrue(applied)
        val participations = dao.observeParticipations(fixture.matchId).first()
        val closed = participations.single { it.playerId == starterA }
        assertEquals(120_000L, closed.endMatchTimeMs)
        val entered = participations.single { it.playerId == benchC }
        assertEquals(120_000L, entered.startMatchTimeMs)
        assertNull(participations.single { it.playerId == starterB }.endMatchTimeMs)

        val events = dao.observeEvents(fixture.matchId).first()
        val substitution = events.single { it.type == "SUBSTITUTION" }
        assertEquals(starterA, substitution.playerId)
        assertEquals(benchC, substitution.relatedPlayerId)
        assertTrue(substitution.lineupSnapshot!!.contains("$benchC:0.400:0.400"))
        assertEquals(PlayerMatchState.BENCH, dao.getMatchSquadPlayer(fixture.matchId, starterA)!!.state)
        assertEquals(PlayerMatchState.ON_PITCH, dao.getMatchSquadPlayer(fixture.matchId, benchC)!!.state)
    }

    @Test
    fun substitutionRoundRejectsTooManyPlayersAndRemovedPlayers() = runBlocking {
        val fixture = liveMatch()
        val lineup = dao.getLineupOnce(fixture.matchId)
        val everyoneOn = lineup.map { it.copy(onPitch = true) }

        assertFalse(dao.applySubstitutionRound(fixture.matchId, everyoneOn, KICK_OFF_MONOTONIC + 1_000, KICK_OFF_WALL + 1_000))

        val removed = fixture.playerIds[3]
        dao.updateSquadPlayerState(fixture.matchId, removed, PlayerMatchState.REMOVED)
        val plan = lineup.map {
            when (it.playerId) {
                fixture.playerIds[0] -> it.copy(onPitch = false)
                removed -> it.copy(onPitch = true)
                else -> it
            }
        }
        assertFalse(dao.applySubstitutionRound(fixture.matchId, plan, KICK_OFF_MONOTONIC + 2_000, KICK_OFF_WALL + 2_000))
        // Only the kick-off lineup picture exists; the rejected rounds wrote nothing.
        assertEquals(listOf("KICK_OFF"), dao.observeEvents(fixture.matchId).first().map { it.type })
    }

    @Test
    fun keeperSavesAndGoalPositionsAreStored() = runBlocking {
        val fixture = liveMatch()

        val saveId = dao.recordKeeperSave(fixture.matchId, fixture.playerIds[0], KICK_OFF_MONOTONIC + 5_000, KICK_OFF_WALL + 5_000)
        val goalId = dao.recordOpponentGoal(fixture.matchId, KICK_OFF_MONOTONIC + 9_000, KICK_OFF_WALL + 9_000)
        dao.setGoalPlacement(goalId!!, 0.1f, 0.2f)

        assertEquals("KEEPER_SAVE", dao.getEventOnce(saveId!!)!!.type)
        val goal = dao.getEventOnce(goalId)!!
        assertEquals(0.1f, goal.goalX!!, 0.0001f)
        assertEquals(0.2f, goal.goalY!!, 0.0001f)
        // Saves never change the score.
        assertEquals(0, dao.getMatchOnce(fixture.matchId)!!.ourScore)
        assertEquals(1, dao.getMatchOnce(fixture.matchId)!!.opponentScore)
    }

    @Test
    fun matchDetailsAndTeamLogoCanBeChangedAfterwards() = runBlocking {
        val fixture = liveMatch()

        dao.updateMatchDetails(fixture.matchId, "New rivals", "2026-10-04", "Park", "Cup", isHome = false)
        dao.updateTeamLogo(fixture.teamId, "iVBORw0KGgo=")

        val match = dao.getMatchOnce(fixture.matchId)!!
        assertEquals("New rivals", match.opponent)
        assertEquals("2026-10-04", match.matchDate)
        assertEquals("Park", match.venue)
        assertEquals("Cup", match.competition)
        assertFalse(match.isHome)
        assertEquals(MatchStatus.LIVE, match.status)
        assertEquals("iVBORw0KGgo=", dao.observeTeams().first().single().logoPng)
    }

    @Test
    fun kickOffStoresTheStartingLineup() = runBlocking {
        val fixture = liveMatch()

        val kickOff = dao.observeEvents(fixture.matchId).first().single { it.type == "KICK_OFF" }

        assertEquals(0L, kickOff.timestampMs)
        val starters = LineupSnapshot.decode(kickOff.lineupSnapshot).map { it.playerId }
        assertEquals(fixture.playerIds.take(2).sorted(), starters.sorted())
    }

    @Test
    fun undoingASubstitutionRoundRestoresLineupAndMinutes() = runBlocking {
        val fixture = liveMatch()
        val (starterA, starterB, benchC, _) = fixture.playerIds
        val before = dao.getLineupOnce(fixture.matchId).associateBy { it.playerId }
        val plan = before.values.map {
            when (it.playerId) {
                starterA -> it.copy(onPitch = false)
                benchC -> it.copy(onPitch = true, normalizedX = before.getValue(starterA).normalizedX,
                    normalizedY = before.getValue(starterA).normalizedY)
                else -> it
            }
        }
        assertTrue(dao.applySubstitutionRound(fixture.matchId, plan, KICK_OFF_MONOTONIC + 120_000, KICK_OFF_WALL + 120_000))

        assertTrue(dao.undoLatestLineupChange(fixture.matchId))

        val lineup = dao.getLineupOnce(fixture.matchId).associateBy { it.playerId }
        assertTrue(lineup.getValue(starterA).onPitch)
        assertTrue(lineup.getValue(starterB).onPitch)
        assertFalse(lineup.getValue(benchC).onPitch)
        assertEquals(PlayerMatchState.ON_PITCH, dao.getMatchSquadPlayer(fixture.matchId, starterA)!!.state)
        assertEquals(PlayerMatchState.BENCH, dao.getMatchSquadPlayer(fixture.matchId, benchC)!!.state)
        val participations = dao.observeParticipations(fixture.matchId).first()
        // Starter A keeps playing as if the round never happened; C never came on.
        assertNull(participations.single { it.playerId == starterA }.endMatchTimeMs)
        assertTrue(participations.none { it.playerId == benchC })
        assertEquals(listOf("KICK_OFF"), dao.observeEvents(fixture.matchId).first().map { it.type })
        // The starting lineup itself cannot be undone.
        assertFalse(dao.undoLatestLineupChange(fixture.matchId))
    }

    @Test
    fun aRedCardCanBeUndone() = runBlocking {
        val fixture = liveMatch()
        val sentOff = fixture.playerIds[0]
        dao.removePlayerFromPitch(fixture.matchId, sentOff, ParticipationReason.DISMISSAL, KICK_OFF_MONOTONIC + 60_000, KICK_OFF_WALL + 60_000)
        assertEquals(PlayerMatchState.DISMISSED, dao.getMatchSquadPlayer(fixture.matchId, sentOff)!!.state)

        assertTrue(dao.undoLatestLineupChange(fixture.matchId))

        assertEquals(PlayerMatchState.ON_PITCH, dao.getMatchSquadPlayer(fixture.matchId, sentOff)!!.state)
        assertTrue(dao.getLineupPlacementOnce(fixture.matchId, sentOff)!!.onPitch)
        assertNull(dao.observeParticipations(fixture.matchId).first().single { it.playerId == sentOff }.endMatchTimeMs)
    }

    @Test
    fun shotsCornersAndCardsAreCountedForBothTeams() = runBlocking {
        val fixture = liveMatch()
        val time = { seconds: Long -> KICK_OFF_MONOTONIC + seconds * 1_000 }
        dao.recordMatchAction(fixture.matchId, MatchActions.OUR_SHOT_OFF_TARGET, fixture.playerIds[0], time(10), KICK_OFF_WALL)
        dao.recordMatchAction(fixture.matchId, MatchActions.OUR_CORNER, null, time(20), KICK_OFF_WALL)
        dao.recordMatchAction(fixture.matchId, MatchActions.YELLOW_CARD, fixture.playerIds[1], time(30), KICK_OFF_WALL)
        dao.recordMatchAction(fixture.matchId, MatchActions.OPPONENT_CORNER, null, time(40), KICK_OFF_WALL)
        dao.recordKeeperSave(fixture.matchId, null, time(50), KICK_OFF_WALL)
        dao.recordOurGoal(fixture.matchId, fixture.playerIds[0], null, time(60), KICK_OFF_WALL)

        val stats = MatchStatsRules.compute(dao.observeEvents(fixture.matchId).first())

        assertEquals(2, stats.ours.shots) // the goal counts as a shot on target
        assertEquals(1, stats.ours.corners)
        assertEquals(1, stats.ours.yellowCards)
        assertEquals(1, stats.opponent.corners)
        assertEquals(1, stats.opponent.shotsOnTarget) // the save
        assertTrue(stats.hasDetail)
        // Actions never change the score.
        assertEquals(1, dao.getMatchOnce(fixture.matchId)!!.ourScore)
        assertEquals(0, dao.getMatchOnce(fixture.matchId)!!.opponentScore)
    }

    @Test
    fun opponentGoalTimeCanBeEditedWithoutAScorer() = runBlocking {
        val fixture = liveMatch()
        val goalId = dao.recordOpponentGoal(fixture.matchId, KICK_OFF_MONOTONIC + 90_000, KICK_OFF_WALL + 90_000)!!

        dao.updateOurGoal(goalId, fixture.playerIds[0], null, 60_000)

        val goal = dao.getEventOnce(goalId)!!
        assertEquals(60_000L, goal.timestampMs)
        assertNull(goal.playerId)
        assertEquals(1, dao.getMatchOnce(fixture.matchId)!!.opponentScore)
    }

    private companion object {
        const val KICK_OFF_MONOTONIC = 3_600_000L
        const val KICK_OFF_WALL = 1_790_000_000_000L
    }
}
