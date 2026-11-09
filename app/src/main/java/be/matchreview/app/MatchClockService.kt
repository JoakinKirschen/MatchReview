package be.matchreview.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchClockSegment
import be.matchreview.app.data.MatchPeriod
import be.matchreview.app.data.MatchStatus
import be.matchreview.app.data.Team
import be.matchreview.app.domain.LiveClockRules
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.PeriodRecordingRules
import be.matchreview.app.domain.PeriodTimeRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Shows the running match clock and score in the notification bar and gives the
 * period-end alert while the phone is locked or another app is open. Stops itself
 * when no match is in play.
 */
class MatchClockService : LifecycleService() {
    private val app by lazy { application as MatchReviewApplication }
    private var observer: Job? = null
    private var alertJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private data class Snapshot(
        val match: GameMatch,
        val segments: List<MatchClockSegment>,
        val periods: List<MatchPeriod>,
        val teams: List<Team>
    )

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Android requires the foreground notification right after a foreground start.
        val started = runCatching {
            ServiceCompat.startForeground(
                this, CLOCK_NOTIFICATION_ID, baseNotification(null).setContentText("Match in progress").build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) observer = lifecycleScope.launch { observe() }
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observe() {
        val repository = app.repository
        repository.activeMatch.flatMapLatest { match ->
            if (match == null || match.status !in LiveClockRules.ACTIVE_STATUSES) flowOf(null)
            else combine(
                repository.match(match.id),
                repository.clockSegments(match.id),
                repository.periods(match.id),
                repository.teams
            ) { current, segments, periods, teams ->
                current?.let { Snapshot(it, segments, periods, teams) }
            }
        }.collect { snapshot ->
            if (snapshot == null || snapshot.match.status !in LiveClockRules.ACTIVE_STATUSES) {
                stopClock()
            } else {
                show(snapshot)
            }
        }
    }

    private fun show(snapshot: Snapshot) {
        val match = snapshot.match
        val clock = LiveClockRules.clock(
            match, snapshot.segments, snapshot.periods,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()
        )
        val teamName = snapshot.teams.firstOrNull { it.id == match.teamId }?.name ?: "Our team"
        val period = "Period ${match.currentPeriod} of ${match.periodCount}"
        val builder = baseNotification(match.id)
            .setContentTitle("$teamName ${match.ourScore}–${match.opponentScore} ${match.opponent}")
        when {
            match.status == MatchStatus.PERIOD_ENDED ->
                builder.setContentText("Break after period ${match.currentPeriod} • ${MatchClockCalculator.formatClock(clock.matchTimeMs)} played")
            clock.running -> {
                val overtime = PeriodTimeRules.isOver(clock.periodTimeMs, clock.plannedPeriodMs)
                builder
                    .setContentText(if (overtime) "$period • time is up" else "$period • ${clock.plannedPeriodMs / 60_000} min")
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - clock.periodTimeMs)
            }
            else -> builder.setContentText("$period • paused at ${MatchClockCalculator.formatClock(clock.periodTimeMs)}")
        }
        notify(CLOCK_NOTIFICATION_ID, builder.build())
        schedulePeriodAlert(match, clock.matchTimeMs, clock.msUntilPeriodEnd, clock.periodTimeMs, clock.plannedPeriodMs, clock.running)
    }

    /** Lets the camera stop at the planned time, if the coach chose that. */
    private fun plannedTimeReached(match: GameMatch, matchTimeMs: Long, periodTimeMs: Long, plannedMs: Long) {
        app.periodRecording.onPlannedTimeReached(
            match.id, PeriodRecordingRules.plannedEndMatchMs(matchTimeMs, periodTimeMs, plannedMs)
        )
    }

    private fun schedulePeriodAlert(
        match: GameMatch,
        matchTimeMs: Long,
        msUntilEnd: Long?,
        periodTimeMs: Long,
        plannedMs: Long,
        running: Boolean
    ) {
        alertJob?.cancel()
        alertJob = null
        if (!running) {
            releaseWakeLock()
            return
        }
        if (msUntilEnd == null) {
            // Already over: alert only if it just happened (for example the app was restarted).
            if (PeriodTimeRules.shouldAlert(periodTimeMs, plannedMs, alreadyAlerted = false)) {
                alert(match)
                plannedTimeReached(match, matchTimeMs, periodTimeMs, plannedMs)
            }
            releaseWakeLock()
            return
        }
        // Keep the processor awake so the alert is on time with the screen off.
        acquireWakeLock(msUntilEnd + 60_000L)
        alertJob = lifecycleScope.launch {
            delay(msUntilEnd)
            alert(match)
            plannedTimeReached(match, matchTimeMs, periodTimeMs, plannedMs)
            releaseWakeLock()
        }
    }

    private fun alert(match: GameMatch) {
        if (!MatchAlerts.claimPeriodAlert(this, match.id, match.currentPeriod)) return
        MatchAlerts.vibratePeriodEnd(this)
        val period = if (match.currentPeriod >= match.periodCount) "The last period" else "Period ${match.currentPeriod}"
        notify(
            ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("$period time is up")
                .setContentText("${match.periodDurationMinutes} minutes played. End the period in MatchReview.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(openIntent(match.id))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build()
        )
    }

    private fun stopClock() {
        alertJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun baseNotification(matchId: Long?) = NotificationCompat.Builder(this, CLOCK_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openIntent(matchId))
        .setShowWhen(false)

    private fun openIntent(matchId: Long?): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { matchId?.let { putExtra(MainActivity.EXTRA_OPEN_LIVE_MATCH, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(id: Int, notification: Notification) {
        // Without the notification permission the clock still runs; only the bar stays empty.
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MatchReview:periodAlert")
            ?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CLOCK_CHANNEL_ID, "Match clock", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "The running match clock and score" }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Period end", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when the planned period time is reached"
                // The app vibrates with its own strong pattern.
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val CLOCK_CHANNEL_ID = "match_clock"
        private const val ALERT_CHANNEL_ID = "period_end"
        private const val CLOCK_NOTIFICATION_ID = 41
        private const val ALERT_NOTIFICATION_ID = 42

        /** Starts the notification clock; call while the app is on screen. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MatchClockService::class.java))
            }
        }
    }
}
