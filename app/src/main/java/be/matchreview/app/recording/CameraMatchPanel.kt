package be.matchreview.app.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.os.StatFs
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.StorageHealthRules
import be.matchreview.app.domain.StorageLevel
import kotlinx.coroutines.delay

@Composable
fun CameraMatchPanel(
    matchId: Long,
    matchClockMs: Long,
    recordings: List<RecordingSegment>,
    quickActionsEnabled: Boolean,
    onOurGoal: () -> Unit,
    onOpponentGoal: () -> Unit,
    onSubstitution: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by CameraRecordingController.state.collectAsState()
    val lens by CameraRecordingController.lens.collectAsState()
    val torch by CameraRecordingController.torchEnabled.collectAsState()
    var audioEnabled by rememberSaveable { mutableStateOf(true) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val activeMatchId = state.activeMatchId
    val recordingOtherMatch = activeMatchId != null && activeMatchId != matchId
    var storageHealth by remember {
        mutableStateOf(StorageHealthRules.evaluate(Long.MAX_VALUE))
    }

    LaunchedEffect(Unit) {
        while (true) {
            val path = runCatching {
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            }.getOrElse { context.filesDir }
            storageHealth = runCatching {
                StorageHealthRules.evaluate(StatFs(path.absolutePath).availableBytes)
            }.getOrElse { StorageHealthRules.evaluate(0L) }
            delay(5_000)
        }
    }

    fun permissionsNeeded(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (audioEnabled) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun currentStorageHealth() = runCatching {
        val path = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        StorageHealthRules.evaluate(StatFs(path.absolutePath).availableBytes)
    }.getOrElse { StorageHealthRules.evaluate(0L) }

    fun hasRequiredPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            (!audioEnabled ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    fun startRecording() {
        // Re-check right before starting: the periodic check may be up to 5 s old.
        storageHealth = currentStorageHealth()
        if (!storageHealth.canStartRecording) {
            permissionMessage = "Not enough free storage to start a safe recording."
            return
        }
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val intent = Intent(context, MatchRecordingService::class.java)
            .setAction(MatchRecordingService.ACTION_START)
            .putExtra(MatchRecordingService.EXTRA_MATCH_ID, matchId)
            .putExtra(MatchRecordingService.EXTRA_MATCH_CLOCK_START_MS, matchClockMs)
            .putExtra(MatchRecordingService.EXTRA_AUDIO_ENABLED, audioEnabled)
            .putExtra(MatchRecordingService.EXTRA_ORIENTATION_DEGREES, degrees)
            .putExtra(MatchRecordingService.EXTRA_LENS, lens.name)
        ContextCompat.startForegroundService(context, intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (grants[Manifest.permission.CAMERA] == true &&
            (!audioEnabled || grants[Manifest.permission.RECORD_AUDIO] == true)
        ) {
            permissionMessage = null
            if (pendingStart) startRecording()
        } else {
            permissionMessage = "Camera permission is required. Microphone permission is only required when audio is enabled."
        }
        pendingStart = false
    }

    // While no recording runs, show a live preview bound to this screen so the camera
    // can be aimed before recording. The recording service takes the camera over.
    val previewWanted = cameraPermissionGranted &&
        (state is CameraRecordingState.Idle || state is CameraRecordingState.Error)
    DisposableEffect(previewWanted, lens, previewView, lifecycleOwner) {
        val target = previewView
        if (!previewWanted || target == null) {
            onDispose { }
        } else {
            val idlePreview = Preview.Builder().build()
            val future = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            var disposed = false
            future.addListener({
                if (disposed) return@addListener
                runCatching {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    idlePreview.setSurfaceProvider(target.surfaceProvider)
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        if (lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA,
                        idlePreview
                    )
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                runCatching { provider?.unbind(idlePreview) }
            }
        }
    }

    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(18.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }.also { previewView = it }
                },
                update = { CameraRecordingController.attachPreview(it.surfaceProvider) },
                modifier = Modifier.fillMaxSize()
            )

            val recordingState = state as? CameraRecordingState.Recording
            if (recordingState != null) {
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(recordingState.segmentId) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(250)
                    }
                }
                Surface(
                    color = Color(0xCCB00020),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                ) {
                    Text(
                        "● REC ${MatchClockCalculator.formatClock(now - recordingState.startedAtElapsedRealtimeMs)}",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            Surface(
                color = Color(0xAA000000),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Text(
                    "Match ${MatchClockCalculator.formatClock(matchClockMs)}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            if (state is CameraRecordingState.Error) {
                Text(
                    (state as CameraRecordingState.Error).message,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            }

            if (quickActionsEnabled) {
                Surface(
                    color = Color(0xAA101810),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(onClick = onOurGoal) { Text("⚽ Goal") }
                        FilledTonalButton(onClick = onSubstitution) { Text("↔ Sub") }
                        FilledTonalButton(onClick = onOpponentGoal) { Text("Opponent") }
                    }
                }
            }
        }

        if (storageHealth.level != StorageLevel.OK) {
            Surface(
                color = if (storageHealth.level == StorageLevel.CRITICAL)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (storageHealth.level == StorageLevel.CRITICAL)
                        "Recording blocked: only ${StorageHealthRules.formatAvailable(storageHealth.availableBytes)} free. Free at least 250 MB."
                    else
                        "Low storage: ${StorageHealthRules.formatAvailable(storageHealth.availableBytes)} free. Shorter recording segments are recommended.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (recordingOtherMatch) {
            Text(
                "The camera is recording another match. Stop that recording from the notification first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        permissionMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = {
                    CameraRecordingController.chooseLens(
                        if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Switch camera" },
                enabled = state is CameraRecordingState.Idle || state is CameraRecordingState.Error
            ) { Text(if (lens == CameraLens.BACK) "↺" else "↻") }

            FilledIconButton(
                onClick = {
                    when (state) {
                        is CameraRecordingState.Recording -> {
                            MatchRecordingService.requestStop(context, matchClockMs)
                        }
                        is CameraRecordingState.Idle, is CameraRecordingState.Error -> {
                            if (!storageHealth.canStartRecording) {
                                permissionMessage = "Not enough free storage to start a safe recording."
                            } else if (hasRequiredPermissions()) {
                                startRecording()
                            } else {
                                pendingStart = true
                                permissionLauncher.launch(permissionsNeeded())
                            }
                        }
                        else -> Unit
                    }
                },
                enabled = !recordingOtherMatch,
                modifier = Modifier
                    .size(72.dp)
                    .semantics {
                        contentDescription = if (state is CameraRecordingState.Recording)
                            "Stop and save recording" else "Start recording"
                    },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (state is CameraRecordingState.Recording)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (state is CameraRecordingState.Recording) "■" else "●")
            }

            IconButton(
                onClick = { CameraRecordingController.setTorch(!torch) },
                modifier = Modifier.semantics {
                    contentDescription = if (torch) "Turn torch off" else "Turn torch on"
                },
                enabled = lens == CameraLens.BACK &&
                    (state is CameraRecordingState.Recording || state is CameraRecordingState.Preparing)
            ) { Text(if (torch) "☀" else "◐") }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = audioEnabled,
                    onCheckedChange = { audioEnabled = it },
                    enabled = state !is CameraRecordingState.Recording &&
                        state !is CameraRecordingState.Preparing &&
                        state !is CameraRecordingState.Finalizing
                )
                Spacer(Modifier.width(8.dp))
                Text(if (audioEnabled) "Audio on" else "Audio off")
            }
            Text(
                when (state) {
                    CameraRecordingState.Idle -> "Ready"
                    is CameraRecordingState.Preparing -> "Preparing…"
                    is CameraRecordingState.Recording -> "Recording"
                    is CameraRecordingState.Finalizing -> "Saving…"
                    is CameraRecordingState.Error -> "Check camera"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Text("Video segments", style = MaterialTheme.typography.titleSmall)
        if (recordings.isEmpty()) {
            Text("No clips recorded yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 120.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(recordings.asReversed(), key = { it.id }) { segment ->
                    ListItem(
                        headlineContent = {
                            Text(
                                "${MatchClockCalculator.formatClock(segment.matchClockStartMs)} – " +
                                    (segment.matchClockEndMs?.let(MatchClockCalculator::formatClock) ?: "now")
                            )
                        },
                        supportingContent = {
                            Text(
                                when (segment.status) {
                                    RecordingStatus.COMPLETED ->
                                        "${segment.recordingDurationMs / 1000}s • ${if (segment.audioEnabled) "audio" else "silent"}"
                                    RecordingStatus.FAILED, RecordingStatus.INTERRUPTED ->
                                        segment.errorMessage ?: segment.status.name.lowercase()
                                    else -> segment.status.name.lowercase()
                                }
                            )
                        },
                        trailingContent = { Text(segment.status.name.take(4)) }
                    )
                }
            }
        }
    }
}
