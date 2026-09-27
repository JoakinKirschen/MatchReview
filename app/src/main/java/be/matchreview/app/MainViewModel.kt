package be.matchreview.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.matchreview.app.backup.BackupResult
import be.matchreview.app.data.*
import be.matchreview.app.domain.FormationSlot
import be.matchreview.app.domain.MatchSetupRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val matchReviewApplication = application as MatchReviewApplication
    private val repository = matchReviewApplication.repository
    private val backupManager = matchReviewApplication.backupManager

    private val _backupOperation = MutableStateFlow<BackupOperationState>(BackupOperationState.Idle)
    val backupOperation: StateFlow<BackupOperationState> = _backupOperation.asStateFlow()

    val teams = repository.teams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val matches = repository.matches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val players = repository.allPlayers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeMatch = repository.activeMatch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playersForTeam(teamId: Long) = repository.players(teamId)
    fun match(matchId: Long) = repository.match(matchId)
    fun events(matchId: Long) = repository.events(matchId)
    fun squad(matchId: Long) = repository.squad(matchId)
    fun lineup(matchId: Long) = repository.lineup(matchId)
    fun periods(matchId: Long) = repository.periods(matchId)
    fun clockSegments(matchId: Long) = repository.clockSegments(matchId)
    fun participations(matchId: Long) = repository.participations(matchId)
    fun recordings(matchId: Long) = repository.recordings(matchId)

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
            done(
                repository.addMatch(
                    teamId, opponent, date, venue, competition, home, formation,
                    periodCount, periodDurationMinutes, playersOnPitch, rollingSubstitutions
                )
            )
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
    fun saveReview(matchId: Long, correctedScore: Pair<Int, Int>?, rating: Int, notes: String) =
        viewModelScope.launch {
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

    fun kickOffMatch(matchId: Long) = viewModelScope.launch {
        repository.kickOffMatch(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun pauseMatchClock(matchId: Long) = viewModelScope.launch {
        repository.pauseMatchClock(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun resumeMatchClock(matchId: Long) = viewModelScope.launch {
        repository.resumeMatchClock(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun endCurrentPeriod(matchId: Long) = viewModelScope.launch {
        repository.endCurrentPeriod(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun startNextPeriod(matchId: Long) = viewModelScope.launch {
        repository.startNextPeriod(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun finishLiveMatch(matchId: Long, done: () -> Unit = {}) = viewModelScope.launch {
        repository.finishLiveMatch(matchId, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        done()
    }


    fun substitutePlayer(matchId: Long, outgoingPlayerId: Long, incomingPlayerId: Long) =
        viewModelScope.launch {
            repository.substitutePlayer(
                matchId,
                outgoingPlayerId,
                incomingPlayerId,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis()
            )
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

    fun putPlayerOnPitch(matchId: Long, playerId: Long) = viewModelScope.launch {
        repository.putPlayerOnPitch(
            matchId,
            playerId,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis()
        )
    }


    fun recordOurGoal(matchId: Long, scorerPlayerId: Long?, assistPlayerId: Long?) =
        viewModelScope.launch {
            repository.recordOurGoal(
                matchId,
                scorerPlayerId,
                assistPlayerId,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis()
            )
        }

    fun recordOpponentGoal(matchId: Long) = viewModelScope.launch {
        repository.recordOpponentGoal(
            matchId,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis()
        )
    }

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
                onSuccess = { BackupOperationState.Success("Backup created", it) },
                onFailure = { BackupOperationState.Error(it.message ?: "Backup failed.") }
            )
        }
    }

    fun restoreBackup(uri: Uri, password: String) {
        if (_backupOperation.value is BackupOperationState.Working) return
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
