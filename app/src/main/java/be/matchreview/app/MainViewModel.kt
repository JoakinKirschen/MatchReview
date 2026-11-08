package be.matchreview.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.matchreview.app.backup.BackupResult
import be.matchreview.app.backup.BackupPreview
import be.matchreview.app.data.*
import be.matchreview.app.domain.FormationSlot
import be.matchreview.app.domain.MatchSetupRules
import be.matchreview.app.domain.LiveCommandGate
import be.matchreview.app.domain.SubstitutionDraftCodec
import be.matchreview.app.domain.MatchFormat
import be.matchreview.app.domain.MatchFormatMemory
import be.matchreview.app.domain.PlayerSeasonStatsRules
import be.matchreview.app.domain.SeasonReportRules
import be.matchreview.app.domain.PlayerSeasonStats
import be.matchreview.app.domain.StatAdjustments
import be.matchreview.app.domain.StatLine
import be.matchreview.app.domain.BackupReminderRules
import be.matchreview.app.domain.PeriodResume
import be.matchreview.app.recording.MatchRecordingService
import be.matchreview.app.ui.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import be.matchreview.app.backup.AutoBackupState

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val matchReviewApplication = application as MatchReviewApplication
    private val repository = matchReviewApplication.repository
    private val backupManager = matchReviewApplication.backupManager
    private val liveCommandGate = LiveCommandGate()
    private val periodRecording = matchReviewApplication.periodRecording

    private val _backupOperation = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val backupOperation: StateFlow<BackupOperationState> = _backupOperation.asStateFlow()

    private val _restorePreview = MutableStateFlow<RestorePreviewState>(RestorePreviewState.Idle)
    val restorePreview: StateFlow<RestorePreviewState> = _restorePreview.asStateFlow()

    private val backupPreferences =
        application.getSharedPreferences("matchreview_backup_status", 0)
    private val _lastBackupEpochMs = MutableStateFlow(
        backupPreferences.getLong("last_success_epoch_ms", 0L).takeIf { it > 0L }
    )
    val lastBackupEpochMs: StateFlow<Long?> = _lastBackupEpochMs.asStateFlow()
    private val _lastBackupIncludedMedia =
        MutableStateFlow(backupPreferences.getBoolean("last_success_included_media", false))
    val lastBackupIncludedMedia: StateFlow<Boolean> = _lastBackupIncludedMedia.asStateFlow()
    private val _lastFinishedMatchEpochMs = MutableStateFlow(
        backupPreferences.getLong("last_finished_match_epoch_ms", 0L).takeIf { it > 0L }
    )
    /** True when a match finished after the last successful backup. */
    val backupDue: StateFlow<Boolean> = combine(_lastBackupEpochMs, _lastFinishedMatchEpochMs) { backup, finished ->
        BackupReminderRules.isDue(backup, finished)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val autoBackup = matchReviewApplication.autoBackup
    val autoBackupState: StateFlow<AutoBackupState> = autoBackup.state

    init {
        // An automatic backup counts as a backup for the status card and the reminder.
        viewModelScope.launch {
            autoBackup.state.collect { state ->
                val completedAt = state.lastSuccessEpochMs ?: return@collect
                if (completedAt > (_lastBackupEpochMs.value ?: 0L)) {
                    _lastBackupEpochMs.value = completedAt
                    _lastBackupIncludedMedia.value = backupPreferences.getBoolean("last_success_included_media", false)
                }
            }
        }
    }

    /** Returns an error message, or null when automatic backups are on. */
    fun enableAutoBackup(folder: Uri, password: String, includeMedia: Boolean): String? =
        autoBackup.enable(folder, password, includeMedia)

    fun setAutoBackupIncludesMedia(includeMedia: Boolean) = autoBackup.setIncludeMedia(includeMedia)

    fun disableAutoBackup() = autoBackup.disable()

    fun runAutoBackupNow() = autoBackup.runInBackground()

    private val displayPreferences =
        application.getSharedPreferences("matchreview_display", 0)
    private val _themeMode = MutableStateFlow(ThemeMode.fromName(displayPreferences.getString("theme", null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        displayPreferences.edit().putString("theme", mode.name).apply()
        _themeMode.value = mode
    }

    private val draftPreferences =
        application.getSharedPreferences("matchreview_live_drafts", 0)

    /** The unconfirmed substitution round for [matchId], if the app closed during one. */
    fun substitutionDraft(matchId: Long): List<MatchLineupPlacement>? =
        SubstitutionDraftCodec.decode(matchId, draftPreferences.getString(draftKey(matchId), null))

    fun saveSubstitutionDraft(matchId: Long, plan: List<MatchLineupPlacement>) {
        draftPreferences.edit().putString(draftKey(matchId), SubstitutionDraftCodec.encode(plan)).apply()
    }

    fun clearSubstitutionDraft(matchId: Long) {
        draftPreferences.edit().remove(draftKey(matchId)).apply()
    }

    private fun draftKey(matchId: Long) = "substitution_round_$matchId"

    private val formatPreferences =
        application.getSharedPreferences("matchreview_match_format", 0)

    /** The format of the last match the coach created, or null before the first one. */
    fun lastMatchFormat(): MatchFormat? {
        if (!formatPreferences.contains("playersOnPitch")) return null
        return MatchFormatMemory.sanitize(
            MatchFormat(
                playersOnPitch = formatPreferences.getInt("playersOnPitch", 11),
                formation = formatPreferences.getString("formation", "").orEmpty(),
                periodCount = formatPreferences.getInt("periodCount", 2),
                periodDurationMinutes = formatPreferences.getInt("periodDurationMinutes", 45),
                rollingSubstitutions = formatPreferences.getBoolean("rollingSubstitutions", true),
                competition = formatPreferences.getString("competition", "").orEmpty(),
                teamId = formatPreferences.getLong("teamId", 0L)
            )
        )
    }

    private fun rememberMatchFormat(format: MatchFormat) {
        formatPreferences.edit()
            .putInt("playersOnPitch", format.playersOnPitch)
            .putString("formation", format.formation)
            .putInt("periodCount", format.periodCount)
            .putInt("periodDurationMinutes", format.periodDurationMinutes)
            .putBoolean("rollingSubstitutions", format.rollingSubstitutions)
            .putString("competition", format.competition)
            .putLong("teamId", format.teamId)
            .apply()
    }

    val teams = repository.teams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val matches = repository.matches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val players = repository.allPlayers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeMatch = repository.activeMatch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val totalRecordingBytes = repository.totalRecordingBytes.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L
    )
    val recordingCount = repository.recordingCount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 0
    )

    fun playersForTeam(teamId: Long) = repository.players(teamId)
    fun match(matchId: Long) = repository.match(matchId)
    fun events(matchId: Long) = repository.events(matchId)
    fun squad(matchId: Long) = repository.squad(matchId)
    fun lineup(matchId: Long) = repository.lineup(matchId)
    fun periods(matchId: Long) = repository.periods(matchId)
    fun clockSegments(matchId: Long) = repository.clockSegments(matchId)
    fun participations(matchId: Long) = repository.participations(matchId)
    fun recordings(matchId: Long) = repository.recordings(matchId)

    /** Minutes, goals, assists and saves per player over the team's played matches. */
    fun seasonStats(teamId: Long) = combine(
        repository.players(teamId),
        repository.matches,
        repository.teamParticipations(teamId),
        repository.teamStatEvents(teamId)
    ) { players, matches, participations, events ->
        PlayerSeasonStatsRules.compute(
            players,
            matches.filter { it.teamId == teamId },
            participations,
            events
        )
    }

    /** Record, results and player lines for the season report export. */
    fun seasonReport(teamId: Long) = combine(
        repository.teams,
        repository.matches,
        seasonStats(teamId),
        repository.teamStatEvents(teamId)
    ) { teams, matches, stats, events ->
        SeasonReportRules.build(
            teams.firstOrNull { it.id == teamId }?.name ?: "Team",
            matches.filter { it.teamId == teamId },
            stats,
            events
        )
    }

    fun ensureVideoEventLinks(matchId: Long) =
        viewModelScope.launch { repository.mapUnlinkedEvents(matchId) }

    fun ensureMatchSquad(matchId: Long, teamId: Long) =
        viewModelScope.launch { repository.ensureMatchSquad(matchId, teamId) }

    fun setSquadAvailability(matchId: Long, playerId: Long, availability: AvailabilityStatus) =
        viewModelScope.launch { repository.setSquadAvailability(matchId, playerId, availability) }

    fun setSquadSelected(matchId: Long, playerId: Long, selected: Boolean) =
        viewModelScope.launch { repository.setSquadSelected(matchId, playerId, selected) }

    fun selectAllAvailable(matchId: Long) =
        viewModelScope.launch { repository.selectAllAvailable(matchId) }

    fun markAndSelectAllAvailable(matchId: Long) =
        viewModelScope.launch { repository.markAndSelectAllAvailable(matchId) }

    fun clearSquadSelection(matchId: Long) =
        viewModelScope.launch { repository.clearSquadSelection(matchId) }

    fun ensureLineup(matchId: Long) =
        viewModelScope.launch { repository.ensureLineup(matchId) }

    fun setLineupPlacement(placement: MatchLineupPlacement) =
        viewModelScope.launch { repository.setLineupPlacement(placement) }

    fun swapLineupPlacements(
        selected: MatchLineupPlacement,
        occupied: MatchLineupPlacement
    ) = viewModelScope.launch {
        repository.swapLineupPlacements(selected, occupied)
    }

    fun autoPlaceLineup(matchId: Long, players: List<Player>, slots: List<FormationSlot>) =
        viewModelScope.launch { repository.autoPlaceLineup(matchId, players, slots) }

    fun markLineupReady(matchId: Long, done: () -> Unit) =
        viewModelScope.launch {
            repository.markLineupReady(matchId)
            done()
        }

    fun addTeam(name: String, club: String, age: String, season: String, done: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addTeam(name, club, age, season); done() }
    }

    fun addPlayer(teamId: Long, name: String, number: Int, position: String, done: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addPlayer(teamId, name, number, position); done() }
    }

    fun updatePlayer(player: Player, done: () -> Unit = {}) {
        if (player.name.isBlank()) return
        viewModelScope.launch {
            repository.updatePlayer(player)
            done()
        }
    }

    /** Saves corrected season totals for a player; null restores the tracked numbers. */
    fun correctSeasonStats(stats: PlayerSeasonStats, desired: StatLine?) = viewModelScope.launch {
        repository.setStatAdjustments(
            stats.player.id,
            desired?.let { PlayerSeasonStatsRules.adjustmentsFor(stats.tracked, it) }
                ?: StatAdjustments(0, 0, 0, 0, 0)
        )
    }

    fun archivePlayer(player: Player, done: () -> Unit = {}) =
        viewModelScope.launch {
            repository.archivePlayer(player)
            done()
        }

    fun deletePlayerPermanently(player: Player, done: () -> Unit = {}) =
        viewModelScope.launch {
            repository.deletePlayerPermanently(player)
            done()
        }

    fun loadTeamMedia(teamId: Long, done: (TeamMedia) -> Unit) =
        viewModelScope.launch { done(repository.teamMedia(teamId)) }

    fun deleteTeam(team: Team, done: () -> Unit = {}) =
        viewModelScope.launch {
            repository.deleteTeam(team)
            done()
        }

    fun setTeamLogo(teamId: Long, logoPng: String?) =
        viewModelScope.launch { repository.setTeamLogo(teamId, logoPng) }

    fun updateMatchDetails(
        matchId: Long,
        opponent: String,
        date: String,
        venue: String,
        competition: String,
        home: Boolean,
        done: () -> Unit = {}
    ) {
        if (opponent.isBlank() || date.isBlank()) return
        viewModelScope.launch {
            repository.updateMatchDetails(matchId, opponent, date, venue, competition, home)
            done()
        }
    }

    fun createPracticeMatch(done: (Long) -> Unit) =
        viewModelScope.launch { done(repository.createPracticeMatch()) }

    fun addMatch(
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
        rollingSubstitutions: Boolean,
        done: (Long) -> Unit
    ) {
        if (
            teamId == 0L ||
            opponent.isBlank() ||
            date.isBlank() ||
            !MatchSetupRules.isLegalFormation(playersOnPitch, formation)
        ) return
        viewModelScope.launch {
            val matchId = repository.addMatch(
                teamId, opponent, date, venue, competition, home, formation,
                periodCount, periodDurationMinutes, playersOnPitch, rollingSubstitutions
            )
            rememberMatchFormat(
                MatchFormat(
                    playersOnPitch = playersOnPitch,
                    formation = formation,
                    periodCount = periodCount,
                    periodDurationMinutes = periodDurationMinutes,
                    rollingSubstitutions = rollingSubstitutions,
                    competition = competition.trim(),
                    teamId = teamId
                )
            )
            done(matchId)
        }
    }

    fun setVideo(matchId: Long, uri: String) =
        viewModelScope.launch { repository.setVideoUri(matchId, uri) }

    fun deleteMatch(match: GameMatch, done: () -> Unit = {}) =
        viewModelScope.launch {
            repository.deleteMatch(match)
            done()
        }

    /**
     * Saves rating and notes. The score is derived from goal events, so a changed
     * score is applied as a correction that adds or removes timeline goals.
     */
    fun saveReview(
        matchId: Long,
        correctedScore: Pair<Int, Int>?,
        rating: Int,
        notes: String,
        done: () -> Unit = {}
    ) = viewModelScope.launch {
        correctedScore?.let { (ours, theirs) ->
            repository.correctScore(
                matchId,
                ours,
                theirs,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis()
            )
        }
        repository.saveReview(matchId, rating, notes)
        done()
    }

    fun addEvent(
        matchId: Long,
        playerId: Long?,
        timestamp: Long,
        type: String,
        sentiment: String,
        note: String
    ) = viewModelScope.launch {
        repository.addEvent(matchId, playerId, timestamp, type, sentiment, note)
    }

    fun deleteEvent(event: MatchEvent) =
        viewModelScope.launch { repository.deleteEvent(event) }

    fun deleteEventById(eventId: Long) =
        viewModelScope.launch { repository.deleteEventById(eventId) }

    fun kickOffMatch(matchId: Long) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:kickoff", now)) return@launch
        repository.kickOffMatch(matchId, now, System.currentTimeMillis())
    }

    fun pauseMatchClock(matchId: Long) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:pause", now)) return@launch
        repository.pauseMatchClock(matchId, now, System.currentTimeMillis())
    }

    fun resumeMatchClock(matchId: Long) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:resume", now)) return@launch
        repository.resumeMatchClock(matchId, now, System.currentTimeMillis())
    }

    /** Whether the camera stops at each period end and restarts with the next period. */
    val recordingFollowsPeriods: StateFlow<Boolean> = periodRecording.enabled
    /** A recording stopped at a period end that restarts when the next period starts. */
    val pendingPeriodRecording: StateFlow<PeriodResume?> = periodRecording.pending

    fun setRecordingFollowsPeriods(enabled: Boolean) = periodRecording.setEnabled(enabled)

    fun endCurrentPeriod(matchId: Long) = viewModelScope.launch {
        val stopCamera = periodRecording.prepareForPeriodEnd(matchId)
        repository.endCurrentPeriod(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (!stopCamera) return@launch
        val ended = repository.match(matchId).first()
        if (ended?.status == MatchStatus.PERIOD_ENDED) {
            // The clip ends exactly at the period end; the break is not filmed.
            MatchRecordingService.requestStop(getApplication(), ended.accumulatedMatchTimeMs)
        } else {
            periodRecording.cancel(matchId)
        }
    }

    fun startNextPeriod(matchId: Long) = viewModelScope.launch {
        val startedAt = SystemClock.elapsedRealtime()
        repository.startNextPeriod(matchId, startedAt, System.currentTimeMillis())
        val started = repository.match(matchId).first()
        // Runs in the application scope: waiting for the previous clip to be saved must
        // not be cut short when the live screen closes.
        matchReviewApplication.applicationScope.launch {
            periodRecording.resumeAfterPeriodStart(
                matchId = matchId,
                status = started?.status,
                periodStartMatchMs = started?.accumulatedMatchTimeMs ?: 0L,
                periodStartedMonotonicMs = startedAt
            )
        }
    }

    fun finishLiveMatch(matchId: Long, done: () -> Unit = {}) = viewModelScope.launch {
        periodRecording.cancel(matchId)
        val finishedAt = System.currentTimeMillis()
        repository.finishLiveMatch(matchId, SystemClock.elapsedRealtime(), finishedAt)
        backupPreferences.edit().putLong("last_finished_match_epoch_ms", finishedAt).apply()
        _lastFinishedMatchEpochMs.value = finishedAt
        autoBackup.runInBackground()
        done()
    }


    /** Applies a substitution-mode round; [done] receives false when it was rejected. */
    fun applySubstitutionRound(
        matchId: Long,
        planned: List<MatchLineupPlacement>,
        done: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:sub-round", now)) return@launch
        done(repository.applySubstitutionRound(matchId, planned, now, System.currentTimeMillis()))
    }

    fun undoLatestLineupChange(matchId: Long, done: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:undo-lineup", now)) return@launch
        // The draft of an open round was based on the lineup that is being undone.
        clearSubstitutionDraft(matchId)
        done(repository.undoLatestLineupChange(matchId))
    }

    fun removePlayerFromPitch(
        matchId: Long,
        playerId: Long,
        reason: ParticipationReason
    ) = viewModelScope.launch {
        repository.removePlayerFromPitch(
            matchId,
            playerId,
            reason,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis()
        )
    }


    fun recordOurGoal(
        matchId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        done: (Long?) -> Unit = {}
    ) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        val key = "$matchId:our-goal:${scorerPlayerId ?: 0}:${assistPlayerId ?: 0}"
        if (!liveCommandGate.accept(key, now)) return@launch
        val eventId = repository.recordOurGoal(
            matchId,
            scorerPlayerId,
            assistPlayerId,
            now,
            System.currentTimeMillis()
        )
        done(eventId)
    }

    fun recordOpponentGoal(matchId: Long, done: (Long?) -> Unit = {}) = viewModelScope.launch {
        val now = SystemClock.elapsedRealtime()
        if (!liveCommandGate.accept("$matchId:opponent-goal", now)) return@launch
        done(repository.recordOpponentGoal(matchId, now, System.currentTimeMillis()))
    }

    fun recordMatchAction(matchId: Long, type: String, playerId: Long?, done: (Long?) -> Unit = {}) =
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (!liveCommandGate.accept("$matchId:$type:${playerId ?: 0}", now)) return@launch
            done(repository.recordMatchAction(matchId, type, playerId, now, System.currentTimeMillis()))
        }

    fun recordKeeperSave(matchId: Long, keeperPlayerId: Long?, done: (Long?) -> Unit = {}) =
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (!liveCommandGate.accept("$matchId:save:${keeperPlayerId ?: 0}", now)) return@launch
            done(repository.recordKeeperSave(matchId, keeperPlayerId, now, System.currentTimeMillis()))
        }

    fun setGoalPlacement(eventId: Long, goalX: Float?, goalY: Float?) =
        viewModelScope.launch { repository.setGoalPlacement(eventId, goalX, goalY) }

    fun updateOurGoal(
        eventId: Long,
        scorerPlayerId: Long?,
        assistPlayerId: Long?,
        correctedTimestampMs: Long
    ) = viewModelScope.launch {
        repository.updateOurGoal(
            eventId, scorerPlayerId, assistPlayerId, correctedTimestampMs
        )
    }

    fun deleteScoringEvent(eventId: Long) = viewModelScope.launch {
        repository.deleteScoringEvent(eventId)
    }

    fun undoLatestScoreAction(matchId: Long) = viewModelScope.launch {
        repository.undoLatestScoreAction(matchId)
    }

    fun correctScore(matchId: Long, ourScore: Int, opponentScore: Int) =
        viewModelScope.launch {
            repository.correctScore(
                matchId,
                ourScore,
                opponentScore,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis()
            )
        }


    fun exportBackup(uri: Uri, password: String, includeMedia: Boolean) {
        if (_backupOperation.value is BackupOperationState.Working) return
        viewModelScope.launch {
            _backupOperation.value = BackupOperationState.Working("Creating encrypted backup…")
            _backupOperation.value = runCatching {
                backupManager.exportBackup(uri, password.toCharArray(), includeMedia)
            }.fold(
                onSuccess = {
                    val completedAt = System.currentTimeMillis()
                    backupPreferences.edit()
                        .putLong("last_success_epoch_ms", completedAt)
                        .putBoolean("last_success_included_media", includeMedia)
                        .apply()
                    _lastBackupEpochMs.value = completedAt
                    _lastBackupIncludedMedia.value = includeMedia
                    BackupOperationState.Success("Backup created", it)
                },
                onFailure = { BackupOperationState.Error(it.message ?: "Backup failed.") }
            )
        }
    }

    fun inspectBackup(uri: Uri, password: String) {
        if (_restorePreview.value is RestorePreviewState.Working) return
        viewModelScope.launch {
            _restorePreview.value = RestorePreviewState.Working
            _restorePreview.value = runCatching {
                backupManager.inspectBackup(uri, password.toCharArray())
            }.fold(
                onSuccess = { RestorePreviewState.Ready(it) },
                onFailure = {
                    RestorePreviewState.Error(it.message ?: "The backup could not be inspected.")
                }
            )
        }
    }

    fun clearRestorePreview() {
        _restorePreview.value = RestorePreviewState.Idle
    }

    fun restoreBackup(uri: Uri, password: String) {
        if (_backupOperation.value is BackupOperationState.Working) return
        _restorePreview.value = RestorePreviewState.Idle
        viewModelScope.launch {
            _backupOperation.value = BackupOperationState.Working("Validating and restoring backup…")
            _backupOperation.value = runCatching {
                backupManager.restoreBackup(uri, password.toCharArray())
            }.fold(
                onSuccess = { BackupOperationState.Success("Backup restored", it) },
                onFailure = { BackupOperationState.Error(it.message ?: "Restore failed.") }
            )
        }
    }

    fun clearBackupOperation() {
        _backupOperation.value = BackupOperationState.Idle
    }

}


sealed interface BackupOperationState {
    data object Idle : BackupOperationState
    data class Working(val message: String) : BackupOperationState
    data class Success(val message: String, val result: BackupResult) : BackupOperationState
    data class Error(val message: String) : BackupOperationState
}

sealed interface RestorePreviewState {
    data object Idle : RestorePreviewState
    data object Working : RestorePreviewState
    data class Ready(val preview: BackupPreview) : RestorePreviewState
    data class Error(val message: String) : RestorePreviewState
}
