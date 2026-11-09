package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import android.net.Uri
import android.os.Build
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.domain.MatchActions
import be.matchreview.app.domain.MatchExportFormatter
import be.matchreview.app.domain.MatchStatsRules
import be.matchreview.app.domain.HighlightClip
import be.matchreview.app.domain.HighlightRules
import be.matchreview.app.domain.MatchVideoClip
import be.matchreview.app.domain.MatchVideoRules
import be.matchreview.app.recording.HighlightReel
import be.matchreview.app.recording.HighlightReelMaker
import be.matchreview.app.domain.ExportPrivacyOptions
import be.matchreview.app.domain.MediaIntegrityRules
import be.matchreview.app.domain.MatchIntegrityRules
import be.matchreview.app.domain.VideoEventRules
import be.matchreview.app.domain.GoalMouthGeometry
import be.matchreview.app.domain.GoalSummaryRules
import be.matchreview.app.domain.StartingLineupRules
import be.matchreview.app.domain.TimelineGrouping
import be.matchreview.app.domain.TimelineItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.activeMatchId
import be.matchreview.app.recording.activeRecordingId
import java.util.*

internal enum class ReviewSection(private val englishLabel: String) {
    SUMMARY("Report"),
    TIMELINE("Timeline"),
    VIDEO("Video"),
    DATA("Data");

    val label: String get() = tr(englishLabel)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(matchId: Long, vm: MainViewModel, nav: NavHostController) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val events by vm.events(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val participations by vm.participations(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val lineup by vm.lineup(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val recordings by vm.recordings(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val periods by vm.periods(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val allPlayers by vm.players.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    val backupDue by vm.backupDue.collectAsStateWithLifecycle()
    var showDetailsEditor by remember { mutableStateOf(false) }
    var showFullVideo by rememberSaveable { mutableStateOf(false) }
    var goalPositionEvent by remember { mutableStateOf<MatchEvent?>(null) }
    val current = match ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val teamPlayers = allPlayers.filter { it.teamId == current.teamId }
    val team = teams.firstOrNull { it.id == current.teamId }
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var showTag by remember { mutableStateOf(false) }
    var videoImportMessage by remember { mutableStateOf<String?>(null) }
    var tagPosition by remember { mutableLongStateOf(0L) }
    var showDeleteMatch by remember { mutableStateOf(false) }
    var pendingEventDeletion by remember { mutableStateOf<MatchEvent?>(null) }
    var selectedClipEvent by remember { mutableStateOf<MatchEvent?>(null) }
    var reviewSection by rememberSaveable { mutableStateOf(ReviewSection.SUMMARY) }
    val clipDeleter = rememberClipDeleter()
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()
    val recordingThisMatch = cameraState.activeMatchId == matchId
    val mediaIssues = remember(recordings, cameraState.activeRecordingId) {
        MediaIntegrityRules.inspect(recordings, cameraState.activeRecordingId, cameraState.activeMatchId)
    }
    val integrityIssues = remember(current, events, participations, recordings) {
        MatchIntegrityRules.inspect(current, events, emptyList(), participations, recordings)
    }
    val deleteBlocked = current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED) || recordingThisMatch
    var exportContent by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pdfMessage by remember { mutableStateOf<String?>(null) }
    var redactedExport by rememberSaveable { mutableStateOf(true) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) {
            exportMessage = tr("Export cancelled")
        } else {
            exportMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                    it.write(exportContent)
                }
                tr("Export saved")
            }.getOrElse { tr("Export failed: {message}", "message" to (it.message ?: tr("unknown error"))) }
        }
    }
    fun writeSummaryPdf(output: java.io.OutputStream) = MatchPdfExporter.write(
        output, current, team, teamPlayers, events, participations, recordings, lineup
    )
    val pdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) {
            pdfMessage = tr("PDF download cancelled")
        } else {
            pdfMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use(::writeSummaryPdf)
                // Open the saved summary straight away so it can be checked and shared.
                if (ExternalApps.open(context, uri, "application/pdf")) tr("PDF summary saved")
                else tr("PDF summary saved. Install a PDF viewer to open it.")
            }.getOrElse { tr("PDF export failed: {message}", "message" to (it.message ?: tr("unknown error"))) }
        }
    }

    DisposableEffect(current.videoUri) {
        val exo = current.videoUri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(it)))
                prepare()
            }
        }
        player = exo
        onDispose { exo?.release(); player = null }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (persisted.isSuccess) {
                vm.setVideo(current.id, uri.toString())
                videoImportMessage = tr("Video linked for future review.")
            } else {
                videoImportMessage =
                    tr("This provider did not grant lasting access. Choose a local file or another provider.")
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogoImage(team?.logoPng, 48.dp, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (current.isHome) tr("{teamName} vs {opponent}", "teamName" to (team?.name ?: tr("Home")), "opponent" to current.opponent)
                    else tr("{opponent} vs {teamName}", "opponent" to current.opponent, "teamName" to (team?.name ?: tr("Away"))),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    listOf(
                        current.matchDate,
                        if (current.isHome) tr("Home") else tr("Away"),
                        current.venue,
                        current.competition,
                        current.formation
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { showDetailsEditor = true }) { Text(tr("Edit")) }
        }
        if (current.status == MatchStatus.FINISHED && backupDue) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("This match is not in a backup yet."),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { nav.navigate("backup") }) { Text(tr("Back up now")) }
                }
            }
        }
        if (integrityIssues.isNotEmpty()) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Data integrity check"), style = MaterialTheme.typography.titleMedium)
                    integrityIssues.take(3).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (mediaIssues.isNotEmpty()) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Recording check"), style = MaterialTheme.typography.titleMedium)
                    mediaIssues.take(3).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (current.status in setOf(MatchStatus.LINEUP_READY, MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) {
            AppButton(
                onClick = {
                    nav.navigate(
                        if (current.status == MatchStatus.LINEUP_READY) "ready/$matchId"
                        else "live/$matchId"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (current.status == MatchStatus.LINEUP_READY) tr("Check match readiness") else tr("Resume live match"))
            }
        }
        // Squad and lineup can only change before kick-off; afterwards the buttons are hidden.
        if (current.status in setOf(MatchStatus.DRAFT, MatchStatus.LINEUP_READY)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { nav.navigate("squad/$matchId") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Edit squad"))
                }
                AppTonalButton(
                    onClick = { nav.navigate("lineup/$matchId") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Starting lineup"))
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReviewSection.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = reviewSection == section,
                    onClick = { reviewSection = section },
                    shape = SegmentedButtonDefaults.itemShape(index, ReviewSection.entries.size),
                    icon = {},
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(section.label, maxLines = 1) }
            }
        }

        if (reviewSection == ReviewSection.VIDEO) {
        val matchClips = remember(recordings, periods) { MatchVideoRules.clips(recordings, periods) }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Match recording"), style = MaterialTheme.typography.titleMedium)
                if (matchClips.isEmpty()) {
                    Text(
                        tr("Record the match with the camera in the live screen to watch it here."),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        tr("{matchClipsCount} clip(s) • {time}. ", "matchClipsCount" to matchClips.size, "time" to (formatTime(MatchVideoRules.totalDurationMs(matchClips)))) +
                            tr("Plays all clips one after another; jump to any period or event."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppButton(
                        onClick = { showFullVideo = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(tr("Watch full match")) }
                }
            }
        }
        HighlightsCard(current, events, recordings, matchClips, allPlayers.associateBy { it.id })

        // A video from another camera (a parent's phone, a club camera) can be linked and tagged.
        Text(tr("Video from another camera"), style = MaterialTheme.typography.titleMedium)
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    onClick = { tagPosition = player?.currentPosition ?: 0L; showTag = true },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Tag moment")) }
                AppOutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                    Text(tr("Change video"))
                }
            }
        } else {
            AppOutlinedButton(
                onClick = { videoPicker.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Import a match video")) }
        }
        videoImportMessage?.let { message ->
            AppCard(
                colors = CardDefaults.cardColors(
                    containerColor = if (message == tr("Video linked for future review.")) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { videoImportMessage = null }) { Text(tr("Dismiss")) }
                }
            }
        }
        }

        if (reviewSection == ReviewSection.TIMELINE) {
        Text(tr("Event timeline"), style = MaterialTheme.typography.titleLarge)
        if (events.isEmpty()) EmptyCard(tr("Play the video and tag important moments."))
        val playersById = allPlayers.associateBy { it.id }
        // Live events use match time while imported-video tags use the video position, so
        // they are listed separately instead of being sorted into one misleading order.
        val (videoTags, matchEvents) = StartingLineupRules
            .withStartingLineup(current.id, events, participations, lineup)
            .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
            .partition(VideoEventRules::isImportedVideoTag)
        listOf(
            Triple(tr("Match events"), tr("Times are match time."), TimelineGrouping.group(matchEvents)),
            Triple(
                tr("Imported video tags"),
                tr("Times are positions in the imported video."),
                videoTags.map { TimelineItem.Single(it) }
            )
        ).filter { it.third.isNotEmpty() }.forEach { (sectionTitle, sectionHint, sectionItems) ->
        Text(sectionTitle, style = MaterialTheme.typography.titleMedium)
        Text(sectionHint, style = MaterialTheme.typography.bodySmall)
        sectionItems.forEach { item ->
            if (item is TimelineItem.LineupChange) {
                val first = item.events.first()
                LineupChangeCard(
                    change = item,
                    playersById = playersById,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (VideoEventRules.isPlayable(first, recordings)) {
                                TextButton(onClick = { selectedClipEvent = first }) { Text(tr("▶ Clip")) }
                            }
                        }
                    }
                )
                return@forEach
            }
            val event = (item as TimelineItem.Single).event
            val playerName = teamPlayers.firstOrNull { it.id == event.playerId }?.name
            // Tags made on the imported video store the video position; live events store
            // match time and can only be shown from the clip recorded in the app.
            val videoTag = VideoEventRules.isImportedVideoTag(event)
            val clipPlayable = !videoTag && VideoEventRules.isPlayable(event, recordings)
            val scoring = event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL"
            AppCard(
                Modifier.fillMaxWidth().clickable(enabled = (videoTag && player != null) || clipPlayable) {
                    if (videoTag) {
                        player?.seekTo(event.timestampMs)
                        player?.play()
                    } else {
                        selectedClipEvent = event
                    }
                }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatTime(event.timestampMs)} • ${reviewEventLabel(event.type)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                playerName,
                                event.sentiment,
                                event.note.takeIf { it.isNotBlank() },
                                GoalMouthGeometry.describe(event.goalX, event.goalY)?.let { tr("goal position: {value}", "value" to it) }
                            ).joinToString(" • ")
                        )
                        Text(
                            when {
                                videoTag -> tr("Imported video position")
                                clipPlayable -> tr("Match time • tap to play the recorded clip")
                                else -> tr("Match time • no recorded clip")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (scoring) {
                            TextButton(onClick = { goalPositionEvent = event }) { Text(tr("Position")) }
                        }
                        TextButton(onClick = { pendingEventDeletion = event }) { Text(tr("Delete")) }
                    }
                }
            }
        }
        }
        }

        if (reviewSection == ReviewSection.SUMMARY) {
            val goalLines = GoalSummaryRules.lines(
                events,
                allPlayers.associate { it.id to it.name },
                team?.name ?: tr("Our team"),
                current.opponent
            )
            if (goalLines.isNotEmpty()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            tr("Goals • {ourScore}–{opponentScore}", "ourScore" to current.ourScore, "opponentScore" to current.opponentScore),
                            style = MaterialTheme.typography.titleMedium
                        )
                        goalLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            val matchStats = MatchStatsRules.compute(events)
            if (matchStats.hasDetail) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("Match stats"), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1.4f))
                            Text(team?.name ?: tr("Our team"), Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.opponent, Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MatchStatsRules.rows(matchStats).forEach { (label, ours, theirs) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(label, Modifier.weight(1.4f))
                                Text("$ours", Modifier.weight(1f), textAlign = TextAlign.End)
                                Text("$theirs", Modifier.weight(1f), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            val saves = events.filter { it.type == "KEEPER_SAVE" }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Goalkeeping"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("{savesCount} save(s) • {opponentScore} goal(s) conceded", "savesCount" to saves.size, "opponentScore" to current.opponentScore))
                    saves.groupBy { it.playerId }.forEach { (keeperId, keeperSaves) ->
                        Text(
                            "${teamPlayers.firstOrNull { it.id == keeperId }?.name ?: "Keeper not assigned"}: ${keeperSaves.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            GoalMap(events, current.opponent, Modifier.fillMaxWidth())
            ReviewEditor(current, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { pdfExportLauncher.launch(MatchPdfExporter.fileName(current)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(tr("Download PDF summary")) }
                AppOutlinedButton(
                    onClick = {
                        pdfMessage = runCatching {
                            ExternalApps.shareNewFile(
                                context,
                                MatchPdfExporter.fileName(current),
                                "application/pdf",
                                tr("Match summary vs {opponent}", "opponent" to current.opponent),
                                ::writeSummaryPdf
                            )
                            null
                        }.getOrElse { tr("PDF sharing failed: {message}", "message" to (it.message ?: tr("unknown error"))) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(tr("Share")) }
            }
            // Messages about the PDF summary, shown under its buttons.
            pdfMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (reviewSection == ReviewSection.DATA) {
        HorizontalDivider()
        Text(tr("Data and privacy"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr("Exports contain player names and match details. Share them only with people who are authorised to receive this data."),
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Privacy-safe CSV"), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (redactedExport) tr("Player names, notes, and media locations are removed.")
                    else tr("Identifiable player data will be included."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = redactedExport, onCheckedChange = { redactedExport = it })
        }
        AppButton(
            onClick = {
                exportContent = MatchExportFormatter.toCsv(
                    current,
                    teamPlayers,
                    events,
                    participations,
                    recordings,
                    ExportPrivacyOptions(
                        includePlayerNames = !redactedExport,
                        includeNotes = !redactedExport,
                        includeMediaUris = false
                    )
                )
                exportLauncher.launch(MatchExportFormatter.fileName(current))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Export match data (CSV)")) }
        AppOutlinedButton(
            onClick = { nav.navigate("backup") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Back up all app data")) }
        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        AppOutlinedButton(
            onClick = { showDeleteMatch = true },
            enabled = !deleteBlocked,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Delete match and recorded clips")) }
        if (recordingThisMatch) {
            Text(
                tr("A camera recording for this match is still running or being saved. Stop it before deleting the match."),
                style = MaterialTheme.typography.bodySmall
            )
        } else if (current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED)) {
            Text(
                tr("Finish or pause/end the active match before deleting it."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(12.dp))
        }
    }

    if (showDeleteMatch) {
        AlertDialog(
            onDismissRequest = { showDeleteMatch = false },
            title = { Text(tr("Permanently delete match?")) },
            text = {
                Text(
                    tr("This removes the match, timeline, player-minute history and {clips} recorded clip(s). ", "clips" to (recordings.count { it.uri != null })) +
                        tr("This cannot be undone. Imported videos are not deleted.")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteBlocked,
                    onClick = {
                        showDeleteMatch = false
                        clipDeleter.delete(recordings.mapNotNull { it.uri }) { remaining ->
                            current.videoUri?.let { context.contentResolver.releaseImportedVideo(it) }
                            vm.deleteMatch(current) {
                                if (remaining > 0) showClipsRemainingToast(context, remaining)
                                nav.popBackStack()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(tr("Delete permanently")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMatch = false }) { Text(tr("Cancel")) }
            }
        )
    }

    pendingEventDeletion?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingEventDeletion = null },
            title = { Text(tr("Delete {event}?", "event" to (event.type.lowercase().replace('_', ' ')))) },
            text = {
                Text(
                    if (event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL")
                        tr("The event is removed from the timeline and the score is updated.")
                    else tr("The event is removed from the timeline.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEvent(event)
                    pendingEventDeletion = null
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingEventDeletion = null }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showDetailsEditor) {
        MatchDetailsDialog(
            match = current,
            onDismiss = { showDetailsEditor = false },
            onSave = { opponent, date, venue, competition, home ->
                vm.updateMatchDetails(matchId, opponent, date, venue, competition, home) {
                    showDetailsEditor = false
                }
            }
        )
    }

    goalPositionEvent?.let { event ->
        GoalPlacementDialog(
            title = if (event.type == "OPPONENT_GOAL") tr("Where did {opponent} score?", "opponent" to current.opponent)
            else tr("Where did {teamName} score?", "teamName" to (team?.name ?: tr("your team"))),
            initialX = event.goalX,
            initialY = event.goalY,
            onSkip = { goalPositionEvent = null },
            onSave = { x, y ->
                vm.setGoalPlacement(event.id, x, y)
                goalPositionEvent = null
            }
        )
    }

    if (showFullVideo) {
        MatchVideoDialog(
            title = if (current.isHome) tr("{teamName} vs {opponent}", "teamName" to (team?.name ?: tr("Home")), "opponent" to current.opponent)
            else tr("{opponent} vs {teamName}", "opponent" to current.opponent, "teamName" to (team?.name ?: tr("Away"))),
            recordings = recordings,
            periods = periods,
            events = events,
            playersById = allPlayers.associateBy { it.id },
            onDismiss = { showFullVideo = false }
        )
    }

    selectedClipEvent?.let { event ->
        VideoEventPlaybackSheet(
            initialEvent = event,
            events = events,
            recordings = recordings,
            playersById = allPlayers.associateBy { it.id },
            onDismiss = { selectedClipEvent = null }
        )
    }

    if (showTag) {
        EventDialog(
            players = teamPlayers.filterNot { it.archived },
            timestamp = tagPosition,
            onDismiss = { showTag = false },
            onSave = { playerId, type, sentiment, note ->
                vm.addEvent(matchId, playerId, tagPosition, type, sentiment, note)
                showTag = false
            }
        )
    }
}

/**
 * Joins recorded clips into one video to watch or share: the goals, the marked moments,
 * one player's moments, or the whole match.
 */
@Composable
internal fun HighlightsCard(
    match: GameMatch,
    events: List<MatchEvent>,
    recordings: List<RecordingSegment>,
    matchClips: List<MatchVideoClip>,
    playersById: Map<Long, Player>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ourClips = remember(events, recordings) { HighlightRules.clips(events, recordings, HighlightRules.OUR_GOALS) }
    val allClips = remember(events, recordings) { HighlightRules.clips(events, recordings, HighlightRules.ALL_GOALS) }
    val markClips = remember(events, recordings) { HighlightRules.clips(events, recordings, HighlightRules.MARKS) }
    val playerMoments = remember(events, recordings) { HighlightRules.playersWithMoments(events, recordings) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var reel by remember { mutableStateOf<HighlightReel?>(null) }
    var reelTitle by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var playerMenu by remember { mutableStateOf(false) }
    fun slug(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    fun make(clips: List<HighlightClip>, label: String, title: String) {
        progress = 0f
        message = null
        reel = null
        reelTitle = title
        scope.launch {
            runCatching {
                HighlightReelMaker.make(
                    context,
                    clips,
                    "matchreview-${slug(label)}-${match.matchDate}-${slug(match.opponent).ifBlank { "match" }}.mp4"
                ) { progress = it }
            }.onSuccess {
                reel = it
                message = buildString {
                    append(tr("{time} video saved", "time" to (formatTime(it.durationMs))))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) append(tr(" to Movies/MatchReview"))
                    if (it.skipped > 0) append(tr(". {skipped} clip(s) recorded with other settings were left out", "skipped" to it.skipped))
                }
            }.onFailure {
                message = tr("Video failed: {message}", "message" to (it.message ?: tr("unknown error")))
            }
            progress = null
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Highlights and video files"), style = MaterialTheme.typography.titleMedium)
            if (matchClips.isEmpty()) {
                Text(
                    tr("Record the match with the camera in the live screen to make highlight videos."),
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }
            Text(
                tr("Joins about {before} s before and {after} s after ", "before" to (HighlightRules.BEFORE_MS / 1000), "after" to (HighlightRules.AFTER_MS / 1000)) +
                    tr("each recorded moment into one video, saved in Movies/MatchReview."),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { make(ourClips, "our-goals", tr("Goals vs {opponent}", "opponent" to match.opponent)) },
                    enabled = progress == null && ourClips.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(tr("Our goals ({count})", "count" to (ourClips.sumOf { it.eventIds.size }))) }
                AppOutlinedButton(
                    onClick = { make(allClips, "goals", tr("All goals vs {opponent}", "opponent" to match.opponent)) },
                    enabled = progress == null && allClips.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(tr("All goals ({count})", "count" to (allClips.sumOf { it.eventIds.size }))) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(
                    onClick = { make(markClips, "marked", tr("Marked moments vs {opponent}", "opponent" to match.opponent)) },
                    enabled = progress == null && markClips.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(tr("Marked ({count})", "count" to (markClips.sumOf { it.eventIds.size })), maxLines = 1) }
                Box(Modifier.weight(1f)) {
                    AppOutlinedButton(
                        onClick = { playerMenu = true },
                        enabled = progress == null && playerMoments.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(tr("One player…"), maxLines = 1) }
                    DropdownMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                        playerMoments.entries
                            .sortedBy { playersById[it.key]?.name.orEmpty() }
                            .forEach { (playerId, count) ->
                                val name = playersById[playerId]?.name ?: return@forEach
                                DropdownMenuItem(
                                    text = { Text("$name ($count)") },
                                    onClick = {
                                        playerMenu = false
                                        make(
                                            HighlightRules.playerClips(events, recordings, playerId),
                                            name,
                                            tr("{player} vs {opponent}", "player" to name, "opponent" to match.opponent)
                                        )
                                    }
                                )
                            }
                    }
                }
            }
            AppOutlinedButton(
                onClick = { make(HighlightRules.fullMatch(matchClips), "full-match", tr("Full match vs {opponent}", "opponent" to match.opponent)) },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(tr("Save the full match as one video")) }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            reel?.let { saved ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        onClick = {
                            if (!ExternalApps.open(context, saved.uri, "video/mp4")) message = tr("Install a video player to watch it.")
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(tr("Play")) }
                    AppOutlinedButton(
                        onClick = { ExternalApps.share(context, saved.uri, "video/mp4", reelTitle) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(tr("Share")) }
                }
            }
        }
    }
}

internal fun reviewEventLabel(type: String): String = MatchActions.label(type)
    ?.let { if (MatchActions.isOpponent(type)) tr("Opponent {value}", "value" to (it.lowercase())) else it }
    ?: when (type) {
    "OUR_GOAL" -> tr("Our goal")
    "OPPONENT_GOAL" -> tr("Opponent goal")
    "SUBSTITUTION" -> tr("Substitution")
    "KICK_OFF" -> tr("Kick-off")
    "KEEPER_SAVE" -> tr("Keeper save")
    "POSITION_CHANGE" -> tr("Positions changed")
    "PLAYER_ON" -> tr("Player on")
    "PLAYER_OFF" -> tr("Player off")
    "INJURY_OFF" -> tr("Injury")
    "DISMISSAL" -> tr("Red card (sent off)")
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
internal fun EventDialog(
    players: List<Player>,
    timestamp: Long,
    onDismiss: () -> Unit,
    onSave: (Long?, String, String, String) -> Unit
) {
    // Saved in English with the tag and translated when shown.
    val types = listOf("Goal", "Shot", "Chance", "Pass", "Cross", "Corner", "Free kick", "Foul", "Save", "Turnover", "Pressing", "Defensive error", "Set piece", "Custom")
    var type by remember { mutableStateOf("Chance") }
    var sentiment by remember { mutableStateOf("Neutral") }
    var note by remember { mutableStateOf("") }
    var playerId by remember { mutableStateOf<Long?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var playerMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tag {time}", "time" to (formatTime(timestamp)))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AppOutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(tr(type)) }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        types.forEach { item ->
                            DropdownMenuItem(text = { Text(tr(item)) }, onClick = { type = item; typeMenu = false })
                        }
                    }
                }
                Box {
                    AppOutlinedButton(onClick = { playerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(players.firstOrNull { it.id == playerId }?.name ?: tr("No player"))
                    }
                    DropdownMenu(playerMenu, { playerMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("No player")) }, onClick = { playerId = null; playerMenu = false })
                        players.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = { playerId = item.id; playerMenu = false })
                        }
                    }
                }
                Row {
                    listOf("Positive", "Neutral", "Improve").forEach {
                        FilterChip(selected = sentiment == it, onClick = { sentiment = it }, label = { Text(tr(it)) })
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Field(note, { note = it }, tr("Note"))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(playerId, type, sentiment, note) }) { Text(tr("Save tag")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
internal fun ReviewEditor(match: GameMatch, vm: MainViewModel) {
    // Keyed on the match id so live updates (for example a new goal) never wipe typed notes.
    var ourScore by remember(match.id) { mutableStateOf(match.ourScore.toString()) }
    var theirScore by remember(match.id) { mutableStateOf(match.opponentScore.toString()) }
    var scoreEdited by remember(match.id) { mutableStateOf(false) }
    var rating by remember(match.id) { mutableStateOf(match.teamRating.toString()) }
    var notes by remember(match.id) { mutableStateOf(match.reviewNotes) }
    var saved by remember(match.id) { mutableStateOf(false) }
    var saving by remember(match.id) { mutableStateOf(false) }

    // Until the coach edits the score, keep showing the event-derived score.
    LaunchedEffect(match.ourScore, match.opponentScore) {
        if (!scoreEdited) {
            ourScore = match.ourScore.toString()
            theirScore = match.opponentScore.toString()
        }
    }

    HorizontalDivider()
    Text(tr("Match summary"), style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(ourScore, { ourScore = it.filter(Char::isDigit).take(2); scoreEdited = true; saved = false }, tr("Our score"), Modifier.weight(1f))
        Field(theirScore, { theirScore = it.filter(Char::isDigit).take(2); scoreEdited = true; saved = false }, tr("Opponent"), Modifier.weight(1f))
        Field(rating, { rating = it.filter(Char::isDigit).take(2); saved = false }, tr("Rating /10"), Modifier.weight(1f))
    }
    Text(
        tr("The score follows the goals on the timeline. Changing it here adds or removes goal events."),
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it; saved = false },
        label = { Text(tr("What went well, improvements, next training focus")) },
        minLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    AppButton(
        onClick = {
            val ours = ourScore.toIntOrNull() ?: match.ourScore
            val theirs = theirScore.toIntOrNull() ?: match.opponentScore
            val correction = (ours to theirs).takeIf {
                scoreEdited && (ours != match.ourScore || theirs != match.opponentScore)
            }
            saving = true
            vm.saveReview(match.id, correction, rating.toIntOrNull() ?: 0, notes) {
                scoreEdited = false
                saving = false
                saved = true
            }
        },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(if (saving) tr("Saving…") else if (saved) tr("Saved") else tr("Save review")) }
}

/** A scrolling form; the screen title is already in the top bar. */
