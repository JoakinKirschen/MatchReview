package be.matchreview.app

import android.net.Uri
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.media3.exoplayer.SeekParameters
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchPeriod
import be.matchreview.app.data.Player
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.MatchVideoClip
import be.matchreview.app.domain.MatchVideoRules
import be.matchreview.app.domain.VideoViewRules
import be.matchreview.app.ui.isLandscape
import kotlinx.coroutines.delay

/**
 * The complete recorded match: all saved clips play back to back, full screen. A list of
 * periods and events jumps straight to a moment; held sideways the video fills the screen
 * and the list can be hidden.
 */
@Composable
fun MatchVideoDialog(
    title: String,
    recordings: List<RecordingSegment>,
    periods: List<MatchPeriod>,
    events: List<MatchEvent>,
    playersById: Map<Long, Player>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clips = remember(recordings, periods) { MatchVideoRules.clips(recordings, periods) }
    val chapterEvents = remember(events, clips) { MatchVideoRules.chapterEvents(events, clips) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var clipIndex by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var showList by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        // Frame steps land on the exact frame instead of the nearest key frame.
        player.setSeekParameters(SeekParameters.EXACT)
        onDispose { player.release() }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    // Only the clip list matters here; a new event elsewhere must not restart playback.
    LaunchedEffect(clips.map { it.segment.id }) {
        if (clips.isEmpty()) return@LaunchedEffect
        player.setMediaItems(clips.map { MediaItem.fromUri(Uri.parse(it.segment.uri)) })
        player.prepare()
        player.playWhenReady = true
    }
    // The match clock follows the video.
    LaunchedEffect(player) {
        while (true) {
            clipIndex = player.currentMediaItemIndex
            positionMs = player.currentPosition
            delay(500)
        }
    }

    fun stepFrame(forward: Boolean) {
        player.pause()
        player.seekTo(VideoViewRules.stepFrame(player.currentPosition, player.duration, forward))
        positionMs = player.currentPosition
    }

    fun play(index: Int, offsetMs: Long) {
        if (index !in clips.indices) return
        player.seekTo(index, offsetMs)
        player.play()
    }

    val currentClip = clips.getOrNull(clipIndex)
    val landscape = isLandscape()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            val video: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(Color.Black)) {
                    if (clips.isEmpty()) {
                        Text(
                            "No saved clips for this match yet.",
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                (LayoutInflater.from(ctx).inflate(R.layout.match_video_player, null) as PlayerView).apply {
                                    this.player = player
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { view ->
                                view.player = player
                                // Only the picture zooms; the playback controls keep their size.
                                view.videoSurfaceView?.apply {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = panX
                                    translationY = panY
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .pinchToZoom { zoomChange, pan, size ->
                                    zoom = VideoViewRules.clampZoom(zoom * zoomChange)
                                    panX = VideoViewRules.clampPan(panX + pan.x, zoom, size.width.toFloat())
                                    panY = VideoViewRules.clampPan(panY + pan.y, zoom, size.height.toFloat())
                                }
                        )
                    }
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x80000000))
                        ) { Icon(Icons.Default.Close, contentDescription = "Close video", tint = Color.White) }
                        currentClip?.let { clip ->
                            Text(
                                "${MatchVideoRules.clipLabel(clip, clips)} • " +
                                    MatchClockCalculator.formatClock(MatchVideoRules.matchClockAt(clip, positionMs)),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (landscape) {
                            IconButton(
                                onClick = { showList = !showList },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x80000000))
                            ) {
                                Icon(
                                    Icons.Default.ViewSidebar,
                                    contentDescription = if (showList) "Hide periods and events" else "Show periods and events",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    // Study tools, in their own row so they fit a phone held upright.
                    if (clips.isNotEmpty()) Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 60.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OverlayAction("◀|", "One frame back") { stepFrame(forward = false) }
                        OverlayAction("|▶", "One frame forward") { stepFrame(forward = true) }
                        OverlayAction(VideoViewRules.speedLabel(speed), "Playback speed ${VideoViewRules.speedLabel(speed)}, tap to change") {
                            speed = VideoViewRules.nextSpeed(speed)
                        }
                        if (zoom > 1f) {
                            OverlayAction("${"%.1f".format(zoom)}×", "Zoomed in, tap to zoom out") {
                                zoom = 1f; panX = 0f; panY = 0f
                            }
                        }
                    }
                }
            }
            val list: @Composable (Modifier) -> Unit = { modifier ->
                MatchVideoChapters(
                    title = title,
                    clips = clips,
                    chapterEvents = chapterEvents,
                    playersById = playersById,
                    currentClipIndex = clipIndex,
                    onPlayClip = { play(it, 0L) },
                    onPlayEvent = { event ->
                        MatchVideoRules.positionOf(event, clips)?.let { play(it.clipIndex, it.offsetMs) }
                    },
                    modifier = modifier
                )
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    video(Modifier.weight(1f).fillMaxHeight())
                    if (showList) list(Modifier.width(280.dp).fillMaxHeight().navigationBarsPadding())
                }
            } else {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    video(Modifier.fillMaxWidth().weight(0.45f))
                    list(Modifier.fillMaxWidth().weight(0.55f))
                }
            }
        }
    }
}

@Composable
private fun MatchVideoChapters(
    title: String,
    clips: List<MatchVideoClip>,
    chapterEvents: List<MatchEvent>,
    playersById: Map<Long, Player>,
    currentClipIndex: Int,
    onPlayClip: (Int) -> Unit,
    onPlayEvent: (MatchEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${clips.size} clip(s) • ${MatchClockCalculator.formatClock(MatchVideoRules.totalDurationMs(clips))} of video",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(clips, key = { "clip-${it.segment.id}" }) { clip ->
                val selected = clip.index == currentClipIndex
                ListItem(
                    headlineContent = {
                        Text(MatchVideoRules.clipLabel(clip, clips), fontWeight = if (selected) FontWeight.Bold else null)
                    },
                    supportingContent = {
                        val segment = clip.segment
                        Text(
                            "Match ${MatchClockCalculator.formatClock(segment.matchClockStartMs)}–" +
                                MatchClockCalculator.formatClock(
                                    segment.matchClockEndMs ?: (segment.matchClockStartMs + segment.recordingDurationMs)
                                ) + " • ${MatchClockCalculator.formatClock(segment.recordingDurationMs)}"
                        )
                    },
                    leadingContent = { Text(if (selected) "▶" else "${clip.index + 1}", fontWeight = FontWeight.Bold) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPlayClip(clip.index) }
                )
            }
            if (chapterEvents.isNotEmpty()) {
                item {
                    Text(
                        "Events",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(chapterEvents, key = { "event-${it.id}" }) { event ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlayEvent(event) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            MatchClockCalculator.formatClock(event.timestampMs),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            eventPlaybackTitle(event, playersById),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▶", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A small text button on the video, readable on any picture. */
@Composable
private fun OverlayAction(label: String, description: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/**
 * Two-finger pinch and drag, read before the player view sees the touches so a single tap
 * still shows the playback controls.
 */
private fun Modifier.pinchToZoom(onTransform: (zoomChange: Float, pan: Offset, size: IntSize) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    onTransform(event.calculateZoom(), event.calculatePan(), size)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
