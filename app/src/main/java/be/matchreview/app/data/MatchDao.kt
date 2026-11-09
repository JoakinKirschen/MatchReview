package be.matchreview.app.data

import androidx.room.*
import be.matchreview.app.domain.FormationLayout
import be.matchreview.app.domain.LineupSnapshot
import be.matchreview.app.domain.LineupUndoRules
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.PracticeMatchRules
import be.matchreview.app.domain.StartingLineupRules
import be.matchreview.app.domain.SubstitutionPlanRules
import be.matchreview.app.domain.VideoEventRules
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {
    @Query("SELECT * FROM teams ORDER BY name")
    fun observeTeams(): Flow<List<Team>>

    @Insert suspend fun insertTeam(team: Team): Long
    @Update suspend fun updateTeam(team: Team)
    @Delete suspend fun deleteTeam(team: Team)

    @Query("UPDATE teams SET logoPng = :logoPng WHERE id = :teamId")
    suspend fun updateTeamLogo(teamId: Long, logoPng: String?)

    @Query("SELECT * FROM players WHERE teamId = :teamId AND archived = 0 ORDER BY shirtNumber, name")
    fun observePlayers(teamId: Long): Flow<List<Player>>

    @Query("SELECT * FROM players WHERE teamId = :teamId AND archived = 0 ORDER BY shirtNumber, name")
    suspend fun getPlayersForTeam(teamId: Long): List<Player>

    @Query("SELECT * FROM players ORDER BY name")
    fun observeAllPlayers(): Flow<List<Player>>

    @Insert suspend fun insertPlayer(player: Player): Long
    @Update suspend fun updatePlayer(player: Player)
    @Delete suspend fun deletePlayer(player: Player)

    @Query("""
        UPDATE players
        SET statMatchesAdjustment = :matches, statMinutesAdjustment = :minutes,
            statGoalsAdjustment = :goals, statAssistsAdjustment = :assists,
            statSavesAdjustment = :saves
        WHERE id = :playerId
    """)
    suspend fun updateStatAdjustments(
        playerId: Long,
        matches: Int,
        minutes: Int,
        goals: Int,
        assists: Int,
        saves: Int
    )

    @Query("UPDATE players SET archived = 1 WHERE id = :playerId")
    suspend fun markPlayerArchived(playerId: Long)

    @Query("""
        DELETE FROM match_lineup_placements
        WHERE playerId = :playerId
          AND matchId IN (SELECT id FROM matches WHERE status IN ('DRAFT', 'LINEUP_READY'))
    """)
    suspend fun deleteUpcomingLineupPlacementsForPlayer(playerId: Long)

    @Query("""
        DELETE FROM match_squad_players
        WHERE playerId = :playerId
          AND matchId IN (SELECT id FROM matches WHERE status IN ('DRAFT', 'LINEUP_READY'))
    """)
    suspend fun deleteUpcomingSquadEntriesForPlayer(playerId: Long)

    @Query("UPDATE events SET relatedPlayerId = NULL WHERE relatedPlayerId = :playerId")
    suspend fun clearRelatedPlayer(playerId: Long)

    /** Hides the player from the team and future squads while keeping match history. */
    @Transaction
    suspend fun archivePlayer(playerId: Long) {
        markPlayerArchived(playerId)
        deleteUpcomingLineupPlacementsForPlayer(playerId)
        deleteUpcomingSquadEntriesForPlayer(playerId)
    }

    /** Erases the player, their squad, lineup and minutes history, and unlinks their events. */
    @Transaction
    suspend fun deletePlayerPermanently(player: Player) {
        clearRelatedPlayer(player.id)
        deletePlayer(player)
    }

    @Query("SELECT * FROM matches ORDER BY matchDate DESC, id DESC")
    fun observeMatches(): Flow<List<GameMatch>>

    @Query("SELECT * FROM matches WHERE id = :id")
    fun observeMatch(id: Long): Flow<GameMatch?>

    @Query("""SELECT * FROM matches
        WHERE status IN ('LINEUP_READY', 'LIVE', 'PAUSED', 'PERIOD_ENDED')
        ORDER BY CASE status
            WHEN 'LIVE' THEN 0
            WHEN 'PAUSED' THEN 1
            WHEN 'PERIOD_ENDED' THEN 2
            ELSE 3
        END, id DESC
        LIMIT 1""")
    fun observeActiveMatch(): Flow<GameMatch?>

    @Insert suspend fun insertMatch(match: GameMatch): Long
    @Update suspend fun updateMatch(match: GameMatch)
    @Delete suspend fun deleteMatch(match: GameMatch)

    @Query("UPDATE matches SET teamRating = :rating, reviewNotes = :notes WHERE id = :matchId")
    suspend fun updateReview(matchId: Long, rating: Int, notes: String)

    @Query("""
        UPDATE matches
        SET opponent = :opponent, matchDate = :matchDate, venue = :venue,
            competition = :competition, isHome = :isHome
        WHERE id = :matchId
    """)
    suspend fun updateMatchDetails(
        matchId: Long,
        opponent: String,
        matchDate: String,
        venue: String,
        competition: String,
        isHome: Boolean
    )

    @Query("UPDATE matches SET videoUri = :videoUri WHERE id = :matchId")
    suspend fun setVideoUri(matchId: Long, videoUri: String?)

    @Query("UPDATE matches SET status = 'LINEUP_READY' WHERE id = :matchId AND status IN ('DRAFT', 'LINEUP_READY')")
    suspend fun markLineupReady(matchId: Long)

    @Query("""
        SELECT uri FROM recording_segments
        WHERE uri IS NOT NULL AND matchId IN (SELECT id FROM matches WHERE teamId = :teamId)
    """)
    suspend fun getRecordingUrisForTeam(teamId: Long): List<String>

    @Query("SELECT videoUri FROM matches WHERE teamId = :teamId AND videoUri IS NOT NULL")
    suspend fun getImportedVideoUrisForTeam(teamId: Long): List<String>

    @Query("SELECT * FROM events WHERE matchId = :matchId ORDER BY timestampMs, id")
    fun observeEvents(matchId: Long): Flow<List<MatchEvent>>

    @Insert suspend fun insertEvent(event: MatchEvent): Long
    @Update suspend fun updateEvent(event: MatchEvent)
    @Delete suspend fun deleteEvent(event: MatchEvent)

    @Query("SELECT * FROM events WHERE id = :eventId LIMIT 1")
    suspend fun getEventOnce(eventId: Long): MatchEvent?

    @Query("SELECT * FROM events WHERE matchId = :matchId ORDER BY timestampMs, id")
    suspend fun getEventsOnce(matchId: Long): List<MatchEvent>

    @Query("SELECT * FROM player_participations WHERE matchId = :matchId ORDER BY id")
    suspend fun getParticipationsOnce(matchId: Long): List<PlayerParticipation>

    @Query("DELETE FROM player_participations WHERE id = :participationId")
    suspend fun deleteParticipationById(participationId: Long)

    @Query("""
        SELECT * FROM events
        WHERE matchId = :matchId AND type IN ('OUR_GOAL', 'OPPONENT_GOAL')
        ORDER BY timestampMs DESC, id DESC
    """)
    suspend fun getScoringEventsNewestFirst(matchId: Long): List<MatchEvent>

    @Query("UPDATE events SET goalX = :goalX, goalY = :goalY WHERE id = :eventId")
    suspend fun setGoalPlacement(eventId: Long, goalX: Float?, goalY: Float?)

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEventById(eventId: Long)

    /** Deletes any timeline event; goals also update the event-derived score. */
    @Transaction
    suspend fun deleteTimelineEvent(eventId: Long) {
        val event = getEventOnce(eventId) ?: return
        deleteEventById(eventId)
        if (event.type in listOf("OUR_GOAL", "OPPONENT_GOAL")) {
            refreshScoreFromEvents(event.matchId)
        }
    }

    @Query("""
        UPDATE matches
        SET ourScore = (
                SELECT COUNT(*) FROM events
                WHERE events.matchId = :matchId AND events.type = 'OUR_GOAL'
            ),
            opponentScore = (
                SELECT COUNT(*) FROM events
                WHERE events.matchId = :matchId AND events.type = 'OPPONENT_GOAL'
            )
        WHERE id = :matchId
    """)
    suspend fun refreshScoreFromEvents(matchId: Long)

    @Query("SELECT * FROM match_squad_players WHERE matchId = :matchId")
    fun observeMatchSquad(matchId: Long): Flow<List<MatchSquadPlayer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMatchSquadPlayer(player: MatchSquadPlayer)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMatchSquadPlayers(players: List<MatchSquadPlayer>)

    @Query("SELECT * FROM match_squad_players WHERE matchId = :matchId")
    suspend fun getMatchSquadOnce(matchId: Long): List<MatchSquadPlayer>

    @Query("SELECT * FROM match_squad_players WHERE matchId = :matchId AND playerId = :playerId LIMIT 1")
    suspend fun getMatchSquadPlayer(matchId: Long, playerId: Long): MatchSquadPlayer?

    @Query("""
        UPDATE match_squad_players
        SET selected = CASE WHEN availability = 'AVAILABLE' THEN 1 ELSE 0 END,
            state = CASE WHEN availability = 'AVAILABLE' THEN 'BENCH' ELSE 'UNAVAILABLE' END
        WHERE matchId = :matchId
    """)
    suspend fun selectAllAvailable(matchId: Long)

    @Query("""
        UPDATE match_squad_players
        SET availability = 'AVAILABLE', selected = 1, state = 'BENCH'
        WHERE matchId = :matchId
    """)
    suspend fun markAndSelectAllAvailable(matchId: Long)

    @Query("""
        UPDATE match_squad_players
        SET selected = 0, state = 'UNAVAILABLE'
        WHERE matchId = :matchId
    """)
    suspend fun clearSquadSelection(matchId: Long)

    @Query("SELECT * FROM match_lineup_placements WHERE matchId = :matchId")
    fun observeLineup(matchId: Long): Flow<List<MatchLineupPlacement>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLineupPlacement(placement: MatchLineupPlacement)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLineupPlacements(placements: List<MatchLineupPlacement>)

    @Query("SELECT * FROM match_lineup_placements WHERE matchId = :matchId")
    suspend fun getLineupOnce(matchId: Long): List<MatchLineupPlacement>

    @Query("SELECT * FROM match_lineup_placements WHERE matchId = :matchId AND playerId = :playerId LIMIT 1")
    suspend fun getLineupPlacementOnce(matchId: Long, playerId: Long): MatchLineupPlacement?


    @Query("""
        DELETE FROM match_lineup_placements
        WHERE matchId = :matchId
          AND playerId NOT IN (
              SELECT playerId FROM match_squad_players
              WHERE matchId = :matchId AND selected = 1
          )
    """)
    suspend fun deleteUnselectedLineupPlacements(matchId: Long)

    @Query("UPDATE match_squad_players SET state = :state WHERE matchId = :matchId AND playerId = :playerId")
    suspend fun updateSquadPlayerState(matchId: Long, playerId: Long, state: PlayerMatchState)

    @Query("UPDATE match_squad_players SET state = 'BENCH' WHERE matchId = :matchId AND selected = 1")
    suspend fun resetSelectedPlayersToBench(matchId: Long)

    @Transaction
    suspend fun saveLineupPlacement(placement: MatchLineupPlacement) {
        upsertLineupPlacement(placement)
        updateSquadPlayerState(
            placement.matchId,
            placement.playerId,
            if (placement.onPitch) PlayerMatchState.ON_PITCH else PlayerMatchState.BENCH
        )
    }

    @Transaction
    suspend fun swapLineupPlacements(
        first: MatchLineupPlacement,
        second: MatchLineupPlacement
    ) {
        saveLineupPlacement(first)
        saveLineupPlacement(second)
    }

    @Transaction
    suspend fun replaceStartingLineup(matchId: Long, placements: List<MatchLineupPlacement>) {
        resetSelectedPlayersToBench(matchId)
        upsertLineupPlacements(placements)
        placements.filter { it.onPitch }.forEach {
            updateSquadPlayerState(matchId, it.playerId, PlayerMatchState.ON_PITCH)
        }
    }

    @Query("SELECT * FROM match_periods WHERE matchId = :matchId ORDER BY periodNumber")
    fun observePeriods(matchId: Long): Flow<List<MatchPeriod>>

    @Insert suspend fun insertPeriod(period: MatchPeriod): Long
    @Update suspend fun updatePeriod(period: MatchPeriod)

    @Query("SELECT * FROM match_clock_segments WHERE matchId = :matchId ORDER BY id")
    fun observeClockSegments(matchId: Long): Flow<List<MatchClockSegment>>

    @Insert suspend fun insertClockSegment(segment: MatchClockSegment): Long
    @Update suspend fun updateClockSegment(segment: MatchClockSegment)

    @Query("""
        SELECT pp.* FROM player_participations pp
        INNER JOIN matches m ON m.id = pp.matchId
        WHERE m.teamId = :teamId
    """)
    fun observeTeamParticipations(teamId: Long): Flow<List<PlayerParticipation>>

    @Query("""
        SELECT e.* FROM events e
        INNER JOIN matches m ON m.id = e.matchId
        WHERE m.teamId = :teamId
          AND e.type IN ('OUR_GOAL', 'KEEPER_SAVE', 'YELLOW_CARD', 'RED_CARD', 'DISMISSAL')
    """)
    fun observeTeamStatEvents(teamId: Long): Flow<List<MatchEvent>>

    @Query("SELECT * FROM player_participations WHERE matchId = :matchId ORDER BY startMatchTimeMs, id")
    fun observeParticipations(matchId: Long): Flow<List<PlayerParticipation>>

    @Insert suspend fun insertParticipation(participation: PlayerParticipation): Long
    @Update suspend fun updateParticipation(participation: PlayerParticipation)

    @Query("SELECT * FROM recording_segments WHERE matchId = :matchId ORDER BY id")
    fun observeRecordings(matchId: Long): Flow<List<RecordingSegment>>

    @Query("SELECT * FROM recording_segments WHERE matchId = :matchId ORDER BY id")
    suspend fun getRecordingsOnce(matchId: Long): List<RecordingSegment>

    @Query("SELECT * FROM events WHERE matchId = :matchId AND recordingSegmentId IS NULL ORDER BY timestampMs, id")
    suspend fun getUnlinkedEventsOnce(matchId: Long): List<MatchEvent>

    @Transaction
    suspend fun insertEventWithVideoLink(event: MatchEvent): Long {
        val link = VideoEventRules.link(event, getRecordingsOnce(event.matchId))
        return insertEvent(
            event.copy(
                recordingSegmentId = link?.recordingSegmentId,
                recordingOffsetMs = link?.recordingOffsetMs
            )
        )
    }

    @Transaction
    suspend fun updateEventWithVideoLink(event: MatchEvent) {
        val link = VideoEventRules.link(event, getRecordingsOnce(event.matchId))
        updateEvent(
            event.copy(
                recordingSegmentId = link?.recordingSegmentId,
                recordingOffsetMs = link?.recordingOffsetMs
            )
        )
    }

    @Transaction
    suspend fun mapUnlinkedEvents(matchId: Long) {
        val recordings = getRecordingsOnce(matchId)
        getUnlinkedEventsOnce(matchId).forEach { event ->
            val link = VideoEventRules.link(event, recordings)
            if (link != null) {
                updateEvent(
                    event.copy(
                        recordingSegmentId = link.recordingSegmentId,
                        recordingOffsetMs = link.recordingOffsetMs
                    )
                )
            }
        }
    }

    @Insert suspend fun insertRecording(recording: RecordingSegment): Long
    @Update suspend fun updateRecording(recording: RecordingSegment)

    @Query("SELECT * FROM recording_segments WHERE id = :recordingId LIMIT 1")
    suspend fun getRecordingOnce(recordingId: Long): RecordingSegment?

    @Query("""
        SELECT * FROM recording_segments
        WHERE matchId = :matchId AND status IN ('PREPARING', 'RECORDING')
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun getOpenRecording(matchId: Long): RecordingSegment?

    @Query("""
        UPDATE recording_segments
        SET status = 'INTERRUPTED',
            matchClockEndMs = COALESCE(matchClockEndMs, matchClockStartMs + recordingDurationMs),
            errorMessage = COALESCE(errorMessage, :message)
        WHERE status IN ('PREPARING', 'RECORDING')
    """)
    suspend fun markOpenRecordingsInterrupted(message: String)

    @Query("SELECT * FROM matches WHERE id = :matchId LIMIT 1")
    suspend fun getMatchOnce(matchId: Long): GameMatch?

    @Query("SELECT * FROM match_periods WHERE matchId = :matchId AND periodNumber = :periodNumber LIMIT 1")
    suspend fun getPeriodOnce(matchId: Long, periodNumber: Int): MatchPeriod?

    @Query("SELECT * FROM match_clock_segments WHERE matchId = :matchId AND monotonicEndMs IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun getOpenClockSegment(matchId: Long): MatchClockSegment?

    @Query("SELECT * FROM player_participations WHERE matchId = :matchId AND endMatchTimeMs IS NULL")
    suspend fun getOpenParticipations(matchId: Long): List<PlayerParticipation>

    @Query("""
        SELECT * FROM player_participations
        WHERE matchId = :matchId AND playerId = :playerId AND endMatchTimeMs IS NULL
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun getOpenParticipationForPlayer(matchId: Long, playerId: Long): PlayerParticipation?


    @Insert
    suspend fun insertParticipations(participations: List<PlayerParticipation>)

    @Query("""
        UPDATE player_participations
        SET endMatchTimeMs = :endMatchTimeMs, exitReason = :reason
        WHERE matchId = :matchId AND endMatchTimeMs IS NULL
    """)
    suspend fun closeOpenParticipations(
        matchId: Long,
        endMatchTimeMs: Long,
        reason: ParticipationReason
    )

    @Transaction
    suspend fun kickOffMatch(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (match.status != MatchStatus.LINEUP_READY || match.currentPeriod != 0) return
        val periodId = insertPeriod(
            MatchPeriod(
                matchId = matchId,
                periodNumber = 1,
                plannedDurationMs = match.periodDurationMinutes * 60_000L,
                status = PeriodStatus.LIVE,
                startMatchTimeMs = match.accumulatedMatchTimeMs
            )
        )
        insertClockSegment(
            MatchClockSegment(
                matchId = matchId,
                periodId = periodId,
                startMatchTimeMs = match.accumulatedMatchTimeMs,
                monotonicStartMs = monotonicNowMs,
                wallClockStartMs = wallClockNowMs
            )
        )
        val lineup = getLineupOnce(matchId)
        val starters = lineup.filter { it.onPitch }
        getMatchSquadOnce(matchId).filter { it.selected }.forEach { squadPlayer ->
            upsertMatchSquadPlayer(
                squadPlayer.copy(
                    state = if (starters.any { it.playerId == squadPlayer.playerId })
                        PlayerMatchState.ON_PITCH
                    else PlayerMatchState.BENCH
                )
            )
        }
        if (starters.isNotEmpty()) {
            insertParticipations(starters.map {
                PlayerParticipation(
                    matchId = matchId,
                    periodId = periodId,
                    playerId = it.playerId,
                    startMatchTimeMs = match.accumulatedMatchTimeMs,
                    entryReason = ParticipationReason.STARTER
                )
            })
            // Keep a picture of the starting positions; later lineup changes only store
            // the situation after each change.
            insertEventWithVideoLink(
                MatchEvent(
                    matchId = matchId,
                    timestampMs = match.accumulatedMatchTimeMs,
                    type = StartingLineupRules.KICK_OFF,
                    note = "Starting lineup",
                    periodNumber = 1,
                    occurredAtEpochMs = wallClockNowMs,
                    lineupSnapshot = LineupSnapshot.encode(starters)
                )
            )
        }
        updateMatch(
            match.copy(
                status = MatchStatus.LIVE,
                currentPeriod = 1,
                clockRunning = true
            )
        )
    }

    @Transaction
    suspend fun resumeMatchClock(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (match.status != MatchStatus.PAUSED || match.clockRunning) return
        val period = getPeriodOnce(matchId, match.currentPeriod) ?: return
        insertClockSegment(
            MatchClockSegment(
                matchId = matchId,
                periodId = period.id,
                startMatchTimeMs = match.accumulatedMatchTimeMs,
                monotonicStartMs = monotonicNowMs,
                wallClockStartMs = wallClockNowMs
            )
        )
        updatePeriod(period.copy(status = PeriodStatus.LIVE))
        updateMatch(match.copy(status = MatchStatus.LIVE, clockRunning = true))
    }

    @Transaction
    suspend fun pauseMatchClock(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long,
        reason: StopReason = StopReason.PAUSE
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (!match.clockRunning) return
        val segment = getOpenClockSegment(matchId) ?: return
        val currentTime = segmentMatchTime(segment, monotonicNowMs, wallClockNowMs)
        updateClockSegment(
            segment.copy(
                monotonicEndMs = monotonicNowMs,
                wallClockEndMs = wallClockNowMs,
                stopReason = reason
            )
        )
        getPeriodOnce(matchId, match.currentPeriod)?.let {
            updatePeriod(it.copy(status = PeriodStatus.PAUSED))
        }
        updateMatch(
            match.copy(
                status = MatchStatus.PAUSED,
                accumulatedMatchTimeMs = currentTime,
                clockRunning = false
            )
        )
    }

    @Transaction
    suspend fun endCurrentPeriod(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        var match = getMatchOnce(matchId) ?: return
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED)) return
        var currentTime = match.accumulatedMatchTimeMs
        if (match.clockRunning) {
            val segment = getOpenClockSegment(matchId)
            if (segment != null) {
                currentTime = segmentMatchTime(segment, monotonicNowMs, wallClockNowMs)
                updateClockSegment(
                    segment.copy(
                        monotonicEndMs = monotonicNowMs,
                        wallClockEndMs = wallClockNowMs,
                        stopReason = StopReason.PERIOD_END
                    )
                )
            }
        }
        getPeriodOnce(matchId, match.currentPeriod)?.let {
            updatePeriod(
                it.copy(
                    status = PeriodStatus.ENDED,
                    endMatchTimeMs = currentTime
                )
            )
        }
        closeOpenParticipations(matchId, currentTime, ParticipationReason.PERIOD_END)
        match = match.copy(
            status = MatchStatus.PERIOD_ENDED,
            accumulatedMatchTimeMs = currentTime,
            clockRunning = false
        )
        updateMatch(match)
    }

    @Transaction
    suspend fun startNextPeriod(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (match.status != MatchStatus.PERIOD_ENDED || match.currentPeriod >= match.periodCount) return
        val nextNumber = match.currentPeriod + 1
        val periodId = insertPeriod(
            MatchPeriod(
                matchId = matchId,
                periodNumber = nextNumber,
                plannedDurationMs = match.periodDurationMinutes * 60_000L,
                status = PeriodStatus.LIVE,
                startMatchTimeMs = match.accumulatedMatchTimeMs
            )
        )
        insertClockSegment(
            MatchClockSegment(
                matchId = matchId,
                periodId = periodId,
                startMatchTimeMs = match.accumulatedMatchTimeMs,
                monotonicStartMs = monotonicNowMs,
                wallClockStartMs = wallClockNowMs
            )
        )
        val onPitch = getLineupOnce(matchId).filter { it.onPitch }
        getMatchSquadOnce(matchId).filter { it.selected }.forEach { squadPlayer ->
            if (squadPlayer.state != PlayerMatchState.DISMISSED &&
                squadPlayer.state != PlayerMatchState.REMOVED
            ) {
                upsertMatchSquadPlayer(
                    squadPlayer.copy(
                        state = if (onPitch.any { it.playerId == squadPlayer.playerId })
                            PlayerMatchState.ON_PITCH
                        else PlayerMatchState.BENCH
                    )
                )
            }
        }
        if (onPitch.isNotEmpty()) {
            insertParticipations(onPitch.map {
                PlayerParticipation(
                    matchId = matchId,
                    periodId = periodId,
                    playerId = it.playerId,
                    startMatchTimeMs = match.accumulatedMatchTimeMs,
                    entryReason = ParticipationReason.PERIOD_START
                )
            })
        }
        updateMatch(
            match.copy(
                status = MatchStatus.LIVE,
                currentPeriod = nextNumber,
                clockRunning = true
            )
        )
    }

    @Transaction
    suspend fun finishLiveMatch(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        var match = getMatchOnce(matchId) ?: return
        var currentTime = match.accumulatedMatchTimeMs
        if (match.clockRunning) {
            getOpenClockSegment(matchId)?.let { segment ->
                currentTime = segmentMatchTime(segment, monotonicNowMs, wallClockNowMs)
                updateClockSegment(
                    segment.copy(
                        monotonicEndMs = monotonicNowMs,
                        wallClockEndMs = wallClockNowMs,
                        stopReason = StopReason.MATCH_END
                    )
                )
            }
        }
        if (match.currentPeriod > 0) {
            getPeriodOnce(matchId, match.currentPeriod)?.let {
                updatePeriod(it.copy(status = PeriodStatus.ENDED, endMatchTimeMs = currentTime))
            }
        }
        closeOpenParticipations(matchId, currentTime, ParticipationReason.MATCH_END)
        match = match.copy(
            status = MatchStatus.FINISHED,
            accumulatedMatchTimeMs = currentTime,
            clockRunning = false
        )
        updateMatch(match)
    }


    @Transaction
    suspend fun substitutePlayer(
        matchId: Long,
        outgoingPlayerId: Long,
        incomingPlayerId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        if (outgoingPlayerId == incomingPlayerId) return
        val match = getMatchOnce(matchId) ?: return
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) return

        val outgoing = getLineupPlacementOnce(matchId, outgoingPlayerId) ?: return
        val incoming = getLineupPlacementOnce(matchId, incomingPlayerId) ?: return
        if (!outgoing.onPitch || incoming.onPitch) return

        val incomingSquad = getMatchSquadPlayer(matchId, incomingPlayerId) ?: return
        if (!incomingSquad.selected ||
            incomingSquad.state in listOf(
                PlayerMatchState.UNAVAILABLE,
                PlayerMatchState.REMOVED,
                PlayerMatchState.DISMISSED
            )
        ) return

        val outgoingSquad = getMatchSquadPlayer(matchId, outgoingPlayerId) ?: return
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )

        if (match.status in listOf(MatchStatus.LIVE, MatchStatus.PAUSED)) {
            getOpenParticipationForPlayer(matchId, outgoingPlayerId)?.let {
                updateParticipation(
                    it.copy(
                        endMatchTimeMs = currentTime,
                        exitReason = ParticipationReason.SUBSTITUTION
                    )
                )
            }
            if (getOpenParticipationForPlayer(matchId, incomingPlayerId) == null) {
                val period = getPeriodOnce(matchId, match.currentPeriod) ?: return
                insertParticipation(
                    PlayerParticipation(
                        matchId = matchId,
                        periodId = period.id,
                        playerId = incomingPlayerId,
                        startMatchTimeMs = currentTime,
                        entryReason = ParticipationReason.SUBSTITUTION
                    )
                )
            }
        }

        upsertLineupPlacement(outgoing.copy(onPitch = false))
        upsertLineupPlacement(
            incoming.copy(
                normalizedX = outgoing.normalizedX,
                normalizedY = outgoing.normalizedY,
                role = outgoing.role,
                formationSlot = outgoing.formationSlot,
                onPitch = true
            )
        )
        upsertMatchSquadPlayer(
            outgoingSquad.copy(
                state = if (match.rollingSubstitutions)
                    PlayerMatchState.BENCH
                else PlayerMatchState.REMOVED
            )
        )
        upsertMatchSquadPlayer(incomingSquad.copy(state = PlayerMatchState.ON_PITCH))
        insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = outgoingPlayerId,
                relatedPlayerId = incomingPlayerId,
                timestampMs = currentTime,
                type = "SUBSTITUTION",
                note = "Player off / player on",
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs,
                lineupSnapshot = LineupSnapshot.encode(getLineupOnce(matchId))
            )
        )
    }

    /**
     * Applies a whole substitution-mode round at one match time: everyone who left the
     * pitch stops, every substitute starts, moved players keep playing in their new
     * place, and one event per change carries a picture of the resulting lineup.
     * Returns false when the plan is not allowed and nothing was changed.
     */
    @Transaction
    suspend fun applySubstitutionRound(
        matchId: Long,
        planned: List<MatchLineupPlacement>,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ): Boolean {
        val match = getMatchOnce(matchId) ?: return false
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) return false
        val original = getLineupOnce(matchId)
        val originalIds = original.mapTo(mutableSetOf()) { it.playerId }
        val plan = SubstitutionPlanRules.planOf(planned.filter { it.matchId == matchId && it.playerId in originalIds })
        if (plan.values.count { it.onPitch } > match.playersOnPitch) return false
        val changes = SubstitutionPlanRules.changes(original, plan)
        if (changes.isEmpty) return true

        val squad = getMatchSquadOnce(matchId).associateBy { it.playerId }
        val blocked = setOf(PlayerMatchState.UNAVAILABLE, PlayerMatchState.REMOVED, PlayerMatchState.DISMISSED)
        if (changes.incoming.any { id -> squad[id]?.let { !it.selected || it.state in blocked } != false }) {
            return false
        }

        val currentTime = liveActionTime(match, getOpenClockSegment(matchId), monotonicNowMs, wallClockNowMs)
        val countsMinutes = match.status in listOf(MatchStatus.LIVE, MatchStatus.PAUSED)
        val period = if (countsMinutes) getPeriodOnce(matchId, match.currentPeriod) ?: return false else null

        changes.outgoing.forEach { playerId ->
            if (countsMinutes) {
                getOpenParticipationForPlayer(matchId, playerId)?.let {
                    updateParticipation(
                        it.copy(endMatchTimeMs = currentTime, exitReason = ParticipationReason.SUBSTITUTION)
                    )
                }
            }
            squad[playerId]?.let {
                upsertMatchSquadPlayer(
                    it.copy(
                        state = if (match.rollingSubstitutions) PlayerMatchState.BENCH
                        else PlayerMatchState.REMOVED
                    )
                )
            }
        }
        changes.incoming.forEach { playerId ->
            if (period != null && getOpenParticipationForPlayer(matchId, playerId) == null) {
                insertParticipation(
                    PlayerParticipation(
                        matchId = matchId,
                        periodId = period.id,
                        playerId = playerId,
                        startMatchTimeMs = currentTime,
                        entryReason = ParticipationReason.SUBSTITUTION
                    )
                )
            }
            squad[playerId]?.let { upsertMatchSquadPlayer(it.copy(state = PlayerMatchState.ON_PITCH)) }
        }
        val changedIds = changes.outgoing + changes.incoming + changes.moved
        upsertLineupPlacements(plan.values.filter { it.playerId in changedIds }.map {
            it.copy(
                normalizedX = it.normalizedX.coerceIn(0f, 1f),
                normalizedY = it.normalizedY.coerceIn(0f, 1f)
            )
        })

        val snapshot = LineupSnapshot.encode(plan.values.toList())
        fun lineupEvent(type: String, playerId: Long?, relatedPlayerId: Long?, note: String) =
            MatchEvent(
                matchId = matchId,
                playerId = playerId,
                relatedPlayerId = relatedPlayerId,
                timestampMs = currentTime,
                type = type,
                note = note,
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs,
                lineupSnapshot = snapshot
            )
        if (changes.hasSubstitutions) {
            SubstitutionPlanRules.pairs(original, plan).forEach { pair ->
                insertEventWithVideoLink(
                    when {
                        pair.outgoingId != null && pair.incomingId != null ->
                            lineupEvent("SUBSTITUTION", pair.outgoingId, pair.incomingId, "Player off / player on")
                        pair.outgoingId != null ->
                            lineupEvent("PLAYER_OFF", pair.outgoingId, null, "Moved to the bench")
                        else ->
                            lineupEvent("PLAYER_ON", pair.incomingId, null, "Entered without a replacement")
                    }
                )
            }
        } else {
            insertEventWithVideoLink(lineupEvent("POSITION_CHANGE", null, null, "Positions changed"))
        }
        return true
    }

    /**
     * Undoes the latest lineup change of the current period, as if it never happened: the
     * previous lineup returns, substitutes lose the minutes since the change, the players
     * they replaced keep playing, and the change's events are removed.
     * Returns false when there is no change that can still be undone.
     */
    @Transaction
    suspend fun undoLatestLineupChange(matchId: Long): Boolean {
        val match = getMatchOnce(matchId) ?: return false
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) return false
        val lineup = getLineupOnce(matchId)
        val plan = LineupUndoRules.plan(getEventsOnce(matchId), lineup) ?: return false
        if (plan.periodNumber != match.currentPeriod) return false
        val countsMinutes = match.status in listOf(MatchStatus.LIVE, MatchStatus.PAUSED)
        if (!countsMinutes) {
            // During an interval only changes made after the period ended can be undone; earlier
            // ones were already closed off by the period end.
            val periodEnd = getPeriodOnce(matchId, match.currentPeriod)?.endMatchTimeMs
            if (periodEnd != null && plan.roundTimeMs < periodEnd) return false
        }

        val before = plan.before.associateBy { it.playerId }
        val slots = FormationLayout.slots(match.formation, match.playersOnPitch)
        upsertLineupPlacements(lineup.mapNotNull { placement ->
            val position = before[placement.playerId]
            when {
                position != null -> {
                    val slot = slots.firstOrNull {
                        kotlin.math.abs(it.normalizedX - position.normalizedX) < 0.002f &&
                            kotlin.math.abs(it.normalizedY - position.normalizedY) < 0.002f
                    }
                    placement.copy(
                        normalizedX = position.normalizedX,
                        normalizedY = position.normalizedY,
                        formationSlot = slot?.id ?: "",
                        role = slot?.label ?: placement.role,
                        onPitch = true
                    )
                }
                placement.onPitch -> placement.copy(onPitch = false, formationSlot = "", role = "")
                else -> null
            }
        })
        getMatchSquadOnce(matchId).filter { it.selected }.forEach { squadPlayer ->
            val nowOnPitch = squadPlayer.playerId in before
            val wasOnPitch = squadPlayer.state == PlayerMatchState.ON_PITCH
            val leftInRound = plan.roundEvents.any { it.playerId == squadPlayer.playerId }
            when {
                nowOnPitch && squadPlayer.state != PlayerMatchState.ON_PITCH ->
                    upsertMatchSquadPlayer(squadPlayer.copy(state = PlayerMatchState.ON_PITCH))
                !nowOnPitch && (wasOnPitch || leftInRound) && squadPlayer.state != PlayerMatchState.BENCH ->
                    upsertMatchSquadPlayer(squadPlayer.copy(state = PlayerMatchState.BENCH))
            }
        }
        if (countsMinutes) {
            val participations = getParticipationsOnce(matchId)
            val exitReasons = setOf(
                ParticipationReason.SUBSTITUTION,
                ParticipationReason.BENCH,
                ParticipationReason.INJURY,
                ParticipationReason.DISMISSAL
            )
            participations
                .filter {
                    it.startMatchTimeMs == plan.roundTimeMs &&
                        it.entryReason == ParticipationReason.SUBSTITUTION &&
                        it.playerId !in before
                }
                .forEach { deleteParticipationById(it.id) }
            participations
                .filter {
                    it.endMatchTimeMs == plan.roundTimeMs &&
                        it.exitReason in exitReasons &&
                        it.playerId in before &&
                        participations.none { other -> other.playerId == it.playerId && other.endMatchTimeMs == null }
                }
                .forEach { updateParticipation(it.copy(endMatchTimeMs = null, exitReason = null)) }
        }
        plan.roundEvents.forEach { deleteEventById(it.id) }
        return true
    }

    @Transaction
    suspend fun removePlayerFromPitch(
        matchId: Long,
        playerId: Long,
        reason: ParticipationReason,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) return
        val placement = getLineupPlacementOnce(matchId, playerId) ?: return
        if (!placement.onPitch) return
        val squadPlayer = getMatchSquadPlayer(matchId, playerId) ?: return
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )

        getOpenParticipationForPlayer(matchId, playerId)?.let {
            updateParticipation(
                it.copy(
                    endMatchTimeMs = currentTime,
                    exitReason = reason
                )
            )
        }
        upsertLineupPlacement(placement.copy(onPitch = false))
        val newState = when (reason) {
            ParticipationReason.DISMISSAL -> PlayerMatchState.DISMISSED
            ParticipationReason.INJURY -> PlayerMatchState.REMOVED
            else -> if (match.rollingSubstitutions)
                PlayerMatchState.BENCH
            else
                PlayerMatchState.REMOVED
        }
        upsertMatchSquadPlayer(squadPlayer.copy(state = newState))
        insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = playerId,
                timestampMs = currentTime,
                type = when (reason) {
                    ParticipationReason.DISMISSAL -> "DISMISSAL"
                    ParticipationReason.INJURY -> "INJURY_OFF"
                    else -> "PLAYER_OFF"
                },
                note = reason.name.lowercase().replace('_', ' '),
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs,
                lineupSnapshot = LineupSnapshot.encode(getLineupOnce(matchId))
            )
        )
    }

    @Transaction
    suspend fun putPlayerOnPitch(
        matchId: Long,
        playerId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        if (match.status !in listOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) return
        val lineup = getLineupOnce(matchId)
        if (lineup.count { it.onPitch } >= match.playersOnPitch) return
        val placement = lineup.firstOrNull { it.playerId == playerId } ?: return
        if (placement.onPitch) return
        val squadPlayer = getMatchSquadPlayer(matchId, playerId) ?: return
        if (!squadPlayer.selected ||
            squadPlayer.state in listOf(
                PlayerMatchState.UNAVAILABLE,
                PlayerMatchState.REMOVED,
                PlayerMatchState.DISMISSED
            )
        ) return

        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        if (match.status in listOf(MatchStatus.LIVE, MatchStatus.PAUSED) &&
            getOpenParticipationForPlayer(matchId, playerId) == null
        ) {
            val period = getPeriodOnce(matchId, match.currentPeriod) ?: return
            insertParticipation(
                PlayerParticipation(
                    matchId = matchId,
                    periodId = period.id,
                    playerId = playerId,
                    startMatchTimeMs = currentTime,
                    entryReason = ParticipationReason.SUBSTITUTION
                )
            )
        }
        upsertLineupPlacement(
            placement.copy(
                normalizedX = placement.normalizedX.coerceIn(0.15f, 0.85f),
                normalizedY = placement.normalizedY.coerceIn(0.15f, 0.85f),
                onPitch = true
            )
        )
        upsertMatchSquadPlayer(squadPlayer.copy(state = PlayerMatchState.ON_PITCH))
        insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = playerId,
                timestampMs = currentTime,
                type = "PLAYER_ON",
                note = "Entered without a replacement",
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs,
                lineupSnapshot = LineupSnapshot.encode(getLineupOnce(matchId))
            )
        )
    }


    @Transaction
    suspend fun recordOurGoal(
        matchId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ): Long? {
        val match = getMatchOnce(matchId) ?: return null
        if (match.status !in listOf(
                MatchStatus.LIVE,
                MatchStatus.PAUSED,
                MatchStatus.PERIOD_ENDED
            )
        ) return null
        if (scorerPlayerId != null && scorerPlayerId == assistPlayerId) return null

        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        val eventId = insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = scorerPlayerId,
                relatedPlayerId = assistPlayerId,
                timestampMs = currentTime,
                type = "OUR_GOAL",
                note = if (scorerPlayerId == null) "Scorer not assigned" else "",
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs
            )
        )
        refreshScoreFromEvents(matchId)
        return eventId
    }

    @Transaction
    suspend fun recordOpponentGoal(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ): Long? {
        val match = getMatchOnce(matchId) ?: return null
        if (match.status !in listOf(
                MatchStatus.LIVE,
                MatchStatus.PAUSED,
                MatchStatus.PERIOD_ENDED
            )
        ) return null
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        val eventId = insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                timestampMs = currentTime,
                type = "OPPONENT_GOAL",
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs
            )
        )
        refreshScoreFromEvents(matchId)
        return eventId
    }

    /** Records a quick match action (shot, corner or card) at the current match time. */
    @Transaction
    suspend fun recordMatchAction(
        matchId: Long,
        type: String,
        playerId: Long?,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ): Long? {
        val match = getMatchOnce(matchId) ?: return null
        if (match.status !in listOf(
                MatchStatus.LIVE,
                MatchStatus.PAUSED,
                MatchStatus.PERIOD_ENDED
            )
        ) return null
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        return insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = playerId,
                timestampMs = currentTime,
                type = type,
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs
            )
        )
    }

    @Transaction
    suspend fun recordKeeperSave(
        matchId: Long,
        keeperPlayerId: Long?,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ): Long? {
        val match = getMatchOnce(matchId) ?: return null
        if (match.status !in listOf(
                MatchStatus.LIVE,
                MatchStatus.PAUSED,
                MatchStatus.PERIOD_ENDED
            )
        ) return null
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        return insertEventWithVideoLink(
            MatchEvent(
                matchId = matchId,
                playerId = keeperPlayerId,
                timestampMs = currentTime,
                type = "KEEPER_SAVE",
                sentiment = "Positive",
                periodNumber = match.currentPeriod,
                occurredAtEpochMs = wallClockNowMs
            )
        )
    }

    @Transaction
    suspend fun updateOurGoal(
        eventId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        correctedTimestampMs: Long
    ) {
        val event = getEventOnce(eventId) ?: return
        if (event.type != "OUR_GOAL" && event.type != "OPPONENT_GOAL") return
        // Opponent goals have no scorer or assist; only their time changes.
        val ours = event.type == "OUR_GOAL"
        if (ours && scorerPlayerId != null && scorerPlayerId == assistPlayerId) return
        val correctedTime = correctedTimestampMs.coerceAtLeast(0L)
        updateEventWithVideoLink(
            event.copy(
                playerId = if (ours) scorerPlayerId else null,
                relatedPlayerId = if (ours) assistPlayerId else null,
                timestampMs = correctedTime,
                // Keep the precise wall-clock anchor so the goal stays linked to the
                // right moment in the video; shift it by the same amount as the clock.
                occurredAtEpochMs = event.occurredAtEpochMs?.plus(correctedTime - event.timestampMs),
                note = if (!ours) event.note
                else if (scorerPlayerId == null) "Goal added by score correction" else ""
            )
        )
        refreshScoreFromEvents(event.matchId)
    }

    @Transaction
    suspend fun deleteScoringEvent(eventId: Long) {
        val event = getEventOnce(eventId) ?: return
        if (event.type !in listOf("OUR_GOAL", "OPPONENT_GOAL")) return
        deleteEventById(eventId)
        refreshScoreFromEvents(event.matchId)
    }

    @Transaction
    suspend fun undoLatestScoreAction(matchId: Long) {
        val latest = getScoringEventsNewestFirst(matchId).firstOrNull() ?: return
        deleteEventById(latest.id)
        refreshScoreFromEvents(matchId)
    }

    @Transaction
    suspend fun correctScore(
        matchId: Long,
        requestedOurScore: Int,
        requestedOpponentScore: Int,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) {
        val match = getMatchOnce(matchId) ?: return
        val ourTarget = requestedOurScore.coerceIn(0, 99)
        val opponentTarget = requestedOpponentScore.coerceIn(0, 99)
        val currentTime = liveActionTime(
            match,
            getOpenClockSegment(matchId),
            monotonicNowMs,
            wallClockNowMs
        )
        val events = getScoringEventsNewestFirst(matchId)
        val ourEvents = events.filter { it.type == "OUR_GOAL" }
        val opponentEvents = events.filter { it.type == "OPPONENT_GOAL" }

        if (ourEvents.size > ourTarget) {
            ourEvents.take(ourEvents.size - ourTarget).forEach { deleteEventById(it.id) }
        } else {
            repeat(ourTarget - ourEvents.size) {
                insertEventWithVideoLink(
                    MatchEvent(
                        matchId = matchId,
                        timestampMs = currentTime,
                        type = "OUR_GOAL",
                        note = "Goal added by score correction",
                        periodNumber = match.currentPeriod,
                        occurredAtEpochMs = wallClockNowMs
                    )
                )
            }
        }

        if (opponentEvents.size > opponentTarget) {
            opponentEvents.take(opponentEvents.size - opponentTarget)
                .forEach { deleteEventById(it.id) }
        } else {
            repeat(opponentTarget - opponentEvents.size) {
                insertEventWithVideoLink(
                    MatchEvent(
                        matchId = matchId,
                        timestampMs = currentTime,
                        type = "OPPONENT_GOAL",
                        note = "Goal added by score correction",
                        periodNumber = match.currentPeriod,
                        occurredAtEpochMs = wallClockNowMs
                    )
                )
            }
        }
        refreshScoreFromEvents(matchId)
    }


    @Query("SELECT * FROM teams WHERE name = :name AND season = :season ORDER BY id LIMIT 1")
    suspend fun findTeamOnce(name: String, season: String): Team?

    /**
     * Creates disposable practice data with no permissions or network requirement.
     * Always uses a dedicated, clearly labelled team so sample players are never added
     * to a real squad.
     */
    @Transaction
    suspend fun createPracticeMatch(): Long {
        val team = findTeamOnce(PracticeMatchRules.TEAM_NAME, PracticeMatchRules.SEASON)
        val teamId = team?.id ?: insertTeam(
            Team(name = PracticeMatchRules.TEAM_NAME, club = "Local practice", ageGroup = "", season = PracticeMatchRules.SEASON)
        )
        var players = getPlayersForTeam(teamId)
        if (players.size < 8) {
            val templates = PracticeMatchRules.players
            val missing = templates.drop(players.size)
            missing.forEach {
                insertPlayer(Player(teamId = teamId, name = it.name, shirtNumber = it.shirtNumber, position = it.position))
            }
            players = getPlayersForTeam(teamId)
        }
        val matchId = insertMatch(
            GameMatch(
                teamId = teamId,
                opponent = "Practice opposition",
                matchDate = java.time.LocalDate.now().toString(),
                venue = "Training ground",
                competition = "Practice",
                formation = "2-4-1",
                periodCount = 2,
                periodDurationMinutes = 10,
                playersOnPitch = 8,
                rollingSubstitutions = true
            )
        )
        val selected = players.take(8)
        upsertMatchSquadPlayers(
            selected.mapIndexed { index, player ->
                MatchSquadPlayer(
                    matchId = matchId,
                    playerId = player.id,
                    availability = AvailabilityStatus.AVAILABLE,
                    selected = true,
                    state = PlayerMatchState.BENCH,
                    isGoalkeeper = index == 0
                )
            }
        )
        upsertLineupPlacements(
            selected.mapIndexed { index, player ->
                MatchLineupPlacement(
                    matchId = matchId,
                    playerId = player.id,
                    normalizedX = 0.2f + (index % 4) * 0.2f,
                    normalizedY = 0.18f + (index / 4) * 0.55f,
                    role = if (index == 0) "GK" else "",
                    formationSlot = "practice-$index",
                    onPitch = true
                )
            }
        )
        selected.forEach { updateSquadPlayerState(matchId, it.id, PlayerMatchState.ON_PITCH) }
        return matchId
    }

    @Query("SELECT COALESCE(SUM(bytesRecorded), 0) FROM recording_segments WHERE uri IS NOT NULL")
    fun observeTotalRecordingBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM recording_segments WHERE uri IS NOT NULL")
    fun observeRecordingCount(): Flow<Int>
}

private fun liveActionTime(
    match: GameMatch,
    segment: MatchClockSegment?,
    monotonicNowMs: Long,
    wallClockNowMs: Long
): Long {
    if (!match.clockRunning || segment == null) return match.accumulatedMatchTimeMs
    return segmentMatchTime(segment, monotonicNowMs, wallClockNowMs)
}

private fun segmentMatchTime(
    segment: MatchClockSegment,
    monotonicNowMs: Long,
    wallClockNowMs: Long
): Long = MatchClockCalculator.recoveredCurrentMatchTimeMs(
    segmentStartMatchTimeMs = segment.startMatchTimeMs,
    monotonicStartMs = segment.monotonicStartMs,
    wallClockStartMs = segment.wallClockStartMs,
    monotonicNowMs = monotonicNowMs,
    wallClockNowMs = wallClockNowMs
)
