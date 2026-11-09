package be.matchreview.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import be.matchreview.app.data.MatchStatus
import be.matchreview.app.domain.PeriodRecordingRules
import be.matchreview.app.domain.PeriodResume
import be.matchreview.app.domain.RecordingStopMode
import be.matchreview.app.domain.StorageBudgetRules
import be.matchreview.app.domain.StorageHealthRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets the camera follow the match periods: ending a period saves the running recording,
 * and starting the next period records again with the same sound and camera. The pending
 * restart is kept in preferences, so it also survives the app being closed at half-time.
 */
class PeriodRecordingController(private val context: Context) {
    private val preferences = context.getSharedPreferences("period_recording", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        // Older versions stored an on/off switch for stopping at the period end.
        if (preferences.contains(KEY_MODE)) RecordingStopMode.fromName(preferences.getString(KEY_MODE, null))
        else if (preferences.getBoolean(KEY_ENABLED, true)) RecordingStopMode.PERIOD_END
        else RecordingStopMode.MANUAL
    )
    /** When recordings stop by themselves; all but manual restart with the next period. */
    val mode: StateFlow<RecordingStopMode> = _mode.asStateFlow()
    private val enabled: Boolean get() = _mode.value.followsPeriods

    private val _pending = MutableStateFlow(PeriodRecordingRules.decode(preferences.getString(KEY_PENDING, null)))
    /** A recording that was stopped at a period end and restarts with the next period. */
    val pending: StateFlow<PeriodResume?> = _pending.asStateFlow()

    fun setMode(value: RecordingStopMode) {
        preferences.edit().putString(KEY_MODE, value.name).apply()
        _mode.value = value
        if (!value.followsPeriods) setPending(null)
    }

    /**
     * Call when the planned time of the current period of [matchId] is reached, at
     * [endMatchClockMs] match time. Saves the running recording when the coach chose to
     * stop filming then; it restarts with the next period.
     */
    fun onPlannedTimeReached(matchId: Long, endMatchClockMs: Long) {
        val recording = CameraRecordingController.state.value as? CameraRecordingState.Recording
        if (!PeriodRecordingRules.stopsAtPlannedTime(_mode.value, recording?.matchId, matchId)) return
        setPending(
            PeriodResume(
                matchId = matchId,
                audioEnabled = recording!!.audioEnabled,
                frontLens = CameraRecordingController.lens.value == CameraLens.FRONT
            )
        )
        MatchRecordingService.requestStop(context, endMatchClockMs)
    }

    /**
     * Call right before a period of [matchId] ends. Remembers the running recording and
     * returns true when it must be stopped and saved.
     */
    fun prepareForPeriodEnd(matchId: Long): Boolean {
        val recording = CameraRecordingController.state.value as? CameraRecordingState.Recording
        if (!PeriodRecordingRules.stopsAtPeriodEnd(enabled, recording?.matchId, matchId)) return false
        setPending(
            PeriodResume(
                matchId = matchId,
                audioEnabled = recording!!.audioEnabled,
                frontLens = CameraRecordingController.lens.value == CameraLens.FRONT
            )
        )
        return true
    }

    /** Forgets a pending restart, for example when the period did not actually end. */
    fun cancel(matchId: Long) {
        if (_pending.value?.matchId == matchId) setPending(null)
    }

    /**
     * Call after a new period of [matchId] has started at [periodStartMatchMs] match time
     * and [periodStartedMonotonicMs] on the elapsed-realtime clock. Restarts the recording
     * that was stopped at the previous period end, if any.
     */
    suspend fun resumeAfterPeriodStart(
        matchId: Long,
        status: MatchStatus?,
        periodStartMatchMs: Long,
        periodStartedMonotonicMs: Long
    ) {
        val resume = _pending.value
        if (!PeriodRecordingRules.resumesAtPeriodStart(enabled, resume, matchId, status)) return
        setPending(null)
        resume!!

        // The previous clip may still be saving; the camera is free again in a moment.
        val state = withTimeoutOrNull(SAVE_WAIT_MS) {
            CameraRecordingController.state.first { it !is CameraRecordingState.Finalizing }
        }
        when (state) {
            null -> return fail("The camera was still saving the previous clip. Start recording again from the camera tab.")
            // The coach already started recording during the break.
            is CameraRecordingState.Recording, is CameraRecordingState.Preparing -> return
            else -> Unit
        }
        if (!granted(Manifest.permission.CAMERA) || !RecordingPermissions.canSaveVideo(context)) {
            return fail("Recording did not restart for the new period: camera or storage permission is missing.")
        }
        val available = runCatching {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            StatFs(path.absolutePath).availableBytes
        }.getOrDefault(0L)
        val budget = StorageBudgetRules.recordingBudget(available)
        if (!StorageHealthRules.evaluate(available).canStartRecording || !budget.canStart) {
            return fail("Recording did not restart for the new period. ${budget.message}")
        }

        val lens = if (resume.frontLens) CameraLens.FRONT else CameraLens.BACK
        withContext(Dispatchers.Main) {
            CameraRecordingController.chooseLens(lens)
            runCatching {
                MatchRecordingService.requestStart(
                    context = context,
                    matchId = matchId,
                    matchClockStartMs = PeriodRecordingRules.restartMatchClockMs(
                        periodStartMatchMs, periodStartedMonotonicMs, SystemClock.elapsedRealtime()
                    ),
                    audioEnabled = resume.audioEnabled && granted(Manifest.permission.RECORD_AUDIO),
                    lens = lens
                )
            }.onFailure { fail("Recording did not restart for the new period: ${it.message ?: "camera unavailable"}") }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun fail(message: String) = withContext(Dispatchers.Main) {
        CameraRecordingController.updateState(CameraRecordingState.Error(message))
    }

    private fun setPending(value: PeriodResume?) {
        preferences.edit().apply {
            if (value == null) remove(KEY_PENDING) else putString(KEY_PENDING, PeriodRecordingRules.encode(value))
        }.apply()
        _pending.value = value
    }

    private companion object {
        const val KEY_ENABLED = "follow_periods"
        const val KEY_MODE = "stop_mode"
        const val KEY_PENDING = "pending_resume"
        const val SAVE_WAIT_MS = 20_000L
    }
}
