package be.matchreview.app.data

import be.matchreview.app.domain.FormationSlot

class MatchRepository(private val dao: MatchDao) {
    val teams = dao.observeTeams()
    val matches = dao.observeMatches()
    val allPlayers = dao.observeAllPlayers()
    val activeMatch = dao.observeActiveMatch()

    fun players(teamId: Long) = dao.observePlayers(teamId)
    fun match(id: Long) = dao.observeMatch(id)
    fun events(matchId: Long) = dao.observeEvents(matchId)
    fun squad(matchId: Long) = dao.observeMatchSquad(matchId)
    fun lineup(matchId: Long) = dao.observeLineup(matchId)
    fun periods(matchId: Long) = dao.observePeriods(matchId)
    fun clockSegments(matchId: Long) = dao.observeClockSegments(matchId)
    fun participations(matchId: Long) = dao.observeParticipations(matchId)
    fun recordings(matchId: Long) = dao.observeRecordings(matchId)

    suspend fun ensureMatchSquad(matchId: Long, teamId: Long) {
        val existingPlayerIds = dao.getMatchSquadOnce(matchId).mapTo(mutableSetOf()) { it.playerId }
        val missing = dao.getPlayersForTeam(teamId)
            .filterNot { it.id in existingPlayerIds }
            .map {
                MatchSquadPlayer(
                    matchId = matchId,
                    playerId = it.id,
                    availability = AvailabilityStatus.AVAILABLE,
                    selected = false,
                    state = PlayerMatchState.UNAVAILABLE
                )
            }
        if (missing.isNotEmpty()) dao.upsertMatchSquadPlayers(missing)
    }

    suspend fun setSquadAvailability(
        matchId: Long,
        playerId: Long,
        availability: AvailabilityStatus
    ) {
        val current = dao.getMatchSquadPlayer(matchId, playerId)
            ?: MatchSquadPlayer(matchId = matchId, playerId = playerId)
        val remainsSelected = availability == AvailabilityStatus.AVAILABLE && current.selected
        dao.upsertMatchSquadPlayer(
            current.copy(
                availability = availability,
                selected = remainsSelected,
                state = if (remainsSelected) PlayerMatchState.BENCH else PlayerMatchState.UNAVAILABLE
            )
        )
    }

    suspend fun setSquadSelected(matchId: Long, playerId: Long, selected: Boolean) {
        val current = dao.getMatchSquadPlayer(matchId, playerId) ?: return
        dao.upsertMatchSquadPlayer(
            current.copy(
                availability = AvailabilityStatus.AVAILABLE,
                selected = selected,
                state = if (selected) PlayerMatchState.BENCH else PlayerMatchState.UNAVAILABLE
            )
        )
    }

    suspend fun selectAllAvailable(matchId: Long) = dao.selectAllAvailable(matchId)
    suspend fun markAndSelectAllAvailable(matchId: Long) = dao.markAndSelectAllAvailable(matchId)
    suspend fun clearSquadSelection(matchId: Long) = dao.clearSquadSelection(matchId)

    suspend fun ensureLineup(matchId: Long) {
        dao.deleteUnselectedLineupPlacements(matchId)
        val selected = dao.getMatchSquadOnce(matchId).filter { it.selected }
        val existingIds = dao.getLineupOnce(matchId).mapTo(mutableSetOf()) { it.playerId }
        val missing = selected
            .filterNot { it.playerId in existingIds }
            .map {
                MatchLineupPlacement(
                    matchId = matchId,
                    playerId = it.playerId,
                    onPitch = false
                )
            }
        if (missing.isNotEmpty()) dao.upsertLineupPlacements(missing)
    }

    suspend fun setLineupPlacement(placement: MatchLineupPlacement) =
        dao.saveLineupPlacement(
            placement.copy(
                normalizedX = placement.normalizedX.coerceIn(0f, 1f),
                normalizedY = placement.normalizedY.coerceIn(0f, 1f)
            )
        )

    suspend fun autoPlaceLineup(
        matchId: Long,
        players: List<Player>,
        slots: List<FormationSlot>
    ) {
        val ordered = players.sortedWith(
            compareByDescending<Player> {
                it.position.contains("goal", ignoreCase = true) ||
                    it.position.equals("GK", ignoreCase = true)
            }.thenBy { it.shirtNumber <= 0 }
                .thenBy { it.shirtNumber }
                .thenBy { it.name }
        )
        val placements = ordered.mapIndexed { index, player ->
            val slot = slots.getOrNull(index)
            MatchLineupPlacement(
                matchId = matchId,
                playerId = player.id,
                normalizedX = slot?.normalizedX ?: 0.5f,
                normalizedY = slot?.normalizedY ?: 0.9f,
                role = slot?.label.orEmpty(),
                formationSlot = slot?.id.orEmpty(),
                onPitch = slot != null
            )
        }
        dao.replaceStartingLineup(matchId, placements)
    }

    suspend fun addTeam(name: String, club: String, ageGroup: String, season: String) =
        dao.insertTeam(Team(name = name.trim(), club = club.trim(), ageGroup = ageGroup.trim(), season = season.trim()))

    suspend fun addPlayer(teamId: Long, name: String, number: Int, position: String) =
        dao.insertPlayer(Player(teamId = teamId, name = name.trim(), shirtNumber = number, position = position.trim()))

    suspend fun updatePlayer(player: Player) = dao.updatePlayer(
        player.copy(
            name = player.name.trim(),
            position = player.position.trim(),
            preferredFoot = player.preferredFoot.trim(),
            notes = player.notes.trim()
        )
    )

    suspend fun archivePlayer(player: Player) = dao.archivePlayer(player.id)

    suspend fun deletePlayerPermanently(player: Player) = dao.deletePlayerPermanently(player)

    suspend fun deleteTeam(team: Team) = dao.deleteTeam(team)

    suspend fun teamMedia(teamId: Long) = TeamMedia(
        recordingUris = dao.getRecordingUrisForTeam(teamId),
        importedVideoUris = dao.getImportedVideoUrisForTeam(teamId)
    )

    suspend fun addMatch(
        teamId: Long,
        opponent: String,
        date: String,
        venue: String,
        competition: String,
        home: Boolean,
        formation: String,
        periodCount: Int,
        periodDurationMinutes: Int,
        playersOnPitch: Int,
        rollingSubstitutions: Boolean
    ) = dao.insertMatch(
        GameMatch(
            teamId = teamId,
            opponent = opponent.trim(),
            matchDate = date.trim(),
            venue = venue.trim(),
            competition = competition.trim(),
            isHome = home,
            formation = formation.trim(),
            periodCount = periodCount.coerceIn(1, 8),
            periodDurationMinutes = periodDurationMinutes.coerceIn(1, 120),
            playersOnPitch = playersOnPitch.coerceIn(1, 11),
            rollingSubstitutions = rollingSubstitutions
        )
    )

    suspend fun saveReview(matchId: Long, rating: Int, notes: String) =
        dao.updateReview(matchId, rating.coerceIn(0, 10), notes)

    suspend fun setVideoUri(matchId: Long, videoUri: String?) = dao.setVideoUri(matchId, videoUri)

    suspend fun markLineupReady(matchId: Long) = dao.markLineupReady(matchId)

    suspend fun deleteMatch(match: GameMatch) = dao.deleteMatch(match)

    suspend fun addEvent(
        matchId: Long,
        playerId: Long?,
        timestampMs: Long,
        type: String,
        sentiment: String,
        note: String
    ) = dao.insertEventWithVideoLink(
        MatchEvent(
            matchId = matchId,
            playerId = playerId,
            timestampMs = timestampMs,
            type = type,
            sentiment = sentiment,
            note = note.trim()
        )
    )

    suspend fun deleteEvent(event: MatchEvent) = dao.deleteTimelineEvent(event.id)

    suspend fun kickOffMatch(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.kickOffMatch(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun pauseMatchClock(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.pauseMatchClock(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun resumeMatchClock(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.resumeMatchClock(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun endCurrentPeriod(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.endCurrentPeriod(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun startNextPeriod(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.startNextPeriod(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun finishLiveMatch(matchId: Long, monotonicNowMs: Long, wallClockNowMs: Long) =
        dao.finishLiveMatch(matchId, monotonicNowMs, wallClockNowMs)


    suspend fun substitutePlayer(
        matchId: Long,
        outgoingPlayerId: Long,
        incomingPlayerId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.substitutePlayer(
        matchId, outgoingPlayerId, incomingPlayerId, monotonicNowMs, wallClockNowMs
    )

    suspend fun removePlayerFromPitch(
        matchId: Long,
        playerId: Long,
        reason: ParticipationReason,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.removePlayerFromPitch(
        matchId, playerId, reason, monotonicNowMs, wallClockNowMs
    )

    suspend fun putPlayerOnPitch(
        matchId: Long,
        playerId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.putPlayerOnPitch(matchId, playerId, monotonicNowMs, wallClockNowMs)


    suspend fun recordOurGoal(
        matchId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.recordOurGoal(
        matchId, scorerPlayerId, assistPlayerId, monotonicNowMs, wallClockNowMs
    )

    suspend fun recordOpponentGoal(
        matchId: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.recordOpponentGoal(matchId, monotonicNowMs, wallClockNowMs)

    suspend fun updateOurGoal(
        eventId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        correctedTimestampMs: Long
    ) = dao.updateOurGoal(
        eventId, scorerPlayerId, assistPlayerId, correctedTimestampMs
    )

    suspend fun deleteScoringEvent(eventId: Long) =
        dao.deleteScoringEvent(eventId)

    suspend fun undoLatestScoreAction(matchId: Long) =
        dao.undoLatestScoreAction(matchId)

    suspend fun correctScore(
        matchId: Long,
        ourScore: Int,
        opponentScore: Int,
        monotonicNowMs: Long,
        wallClockNowMs: Long
    ) = dao.correctScore(
        matchId, ourScore, opponentScore, monotonicNowMs, wallClockNowMs
    )

    suspend fun beginRecordingSegment(
        matchId: Long,
        matchClockStartMs: Long,
        orientationDegrees: Int,
        audioEnabled: Boolean
    ): Long = dao.insertRecording(
        RecordingSegment(
            matchId = matchId,
            status = RecordingStatus.PREPARING,
            matchClockStartMs = matchClockStartMs.coerceAtLeast(0L),
            orientationDegrees = orientationDegrees,
            audioEnabled = audioEnabled
        )
    )

    suspend fun markRecordingStarted(recordingId: Long, startedAtEpochMs: Long) {
        dao.getRecordingOnce(recordingId)?.let {
            dao.updateRecording(
                it.copy(
                    status = RecordingStatus.RECORDING,
                    startedAtEpochMs = startedAtEpochMs,
                    errorMessage = null
                )
            )
        }
    }

    suspend fun completeRecordingSegment(
        recordingId: Long,
        uri: String?,
        matchClockEndMs: Long,
        recordingDurationMs: Long,
        bytesRecorded: Long,
        warning: String? = null
    ) {
        dao.getRecordingOnce(recordingId)?.let {
            dao.updateRecording(
                it.copy(
                    uri = uri,
                    status = RecordingStatus.COMPLETED,
                    matchClockEndMs = matchClockEndMs.coerceAtLeast(it.matchClockStartMs),
                    recordingDurationMs = recordingDurationMs.coerceAtLeast(0L),
                    bytesRecorded = bytesRecorded.coerceAtLeast(0L),
                    errorMessage = warning?.take(500)
                )
            )
            dao.mapUnlinkedEvents(it.matchId)
        }
    }

    suspend fun failRecordingSegment(
        recordingId: Long,
        status: RecordingStatus,
        matchClockEndMs: Long?,
        recordingDurationMs: Long,
        errorMessage: String,
        uri: String? = null
    ) {
        dao.getRecordingOnce(recordingId)?.let {
            dao.updateRecording(
                it.copy(
                    // Keep any file reference so deleting the match also removes it.
                    uri = uri ?: it.uri,
                    status = status,
                    matchClockEndMs = matchClockEndMs,
                    recordingDurationMs = recordingDurationMs.coerceAtLeast(0L),
                    errorMessage = errorMessage.take(500)
                )
            )
        }
    }

    suspend fun mapUnlinkedEvents(matchId: Long) = dao.mapUnlinkedEvents(matchId)

    suspend fun markOpenRecordingsInterrupted(message: String) =
        dao.markOpenRecordingsInterrupted(message)

}

data class TeamMedia(
    val recordingUris: List<String>,
    val importedVideoUris: List<String>
)
