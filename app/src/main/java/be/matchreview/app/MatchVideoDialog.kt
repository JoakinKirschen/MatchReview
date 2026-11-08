package be.matchreview.app

import android.net.Uri
import android.widget.FrameLayout
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

    DisposableEffect(Unit) { onDispose { player.release() } }
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
                                PlayerView(ctx).apply {
                                    this.player = player
                                    keepScreenOn = true
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { it.player = player },
                            modifier = Modifier.fillMaxSize()
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
