package be.matchreview.app

import android.app.Application
import be.matchreview.app.backup.MatchBackupManager
import be.matchreview.app.data.AppDatabase
import be.matchreview.app.data.MatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MatchReviewApplication : Application() {
    /** Outlives activities and services, so writes that must finish are not cancelled. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { MatchRepository(database.matchDao()) }
    val backupManager by lazy { MatchBackupManager(this, database) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.markOpenRecordingsInterrupted(
                "Recording was interrupted because the app or device stopped unexpectedly"
            )
        }
    }
}
