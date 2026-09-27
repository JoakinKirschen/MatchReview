package be.matchreview.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.VideoEventRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEventPlaybackSheet(
    initialEvent: MatchEvent,
    events: List<MatchEvent>,
    recordings: List<RecordingSegment>,
    playersById: Map<Long, Player>,
    onDismiss: () -> Unit
) {
    val playableEvents = remember(events, recordings) {
        events.filter { VideoEventRules.isPlayable(it, recordings) }
            .sortedWith(
                compareBy<MatchEvent> { it.recordingSegmentId }
                    .thenBy { it.recordingOffsetMs }
                    .thenBy { it.id }
            )
    }
    var selectedId by remember(initialEvent.id) { mutableLongStateOf(initialEvent.id) }
    val selected = playableEvents.firstOrNull { it.id == selectedId } ?: initialEvent
    val selectedIndex = playableEvents.indexOfFirst { it.id == selected.id }
    val segment = recordings.firstOrNull { it.id == selected.recordingSegmentId }
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(segment?.id, selected.recordingOffsetMs) {
        val uri = segment?.uri
        if (!uri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            player.seekTo(selected.recordingOffsetMs ?: 0L)
            player.playWhenReady = true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Event video", style = MaterialTheme.typography.headlineSmall)
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Text(eventPlaybackTitle(selected, playersById), style = MaterialTheme.typography.titleMedium)
            Text(
                "Match ${MatchClockCalculator.formatClock(selected.timestampMs)} • " +
                    "clip +${MatchClockCalculator.formatClock(selected.recordingOffsetMs ?: 0L)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { selectedId = playableEvents[selectedIndex - 1].id },
                    enabled = selectedIndex > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Previous event") }
                Button(
                    onClick = { selectedId = playableEvents[selectedIndex + 1].id },
                    enabled = selectedIndex >= 0 && selectedIndex < playableEvents.lastIndex,
                    modifier = Modifier.weight(1f)
                ) { Text("Next event") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

private fun eventPlaybackTitle(event: MatchEvent, playersById: Map<Long, Player>): String =
    when (event.type) {
        "OUR_GOAL" -> "Goal • ${playersById[event.playerId]?.name ?: "Unknown scorer"}"
        "OPPONENT_GOAL" -> "Opponent goal"
        "SUBSTITUTION" -> "Substitution • ${playersById[event.playerId]?.name ?: "Player"} off"
        "PLAYER_ON" -> "${playersById[event.playerId]?.name ?: "Player"} entered"
        "PLAYER_OFF" -> "${playersById[event.playerId]?.name ?: "Player"} left"
        "INJURY_OFF" -> "Injury • ${playersById[event.playerId]?.name ?: "Player"}"
        "DISMISSAL" -> "Dismissal • ${playersById[event.playerId]?.name ?: "Player"}"
        else -> event.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
