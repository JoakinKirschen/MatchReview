package be.matchreview.app.recording

import be.matchreview.app.i18n.tr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import be.matchreview.app.MainActivity
import be.matchreview.app.MatchReviewApplication
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.RecordingRules
import be.matchreview.app.domain.StorageHealthRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchRecordingService : LifecycleService() {
    private val app by lazy { application as MatchReviewApplication }
    private val repository by lazy { app.repository }
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var recording: Recording? = null
    private var segmentId: Long? = null
    private var matchId: Long = 0L
    private var matchClockStartMs: Long = 0L
    private var requestedMatchClockEndMs: Long? = null
    private var audioEnabled = false
    private var orientationDegrees = 0
    private var startedAtElapsedRealtimeMs = 0L
    private var lens = CameraLens.BACK
    private var stoppingByUser = false
    private var cancelledBeforeStart = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CameraRecordingController.setPreviewConsumer { provider ->
            preview?.setSurfaceProvider(provider)
        }
        CameraRecordingController.setTorchConsumer { enabled ->
            runCatching {
                camera?.cameraControl?.enableTorch(enabled)
                CameraRecordingController.updateTorch(enabled)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        CameraRecordingController.setPreviewConsumer(null)
        CameraRecordingController.setTorchConsumer(null)
        val active = recording
        if (active != null) {
            // CameraX delivers the Finalize event after this point; it is persisted
            // through the application scope, which outlives the service.
            stoppingByUser = false
            active.stop()
        } else {
            abandonPreparingSegment(tr("Recording stopped before it started"))
        }
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRequested(intent)
            ACTION_STOP, ACTION_STOP_FROM_NOTIFICATION -> {
                requestedMatchClockEndMs = intent.getLongExtra(EXTRA_MATCH_CLOCK_END_MS, -1L)
                    .takeIf { it >= 0L }
                stopRequested()
            }
            else -> Unit
        }
        return Service.START_NOT_STICKY
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startRequested(intent: Intent) {
        val busy = recording != null || segmentId != null ||
            CameraRecordingController.state.value is CameraRecordingState.Preparing
        if (busy) {
            // startForegroundService() was called again; confirm the running foreground state.
            enterForeground(tr("Recording match video"), audioEnabled)
            return
        }
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, false) &&
            hasPermission(Manifest.permission.RECORD_AUDIO)

        // startForeground() must follow startForegroundService() even when the start
        // is refused below, otherwise Android crashes the app.
        val foreground = hasPermission(Manifest.permission.CAMERA) &&
            enterForeground(tr("Preparing camera…"), audioEnabled)
        if (!foreground) {
            refuseStart(
                if (hasPermission(Manifest.permission.CAMERA)) tr("Unable to start the camera service")
                else tr("Camera permission is required")
            )
            return
        }
        if (!RecordingPermissions.canSaveVideo(this)) {
            refuseStart(tr("Storage permission is required to save the video"))
            return
        }

        val movies = runCatching {
            getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        }.getOrElse { filesDir }
        val storage = runCatching {
            StorageHealthRules.evaluate(StatFs(movies.absolutePath).availableBytes)
        }.getOrElse { StorageHealthRules.evaluate(0L) }
        if (!storage.canStartRecording) {
            refuseStart(tr("Not enough free storage ({availableBytes})", "availableBytes" to (StorageHealthRules.formatAvailable(storage.availableBytes))))
            return
        }

        matchId = intent.getLongExtra(EXTRA_MATCH_ID, 0L)
        matchClockStartMs = intent.getLongExtra(EXTRA_MATCH_CLOCK_START_MS, 0L).coerceAtLeast(0L)
        orientationDegrees = intent.getIntExtra(EXTRA_ORIENTATION_DEGREES, 0)
        lens = if (intent.getStringExtra(EXTRA_LENS) == CameraLens.FRONT.name) CameraLens.FRONT else CameraLens.BACK
        cancelledBeforeStart = false
        CameraRecordingController.updateLens(lens)
        CameraRecordingController.updateState(CameraRecordingState.Preparing(matchId))

        val startClock = matchClockStartMs
        val segmentMatchId = matchId
        val segmentOrientation = orientationDegrees
        val segmentAudio = audioEnabled
        app.applicationScope.launch {
            repository.markOpenRecordingsInterrupted(tr("Recording was interrupted before finalization"))
            val id = repository.beginRecordingSegment(
                matchId = segmentMatchId,
                matchClockStartMs = startClock,
                orientationDegrees = segmentOrientation,
                audioEnabled = segmentAudio
            )
            withContext(Dispatchers.Main) {
                if (destroyed || cancelledBeforeStart) {
                    failSegment(id, startClock, tr("Recording cancelled before it started"))
                } else {
                    segmentId = id
                    bindCamera()
                }
            }
        }
    }

    private fun refuseStart(message: String) {
        CameraRecordingController.updateState(CameraRecordingState.Error(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (destroyed || cancelledBeforeStart) return@addListener
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                preview = Preview.Builder().build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                // Record in the orientation the phone was held at the start, so a match
                // filmed in landscape plays back in landscape.
                val videoCapture = VideoCapture.Builder(recorder)
                    .setTargetRotation(surfaceRotation(orientationDegrees))
                    .build()
                val selector = if (lens == CameraLens.FRONT)
                    CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                camera = provider.bindToLifecycle(this, selector, preview, videoCapture)
                CameraRecordingController.setPreviewConsumer { surfaceProvider ->
                    preview?.setSurfaceProvider(surfaceProvider)
                }
                startCameraXRecording(videoCapture)
            }.onFailure { failCurrent(tr("Unable to open camera: {message}", "message" to it.message)) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCameraXRecording(videoCapture: VideoCapture<Recorder>) {
        val id = segmentId ?: return failCurrent(tr("Recording segment was not created"))
        val fileName = "MatchReview_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MatchReview")
            } else {
                // Android 9 and older have no relative path: name the file in Movies/MatchReview.
                @Suppress("DEPRECATION")
                val folder = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MatchReview"
                ).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, java.io.File(folder, "$fileName.mp4").absolutePath)
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending: PendingRecording = videoCapture.output.prepareRecording(this, output)
        if (audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }

        val recordingMatchId = matchId
        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    app.applicationScope.launch {
                        repository.markRecordingStarted(id, System.currentTimeMillis())
                    }
                    CameraRecordingController.updateState(
                        CameraRecordingState.Recording(id, recordingMatchId, startedAtElapsedRealtimeMs, audioEnabled)
                    )
                    updateNotification(tr("Recording match video"))
                }
                is VideoRecordEvent.Finalize -> finalizeRecording(id, recordingMatchId, event)
            }
        }
    }

    private fun stopRequested() {
        val active = recording
        if (active == null) {
            when (CameraRecordingController.state.value) {
                // The clip is being saved; the save itself stops the service.
                is CameraRecordingState.Finalizing -> return
                is CameraRecordingState.Preparing -> {
                    cancelledBeforeStart = true
                    abandonPreparingSegment(tr("Recording cancelled before it started"))
                }
                else -> Unit
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val id = segmentId ?: return
        stoppingByUser = true
        CameraRecordingController.updateState(CameraRecordingState.Finalizing(id, matchId))
        updateNotification(tr("Saving video…"))
        active.stop()
    }

    private fun finalizeRecording(id: Long, recordingMatchId: Long, event: VideoRecordEvent.Finalize) {
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
        val bytesRecorded = event.recordingStats.numBytesRecorded
        val endClock = RecordingRules.endMatchClockMs(matchClockStartMs, requestedMatchClockEndMs, durationMs)
        val uri = event.outputResults.outputUri.takeIf { it != android.net.Uri.EMPTY }?.toString()
        val error = if (event.hasError()) finalizeErrorMessage(event) else null
        val keepFile = RecordingRules.keepsRecordedFile(
            finalizeSucceeded = !event.hasError(),
            errorLeavesPlayableFile = event.error in PLAYABLE_FINALIZE_ERRORS,
            hasOutputUri = uri != null,
            bytesRecorded = bytesRecorded
        )
        val failedStatus = RecordingRules.terminalStatus(
            cameraFinalizeSucceeded = false,
            userRequestedStop = stoppingByUser
        )

        recording?.close()
        recording = null
        segmentId = null
        requestedMatchClockEndMs = null
        stoppingByUser = false
        CameraRecordingController.updateTorch(false)
        CameraRecordingController.updateState(CameraRecordingState.Finalizing(id, recordingMatchId))

        // Persist before stopping the service: stopping destroys the service and would
        // cancel any work started in its own lifecycle scope.
        app.applicationScope.launch {
            runCatching {
                if (keepFile) {
                    repository.completeRecordingSegment(
                        recordingId = id,
                        uri = uri,
                        matchClockEndMs = endClock,
                        recordingDurationMs = durationMs,
                        bytesRecorded = bytesRecorded,
                        warning = error
                    )
                } else {
                    repository.failRecordingSegment(
                        recordingId = id,
                        status = failedStatus,
                        matchClockEndMs = endClock,
                        recordingDurationMs = durationMs,
                        errorMessage = error ?: tr("Recording failed"),
                        uri = uri
                    )
                }
            }
            withContext(Dispatchers.Main) {
                CameraRecordingController.updateState(
                    when {
                        error == null -> CameraRecordingState.Idle
                        keepFile -> CameraRecordingState.Error(
                            tr("Recording stopped ({error}). The clip was saved; start recording again to continue.", "error" to error)
                        )
                        else -> CameraRecordingState.Error(error)
                    }
                )
                if (!destroyed) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun finalizeErrorMessage(event: VideoRecordEvent.Finalize): String =
        when (event.error) {
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> tr("File-size limit reached")
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> tr("Insufficient storage")
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> tr("Camera source became inactive")
            else -> event.cause?.message ?: tr("CameraX finalize error {error}", "error" to event.error)
        }

    private fun failCurrent(message: String) {
        val id = segmentId
        segmentId = null
        recording?.close()
        recording = null
        if (id != null) failSegment(id, matchClockStartMs, message)
        CameraRecordingController.updateState(CameraRecordingState.Error(message))
        if (!destroyed) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Marks a segment that never started recording as failed and clears the preparing state. */
    private fun abandonPreparingSegment(message: String) {
        val id = segmentId
        segmentId = null
        if (id != null) failSegment(id, matchClockStartMs, message)
        if (CameraRecordingController.state.value is CameraRecordingState.Preparing) {
            CameraRecordingController.updateState(CameraRecordingState.Idle)
        }
    }

    private fun failSegment(id: Long, matchClockMs: Long, message: String) {
        app.applicationScope.launch {
            runCatching {
                repository.failRecordingSegment(
                    recordingId = id,
                    status = RecordingStatus.FAILED,
                    matchClockEndMs = matchClockMs,
                    recordingDurationMs = 0L,
                    errorMessage = message
                )
            }
        }
    }

    private fun enterForeground(text: String, withMicrophone: Boolean): Boolean = runCatching {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    (if (withMicrophone) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            else 0
        )
    }.isSuccess

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MatchRecordingService::class.java).setAction(ACTION_STOP_FROM_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(tr("MatchReview camera"))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, tr("Stop and save"), stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    tr("Match recording"),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = tr("Keeps match video recording active") }
            )
        }
    }

    companion object {
        const val ACTION_START = "be.matchreview.app.recording.START"
        const val ACTION_STOP = "be.matchreview.app.recording.STOP"
        const val ACTION_STOP_FROM_NOTIFICATION = "be.matchreview.app.recording.STOP_NOTIFICATION"
        const val EXTRA_MATCH_ID = "matchId"
        const val EXTRA_MATCH_CLOCK_START_MS = "matchClockStartMs"
        const val EXTRA_MATCH_CLOCK_END_MS = "matchClockEndMs"
        const val EXTRA_AUDIO_ENABLED = "audioEnabled"
        const val EXTRA_ORIENTATION_DEGREES = "orientationDegrees"
        const val EXTRA_LENS = "lens"
        private const val CHANNEL_ID = "match_recording"
        private const val NOTIFICATION_ID = 811

        /** Finalize errors after which CameraX has still written a playable file. */
        private val PLAYABLE_FINALIZE_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
        )

        /**
         * Stops and saves the running recording, if any. The service is already in the
         * foreground while recording, so a plain start request is used.
         */
        fun requestStart(
            context: Context,
            matchId: Long,
            matchClockStartMs: Long,
            audioEnabled: Boolean,
            lens: CameraLens,
            orientationDegrees: Int = currentOrientationDegrees(context)
        ) {
            val intent = Intent(context, MatchRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .putExtra(EXTRA_MATCH_CLOCK_START_MS, matchClockStartMs.coerceAtLeast(0L))
                .putExtra(EXTRA_AUDIO_ENABLED, audioEnabled)
                .putExtra(EXTRA_ORIENTATION_DEGREES, orientationDegrees)
                .putExtra(EXTRA_LENS, lens.name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** How far the screen is turned from its natural orientation, in degrees. */
        fun currentOrientationDegrees(context: Context): Int =
            when (runCatching { ContextCompat.getDisplayOrDefault(context).rotation }.getOrDefault(Surface.ROTATION_0)) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        private fun surfaceRotation(degrees: Int): Int = when (degrees) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }

        fun requestStop(context: Context, matchClockEndMs: Long?) {
            if (!CameraRecordingController.state.value.isBusy) return
            val intent = Intent(context, MatchRecordingService::class.java).setAction(ACTION_STOP)
            matchClockEndMs?.let { intent.putExtra(EXTRA_MATCH_CLOCK_END_MS, it.coerceAtLeast(0L)) }
            runCatching { context.startService(intent) }
        }
    }
}
