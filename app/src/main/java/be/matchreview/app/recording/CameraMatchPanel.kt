package be.matchreview.app.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
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
import be.matchreview.app.domain.StorageBudgetRules
import be.matchreview.app.domain.StorageLevel
import be.matchreview.app.ui.isLandscape
import kotlinx.coroutines.delay

@Composable
fun CameraMatchPanel(
    matchId: Long,
    matchClockMs: Long,
    recordings: List<RecordingSegment>,
    /** Whether recording stops at each period end and restarts with the next period. */
    followPeriods: Boolean,
    onFollowPeriodsChange: (Boolean) -> Unit,
    /** Opens all saved clips of the match as one video. */
    onWatchVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by CameraRecordingController.state.collectAsState()
    val lens by CameraRecordingController.lens.collectAsState()
    val torch by CameraRecordingController.torchEnabled.collectAsState()
    var audioEnabled by rememberSaveable { mutableStateOf(false) }
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
        val budget = StorageBudgetRules.recordingBudget(storageHealth.availableBytes)
        if (!storageHealth.canStartRecording || !budget.canStart) {
            permissionMessage = budget.message
            return
        }
        val degrees = when (view.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        MatchRecordingService.requestStart(context, matchId, matchClockMs, audioEnabled, lens, degrees)
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

    val cameraIdle = state is CameraRecordingState.Idle || state is CameraRecordingState.Error
    val recording = state is CameraRecordingState.Recording

    fun toggleRecording() {
        when (state) {
            is CameraRecordingState.Recording -> MatchRecordingService.requestStop(context, matchClockMs)
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
    }

    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            val sideControls = maxWidth > maxHeight * 1.2f && maxHeight < 480.dp
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

            // Status top left, match clock top right.
            val recordingState = state as? CameraRecordingState.Recording
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            if (recordingState != null) {
                LaunchedEffect(recordingState.segmentId) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(250)
                    }
                }
            }
            OverlayPill(
                text = when (state) {
                    is CameraRecordingState.Recording ->
                        "● REC ${MatchClockCalculator.formatClock(now - recordingState!!.startedAtElapsedRealtimeMs)}"
                    is CameraRecordingState.Preparing -> "Starting…"
                    is CameraRecordingState.Finalizing -> "Saving…"
                    is CameraRecordingState.Error -> "Camera problem"
                    CameraRecordingState.Idle -> if (cameraPermissionGranted) "Ready" else "Camera off"
                },
                background = if (recording) Color(0xE6B00020) else Color(0x99000000),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            )
            OverlayPill(
                text = MatchClockCalculator.formatClock(matchClockMs),
                background = Color(0x99000000),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, bottom = 10.dp, start = 10.dp, end = if (sideControls) 132.dp else 10.dp)
            )

            val centerMessage = when {
                state is CameraRecordingState.Error -> (state as CameraRecordingState.Error).message
                recordingOtherMatch -> "The camera is recording another match. Stop it from the notification first."
                !cameraPermissionGranted -> "Tap the record button to allow the camera."
                else -> null
            }
            centerMessage?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(start = 32.dp, top = 32.dp, bottom = 32.dp, end = if (sideControls) 132.dp else 32.dp)
                )
            }

            val recordEnabled = !recordingOtherMatch &&
                state !is CameraRecordingState.Preparing && state !is CameraRecordingState.Finalizing
            val micButton: @Composable () -> Unit = {
                OverlayIconButton(
                    icon = if (audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    description = if (audioEnabled) "Record without sound" else "Record with sound",
                    enabled = cameraIdle,
                    onClick = { audioEnabled = !audioEnabled }
                )
            }
            val lensButton: @Composable () -> Unit = {
                OverlayIconButton(
                    icon = Icons.Filled.Cameraswitch,
                    description = "Switch camera",
                    enabled = cameraIdle,
                    onClick = {
                        CameraRecordingController.chooseLens(
                            if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
                        )
                    }
                )
            }
            val torchButton: @Composable () -> Unit = {
                OverlayIconButton(
                    icon = if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    description = if (torch) "Turn torch off" else "Turn torch on",
                    enabled = lens == CameraLens.BACK &&
                        (state is CameraRecordingState.Recording || state is CameraRecordingState.Preparing),
                    onClick = { CameraRecordingController.setTorch(!torch) }
                )
            }
            if (sideControls) {
                // A wide, short preview (phone held sideways) keeps its height free: the
                // controls stand along the right edge, like in a camera app.
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99000000))))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        micButton()
                        lensButton()
                    }
                    RecordButton(recording = recording, enabled = recordEnabled, onClick = ::toggleRecording)
                    torchButton()
                }
            } else {
                // Camera controls along the bottom of the preview, like a camera app.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    micButton()
                    lensButton()
                    RecordButton(recording = recording, enabled = recordEnabled, onClick = ::toggleRecording)
                    torchButton()
                    // Keeps the record button centred.
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        // One quiet line about clips and space; warnings only when something needs attention.
        val budget = StorageBudgetRules.recordingBudget(storageHealth.availableBytes)
        val saved = recordings.filter { it.status == RecordingStatus.COMPLETED }
        val failed = recordings.count { it.status == RecordingStatus.FAILED || it.status == RecordingStatus.INTERRUPTED }
        val storageProblem = storageHealth.level != StorageLevel.OK || !budget.canStart
        val clipSummary: @Composable (Modifier) -> Unit = { modifier ->
            Text(
                listOfNotNull(
                    when (saved.size) {
                        0 -> "No clips yet"
                        1 -> "1 clip"
                        else -> "${saved.size} clips"
                    } + saved.sumOf { it.recordingDurationMs }.takeIf { it > 0 }
                        ?.let { " • ${MatchClockCalculator.formatClock(it)}" }.orEmpty(),
                    "$failed failed".takeIf { failed > 0 },
                    if (storageProblem) budget.message
                    else "${StorageHealthRules.formatAvailable(storageHealth.availableBytes)} free"
                ).joinToString("  •  "),
                style = MaterialTheme.typography.labelMedium,
                color = if (storageProblem || failed > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = modifier.padding(horizontal = 4.dp)
            )
        }
        val hasSavedClips = saved.any { !it.uri.isNullOrBlank() }
        val followSwitch: @Composable () -> Unit = {
            Switch(
                checked = followPeriods,
                onCheckedChange = onFollowPeriodsChange,
                modifier = Modifier.semantics {
                    contentDescription = "Stop recording at each period end and restart with the next period"
                }
            )
        }
        if (isLandscape()) {
            // One row, so the sideways preview keeps as much height as possible.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                clipSummary(Modifier.weight(1f))
                if (hasSavedClips) TextButton(onClick = onWatchVideo) { Text("Watch") }
                Text(
                    "Pause between periods",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 96.dp)
                )
                followSwitch()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                clipSummary(Modifier.weight(1f))
                if (hasSavedClips) TextButton(onClick = onWatchVideo) { Text("Watch video") }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable { onFollowPeriodsChange(!followPeriods) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Stop at each period end, record again when the next period starts",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                followSwitch()
            }
        }

        permissionMessage?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }) { Text("Settings") }
                }
            }
        }
    }
}

@Composable
private fun OverlayPill(text: String, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color(0x66000000),
            contentColor = Color.White,
            disabledContainerColor = Color(0x33000000),
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        ),
        modifier = Modifier.size(48.dp)
    ) { Icon(icon, contentDescription = description) }
}

/** White ring with a red dot to start, a red square to stop. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop and save recording" else "Start recording" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(if (recording) 28.dp else 54.dp)
                .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .background(Color(0xFFE53935).copy(alpha = if (enabled) 1f else 0.4f))
        )
    }
}
