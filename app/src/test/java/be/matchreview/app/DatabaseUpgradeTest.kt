package be.matchreview.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.matchreview.app.data.AppDatabase
import be.matchreview.app.data.MatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades a version-1 database through every migration and opens it with Room.
 * Room validates the migrated tables, columns, defaults, indices and foreign keys
 * against the current entities, so any drift between migrations and entities fails here.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "upgrade-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1DatabaseUpgradesToCurrentSchemaAndKeepsData() {
        context.deleteDatabase(name)
        val version1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_1_SCHEMA.forEach { db.execSQL(it) }
                        db.execSQL("INSERT INTO teams (id, name, club, ageGroup, season) VALUES (1, 'U12', 'Club', 'U12', '2025/26')")
                        db.execSQL("INSERT INTO players (id, teamId, name, shirtNumber, position, preferredFoot, notes) VALUES (1, 1, 'Sam', 9, 'FW', '', '')")
                        db.execSQL(
                            "INSERT INTO matches (id, teamId, opponent, matchDate, venue, competition, isHome, ourScore, opponentScore, formation, videoUri, teamRating, reviewNotes) " +
                                "VALUES (1, 1, 'Rivals', '2025-05-01', '', '', 1, 2, 1, '4-3-3', NULL, 7, 'Good')"
                        )
                        db.execSQL("INSERT INTO events (id, matchId, playerId, timestampMs, type, sentiment, note) VALUES (1, 1, 1, 65000, 'Goal', 'Positive', '')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        version1.writableDatabase.close()
        version1.close()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening runs the migrations and Room's schema validation.
            database.openHelper.writableDatabase
            val dao = database.matchDao()
            runBlocking {
                val match = dao.getMatchOnce(1)!!
                assertEquals(MatchStatus.DRAFT, match.status)
                assertEquals(2, match.ourScore)
                assertEquals(7, match.teamRating)
                assertFalse(dao.observeAllPlayers().first().single().archived)
                assertEquals(0, dao.getEventOnce(1)!!.periodNumber)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        /** The original schema, before match-day features were added. */
        val VERSION_1_SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS teams (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, club TEXT NOT NULL, ageGroup TEXT NOT NULL, season TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS players (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                teamId INTEGER NOT NULL, name TEXT NOT NULL, shirtNumber INTEGER NOT NULL,
                position TEXT NOT NULL, preferredFoot TEXT NOT NULL, notes TEXT NOT NULL,
                FOREIGN KEY(teamId) REFERENCES teams(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX IF NOT EXISTS index_players_teamId ON players(teamId)",
            """CREATE TABLE IF NOT EXISTS matches (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                teamId INTEGER NOT NULL, opponent TEXT NOT NULL, matchDate TEXT NOT NULL,
                venue TEXT NOT NULL, competition TEXT NOT NULL, isHome INTEGER NOT NULL,
                ourScore INTEGER NOT NULL, opponentScore INTEGER NOT NULL, formation TEXT NOT NULL,
                videoUri TEXT, teamRating INTEGER NOT NULL, reviewNotes TEXT NOT NULL,
                FOREIGN KEY(teamId) REFERENCES teams(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
            "CREATE INDEX IF NOT EXISTS index_matches_teamId ON matches(teamId)",
            """CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                matchId INTEGER NOT NULL, playerId INTEGER, timestampMs INTEGER NOT NULL,
                type TEXT NOT NULL, sentiment TEXT NOT NULL, note TEXT NOT NULL,
                FOREIGN KEY(matchId) REFERENCES matches(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(playerId) REFERENCES players(id) ON UPDATE NO ACTION ON DELETE SET NULL)""",
            "CREATE INDEX IF NOT EXISTS index_events_matchId ON events(matchId)",
            "CREATE INDEX IF NOT EXISTS index_events_playerId ON events(playerId)"
        )
    }
}
