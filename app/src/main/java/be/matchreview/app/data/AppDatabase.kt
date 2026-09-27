package be.matchreview.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Team::class,
        Player::class,
        GameMatch::class,
        MatchEvent::class,
        MatchSquadPlayer::class,
        MatchLineupPlacement::class,
        MatchPeriod::class,
        MatchClockSegment::class,
        PlayerParticipation::class,
        RecordingSegment::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matchDao(): MatchDao

    companion object {
        const val DATABASE_VERSION = 4

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE matches ADD COLUMN status TEXT NOT NULL DEFAULT 'DRAFT'")
                db.execSQL("ALTER TABLE matches ADD COLUMN periodCount INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE matches ADD COLUMN periodDurationMinutes INTEGER NOT NULL DEFAULT 45")
                db.execSQL("ALTER TABLE matches ADD COLUMN playersOnPitch INTEGER NOT NULL DEFAULT 11")
                db.execSQL("ALTER TABLE matches ADD COLUMN rollingSubstitutions INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE matches ADD COLUMN currentPeriod INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE matches ADD COLUMN accumulatedMatchTimeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE matches ADD COLUMN clockRunning INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_matches_status ON matches(status)")

                db.execSQL("ALTER TABLE events ADD COLUMN periodNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN occurredAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE events ADD COLUMN relatedPlayerId INTEGER")
                db.execSQL("ALTER TABLE events ADD COLUMN relatedEventId INTEGER")
                db.execSQL("ALTER TABLE events ADD COLUMN recordingSegmentId INTEGER")
                db.execSQL("ALTER TABLE events ADD COLUMN recordingOffsetMs INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_relatedEventId ON events(relatedEventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_recordingSegmentId ON events(recordingSegmentId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS match_squad_players (
                        matchId INTEGER NOT NULL,
                        playerId INTEGER NOT NULL,
                        availability TEXT NOT NULL,
                        selected INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        isGoalkeeper INTEGER NOT NULL,
                        matchRole TEXT NOT NULL,
                        PRIMARY KEY(matchId, playerId),
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE,
                        FOREIGN KEY(playerId) REFERENCES players(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_squad_players_matchId ON match_squad_players(matchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_squad_players_playerId ON match_squad_players(playerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_squad_players_availability ON match_squad_players(availability)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_squad_players_state ON match_squad_players(state)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS match_lineup_placements (
                        matchId INTEGER NOT NULL,
                        playerId INTEGER NOT NULL,
                        normalizedX REAL NOT NULL,
                        normalizedY REAL NOT NULL,
                        role TEXT NOT NULL,
                        formationSlot TEXT NOT NULL,
                        onPitch INTEGER NOT NULL,
                        PRIMARY KEY(matchId, playerId),
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE,
                        FOREIGN KEY(playerId) REFERENCES players(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_lineup_placements_matchId ON match_lineup_placements(matchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_lineup_placements_playerId ON match_lineup_placements(playerId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS match_periods (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        matchId INTEGER NOT NULL,
                        periodNumber INTEGER NOT NULL,
                        plannedDurationMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        startMatchTimeMs INTEGER,
                        endMatchTimeMs INTEGER,
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_periods_matchId ON match_periods(matchId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_match_periods_matchId_periodNumber ON match_periods(matchId, periodNumber)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS match_clock_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        matchId INTEGER NOT NULL,
                        periodId INTEGER NOT NULL,
                        startMatchTimeMs INTEGER NOT NULL,
                        monotonicStartMs INTEGER NOT NULL,
                        wallClockStartMs INTEGER NOT NULL,
                        monotonicEndMs INTEGER,
                        wallClockEndMs INTEGER,
                        stopReason TEXT,
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE,
                        FOREIGN KEY(periodId) REFERENCES match_periods(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_clock_segments_matchId ON match_clock_segments(matchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_clock_segments_periodId ON match_clock_segments(periodId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS player_participations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        matchId INTEGER NOT NULL,
                        periodId INTEGER NOT NULL,
                        playerId INTEGER NOT NULL,
                        startMatchTimeMs INTEGER NOT NULL,
                        endMatchTimeMs INTEGER,
                        entryReason TEXT NOT NULL,
                        exitReason TEXT,
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE,
                        FOREIGN KEY(periodId) REFERENCES match_periods(id) ON DELETE CASCADE,
                        FOREIGN KEY(playerId) REFERENCES players(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_participations_matchId ON player_participations(matchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_participations_periodId ON player_participations(periodId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_player_participations_playerId ON player_participations(playerId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recording_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        matchId INTEGER NOT NULL,
                        uri TEXT,
                        status TEXT NOT NULL,
                        matchClockStartMs INTEGER NOT NULL,
                        matchClockEndMs INTEGER,
                        recordingDurationMs INTEGER NOT NULL,
                        orientationDegrees INTEGER NOT NULL,
                        audioEnabled INTEGER NOT NULL,
                        bytesRecorded INTEGER NOT NULL,
                        errorMessage TEXT,
                        FOREIGN KEY(matchId) REFERENCES matches(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recording_segments_matchId ON recording_segments(matchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recording_segments_status ON recording_segments(status)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recording_segments " +
                        "ADD COLUMN startedAtEpochMs INTEGER DEFAULT NULL"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE players ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "match_review.db"
            )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
