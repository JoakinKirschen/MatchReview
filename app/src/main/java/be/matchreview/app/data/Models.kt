package be.matchreview.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MatchStatus { DRAFT, LINEUP_READY, LIVE, PAUSED, PERIOD_ENDED, FINISHED, ABANDONED, CANCELLED }
enum class AvailabilityStatus { AVAILABLE, UNAVAILABLE, INJURED, SUSPENDED, NOT_SELECTED, UNKNOWN }
enum class PlayerMatchState { ON_PITCH, BENCH, UNAVAILABLE, REMOVED, DISMISSED }
enum class PeriodStatus { NOT_STARTED, LIVE, PAUSED, ENDED }
enum class RecordingStatus { PREPARING, RECORDING, COMPLETED, INTERRUPTED, FAILED }
enum class StopReason { USER, PAUSE, PERIOD_END, MATCH_END, BACKGROUND, INTERRUPTION, RECOVERY }
enum class ParticipationReason { STARTER, SUBSTITUTION, PERIOD_START, PERIOD_END, MATCH_END, BENCH, INJURY, DISMISSAL, CORRECTION }

@Entity(tableName = "teams")
data class Team(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val club: String = "",
    val ageGroup: String = "",
    val season: String = ""
)

@Entity(
    tableName = "players",
    foreignKeys = [ForeignKey(
        entity = Team::class,
        parentColumns = ["id"],
        childColumns = ["teamId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("teamId")]
)
data class Player(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamId: Long,
    val name: String,
    val shirtNumber: Int = 0,
    val position: String = "",
    val preferredFoot: String = "",
    val notes: String = "",
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false
)

@Entity(
    tableName = "matches",
    foreignKeys = [ForeignKey(
        entity = Team::class,
        parentColumns = ["id"],
        childColumns = ["teamId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("teamId"), Index("status")]
)
data class GameMatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teamId: Long,
    val opponent: String,
    val matchDate: String,
    val venue: String = "",
    val competition: String = "",
    val isHome: Boolean = true,
    val ourScore: Int = 0,
    val opponentScore: Int = 0,
    val formation: String = "4-3-3",
    val videoUri: String? = null,
    val teamRating: Int = 0,
    val reviewNotes: String = "",
    @ColumnInfo(defaultValue = "'DRAFT'") val status: MatchStatus = MatchStatus.DRAFT,
    @ColumnInfo(defaultValue = "2") val periodCount: Int = 2,
    @ColumnInfo(defaultValue = "45") val periodDurationMinutes: Int = 45,
    @ColumnInfo(defaultValue = "11") val playersOnPitch: Int = 11,
    @ColumnInfo(defaultValue = "1") val rollingSubstitutions: Boolean = true,
    @ColumnInfo(defaultValue = "0") val currentPeriod: Int = 0,
    @ColumnInfo(defaultValue = "0") val accumulatedMatchTimeMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val clockRunning: Boolean = false
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = GameMatch::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("matchId"), Index("playerId"), Index("relatedEventId"), Index("recordingSegmentId")]
)
data class MatchEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val playerId: Long? = null,
    val timestampMs: Long,
    val type: String,
    val sentiment: String = "Neutral",
    val note: String = "",
    @ColumnInfo(defaultValue = "0") val periodNumber: Int = 0,
    val occurredAtEpochMs: Long? = null,
    val relatedPlayerId: Long? = null,
    val relatedEventId: Long? = null,
    val recordingSegmentId: Long? = null,
    val recordingOffsetMs: Long? = null
)

@Entity(
    tableName = "match_squad_players",
    primaryKeys = ["matchId", "playerId"],
    foreignKeys = [
        ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Player::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("matchId"), Index("playerId"), Index("availability"), Index("state")]
)
data class MatchSquadPlayer(
    val matchId: Long,
    val playerId: Long,
    val availability: AvailabilityStatus = AvailabilityStatus.UNKNOWN,
    val selected: Boolean = false,
    val state: PlayerMatchState = PlayerMatchState.UNAVAILABLE,
    val isGoalkeeper: Boolean = false,
    val matchRole: String = ""
)

@Entity(
    tableName = "match_lineup_placements",
    primaryKeys = ["matchId", "playerId"],
    foreignKeys = [
        ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Player::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("matchId"), Index("playerId")]
)
data class MatchLineupPlacement(
    val matchId: Long,
    val playerId: Long,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.85f,
    val role: String = "",
    val formationSlot: String = "",
    val onPitch: Boolean = false
)

@Entity(
    tableName = "match_periods",
    foreignKeys = [ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("matchId"), Index(value = ["matchId", "periodNumber"], unique = true)]
)
data class MatchPeriod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val periodNumber: Int,
    val plannedDurationMs: Long,
    val status: PeriodStatus = PeriodStatus.NOT_STARTED,
    val startMatchTimeMs: Long? = null,
    val endMatchTimeMs: Long? = null
)

@Entity(
    tableName = "match_clock_segments",
    foreignKeys = [
        ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MatchPeriod::class, parentColumns = ["id"], childColumns = ["periodId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("matchId"), Index("periodId")]
)
data class MatchClockSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val periodId: Long,
    val startMatchTimeMs: Long,
    val monotonicStartMs: Long,
    val wallClockStartMs: Long,
    val monotonicEndMs: Long? = null,
    val wallClockEndMs: Long? = null,
    val stopReason: StopReason? = null
)

@Entity(
    tableName = "player_participations",
    foreignKeys = [
        ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MatchPeriod::class, parentColumns = ["id"], childColumns = ["periodId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Player::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("matchId"), Index("periodId"), Index("playerId")]
)
data class PlayerParticipation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val periodId: Long,
    val playerId: Long,
    val startMatchTimeMs: Long,
    val endMatchTimeMs: Long? = null,
    val entryReason: ParticipationReason = ParticipationReason.STARTER,
    val exitReason: ParticipationReason? = null
)

@Entity(
    tableName = "recording_segments",
    foreignKeys = [ForeignKey(entity = GameMatch::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("matchId"), Index("status")]
)
data class RecordingSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val uri: String? = null,
    val status: RecordingStatus = RecordingStatus.PREPARING,
    val matchClockStartMs: Long,
    @ColumnInfo(defaultValue = "NULL") val startedAtEpochMs: Long? = null,
    val matchClockEndMs: Long? = null,
    val recordingDurationMs: Long = 0,
    val orientationDegrees: Int = 0,
    val audioEnabled: Boolean = false,
    val bytesRecorded: Long = 0,
    val errorMessage: String? = null
)
