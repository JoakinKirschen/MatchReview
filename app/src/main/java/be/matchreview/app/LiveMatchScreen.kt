package be.matchreview.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import be.matchreview.app.data.*
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.recording.CameraMatchPanel
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.MatchRecordingService
import be.matchreview.app.recording.activeMatchId
import kotlinx.coroutines.delay

private enum class LiveTab { MATCH, CAMERA, TIMELINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMatchScreen(
    matchId: Long,
    vm: MainViewModel,
    nav: NavHostController
) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val players by vm.players.collectAsStateWithLifecycle()
    val placements by vm.lineup(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val squad by vm.squad(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val periods by vm.periods(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val segments by vm.clockSegments(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val participations by vm.participations(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val events by vm.events(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val recordings by vm.recordings(matchId).collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(matchId) { vm.ensureVideoEventLinks(matchId) }

    val current = match
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val view = LocalView.current
    val context = LocalContext.current
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()
    val recordingThisMatch = cameraState.activeMatchId == matchId
    DisposableEffect(current.status) {
        val previous = view.keepScreenOn
        view.keepScreenOn = current.status in setOf(
            MatchStatus.LINEUP_READY,
            MatchStatus.LIVE,
            MatchStatus.PAUSED,
            MatchStatus.PERIOD_ENDED
        )
        onDispose { view.keepScreenOn = previous }
    }

    var nowMonotonic by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var nowWall by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current.clockRunning, segments.lastOrNull()?.id) {
        do {
            nowMonotonic = SystemClock.elapsedRealtime()
            nowWall = System.currentTimeMillis()
            if (current.clockRunning) delay(250)
        } while (current.clockRunning)
    }

    val openSegment = segments.lastOrNull { it.monotonicEndMs == null }
    val matchTimeMs = if (current.clockRunning && openSegment != null) {
        MatchClockCalculator.recoveredCurrentMatchTimeMs(
            segmentStartMatchTimeMs = openSegment.startMatchTimeMs,
            monotonicStartMs = openSegment.monotonicStartMs,
            wallClockStartMs = openSegment.wallClockStartMs,
            monotonicNowMs = nowMonotonic,
            wallClockNowMs = nowWall
        )
    } else current.accumulatedMatchTimeMs

    val activePeriod = periods.firstOrNull { it.periodNumber == current.currentPeriod }
    val periodTimeMs = (matchTimeMs - (activePeriod?.startMatchTimeMs ?: matchTimeMs))
        .coerceAtLeast(0L)

    val playersById = players.associateBy { it.id }
    val squadById = squad.associateBy { it.playerId }
    val onPitch = placements.filter { it.onPitch }
    val bench = placements.filterNot { it.onPitch }
    val eligibleBenchIds = bench.mapNotNull { placement ->
        val state = squadById[placement.playerId]?.state
        placement.playerId.takeIf {
            state !in setOf(
                PlayerMatchState.UNAVAILABLE,
                PlayerMatchState.REMOVED,
                PlayerMatchState.DISMISSED
            )
        }
    }.toSet()
    val liveActionsEnabled = current.status in setOf(
        MatchStatus.LIVE,
        MatchStatus.PAUSED,
        MatchStatus.PERIOD_ENDED
    )

    fun playedMs(playerId: Long): Long =
        participations
            .asSequence()
            .filter { it.playerId == playerId }
            .sumOf { interval ->
                ((interval.endMatchTimeMs ?: matchTimeMs) - interval.startMatchTimeMs)
                    .coerceAtLeast(0L)
            }

    var tab by remember { mutableStateOf(LiveTab.MATCH) }
    var selectedPlayerId by remember { mutableStateOf<Long?>(null) }
    var substitutionOutgoingId by remember { mutableStateOf<Long?>(null) }
    var substitutionIncomingId by remember { mutableStateOf<Long?>(null) }
    var removalPlayerId by remember { mutableStateOf<Long?>(null) }
    var scorerSelectionOpen by remember { mutableStateOf(false) }
    var pendingGoalScorerId by remember { mutableStateOf<Long?>(null) }
    var goalFlowActive by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf<MatchEvent?>(null) }
    var showOpponentGoalConfirmation by remember { mutableStateOf(false) }
    var showScoreCorrection by remember { mutableStateOf(false) }
    var showFinishConfirmation by remember { mutableStateOf(false) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var selectedVideoEvent by remember { mutableStateOf<MatchEvent?>(null) }
    var pendingGoalDeletion by remember { mutableStateOf<MatchEvent?>(null) }

    BackHandler { showLeaveConfirmation = true }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showLeaveConfirmation = true }) { Text("Back") }
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "vs ${current.opponent}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(statusLabel(current), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showScoreCorrection = true }) { Text("Correct") }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.navigationBarsPadding()) {
                    CompactLiveTabs(
                        selected = tab,
                        onSelected = { tab = it }
                    )
                    LiveControlBar(
                        match = current,
                        onKickOff = { vm.kickOffMatch(matchId) },
                        onPause = { vm.pauseMatchClock(matchId) },
                        onResume = { vm.resumeMatchClock(matchId) },
                        onEndPeriod = { vm.endCurrentPeriod(matchId) },
                        onNextPeriod = { vm.startNextPeriod(matchId) },
                        onFinish = { showFinishConfirmation = true }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiveScoreboard(
                match = current,
                matchTimeMs = matchTimeMs,
                periodTimeMs = periodTimeMs
            )

            if (liveActionsEnabled) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Button(
                        onClick = { scorerSelectionOpen = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Our goal", maxLines = 1) }
                    OutlinedButton(
                        onClick = { showOpponentGoalConfirmation = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Opponent", maxLines = 1) }
                    OutlinedButton(
                        onClick = { vm.undoLatestScoreAction(matchId) },
                        enabled = events.any { it.type == "OUR_GOAL" || it.type == "OPPONENT_GOAL" },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("Undo") }
                }
            }

            if (tab == LiveTab.MATCH) {
                LivePitch(
                    placements = onPitch,
                    playersById = playersById,
                    minutesFor = { playedMs(it) },
                    onPlayerClick = { selectedPlayerId = it },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                Row(
                    Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Subs • ${onPitch.size}/${current.playersOnPitch} on pitch • ${eligibleBenchIds.size} available",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            eligibleBenchIds.firstOrNull()?.let { substitutionIncomingId = it }
                        },
                        enabled = liveActionsEnabled && eligibleBenchIds.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("Substitute") }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(bench, key = { it.playerId }) { placement ->
                        playersById[placement.playerId]?.let { player ->
                            val state = squadById[player.id]?.state
                            BenchMinuteCard(
                                player = player,
                                minutes = MatchClockCalculator.displayedWholeMinutes(playedMs(player.id)),
                                enabled = state !in setOf(
                                    PlayerMatchState.REMOVED,
                                    PlayerMatchState.DISMISSED,
                                    PlayerMatchState.UNAVAILABLE
                                ),
                                stateLabel = when (state) {
                                    PlayerMatchState.REMOVED -> "Out"
                                    PlayerMatchState.DISMISSED -> "Sent off"
                                    else -> null
                                },
                                onClick = { selectedPlayerId = player.id }
                            )
                        }
                    }
                }
            } else if (tab == LiveTab.CAMERA) {
                CameraMatchPanel(
                    matchId = matchId,
                    matchClockMs = matchTimeMs,
                    recordings = recordings,
                    quickActionsEnabled = liveActionsEnabled,
                    onOurGoal = { scorerSelectionOpen = true },
                    onOpponentGoal = { showOpponentGoalConfirmation = true },
                    onSubstitution = {
                        eligibleBenchIds.firstOrNull()?.let { substitutionIncomingId = it }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                MatchTimeline(
                    events = events.sortedWith(
                        compareByDescending<MatchEvent> { it.timestampMs }.thenByDescending { it.id }
                    ),
                    playersById = playersById,
                    recordings = recordings,
                    modifier = Modifier.weight(1f),
                    onPlayEvent = { selectedVideoEvent = it },
                    onEditGoal = { editGoal = it },
                    onDeleteGoal = { pendingGoalDeletion = it }
                )
            }
        }
    }

    playersById[selectedPlayerId]?.let { player ->
        val isOnPitch = placements.firstOrNull { it.playerId == player.id }?.onPitch == true
        val squadState = squadById[player.id]?.state
        ModalBottomSheet(onDismissRequest = { selectedPlayerId = null }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(player.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "#${player.shirtNumber.takeIf { it > 0 } ?: "—"} • " +
                        "${MatchClockCalculator.displayedWholeMinutes(playedMs(player.id))} minutes"
                )
                Text(if (isOnPitch) "Currently on the pitch" else playerStateLabel(squadState))

                if (liveActionsEnabled && isOnPitch) {
                    Button(
                        onClick = {
                            selectedPlayerId = null
                            pendingGoalScorerId = player.id
                            goalFlowActive = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Record goal") }
                    OutlinedButton(
                        onClick = {
                            selectedPlayerId = null
                            substitutionOutgoingId = player.id
                        },
                        enabled = eligibleBenchIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Substitute player") }
                    OutlinedButton(
                        onClick = {
                            selectedPlayerId = null
                            removalPlayerId = player.id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Remove from pitch") }
                } else if (liveActionsEnabled && !isOnPitch && player.id in eligibleBenchIds) {
                    if (onPitch.size < current.playersOnPitch) {
                        Button(
                            onClick = {
                                selectedPlayerId = null
                                vm.putPlayerOnPitch(matchId, player.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Put on pitch") }
                    }
                    OutlinedButton(
                        onClick = {
                            selectedPlayerId = null
                            substitutionIncomingId = player.id
                        },
                        enabled = onPitch.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose player to replace") }
                }
            }
        }
    }

    if (scorerSelectionOpen) {
        PlayerChoiceSheet(
            title = "Who scored?",
            playerIds = onPitch.map { it.playerId },
            playersById = playersById,
            includeNone = true,
            noneLabel = "Unknown scorer",
            onDismiss = { scorerSelectionOpen = false },
            onSelect = {
                scorerSelectionOpen = false
                pendingGoalScorerId = it
                goalFlowActive = true
            }
        )
    }

    if (goalFlowActive) {
        val scorerId = pendingGoalScorerId
        PlayerChoiceSheet(
            title = "Assist for ${playersById[scorerId]?.name ?: "the goal"}",
            playerIds = onPitch.map { it.playerId }.filterNot { it == scorerId },
            playersById = playersById,
            includeNone = true,
            noneLabel = "No assist",
            onDismiss = {
                goalFlowActive = false
                pendingGoalScorerId = null
            },
            onSelect = { assistId ->
                vm.recordOurGoal(matchId, scorerId, assistId)
                goalFlowActive = false
                pendingGoalScorerId = null
            }
        )
    }

    editGoal?.let { event ->
        GoalEditSheet(
            event = event,
            maxTimeMs = maxOf(matchTimeMs, event.timestampMs),
            players = players.filter { player ->
                squad.any { it.playerId == player.id && it.selected }
            },
            onDismiss = { editGoal = null },
            onSave = { scorerId, assistId, timeMs ->
                vm.updateOurGoal(event.id, scorerId, assistId, timeMs)
                editGoal = null
            },
            onDelete = {
                editGoal = null
                pendingGoalDeletion = event
            }
        )
    }

    substitutionOutgoingId?.let { outgoingId ->
        PlayerChoiceSheet(
            title = "Replace ${playersById[outgoingId]?.name ?: "player"} with",
            playerIds = eligibleBenchIds.toList(),
            playersById = playersById,
            onDismiss = { substitutionOutgoingId = null },
            onSelect = { incomingId ->
                incomingId?.let { vm.substitutePlayer(matchId, outgoingId, it) }
                substitutionOutgoingId = null
            }
        )
    }

    substitutionIncomingId?.let { incomingId ->
        PlayerChoiceSheet(
            title = "Who should ${playersById[incomingId]?.name ?: "the substitute"} replace?",
            playerIds = onPitch.map { it.playerId },
            playersById = playersById,
            onDismiss = { substitutionIncomingId = null },
            onSelect = { outgoingId ->
                outgoingId?.let { vm.substitutePlayer(matchId, it, incomingId) }
                substitutionIncomingId = null
            }
        )
    }

    removalPlayerId?.let { playerId ->
        AlertDialog(
            onDismissRequest = { removalPlayerId = null },
            title = { Text("Remove ${playersById[playerId]?.name ?: "player"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.BENCH)
                        removalPlayerId = null
                    }) { Text(if (current.rollingSubstitutions) "Move to bench" else "Remove • cannot return") }
                    TextButton(onClick = {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.INJURY)
                        removalPlayerId = null
                    }) { Text("Injury • cannot return") }
                    TextButton(onClick = {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.DISMISSAL)
                        removalPlayerId = null
                    }) { Text("Dismissal • cannot return") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { removalPlayerId = null }) { Text("Cancel") } }
        )
    }

    if (showOpponentGoalConfirmation) {
        AlertDialog(
            onDismissRequest = { showOpponentGoalConfirmation = false },
            title = { Text("Opponent goal?") },
            text = { Text("This adds a goal at the current match time.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.recordOpponentGoal(matchId)
                    showOpponentGoalConfirmation = false
                }) { Text("Add goal") }
            },
            dismissButton = {
                TextButton(onClick = { showOpponentGoalConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showScoreCorrection) {
        ScoreCorrectionDialog(
            ourScore = current.ourScore,
            opponentScore = current.opponentScore,
            opponentName = current.opponent,
            onDismiss = { showScoreCorrection = false },
            onSave = { ours, opponents ->
                vm.correctScore(matchId, ours, opponents)
                showScoreCorrection = false
            }
        )
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            title = { Text("Finish match?") },
            text = {
                Text(
                    if (recordingThisMatch)
                        "The clock will stop, the camera recording will be stopped and saved, and the match will move to review."
                    else "The clock will stop and the match will move to review."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFinishConfirmation = false
                    if (recordingThisMatch) {
                        MatchRecordingService.requestStop(context, matchTimeMs)
                    }
                    vm.finishLiveMatch(matchId) {
                        nav.navigate("review/$matchId") {
                            popUpTo("live/$matchId") { inclusive = true }
                        }
                    }
                }) { Text("Finish match") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    pendingGoalDeletion?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingGoalDeletion = null },
            title = {
                Text(if (event.type == "OPPONENT_GOAL") "Delete opponent goal?" else "Delete goal?")
            },
            text = {
                Text("The goal at ${MatchClockCalculator.formatClock(event.timestampMs)} is removed from the timeline and the score.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScoringEvent(event.id)
                    pendingGoalDeletion = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGoalDeletion = null }) { Text("Cancel") }
            }
        )
    }

    selectedVideoEvent?.let { event ->
        VideoEventPlaybackSheet(
            initialEvent = event,
            events = events,
            recordings = recordings,
            playersById = playersById,
            onDismiss = { selectedVideoEvent = null }
        )
    }

    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Leave live screen?") },
            text = {
                Text(
                    if (current.clockRunning) "The match clock will continue."
                    else "Your match state is saved."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirmation = false
                    nav.navigate("home") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }) { Text("Leave screen") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmation = false }) { Text("Stay") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChoiceSheet(
    title: String,
    playerIds: List<Long>,
    playersById: Map<Long, Player>,
    includeNone: Boolean = false,
    noneLabel: String = "None",
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (includeNone) {
                    item {
                        ListItem(
                            headlineContent = { Text(noneLabel) },
                            modifier = Modifier.clickable { onSelect(null) }
                        )
                    }
                }
                items(playerIds, key = { it }) { playerId ->
                    val player = playersById[playerId] ?: return@items
                    ListItem(
                        headlineContent = { Text(player.name, fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Text("#${player.shirtNumber.takeIf { it > 0 } ?: "—"}")
                        },
                        modifier = Modifier.clickable { onSelect(playerId) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditSheet(
    event: MatchEvent,
    maxTimeMs: Long,
    players: List<Player>,
    onDismiss: () -> Unit,
    onSave: (Long?, Long?, Long) -> Unit,
    onDelete: () -> Unit
) {
    var scorer by remember(event.id) { mutableStateOf(event.playerId) }
    var assist by remember(event.id) { mutableStateOf(event.relatedPlayerId) }
    var timeMs by remember(event.id) { mutableLongStateOf(event.timestampMs) }
    var choosingScorer by remember { mutableStateOf(false) }
    var choosingAssist by remember { mutableStateOf(false) }
    val byId = players.associateBy { it.id }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Edit goal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { choosingScorer = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Scorer: ${byId[scorer]?.name ?: "Unknown"}")
            }
            OutlinedButton(onClick = { choosingAssist = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Assist: ${byId[assist]?.name ?: "None"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { timeMs = (timeMs - 60_000L).coerceAtLeast(0L) },
                    modifier = Modifier.weight(1f)
                ) { Text("−1 min") }
                Text(
                    MatchClockCalculator.formatClock(timeMs),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { timeMs = (timeMs + 60_000L).coerceAtMost(maxTimeMs) },
                    enabled = timeMs < maxTimeMs,
                    modifier = Modifier.weight(1f)
                ) { Text("+1 min") }
            }
            Button(
                onClick = { onSave(scorer, assist?.takeIf { it != scorer }, timeMs) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save changes") }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text("Delete goal", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (choosingScorer) {
        PlayerChoiceSheet(
            title = "Choose scorer",
            playerIds = players.map { it.id },
            playersById = byId,
            includeNone = true,
            noneLabel = "Unknown scorer",
            onDismiss = { choosingScorer = false },
            onSelect = {
                scorer = it
                if (assist == scorer) assist = null
                choosingScorer = false
            }
        )
    }
    if (choosingAssist) {
        PlayerChoiceSheet(
            title = "Choose assist",
            playerIds = players.map { it.id }.filterNot { it == scorer },
            playersById = byId,
            includeNone = true,
            noneLabel = "No assist",
            onDismiss = { choosingAssist = false },
            onSelect = {
                assist = it
                choosingAssist = false
            }
        )
    }
}

@Composable
private fun ScoreCorrectionDialog(
    ourScore: Int,
    opponentScore: Int,
    opponentName: String,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    var ours by remember { mutableIntStateOf(ourScore) }
    var opponents by remember { mutableIntStateOf(opponentScore) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct score") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreStepper("Our team", ours) { ours = it }
                ScoreStepper(opponentName, opponents) { opponents = it }
                Text(
                    "Corrections add or remove timeline goal events so the score and timeline remain consistent.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ours, opponents) }) { Text("Save score") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScoreStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(99)) }) { Text("+") }
    }
}

@Composable
private fun MatchTimeline(
    events: List<MatchEvent>,
    playersById: Map<Long, Player>,
    recordings: List<RecordingSegment>,
    modifier: Modifier,
    onPlayEvent: (MatchEvent) -> Unit,
    onEditGoal: (MatchEvent) -> Unit,
    onDeleteGoal: (MatchEvent) -> Unit
) {
    if (events.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No match events yet")
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(events, key = { it.id }) { event ->
            val scoring = event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL"
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            MatchClockCalculator.formatClock(event.timestampMs),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(eventTitle(event, playersById), fontWeight = FontWeight.Bold)
                        eventSubtitle(event, playersById)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (be.matchreview.app.domain.VideoEventRules.isPlayable(event, recordings)) {
                            FilledTonalButton(
                                onClick = { onPlayEvent(event) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) { Text("▶ Clip") }
                            Text(
                                "+${MatchClockCalculator.formatClock(event.recordingOffsetMs ?: 0L)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else if (event.recordingSegmentId != null) {
                            Text("Clip saving…", style = MaterialTheme.typography.labelSmall)
                        }
                        if (event.type == "OUR_GOAL") {
                            TextButton(onClick = { onEditGoal(event) }) { Text("Edit") }
                        }
                        if (scoring) {
                            TextButton(onClick = { onDeleteGoal(event) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun eventTitle(event: MatchEvent, playersById: Map<Long, Player>): String = when (event.type) {
    "OUR_GOAL" -> "Goal • ${playersById[event.playerId]?.name ?: "Unknown scorer"}"
    "OPPONENT_GOAL" -> "Opponent goal"
    "SUBSTITUTION" -> "Substitution • ${playersById[event.playerId]?.name ?: "Player"} off"
    "PLAYER_ON" -> "${playersById[event.playerId]?.name ?: "Player"} entered"
    "DISMISSAL" -> "Dismissal • ${playersById[event.playerId]?.name ?: "Player"}"
    "INJURY_OFF" -> "Injury • ${playersById[event.playerId]?.name ?: "Player"} off"
    "PLAYER_OFF" -> "${playersById[event.playerId]?.name ?: "Player"} off"
    else -> event.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun eventSubtitle(event: MatchEvent, playersById: Map<Long, Player>): String? = when {
    event.type == "OUR_GOAL" && event.relatedPlayerId != null ->
        "Assist: ${playersById[event.relatedPlayerId]?.name ?: "Unknown"}"
    event.type == "SUBSTITUTION" && event.relatedPlayerId != null ->
        "${playersById[event.relatedPlayerId]?.name ?: "Player"} on"
    event.note.isNotBlank() -> event.note
    else -> null
}

@Composable
private fun LiveScoreboard(match: GameMatch, matchTimeMs: Long, periodTimeMs: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3263F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ScoreSide("Our team", match.ourScore)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    MatchClockCalculator.formatClock(matchTimeMs),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (match.currentPeriod == 0) "Ready"
                    else "Period ${match.currentPeriod}/${match.periodCount} • " +
                        MatchClockCalculator.formatClock(periodTimeMs),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ScoreSide(match.opponent, match.opponentScore)
        }
    }
}

@Composable
private fun ScoreSide(label: String, score: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(86.dp)) {
        Surface(shape = CircleShape, color = Color.White) {
            Text(
                score.toString(),
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color(0xFF222222)
            )
        }
        Text(label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LivePitch(
    placements: List<MatchLineupPlacement>,
    playersById: Map<Long, Player>,
    minutesFor: (Long) -> Long,
    onPlayerClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF86C440)).padding(2.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.72f)
            drawRect(line, style = Stroke(width = 3f))
            drawLine(line, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 3f)
            drawCircle(line, radius = size.minDimension * 0.13f, center = center, style = Stroke(3f))
            drawRect(
                line,
                topLeft = Offset(size.width * 0.22f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.16f),
                style = Stroke(3f)
            )
            drawRect(
                line,
                topLeft = Offset(size.width * 0.22f, size.height * 0.84f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.16f),
                style = Stroke(3f)
            )
        }
        placements.forEach { placement ->
            val player = playersById[placement.playerId] ?: return@forEach
            val marker = 58.dp
            val x = (maxWidth * placement.normalizedX.coerceIn(0.06f, 0.94f) - marker / 2)
            val y = (maxHeight * placement.normalizedY.coerceIn(0.06f, 0.94f) - marker / 2)
            Column(
                Modifier.offset(x = x, y = y).width(marker).clickable { onPlayerClick(player.id) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFE8EEF7),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (player.shirtNumber > 0) player.shirtNumber.toString()
                            else player.name.take(2).uppercase(),
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF24324A)
                        )
                    }
                }
                Text(
                    player.name.substringBefore(" "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${MatchClockCalculator.displayedWholeMinutes(minutesFor(player.id))}'",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF16330E),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BenchMinuteCard(
    player: Player,
    minutes: Long,
    enabled: Boolean,
    stateLabel: String?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(78.dp).fillMaxHeight().clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (player.shirtNumber > 0) player.shirtNumber.toString() else "•",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                player.name.substringBefore(" "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stateLabel ?: "$minutes'",
                style = MaterialTheme.typography.labelSmall,
                color = if (stateLabel == null) LocalContentColor.current else MaterialTheme.colorScheme.error,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactLiveTabs(
    selected: LiveTab,
    onSelected: (LiveTab) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            LiveTab.MATCH to "⚽ Match",
            LiveTab.CAMERA to "● Camera",
            LiveTab.TIMELINE to "≡ Timeline"
        ).forEach { (tab, label) ->
            if (selected == tab) {
                FilledTonalButton(
                    onClick = { onSelected(tab) },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            } else {
                TextButton(
                    onClick = { onSelected(tab) },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun LiveControlBar(
    match: GameMatch,
    onKickOff: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEndPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onFinish: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 5.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (match.status) {
            MatchStatus.LINEUP_READY ->
                Button(onClick = onKickOff, modifier = Modifier.fillMaxWidth()) {
                    Text("Kick off • Start period 1")
                }
            MatchStatus.LIVE -> {
                Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Pause") }
                OutlinedButton(onClick = onEndPeriod, modifier = Modifier.weight(1f)) { Text("End period") }
                TextButton(onClick = onFinish) { Text("Finish") }
            }
            MatchStatus.PAUSED -> {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
                OutlinedButton(onClick = onEndPeriod, modifier = Modifier.weight(1f)) { Text("End period") }
                TextButton(onClick = onFinish) { Text("Finish") }
            }
            MatchStatus.PERIOD_ENDED -> {
                if (match.currentPeriod < match.periodCount) {
                    Button(onClick = onNextPeriod, modifier = Modifier.weight(1f)) {
                        Text("Start period ${match.currentPeriod + 1}")
                    }
                    TextButton(onClick = onFinish) { Text("Finish") }
                } else {
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Finish match") }
                }
            }
            MatchStatus.FINISHED ->
                Text("Match finished", modifier = Modifier.align(Alignment.CenterVertically))
            else -> Text("Save the starting lineup first.")
        }
    }
}

private fun playerStateLabel(state: PlayerMatchState?): String = when (state) {
    PlayerMatchState.DISMISSED -> "Dismissed • cannot return"
    PlayerMatchState.REMOVED -> "Removed • cannot return"
    PlayerMatchState.UNAVAILABLE -> "Unavailable"
    else -> "Currently on the bench"
}

private fun statusLabel(match: GameMatch): String = when (match.status) {
    MatchStatus.LINEUP_READY -> "Ready for kick-off"
    MatchStatus.LIVE -> "Period ${match.currentPeriod} live"
    MatchStatus.PAUSED -> "Period ${match.currentPeriod} paused"
    MatchStatus.PERIOD_ENDED ->
        if (match.currentPeriod < match.periodCount) "Interval" else "All periods complete"
    MatchStatus.FINISHED -> "Finished"
    else -> match.status.name.lowercase().replaceFirstChar { it.uppercase() }
}
