package be.matchreview.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.matchreview.app.data.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test
    fun migration2To3AddsRecordingStartEpoch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3.db"
        context.deleteDatabase(name)

        val version2 = helper(context, name, 2, object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE recording_segments (
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
                        errorMessage TEXT
                    )"""
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        version2.writableDatabase
        version2.close()

        val version3 = helper(context, name, 3, object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_2_3.migrate(db)
            }
        })
        val db = version3.writableDatabase
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(recording_segments)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue(columns.contains("startedAtEpochMs"))
        version3.close()
        context.deleteDatabase(name)
    }

    private fun helper(
        context: Context,
        name: String,
        version: Int,
        callback: SupportSQLiteOpenHelper.Callback
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
    )
}
