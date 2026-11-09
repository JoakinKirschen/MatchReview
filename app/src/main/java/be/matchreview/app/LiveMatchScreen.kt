@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButtons
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import be.matchreview.app.ui.LocalLargeControls
import be.matchreview.app.ui.isLandscape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import be.matchreview.app.data.*
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.LiveAnnouncementRules
import be.matchreview.app.domain.ClockRecoveryRules
import be.matchreview.app.domain.ClockRecoveryConfidence
import be.matchreview.app.domain.GoalMouthGeometry
import be.matchreview.app.domain.GoalkeeperRules
import be.matchreview.app.domain.PlayingTimeRules
import be.matchreview.app.domain.LineupUndoRules
import be.matchreview.app.domain.LiveClockRules
import be.matchreview.app.domain.MatchActions
import be.matchreview.app.domain.MatchStats
import be.matchreview.app.domain.MatchStatsRules
import be.matchreview.app.domain.PeriodTimeRules
import be.matchreview.app.domain.PitchOrientation
import androidx.compose.runtime.saveable.rememberSaveable
import be.matchreview.app.domain.TimelineGrouping
import be.matchreview.app.domain.TimelineItem
import be.matchreview.app.recording.CameraMatchPanel
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.CameraRecordingState
import be.matchreview.app.recording.MatchRecordingService
import be.matchreview.app.recording.activeMatchId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LiveTab { MATCH, CAMERA, TIMELINE }

/** A goal that was just recorded or is being edited, waiting for its position in the goal. */
private data class GoalPlacementRequest(
    val eventId: Long,
    val title: String,
    val initialX: Float? = null,
    val initialY: Float? = null,
    /** Quick goals offer to add the scorer once the position step is finished. */
    val offerScorerDetails: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMatchScreen(
    matchId: Long,
    vm: MainViewModel,
    nav: NavHostController
) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val players by vm.players.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
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
    val pendingPeriodResume by vm.pendingPeriodRecording.collectAsStateWithLifecycle()
    val recordingStopMode by vm.recordingStopMode.collectAsStateWithLifecycle()
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
    val clockRecovery = if (current.clockRunning && openSegment != null) {
        ClockRecoveryRules.assess(
            segmentStartMatchTimeMs = openSegment.startMatchTimeMs,
            monotonicStartMs = openSegment.monotonicStartMs,
            wallClockStartMs = openSegment.wallClockStartMs,
            monotonicNowMs = nowMonotonic,
            wallClockNowMs = nowWall
        )
    } else null
    val matchTimeMs = clockRecovery?.recoveredMatchTimeMs ?: current.accumulatedMatchTimeMs

    val activePeriod = periods.firstOrNull { it.periodNumber == current.currentPeriod }
    val periodTimeMs = (matchTimeMs - (activePeriod?.startMatchTimeMs ?: matchTimeMs))
        .coerceAtLeast(0L)
    val plannedPeriodMs = activePeriod?.plannedDurationMs ?: (current.periodDurationMinutes * 60_000L)
    val periodTimeUp = current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED) &&
        PeriodTimeRules.isOver(periodTimeMs, plannedPeriodMs)

    // Vibrate once when the planned period time is reached; the clock itself keeps running.
    // The notification clock alerts too when the phone is locked; whichever is first wins.
    LaunchedEffect(periodTimeUp, current.currentPeriod) {
        if (current.status == MatchStatus.LIVE &&
            PeriodTimeRules.shouldAlert(periodTimeMs, plannedPeriodMs, alreadyAlerted = false) &&
            MatchAlerts.claimPeriodAlert(context, matchId, current.currentPeriod)
        ) {
            MatchAlerts.vibratePeriodEnd(context)
        }
    }
    // The clock and score in the notification bar, also when another app is open.
    LaunchedEffect(current.status) {
        if (current.status in LiveClockRules.ACTIVE_STATUSES) MatchClockService.start(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The clock works either way; only the notification needs it. */ }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val teamName = teams.firstOrNull { it.id == current.teamId }?.name ?: tr("Our team")
    // Only the latest lineup change of the current period can be undone.
    val undoPlan = remember(events, placements) { LineupUndoRules.plan(events, placements) }
        ?.takeIf { it.periodNumber == current.currentPeriod }
    val undoableRoundId = undoPlan?.roundEvents?.first()?.id
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

    // Saved, so turning the phone keeps the camera or timeline open.
    var tab by rememberSaveable { mutableStateOf(LiveTab.MATCH) }
    var selectedPlayerId by remember { mutableStateOf<Long?>(null) }
    var removalPlayerId by remember { mutableStateOf<Long?>(null) }
    var scorerSelectionOpen by remember { mutableStateOf(false) }
    var pendingGoalScorerId by remember { mutableStateOf<Long?>(null) }
    var goalFlowActive by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf<MatchEvent?>(null) }
    var showOpponentGoalConfirmation by remember { mutableStateOf(false) }
    var showScoreCorrection by remember { mutableStateOf(false) }
    var showMatchMenu by remember { mutableStateOf(false) }
    var showUndoRoundConfirmation by remember { mutableStateOf(false) }
    var showDetailsEditor by remember { mutableStateOf(false) }
    var showFinishConfirmation by remember { mutableStateOf(false) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var selectedVideoEvent by remember { mutableStateOf<MatchEvent?>(null) }
    var showFullVideo by remember { mutableStateOf(false) }
    var pendingGoalDeletion by remember { mutableStateOf<MatchEvent?>(null) }
    var pendingQuickGoalEditId by remember { mutableStateOf<Long?>(null) }
    /** A quick goal waiting for its scorer (scorerChosen = false) or its assist. */
    var goalDetails by remember { mutableStateOf<QuickGoalDetails?>(null) }
    var showEndPeriodConfirmation by remember { mutableStateOf(false) }
    var liveActionLocked by remember { mutableStateOf(false) }
    var substitutionMode by remember { mutableStateOf(false) }
    var restoredSubstitutionPlan by remember { mutableStateOf<List<MatchLineupPlacement>?>(null) }
    var goalPlacement by remember { mutableStateOf<GoalPlacementRequest?>(null) }
    var keeperChoiceOpen by remember { mutableStateOf(false) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    var pendingPlayerAction by remember { mutableStateOf<PendingPlayerAction?>(null) }
    var pendingEventDeletion by remember { mutableStateOf<MatchEvent?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun runLiveAction(action: () -> Unit) {
        if (liveActionLocked) return
        liveActionLocked = true
        action()
        scope.launch {
            delay(650)
            liveActionLocked = false
        }
    }

    fun offerQuickGoalDetails(eventId: Long) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = tr("Goal recorded — scorer not assigned"),
                actionLabel = tr("Add details"),
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                pendingQuickGoalEditId = eventId
            }
        }
    }

    fun recordQuickGoal() {
        runLiveAction {
            vm.recordOurGoal(matchId, null, null) { eventId ->
                if (eventId != null) {
                    goalPlacement = GoalPlacementRequest(
                        eventId = eventId,
                        title = tr("Where did {teamName} score?", "teamName" to teamName),
                        offerScorerDetails = true
                    )
                }
            }
        }
    }

    fun recordSave(keeperId: Long?) {
        runLiveAction {
            vm.recordKeeperSave(matchId, keeperId) { eventId ->
                if (eventId != null) {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = tr("Save • {keeper}", "keeper" to (playersById[keeperId]?.name ?: tr("keeper not assigned"))),
                            actionLabel = tr("Undo"),
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.deleteEventById(eventId)
                    }
                }
            }
        }
    }

    fun recordAction(type: String, playerId: Long?) {
        runLiveAction {
            vm.recordMatchAction(matchId, type, playerId) { eventId ->
                if (eventId != null) {
                    scope.launch {
                        val who = playerId?.let { playersById[it]?.name }
                            ?: if (MatchActions.isOpponent(type)) current.opponent else teamName
                        val result = snackbarHostState.showSnackbar(
                            message = if (type == MatchActions.MARK) tr("Moment marked in the video")
                            else "${MatchActions.label(type) ?: type} • $who",
                            actionLabel = tr("Undo"),
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.deleteEventById(eventId)
                    }
                }
            }
        }
    }

    fun startAction(type: String) {
        actionsSheetOpen = false
        val onPitchIds = onPitch.map { it.playerId }
        val squadIds = squad.filter { it.selected }.map { it.playerId }
        when (type) {
            MatchActions.OUR_SHOT_ON_TARGET, MatchActions.OUR_SHOT_OFF_TARGET ->
                pendingPlayerAction = PendingPlayerAction(type, tr("Who took the shot?"), onPitchIds, allowNone = true)
            MatchActions.YELLOW_CARD ->
                pendingPlayerAction = PendingPlayerAction(type, tr("Yellow card for?"), squadIds, allowNone = true)
            MatchActions.DISMISSAL ->
                pendingPlayerAction = PendingPlayerAction(type, tr("Red card for?"), onPitchIds, allowNone = false)
            else -> recordAction(type, null)
        }
    }

    fun startSave() {
        val keepers = GoalkeeperRules.onPitchGoalkeepers(onPitch, squad, players)
        if (keepers.size == 1) recordSave(keepers.single()) else keeperChoiceOpen = true
    }

    fun enterSubstitutionMode() {
        selectedPlayerId = null
        tab = LiveTab.MATCH
        substitutionMode = true
    }

    fun leaveSubstitutionMode() {
        substitutionMode = false
        restoredSubstitutionPlan = null
        vm.clearSubstitutionDraft(matchId)
    }

    // Continue a round that was open when the app closed, if the lineup is unchanged.
    LaunchedEffect(matchId, placements.isNotEmpty()) {
        if (placements.isEmpty() || substitutionMode) return@LaunchedEffect
        val draft = vm.substitutionDraft(matchId) ?: return@LaunchedEffect
        if (liveActionsEnabled &&
            be.matchreview.app.domain.SubstitutionDraftCodec.fitsLineup(draft, placements)
        ) {
            restoredSubstitutionPlan = draft
            tab = LiveTab.MATCH
            substitutionMode = true
        } else {
            vm.clearSubstitutionDraft(matchId)
        }
    }

    LaunchedEffect(events, pendingQuickGoalEditId) {
        val eventId = pendingQuickGoalEditId ?: return@LaunchedEffect
        events.firstOrNull { it.id == eventId }?.let {
            editGoal = it
            pendingQuickGoalEditId = null
        }
    }

    BackHandler { showLeaveConfirmation = true }
    // Registered later, so it wins while substitution mode is open.
    BackHandler(enabled = substitutionMode) { leaveSubstitutionMode() }
    LaunchedEffect(liveActionsEnabled) { if (!liveActionsEnabled && substitutionMode) leaveSubstitutionMode() }

    val landscape = isLandscape()
    val configuration = LocalConfiguration.current
    // A tablet has room for the camera next to the pitch.
    val cameraBesidePitch = if (landscape) configuration.screenWidthDp >= 1000
    else configuration.screenWidthDp >= 700 && configuration.screenHeightDp >= 1000

    val liveHeader: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showLeaveConfirmation = true }) { Text(tr("Back")) }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    tr("vs {opponent}", "opponent" to current.opponent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(statusLabel(current), style = MaterialTheme.typography.labelSmall)
            }
            // Rare corrections live in a menu so they are not tapped by accident.
            Box {
                IconButton(onClick = { showMatchMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = tr("More match options"))
                }
                DropdownMenu(expanded = showMatchMenu, onDismissRequest = { showMatchMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Correct score")) },
                        onClick = {
                            showMatchMenu = false
                            showScoreCorrection = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Edit match details")) },
                        onClick = {
                            showMatchMenu = false
                            showDetailsEditor = true
                        }
                    )
                }
            }
        }
    }

    val liveTabs: @Composable () -> Unit = {
        CompactLiveTabs(
            selected = if (cameraBesidePitch && tab == LiveTab.CAMERA) LiveTab.MATCH else tab,
            onSelected = { tab = it },
            tabs = if (cameraBesidePitch) listOf(LiveTab.MATCH, LiveTab.TIMELINE) else LiveTab.entries
        )
    }

    val controlBar: @Composable () -> Unit = {
        LiveControlBar(
            match = current,
            actionEnabled = !liveActionLocked,
            onKickOff = {
                askForNotifications()
                runLiveAction { vm.kickOffMatch(matchId) }
            },
            onPause = { runLiveAction { vm.pauseMatchClock(matchId) } },
            onResume = { runLiveAction { vm.resumeMatchClock(matchId) } },
            onEndPeriod = { showEndPeriodConfirmation = true },
            onNextPeriod = { runLiveAction { vm.startNextPeriod(matchId) } },
            onFinish = { showFinishConfirmation = true }
        )
    }

    /** Scoreboard and the notices that belong with it. */
    val statusSection: @Composable () -> Unit = {
        LiveScoreboard(
            match = current,
            teamName = teamName,
            matchTimeMs = matchTimeMs,
            periodTimeMs = periodTimeMs,
            plannedPeriodMs = plannedPeriodMs
        )

        if (periodTimeUp) {
            Surface(
                color = Color(0xFFFFC247),
                contentColor = Color(0xFF1B1B1F),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("Period time reached • +{time}", "time" to (MatchClockCalculator.formatClock(
                            PeriodTimeRules.overtimeMs(periodTimeMs, plannedPeriodMs)
                        ))),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showEndPeriodConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1B1B1F)),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(tr("End period"), fontWeight = FontWeight.Bold) }
                }
            }
        }

        RecordingStatusChip(
            state = cameraState,
            matchId = matchId,
            restartsWithPeriod = pendingPeriodResume?.matchId == matchId &&
                current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED) &&
                current.currentPeriod < current.periodCount,
            // Stopped at the planned time while play goes on (added time).
            stoppedInPlay = current.status != MatchStatus.PERIOD_ENDED,
            nextPeriod = current.currentPeriod + 1,
            onClick = { tab = LiveTab.CAMERA }
        )

        if (clockRecovery?.confidence == ClockRecoveryConfidence.NEEDS_CONFIRMATION) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    tr("Check the match clock: {explanation}", "explanation" to clockRecovery.explanation),
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // The quick actions; in landscape they wrap onto two rows in the narrower side panel.
    // The Outdoor theme makes them taller with bigger text.
    val largeControls = LocalLargeControls.current
    val actionHeight = if (largeControls) 68.dp else AppButtons.LargeHeight
    val actionTextStyle = if (largeControls) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    else LocalTextStyle.current
    val ourGoalButton: @Composable (Modifier) -> Unit = { modifier ->
        AppButton(
            onClick = ::recordQuickGoal,
            enabled = !liveActionLocked,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = modifier.heightIn(min = actionHeight)
        ) { Text(tr("Our goal"), style = actionTextStyle, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
    }
    val theirGoalButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = { showOpponentGoalConfirmation = true },
            enabled = !liveActionLocked,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = modifier.heightIn(min = actionHeight)
        ) { Text(tr("Their goal"), style = actionTextStyle, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) }
    }
    val saveButton: @Composable (Modifier) -> Unit = { modifier ->
        AppTonalButton(
            onClick = ::startSave,
            enabled = !liveActionLocked,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = modifier.heightIn(min = actionHeight)
        ) {
            val saves = events.count { it.type == "KEEPER_SAVE" }
            Text(
                if (saves > 0) "${tr("Save|keeper")} $saves"
                else tr("Save|keeper"),
                style = actionTextStyle,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
    val undoButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = {
                runLiveAction { vm.undoLatestScoreAction(matchId) }
                scope.launch { snackbarHostState.showSnackbar(tr("Latest score action undone")) }
            },
            enabled = !liveActionLocked &&
                events.any { it.type == "OUR_GOAL" || it.type == "OPPONENT_GOAL" },
            contentPadding = PaddingValues(horizontal = 6.dp),
            // An icon keeps the row short enough for longer translations of the other buttons.
            modifier = modifier.widthIn(min = 48.dp).heightIn(min = actionHeight)
                .semantics { contentDescription = tr("Undo the latest goal") }
        ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) }
    }
    val moreActionsButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = { actionsSheetOpen = true },
            enabled = !liveActionLocked,
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = modifier.widthIn(min = 48.dp).heightIn(min = actionHeight)
                .semantics { contentDescription = tr("Shots, corners and cards") }
        ) { Text("⋯", fontWeight = FontWeight.Black) }
    }

    val substitutesHeader: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tr("Subs • {onPitchCount}/{playersOnPitch} on pitch • {eligibleBenchIdsCount} available", "onPitchCount" to onPitch.size, "playersOnPitch" to current.playersOnPitch, "eligibleBenchIdsCount" to eligibleBenchIds.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = ::enterSubstitutionMode,
                enabled = liveActionsEnabled && placements.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(tr("Substitution mode")) }
        }
    }

    val leastPlayedBench = PlayingTimeRules.leastPlayed(eligibleBenchIds, ::playedMs)
    val sortedBench = bench.sortedWith(
        compareBy<MatchLineupPlacement>({ it.playerId !in eligibleBenchIds }, { playedMs(it.playerId) })
    )
    val benchCard: @Composable (MatchLineupPlacement, Modifier) -> Unit = { placement, modifier ->
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
                    PlayerMatchState.REMOVED -> tr("Out")
                    PlayerMatchState.DISMISSED -> tr("Sent off")
                    else -> null
                },
                highlighted = player.id in leastPlayedBench,
                onClick = { selectedPlayerId = player.id },
                modifier = modifier
            )
        }
    }

    val livePitch: @Composable (Modifier) -> Unit = { modifier ->
        LivePitch(
            placements = onPitch,
            playersById = playersById,
            minutesFor = { playedMs(it) },
            onPlayerClick = { selectedPlayerId = it },
            modifier = modifier
        )
    }

    val matchContent: @Composable ColumnScope.() -> Unit = {
        if (landscape) {
            substitutesHeader()
            // The pitch lies on its side, the substitutes in a column beside it.
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                livePitch(Modifier.weight(1f).fillMaxHeight())
                LazyColumn(
                    modifier = Modifier.width(80.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedBench, key = { it.playerId }) { placement ->
                        benchCard(placement, Modifier.fillMaxWidth().height(64.dp))
                    }
                }
            }
        } else {
            livePitch(Modifier.weight(1f).fillMaxWidth())
            substitutesHeader()
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                items(sortedBench, key = { it.playerId }) { placement ->
                    benchCard(placement, Modifier.width(78.dp).fillMaxHeight())
                }
            }
        }
    }
    val cameraPanel: @Composable (Modifier) -> Unit = { modifier ->
        CameraMatchPanel(
            matchId = matchId,
            matchClockMs = matchTimeMs,
            recordings = recordings,
            stopMode = recordingStopMode,
            onStopModeChange = vm::setRecordingStopMode,
            onWatchVideo = { showFullVideo = true },
            onMarkMoment = { recordAction(MatchActions.MARK, null) },
            modifier = modifier
        )
    }

    val tabContent: @Composable ColumnScope.() -> Unit = {
        when (tab) {
            LiveTab.MATCH, LiveTab.CAMERA -> when {
                // Tablets show the camera beside or above the pitch, no tab switching needed.
                cameraBesidePitch && landscape -> Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        matchContent()
                    }
                    cameraPanel(Modifier.weight(1f).fillMaxHeight())
                }
                cameraBesidePitch -> {
                    cameraPanel(Modifier.weight(0.45f).fillMaxWidth())
                    Column(Modifier.weight(0.55f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        matchContent()
                    }
                }
                tab == LiveTab.CAMERA -> cameraPanel(Modifier.weight(1f).fillMaxWidth())
                else -> matchContent()
            }
            LiveTab.TIMELINE -> MatchTimeline(
                // Tags on an imported video use video positions, not match time.
                events = events.filterNot(be.matchreview.app.domain.VideoEventRules::isImportedVideoTag).sortedWith(
                    compareByDescending<MatchEvent> { it.timestampMs }.thenByDescending { it.id }
                ),
                playersById = playersById,
                recordings = recordings,
                modifier = Modifier.weight(1f),
                onPlayEvent = { selectedVideoEvent = it },
                onEditGoal = { editGoal = it },
                onEditGoalPosition = { event ->
                    goalPlacement = GoalPlacementRequest(
                        eventId = event.id,
                        title = if (event.type == "OPPONENT_GOAL") tr("Where did {opponent} score?", "opponent" to current.opponent)
                        else tr("Where did {teamName} score?", "teamName" to teamName),
                        initialX = event.goalX,
                        initialY = event.goalY
                    )
                },
                onDeleteGoal = { pendingGoalDeletion = it },
                onDeleteEvent = { pendingEventDeletion = it },
                undoableRoundId = undoableRoundId,
                onUndoRound = { showUndoRoundConfirmation = true }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Substitution mode gives the whole screen to the pitch and bench. In landscape
            // the header moves into the side panel to leave the height for the content.
            if (!substitutionMode && !landscape) {
                Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    liveHeader()
                }
            }
        },
        bottomBar = {
            if (!substitutionMode && !landscape) {
                Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.navigationBarsPadding()) {
                        liveTabs()
                        controlBar()
                    }
                }
            }
        }
    ) { padding ->
        if (substitutionMode) {
            SubstitutionModePanel(
                match = current,
                livePlacements = placements,
                restoredPlan = restoredSubstitutionPlan,
                playersById = playersById,
                eligibleBenchIds = eligibleBenchIds,
                clockLabel = MatchClockCalculator.formatClock(matchTimeMs),
                scoreLabel = "${current.ourScore}–${current.opponentScore}",
                minutesLabel = { "${MatchClockCalculator.displayedWholeMinutes(playedMs(it))}'" },
                playedMs = ::playedMs,
                onDraftChanged = { draft ->
                    if (draft == null) vm.clearSubstitutionDraft(matchId)
                    else vm.saveSubstitutionDraft(matchId, draft)
                },
                onCancel = ::leaveSubstitutionMode,
                onConfirm = { plan ->
                    vm.applySubstitutionRound(matchId, plan) { applied ->
                        if (applied) {
                            leaveSubstitutionMode()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = tr("Lineup changes saved"),
                                    actionLabel = tr("Undo"),
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.undoLatestLineupChange(matchId)
                                }
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    tr("Those changes are not allowed any more. Check the lineup and try again.")
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (landscape) {
            // Held sideways: score, quick actions and the clock buttons on the left, the
            // pitch, camera or timeline filling the rest of the screen on the right.
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    liveHeader()
                    statusSection()
                    if (liveActionsEnabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ourGoalButton(Modifier.weight(1f))
                            theirGoalButton(Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            saveButton(Modifier.weight(1f))
                            undoButton(Modifier)
                            moreActionsButton(Modifier)
                        }
                    }
                    controlBar()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    liveTabs()
                    tabContent()
                }
            }
        } else Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            statusSection()

            if (liveActionsEnabled && largeControls) {
                // The big outdoor buttons need two rows to keep their labels readable.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ourGoalButton(Modifier.weight(1f))
                    theirGoalButton(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    saveButton(Modifier.weight(1f))
                    undoButton(Modifier)
                    moreActionsButton(Modifier)
                }
            } else if (liveActionsEnabled) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ourGoalButton(Modifier.weight(1f))
                    theirGoalButton(Modifier.weight(1f))
                    saveButton(Modifier.weight(1f))
                    undoButton(Modifier)
                    moreActionsButton(Modifier)
                }
            }

            tabContent()
        }
    }

    if (showFullVideo) {
        MatchVideoDialog(
            title = tr("{teamName} vs {opponent}", "teamName" to teamName, "opponent" to current.opponent),
            recordings = recordings,
            periods = periods,
            events = events,
            playersById = playersById,
            onDismiss = { showFullVideo = false }
        )
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
                        tr("{minutes} minutes", "minutes" to (MatchClockCalculator.displayedWholeMinutes(playedMs(player.id))))
                )
                Text(if (isOnPitch) tr("Currently on the pitch") else playerStateLabel(squadState))

                if (liveActionsEnabled && isOnPitch) {
                    AppButton(
                        onClick = {
                            selectedPlayerId = null
                            pendingGoalScorerId = player.id
                            goalFlowActive = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Record goal")) }
                    AppOutlinedButton(
                        onClick = {
                            selectedPlayerId = null
                            recordSave(player.id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Record save")) }
                    AppOutlinedButton(
                        onClick = {
                            selectedPlayerId = null
                            removalPlayerId = player.id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Injury or sent off")) }
                }
                // Substitutions happen only in substitution mode.
                if (liveActionsEnabled && (isOnPitch || player.id in eligibleBenchIds)) {
                    AppOutlinedButton(
                        onClick = ::enterSubstitutionMode,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("Open substitution mode")) }
                }
            }
        }
    }

    if (scorerSelectionOpen) {
        PlayerChoiceSheet(
            title = tr("Who scored?"),
            playerIds = onPitch.map { it.playerId },
            playersById = playersById,
            includeNone = true,
            noneLabel = tr("Unknown scorer"),
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
            title = tr("Assist for {scorer}", "scorer" to (playersById[scorerId]?.name ?: tr("the goal"))),
            playerIds = onPitch.map { it.playerId }.filterNot { it == scorerId },
            playersById = playersById,
            includeNone = true,
            noneLabel = tr("No assist"),
            onDismiss = {
                goalFlowActive = false
                pendingGoalScorerId = null
            },
            onSelect = { assistId ->
                vm.recordOurGoal(matchId, scorerId, assistId) { eventId ->
                    if (eventId != null) {
                        goalPlacement = GoalPlacementRequest(
                            eventId = eventId,
                            title = tr("Where did {scorer} score?", "scorer" to (playersById[scorerId]?.name ?: teamName))
                        )
                    }
                }
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
            },
            onEditPosition = {
                editGoal = null
                goalPlacement = GoalPlacementRequest(
                    eventId = event.id,
                    title = if (event.type == "OPPONENT_GOAL") tr("Where did {opponent} score?", "opponent" to current.opponent)
                    else tr("Where did {teamName} score?", "teamName" to teamName),
                    initialX = event.goalX,
                    initialY = event.goalY
                )
            }
        )
    }

    goalPlacement?.let { request ->
        fun close() {
            goalPlacement = null
            // Straight on to the scorer, so goals are not left unassigned.
            if (request.offerScorerDetails) goalDetails = QuickGoalDetails(request.eventId)
        }
        GoalPlacementDialog(
            title = request.title,
            initialX = request.initialX,
            initialY = request.initialY,
            onSkip = ::close,
            onSave = { x, y ->
                vm.setGoalPlacement(request.eventId, x, y)
                close()
            }
        )
    }

    goalDetails?.let { details ->
        val onPitchIds = onPitch.map { it.playerId }
        fun save(scorerId: Long?, assistId: Long?) {
            goalDetails = null
            val event = events.firstOrNull { it.id == details.eventId } ?: return
            vm.updateOurGoal(event.id, scorerId, assistId, event.timestampMs)
        }
        if (!details.scorerChosen) {
            PlayerChoiceSheet(
                title = tr("Who scored?"),
                playerIds = onPitchIds,
                playersById = playersById,
                includeNone = true,
                noneLabel = tr("Scorer not known"),
                onDismiss = {
                    goalDetails = null
                    offerQuickGoalDetails(details.eventId)
                },
                onSelect = { scorerId ->
                    if (scorerId == null) save(null, null)
                    else goalDetails = details.copy(scorerChosen = true, scorerId = scorerId)
                }
            )
        } else {
            PlayerChoiceSheet(
                title = tr("Assist by?"),
                playerIds = onPitchIds.filter { it != details.scorerId },
                playersById = playersById,
                includeNone = true,
                noneLabel = tr("No assist"),
                onDismiss = { save(details.scorerId, null) },
                onSelect = { assistId -> save(details.scorerId, assistId) }
            )
        }
    }

    if (actionsSheetOpen) {
        MatchActionsSheet(
            teamName = teamName,
            opponentName = current.opponent,
            stats = MatchStatsRules.compute(events),
            onAction = ::startAction,
            onDismiss = { actionsSheetOpen = false }
        )
    }

    pendingPlayerAction?.let { action ->
        PlayerChoiceSheet(
            title = action.title,
            playerIds = action.playerIds,
            playersById = playersById,
            includeNone = action.allowNone,
            noneLabel = tr("Player not assigned"),
            onDismiss = { pendingPlayerAction = null },
            onSelect = { playerId ->
                pendingPlayerAction = null
                if (action.type == MatchActions.DISMISSAL) {
                    if (playerId != null) {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.DISMISSAL)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = tr("Red card • {player} sent off", "player" to (playersById[playerId]?.name ?: "player")),
                                actionLabel = tr("Undo"),
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) vm.undoLatestLineupChange(matchId)
                        }
                    }
                } else {
                    recordAction(action.type, playerId)
                }
            }
        )
    }

    pendingEventDeletion?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingEventDeletion = null },
            title = { Text(tr("Delete {type}?", "type" to ((MatchActions.label(event.type) ?: eventTitle(event, playersById)).lowercase()))) },
            text = { Text(tr("It is removed from the timeline and the match stats.")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEventById(event.id)
                    pendingEventDeletion = null
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingEventDeletion = null }) { Text(tr("Cancel")) } }
        )
    }

    if (keeperChoiceOpen) {
        PlayerChoiceSheet(
            title = tr("Who made the save?"),
            playerIds = onPitch.map { it.playerId },
            playersById = playersById,
            includeNone = true,
            noneLabel = tr("Keeper not assigned"),
            onDismiss = { keeperChoiceOpen = false },
            onSelect = {
                keeperChoiceOpen = false
                recordSave(it)
            }
        )
    }

    removalPlayerId?.let { playerId ->
        AlertDialog(
            onDismissRequest = { removalPlayerId = null },
            title = { Text(tr("Take {player} off?", "player" to (playersById[playerId]?.name ?: "player"))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.INJURY)
                        removalPlayerId = null
                    }) { Text(tr("Injury • cannot return")) }
                    TextButton(onClick = {
                        vm.removePlayerFromPitch(matchId, playerId, ParticipationReason.DISMISSAL)
                        removalPlayerId = null
                    }) { Text(tr("Dismissal • cannot return")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { removalPlayerId = null }) { Text(tr("Cancel")) } }
        )
    }

    if (showOpponentGoalConfirmation) {
        AlertDialog(
            onDismissRequest = { showOpponentGoalConfirmation = false },
            title = { Text(tr("Opponent goal?")) },
            text = { Text(tr("This adds a goal at the current match time.")) },
            confirmButton = {
                TextButton(
                    enabled = !liveActionLocked,
                    onClick = {
                        runLiveAction {
                            vm.recordOpponentGoal(matchId) { eventId ->
                                if (eventId != null) {
                                    goalPlacement = GoalPlacementRequest(
                                        eventId = eventId,
                                        title = tr("Where did {opponent} score?", "opponent" to current.opponent)
                                    )
                                }
                            }
                        }
                        showOpponentGoalConfirmation = false
                    }
                ) { Text(tr("Add goal")) }
            },
            dismissButton = {
                TextButton(onClick = { showOpponentGoalConfirmation = false }) { Text(tr("Cancel")) }
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

    if (showUndoRoundConfirmation) {
        AlertDialog(
            onDismissRequest = { showUndoRoundConfirmation = false },
            title = { Text(tr("Undo lineup change?")) },
            text = {
                Text(
                    tr("The lineup from before {time} ", "time" to (undoPlan?.let { MatchClockCalculator.formatClock(it.roundTimeMs) } ?: tr("the change"))) +
                        tr("returns and the minutes are counted as if the change never happened.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUndoRoundConfirmation = false
                    vm.undoLatestLineupChange(matchId) { undone ->
                        if (!undone) scope.launch { snackbarHostState.showSnackbar(tr("This change can no longer be undone")) }
                    }
                }) { Text(tr("Undo change")) }
            },
            dismissButton = { TextButton(onClick = { showUndoRoundConfirmation = false }) { Text(tr("Cancel")) } }
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

    if (showEndPeriodConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndPeriodConfirmation = false },
            title = { Text(tr("End period {currentPeriod}?", "currentPeriod" to current.currentPeriod)) },
            text = {
                Text(
                    if (recordingThisMatch && recordingStopMode.followsPeriods) {
                        tr("The match clock will stop and the video of this period is saved. ") +
                            tr("Recording starts again when you start the next period.")
                    } else tr("The match clock will stop. You can start the next period afterward.")
                )
            },
            confirmButton = {
                AppButton(
                    onClick = {
                        showEndPeriodConfirmation = false
                        runLiveAction { vm.endCurrentPeriod(matchId) }
                        scope.launch { snackbarHostState.showSnackbar(tr("Period {currentPeriod} ended", "currentPeriod" to current.currentPeriod)) }
                    }
                ) { Text(tr("End period")) }
            },
            dismissButton = {
                TextButton(onClick = { showEndPeriodConfirmation = false }) { Text(tr("Keep playing")) }
            }
        )
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            title = { Text(tr("Finish match?")) },
            text = {
                Text(
                    if (recordingThisMatch)
                        tr("The clock will stop, the camera recording will be stopped and saved, and the match will move to review.")
                    else tr("The clock will stop and the match will move to review.")
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
                }) { Text(tr("Finish match")) }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) { Text(tr("Cancel")) }
            }
        )
    }

    pendingGoalDeletion?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingGoalDeletion = null },
            title = {
                Text(if (event.type == "OPPONENT_GOAL") tr("Delete opponent goal?") else tr("Delete goal?"))
            },
            text = {
                Text(tr("The goal at {time} is removed from the timeline and the score.", "time" to (MatchClockCalculator.formatClock(event.timestampMs))))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteScoringEvent(event.id)
                    pendingGoalDeletion = null
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGoalDeletion = null }) { Text(tr("Cancel")) }
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
            title = { Text(tr("Leave live screen?")) },
            text = {
                Text(
                    if (current.clockRunning) tr("The match clock will continue.")
                    else tr("Your match state is saved.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirmation = false
                    nav.navigate("home") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }) { Text(tr("Leave screen")) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmation = false }) { Text(tr("Stay")) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingStatusChip(
    state: CameraRecordingState,
    matchId: Long,
    /** The recording was saved at the period end and starts again with the next period. */
    restartsWithPeriod: Boolean,
    stoppedInPlay: Boolean,
    nextPeriod: Int,
    onClick: () -> Unit
) {
    if (restartsWithPeriod && state !is CameraRecordingState.Error && state.activeMatchId == null) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    if (stoppedInPlay) tr("Time is up • camera records again when period {nextPeriod} starts", "nextPeriod" to nextPeriod)
                    else tr("Camera paused • records again when period {nextPeriod} starts", "nextPeriod" to nextPeriod),
                    maxLines = 1
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
        return
    }
    val relevant = state.activeMatchId == matchId || state is CameraRecordingState.Error
    if (!relevant) return

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val recording = state as? CameraRecordingState.Recording
    LaunchedEffect(recording?.segmentId) {
        while (recording != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val label = when (state) {
        is CameraRecordingState.Preparing -> tr("Camera preparing…")
        is CameraRecordingState.Recording -> {
            val elapsed = (now - state.startedAtElapsedRealtimeMs).coerceAtLeast(0L)
            tr("REC {time}", "time" to (MatchClockCalculator.formatClock(elapsed))) +
                if (state.audioEnabled) tr(" • audio") else tr(" • silent")
        }
        is CameraRecordingState.Finalizing -> tr("Saving recording…")
        is CameraRecordingState.Error -> tr("Recording error • tap to review")
        CameraRecordingState.Idle -> return
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (state is CameraRecordingState.Error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    )
}

@Composable
private fun PlayerChoiceSheet(
    title: String,
    playerIds: List<Long>,
    playersById: Map<Long, Player>,
    includeNone: Boolean = false,
    noneLabel: String = tr("None"),
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
    onDelete: () -> Unit,
    onEditPosition: () -> Unit
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
            val ours = event.type == "OUR_GOAL"
            Text(
                if (ours) tr("Edit goal") else tr("Edit opponent goal"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (ours) {
                AppOutlinedButton(onClick = { choosingScorer = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Scorer: {scorer}", "scorer" to (byId[scorer]?.name ?: tr("Unknown"))))
                }
                AppOutlinedButton(onClick = { choosingAssist = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Assist: {assist}", "assist" to (byId[assist]?.name ?: tr("None"))))
                }
            }
            AppOutlinedButton(onClick = onEditPosition, modifier = Modifier.fillMaxWidth()) {
                Text(
                    tr("Position in goal: ") +
                        (GoalMouthGeometry.describe(event.goalX, event.goalY) ?: tr("not set"))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(
                    onClick = { timeMs = (timeMs - 60_000L).coerceAtLeast(0L) },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("−1 min")) }
                Text(
                    MatchClockCalculator.formatClock(timeMs),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    fontWeight = FontWeight.Bold
                )
                AppOutlinedButton(
                    onClick = { timeMs = (timeMs + 60_000L).coerceAtMost(maxTimeMs) },
                    enabled = timeMs < maxTimeMs,
                    modifier = Modifier.weight(1f)
                ) { Text(tr("+1 min")) }
            }
            AppButton(
                onClick = { onSave(scorer, assist?.takeIf { it != scorer }, timeMs) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Save changes")) }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text(tr("Delete goal"), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (choosingScorer) {
        PlayerChoiceSheet(
            title = tr("Choose scorer"),
            playerIds = players.map { it.id },
            playersById = byId,
            includeNone = true,
            noneLabel = tr("Unknown scorer"),
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
            title = tr("Choose assist"),
            playerIds = players.map { it.id }.filterNot { it == scorer },
            playersById = byId,
            includeNone = true,
            noneLabel = tr("No assist"),
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
        title = { Text(tr("Correct score")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreStepper(tr("Our team"), ours) { ours = it }
                ScoreStepper(opponentName, opponents) { opponents = it }
                Text(
                    tr("Corrections add or remove timeline goal events so the score and timeline remain consistent."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ours, opponents) }) { Text(tr("Save score")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
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
    onEditGoalPosition: (MatchEvent) -> Unit,
    onDeleteGoal: (MatchEvent) -> Unit,
    onDeleteEvent: (MatchEvent) -> Unit,
    undoableRoundId: Long?,
    onUndoRound: () -> Unit
) {
    if (events.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(tr("No match events yet"))
        }
        return
    }
    val timelineItems = remember(events) { TimelineGrouping.group(events) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(timelineItems, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.LineupChange -> LineupChangeCard(
                    change = item,
                    playersById = playersById,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            ClipButton(item.events.first(), recordings, onPlayEvent)
                            if (item.events.first().id == undoableRoundId) {
                                TextButton(onClick = onUndoRound) { Text(tr("Undo")) }
                            }
                        }
                    }
                )
                is TimelineItem.Single -> TimelineEventCard(
                    event = item.event,
                    playersById = playersById,
                    recordings = recordings,
                    onPlayEvent = onPlayEvent,
                    onEditGoal = onEditGoal,
                    onEditGoalPosition = onEditGoalPosition,
                    onDeleteGoal = onDeleteGoal,
                    onDeleteEvent = onDeleteEvent
                )
            }
        }
    }
}

@Composable
private fun ClipButton(
    event: MatchEvent,
    recordings: List<RecordingSegment>,
    onPlayEvent: (MatchEvent) -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        if (be.matchreview.app.domain.VideoEventRules.isPlayable(event, recordings)) {
            AppTonalButton(
                onClick = { onPlayEvent(event) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text(tr("▶ Clip")) }
            Text(
                "+${MatchClockCalculator.formatClock(event.recordingOffsetMs ?: 0L)}",
                style = MaterialTheme.typography.labelSmall
            )
        } else if (event.recordingSegmentId != null) {
            Text(tr("Clip saving…"), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TimelineEventCard(
    event: MatchEvent,
    playersById: Map<Long, Player>,
    recordings: List<RecordingSegment>,
    onPlayEvent: (MatchEvent) -> Unit,
    onEditGoal: (MatchEvent) -> Unit,
    onEditGoalPosition: (MatchEvent) -> Unit,
    onDeleteGoal: (MatchEvent) -> Unit,
    onDeleteEvent: (MatchEvent) -> Unit
) {
    val scoring = event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL"
    AppCard(Modifier.fillMaxWidth()) {
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
                ClipButton(event, recordings, onPlayEvent)
                if (scoring) {
                    TextButton(onClick = { onEditGoal(event) }) { Text(tr("Edit")) }
                }
                if (scoring) {
                    TextButton(onClick = { onDeleteGoal(event) }) {
                        Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                    }
                } else if (event.type in MatchActions.DELETABLE) {
                    TextButton(onClick = { onDeleteEvent(event) }) {
                        Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun eventTitle(event: MatchEvent, playersById: Map<Long, Player>): String = when (event.type) {
    "OUR_GOAL" -> tr("Goal • {player}", "player" to (playersById[event.playerId]?.name ?: tr("Unknown scorer")))
    "OPPONENT_GOAL" -> tr("Opponent goal")
    "KEEPER_SAVE" -> tr("Save • {player}", "player" to (playersById[event.playerId]?.name ?: tr("Keeper")))
    MatchActions.OUR_SHOT_ON_TARGET, MatchActions.OUR_SHOT_OFF_TARGET, MatchActions.OUR_CORNER,
    MatchActions.YELLOW_CARD, MatchActions.RED_CARD ->
        "${MatchActions.label(event.type)} • ${playersById[event.playerId]?.name ?: "our team"}"
    MatchActions.OPPONENT_SHOT_ON_TARGET, MatchActions.OPPONENT_SHOT_OFF_TARGET, MatchActions.OPPONENT_CORNER,
    MatchActions.OPPONENT_YELLOW_CARD, MatchActions.OPPONENT_RED_CARD ->
        tr("{type} • opponent", "type" to (MatchActions.label(event.type)))
    "SUBSTITUTION" -> tr("Substitution • {player} off", "player" to (playersById[event.playerId]?.name ?: tr("Player")))
    "PLAYER_ON" -> tr("{player} entered", "player" to (playersById[event.playerId]?.name ?: tr("Player")))
    "DISMISSAL" -> tr("Dismissal • {player}", "player" to (playersById[event.playerId]?.name ?: tr("Player")))
    "INJURY_OFF" -> tr("Injury • {player} off", "player" to (playersById[event.playerId]?.name ?: tr("Player")))
    "PLAYER_OFF" -> tr("{player} off", "player" to (playersById[event.playerId]?.name ?: tr("Player")))
    "POSITION_CHANGE" -> tr("Positions changed")
    "KICK_OFF" -> tr("Kick-off")
    MatchActions.MARK -> tr("Marked moment")
    else -> event.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun eventSubtitle(event: MatchEvent, playersById: Map<Long, Player>): String? {
    val placement = GoalMouthGeometry.describe(event.goalX, event.goalY)?.let { tr("Goal position: {value}", "value" to it) }
    val detail = when {
        event.type == "OUR_GOAL" && event.relatedPlayerId != null ->
            tr("Assist: {relatedPlayer}", "relatedPlayer" to (playersById[event.relatedPlayerId]?.name ?: tr("Unknown")))
        event.type == "SUBSTITUTION" && event.relatedPlayerId != null ->
            tr("{relatedPlayer} on", "relatedPlayer" to (playersById[event.relatedPlayerId]?.name ?: tr("Player")))
        event.note.isNotBlank() -> event.note
        else -> null
    }
    return listOfNotNull(detail, placement).joinToString(" • ").ifBlank { null }
}

@Composable
private fun LiveScoreboard(
    match: GameMatch,
    teamName: String,
    matchTimeMs: Long,
    periodTimeMs: Long,
    plannedPeriodMs: Long
) {
    AppCard(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3263F)),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = LiveAnnouncementRules.scoreAnnouncement(
                    match.ourScore,
                    match.opponentScore
                )
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The home side is shown on the left, as on a stadium scoreboard.
            if (match.isHome) ScoreSide(teamName, match.ourScore)
            else ScoreSide(match.opponent, match.opponentScore)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    MatchClockCalculator.formatClock(matchTimeMs),
                    color = Color.White,
                    style = if (LocalLargeControls.current) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                val overtime = PeriodTimeRules.overtimeMs(periodTimeMs, plannedPeriodMs)
                Text(
                    when {
                        match.currentPeriod == 0 -> tr("Ready")
                        overtime > 0L -> tr("Period {currentPeriod}/{periodCount} • ", "currentPeriod" to match.currentPeriod, "periodCount" to match.periodCount) +
                            "${MatchClockCalculator.formatClock(plannedPeriodMs)} +${MatchClockCalculator.formatClock(overtime)}"
                        else -> tr("Period {currentPeriod}/{periodCount} • ", "currentPeriod" to match.currentPeriod, "periodCount" to match.periodCount) +
                            MatchClockCalculator.formatClock(periodTimeMs)
                    },
                    color = if (overtime > 0L) Color(0xFFFFE08A) else Color.White.copy(alpha = 0.9f),
                    fontWeight = if (overtime > 0L) FontWeight.Bold else null,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (match.isHome) ScoreSide(match.opponent, match.opponentScore)
            else ScoreSide(teamName, match.ourScore)
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
        modifier.clip(RoundedCornerShape(16.dp)).background(PitchColors.grass)
    ) {
        // A wide space gets the pitch on its side, our goal on the left.
        val horizontal = maxWidth > maxHeight
        PitchLines(Modifier.fillMaxSize(), horizontal = horizontal)
        placements.forEach { placement ->
            val player = playersById[placement.playerId] ?: return@forEach
            val marker = 72.dp
            val (shownX, shownY) = PitchOrientation.toDisplay(placement.normalizedX, placement.normalizedY, horizontal)
            // Name and minutes hang below the badge; keep the whole marker on the grass.
            val x = (maxWidth * shownX - marker / 2).coerceIn(0.dp, (maxWidth - marker).coerceAtLeast(0.dp))
            val y = (maxHeight * shownY - 21.dp).coerceIn(0.dp, (maxHeight - 62.dp).coerceAtLeast(0.dp))
            Column(
                Modifier.offset(x = x, y = y).width(marker).clickable { onPlayerClick(player.id) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFF5F7FA),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (player.shirtNumber > 0) player.shirtNumber.toString()
                            else player.name.take(2).uppercase(),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1C3D6E)
                        )
                    }
                }
                // Name and minutes on one line keeps the markers compact on a crowded pitch.
                Text(
                    "${player.name.substringBefore(" ")} ${MatchClockCalculator.displayedWholeMinutes(minutesFor(player.id))}'",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
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
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(78.dp).fillMaxHeight()
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
        color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outlineVariant
                    highlighted -> Color(0xFFFFC247)
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (highlighted) Color(0xFF1B1B1F) else contentColorFor(MaterialTheme.colorScheme.secondaryContainer),
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
    onSelected: (LiveTab) -> Unit,
    tabs: List<LiveTab> = LiveTab.entries
) {
    TabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        tabs.map { tab ->
            tab to when (tab) {
                LiveTab.MATCH -> tr("Match")
                LiveTab.CAMERA -> tr("Camera")
                LiveTab.TIMELINE -> tr("Timeline")
            }
        }.forEach { (tab, label) ->
            Tab(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                text = { Text(label, maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun LiveControlBar(
    match: GameMatch,
    actionEnabled: Boolean,
    onKickOff: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEndPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onFinish: () -> Unit
) {
    val buttonHeight = if (LocalLargeControls.current) 60.dp else 48.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (match.status) {
            MatchStatus.LINEUP_READY ->
                AppButton(
                    onClick = onKickOff,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight)
                ) { Text(tr("Kick off • Start period 1")) }
            MatchStatus.LIVE -> {
                AppButton(
                    onClick = onPause,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = buttonHeight)
                ) { Text(tr("Pause match")) }
                AppOutlinedButton(
                    onClick = onEndPeriod,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = buttonHeight)
                ) { Text(tr("End period")) }
            }
            MatchStatus.PAUSED -> {
                AppButton(
                    onClick = onResume,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = buttonHeight)
                ) { Text(tr("Resume match")) }
                AppOutlinedButton(
                    onClick = onEndPeriod,
                    enabled = actionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = buttonHeight)
                ) { Text(tr("End period")) }
            }
            MatchStatus.PERIOD_ENDED -> {
                if (match.currentPeriod < match.periodCount) {
                    AppButton(
                        onClick = onNextPeriod,
                        enabled = actionEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = buttonHeight)
                    ) { Text(tr("Start period {currentPeriod}", "currentPeriod" to (match.currentPeriod + 1))) }
                    AppOutlinedButton(
                        onClick = onFinish,
                        enabled = actionEnabled,
                        modifier = Modifier.heightIn(min = buttonHeight)
                    ) { Text(tr("Finish match")) }
                } else {
                    AppButton(
                        onClick = onFinish,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight)
                    ) { Text(tr("Finish match")) }
                }
            }
            MatchStatus.FINISHED ->
                Text(tr("Match finished"), modifier = Modifier.align(Alignment.CenterVertically))
            else -> Text(tr("Save the starting lineup first."))
        }
    }
}

private fun playerStateLabel(state: PlayerMatchState?): String = when (state) {
    PlayerMatchState.DISMISSED -> tr("Dismissed • cannot return")
    PlayerMatchState.REMOVED -> tr("Removed • cannot return")
    PlayerMatchState.UNAVAILABLE -> tr("Unavailable")
    else -> tr("Currently on the bench")
}

private fun statusLabel(match: GameMatch): String = when (match.status) {
    MatchStatus.LINEUP_READY -> tr("Ready for kick-off")
    MatchStatus.LIVE -> tr("Period {currentPeriod} live", "currentPeriod" to match.currentPeriod)
    MatchStatus.PAUSED -> tr("Period {currentPeriod} paused", "currentPeriod" to match.currentPeriod)
    MatchStatus.PERIOD_ENDED ->
        if (match.currentPeriod < match.periodCount) tr("Interval") else tr("All periods complete")
    MatchStatus.FINISHED -> tr("Finished")
    else -> match.status.name.lowercase().replaceFirstChar { it.uppercase() }
}

private data class QuickGoalDetails(
    val eventId: Long,
    val scorerChosen: Boolean = false,
    val scorerId: Long? = null
)

/** An action waiting for the coach to pick the player involved. */
private data class PendingPlayerAction(
    val type: String,
    val title: String,
    val playerIds: List<Long>,
    val allowNone: Boolean
)

/** Shots, corners and cards for both teams, with the running totals. */
@Composable
private fun MatchActionsSheet(
    teamName: String,
    opponentName: String,
    stats: MatchStats,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(teamName, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Text(opponentName, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val rows = listOf(
                Triple(tr("Shot on target"), MatchActions.OUR_SHOT_ON_TARGET, MatchActions.OPPONENT_SHOT_ON_TARGET) to
                    (stats.ours.shotsOnTarget to stats.opponent.shotsOnTarget),
                Triple(tr("Shot off target"), MatchActions.OUR_SHOT_OFF_TARGET, MatchActions.OPPONENT_SHOT_OFF_TARGET) to
                    (stats.ours.shotsOffTarget to stats.opponent.shotsOffTarget),
                Triple(tr("Corner"), MatchActions.OUR_CORNER, MatchActions.OPPONENT_CORNER) to
                    (stats.ours.corners to stats.opponent.corners),
                Triple(tr("Yellow card"), MatchActions.YELLOW_CARD, MatchActions.OPPONENT_YELLOW_CARD) to
                    (stats.ours.yellowCards to stats.opponent.yellowCards),
                Triple(tr("Red card"), MatchActions.DISMISSAL, MatchActions.OPPONENT_RED_CARD) to
                    (stats.ours.redCards to stats.opponent.redCards)
            )
            rows.forEach { (action, totals) ->
                val (label, ourType, opponentType) = action
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(ourType to totals.first, opponentType to totals.second).forEach { (type, total) ->
                        val card = type == MatchActions.YELLOW_CARD || type == MatchActions.OPPONENT_YELLOW_CARD
                        val red = type == MatchActions.DISMISSAL || type == MatchActions.OPPONENT_RED_CARD
                        AppTonalButton(
                            onClick = { onAction(type) },
                            colors = when {
                                card -> ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFFFE082), contentColor = Color(0xFF1B1B1F)
                                )
                                red -> ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFEF9A9A), contentColor = Color(0xFF1B1B1F)
                                )
                                else -> ButtonDefaults.filledTonalButtonColors()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$label ($total)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text(
                tr("Goals count as shots on target; saves count as {opponentName} shots on target.", "opponentName" to opponentName),
                style = MaterialTheme.typography.bodySmall
            )
            AppOutlinedButton(
                onClick = { onAction(MatchActions.MARK) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Mark this moment in the video")) }
        }
    }
}
