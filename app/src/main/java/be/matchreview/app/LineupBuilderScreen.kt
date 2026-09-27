package be.matchreview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.Player
import be.matchreview.app.domain.FormationLayout
import be.matchreview.app.domain.FormationSlot
import be.matchreview.app.domain.LineupDragDropRules
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class LineupDragState(
    val playerId: Long,
    val fromPitch: Boolean,
    val pointerInRoot: Offset
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineupBuilderScreen(
    matchId: Long,
    vm: MainViewModel,
    nav: NavHostController
) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val allPlayers by vm.players.collectAsStateWithLifecycle()
    val squad by vm.squad(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val placements by vm.lineup(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val current = match

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(matchId) { vm.ensureLineup(matchId) }

    val selectedIds = remember(squad) {
        squad.filter { it.selected }.mapTo(mutableSetOf()) { it.playerId }
    }
    val selectedPlayers = remember(allPlayers, selectedIds) {
        allPlayers.filter { it.id in selectedIds }
            .sortedWith(compareBy<Player>({ it.shirtNumber <= 0 }, { it.shirtNumber }, { it.name }))
    }
    val playersById = remember(selectedPlayers) { selectedPlayers.associateBy { it.id } }
    val activePlacements = remember(placements, selectedIds) {
        placements.filter { it.playerId in selectedIds }
    }
    val placementByPlayer = remember(activePlacements) { activePlacements.associateBy { it.playerId } }
    val onPitch = activePlacements.filter { it.onPitch }
    val bench = selectedPlayers.filter { placementByPlayer[it.id]?.onPitch != true }
    val slots = remember(current.formation, current.playersOnPitch) {
        FormationLayout.slots(current.formation, current.playersOnPitch)
    }

    var selectedPlayerId by remember { mutableStateOf<Long?>(null) }
    var showUnderfilledConfirmation by remember { mutableStateOf(false) }
    var dragState by remember { mutableStateOf<LineupDragState?>(null) }
    var pitchBounds by remember { mutableStateOf<Rect?>(null) }
    var benchBounds by remember { mutableStateOf<Rect?>(null) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun finishDrag(playerId: Long, pointer: Offset) {
        val existing = placementByPlayer[playerId]
            ?: MatchLineupPlacement(matchId = matchId, playerId = playerId)
        val pitch = pitchBounds
        val benchArea = benchBounds
        when {
            pitch != null && pitch.contains(pointer) -> {
                if (!existing.onPitch && onPitch.size >= current.playersOnPitch) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        snackbar.showSnackbar("The pitch is full. Move a player to the bench first.")
                    }
                } else {
                    val (rawX, rawY) = LineupDragDropRules.normalize(
                        pointer.x, pointer.y,
                        pitch.left, pitch.top, pitch.width, pitch.height
                    )
                    val occupied = onPitch
                        .filterNot { it.playerId == playerId }
                        .mapNotNullTo(mutableSetOf()) {
                            it.formationSlot.takeIf(String::isNotBlank)
                        }
                    val drop = LineupDragDropRules.resolvePitchDrop(
                        rawX, rawY, slots, occupied
                    )
                    vm.setLineupPlacement(
                        existing.copy(
                            normalizedX = drop.normalizedX,
                            normalizedY = drop.normalizedY,
                            formationSlot = drop.formationSlot,
                            role = drop.role.ifBlank { existing.role },
                            onPitch = true
                        )
                    )
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            benchArea != null && benchArea.contains(pointer) -> {
                if (existing.onPitch) {
                    vm.setLineupPlacement(
                        existing.copy(onPitch = false, formationSlot = "", role = "")
                    )
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { snackbar.showSnackbar("Player moved to the bench") }
                }
            }
            else -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { snackbar.showSnackbar("Drop cancelled") }
            }
        }
        dragState = null
    }

    val dragPointer = dragState?.pointerInRoot
    val pitchIsDropTarget = dragPointer?.let { pitchBounds?.contains(it) } == true
    val benchIsDropTarget = dragPointer?.let { benchBounds?.contains(it) } == true

    val hoveredSlotId = remember(dragState, pitchBounds, onPitch, slots) {
        val drag = dragState ?: return@remember null
        val pitch = pitchBounds ?: return@remember null
        if (!pitch.contains(drag.pointerInRoot)) return@remember null
        val (x, y) = LineupDragDropRules.normalize(
            drag.pointerInRoot.x, drag.pointerInRoot.y,
            pitch.left, pitch.top, pitch.width, pitch.height
        )
        val occupied = onPitch
            .filterNot { it.playerId == drag.playerId }
            .mapNotNullTo(mutableSetOf()) { it.formationSlot.takeIf(String::isNotBlank) }
        LineupDragDropRules.resolvePitchDrop(x, y, slots, occupied)
            .takeIf { it.snapped }
            ?.formationSlot
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LineupHeader(current, onPitch.size, selectedPlayers.size)

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { editorOrigin = it.boundsInRoot().topLeft }
            ) {
                Column(Modifier.fillMaxSize()) {
                    PitchView(
                        slots = slots,
                        placements = onPitch,
                        playersById = playersById,
                        draggingPlayerId = dragState?.playerId,
                        highlightedSlotId = hoveredSlotId,
                        isDropTarget = pitchIsDropTarget,
                        onBoundsChanged = { pitchBounds = it },
                        onPlayerClick = { selectedPlayerId = it },
                        onNudge = { playerId, dx, dy ->
                            placementByPlayer[playerId]?.let { existing ->
                                vm.setLineupPlacement(
                                    existing.copy(
                                        normalizedX = (existing.normalizedX + dx).coerceIn(0.08f, 0.92f),
                                        normalizedY = (existing.normalizedY + dy).coerceIn(0.08f, 0.92f),
                                        formationSlot = ""
                                    )
                                )
                            }
                        },
                        onDragStart = { playerId, pointer ->
                            dragState = LineupDragState(playerId, true, pointer)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragMove = { pointer ->
                            dragState = dragState?.copy(pointerInRoot = pointer)
                        },
                        onDragEnd = ::finishDrag,
                        onDragCancel = { dragState = null },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    BenchPanel(
                        players = bench,
                        draggingPlayerId = dragState?.playerId,
                        isDropTarget = benchIsDropTarget,
                        onBoundsChanged = { benchBounds = it },
                        onPlayerClick = { selectedPlayerId = it },
                        onDragStart = { playerId, pointer ->
                            dragState = LineupDragState(playerId, false, pointer)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragMove = { pointer ->
                            dragState = dragState?.copy(pointerInRoot = pointer)
                        },
                        onDragEnd = ::finishDrag,
                        onDragCancel = { dragState = null }
                    )
                }

                dragState?.let { drag ->
                    playersById[drag.playerId]?.let { player ->
                        PlayerDragGhost(
                            player = player,
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (drag.pointerInRoot.x - editorOrigin.x - 29.dp.toPx()).roundToInt(),
                                        (drag.pointerInRoot.y - editorOrigin.y - 29.dp.toPx()).roundToInt()
                                    )
                                }
                                .zIndex(20f)
                        )
                    }
                }
            }

            Surface(shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { vm.autoPlaceLineup(matchId, selectedPlayers, slots) },
                            enabled = selectedPlayers.isNotEmpty() && dragState == null,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.weight(0.8f).heightIn(min = 40.dp)
                        ) { Text("Auto", maxLines = 1) }
                        OutlinedButton(
                            onClick = { nav.navigate("squad/$matchId") },
                            enabled = dragState == null,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.weight(0.9f).heightIn(min = 40.dp)
                        ) { Text("Players", maxLines = 1) }
                        Button(
                            enabled = onPitch.isNotEmpty() && dragState == null,
                            onClick = {
                                if (onPitch.size < current.playersOnPitch) {
                                    showUnderfilledConfirmation = true
                                } else {
                                    vm.markLineupReady(matchId) {
                                        nav.navigate("live/$matchId") {
                                            popUpTo("lineup/$matchId") { inclusive = true }
                                        }
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1.35f).heightIn(min = 40.dp)
                        ) { Text("Match day", maxLines = 1) }
                    }
                    Text(
                        if (onPitch.size == current.playersOnPitch) {
                            "Lineup complete • drag players to reposition"
                        } else {
                            "${onPitch.size}/${current.playersOnPitch} on pitch • drag a player from the bench onto the pitch"
                        },
                        color = if (onPitch.size == current.playersOnPitch) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }

    val selectedPlayer = playersById[selectedPlayerId]
    if (selectedPlayer != null && dragState == null) {
        val existing = placementByPlayer[selectedPlayer.id]
            ?: MatchLineupPlacement(matchId = matchId, playerId = selectedPlayer.id)
        PlayerPlacementSheet(
            player = selectedPlayer,
            placement = existing,
            pitchFull = onPitch.size >= current.playersOnPitch && !existing.onPitch,
            slots = slots,
            occupiedPlacements = onPitch.filterNot { it.playerId == selectedPlayer.id },
            onDismiss = { selectedPlayerId = null },
            onMoveToPitch = { slot ->
                if (onPitch.size >= current.playersOnPitch && !existing.onPitch) {
                    scope.launch { snackbar.showSnackbar("The pitch is full. Move a player to the bench first.") }
                } else {
                    vm.setLineupPlacement(
                        existing.copy(
                            normalizedX = slot.normalizedX,
                            normalizedY = slot.normalizedY,
                            role = slot.label,
                            formationSlot = slot.id,
                            onPitch = true
                        )
                    )
                    selectedPlayerId = null
                }
            },
            onMoveToBench = {
                vm.setLineupPlacement(existing.copy(onPitch = false, formationSlot = "", role = ""))
                selectedPlayerId = null
            },
            onNudge = { dx, dy ->
                vm.setLineupPlacement(
                    existing.copy(
                        normalizedX = (existing.normalizedX + dx).coerceIn(0.08f, 0.92f),
                        normalizedY = (existing.normalizedY + dy).coerceIn(0.08f, 0.92f),
                        formationSlot = "",
                        onPitch = true
                    )
                )
            }
        )
    }

    if (showUnderfilledConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnderfilledConfirmation = false },
            title = { Text("Save a smaller starting lineup?") },
            text = {
                Text(
                    "The match is configured for ${current.playersOnPitch} players, but ${onPitch.size} are currently on the pitch."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnderfilledConfirmation = false
                    vm.markLineupReady(matchId) {
                        nav.navigate("live/$matchId") {
                            popUpTo("lineup/$matchId") { inclusive = true }
                        }
                    }
                }) { Text("Save anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showUnderfilledConfirmation = false }) {
                    Text("Keep editing")
                }
            }
        )
    }
}

@Composable
private fun LineupHeader(match: GameMatch, onPitch: Int, selected: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Starting lineup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("vs ${match.opponent} • ${match.formation}", style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    "$onPitch / ${match.playersOnPitch} on pitch\n$selected selected",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun PitchView(
    slots: List<FormationSlot>,
    placements: List<MatchLineupPlacement>,
    playersById: Map<Long, Player>,
    draggingPlayerId: Long?,
    highlightedSlotId: String?,
    isDropTarget: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onPlayerClick: (Long) -> Unit,
    onNudge: (Long, Float, Float) -> Unit,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .testTag(LineupTestTags.PITCH)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isDropTarget) {
                    Brush.verticalGradient(listOf(Color(0xFF8BCB56), Color(0xFF69B442), Color(0xFF55A23A)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFF79B94A), Color(0xFF5FA63B), Color(0xFF4A9134)))
                }
            )
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val markerPx = with(density) { 54.dp.toPx() }
        val slotRadiusPx = with(density) { 13.dp.toPx() }

        PitchLines(Modifier.fillMaxSize())

        slots.forEach { slot ->
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (slot.normalizedX * widthPx - slotRadiusPx).roundToInt(),
                            (slot.normalizedY * heightPx - slotRadiusPx).roundToInt()
                        )
                    }
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (slot.id == highlightedSlotId) Color(0xFFFFD54F).copy(alpha = 0.9f)
                        else Color.White.copy(alpha = 0.18f)
                    )
            )
        }

        placements.forEach { placement ->
            val player = playersById[placement.playerId] ?: return@forEach
            PlayerMarker(
                player = player,
                role = placement.role,
                dragging = draggingPlayerId == player.id,
                onClick = { onPlayerClick(player.id) },
                onNudge = { dx, dy -> onNudge(player.id, dx, dy) },
                onDragStart = { pointer -> onDragStart(player.id, pointer) },
                onDragMove = onDragMove,
                onDragEnd = { pointer -> onDragEnd(player.id, pointer) },
                onDragCancel = onDragCancel,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (placement.normalizedX * widthPx - markerPx / 2).roundToInt(),
                            (placement.normalizedY * heightPx - markerPx / 2).roundToInt()
                        )
                    }
                    .testTag(LineupTestTags.pitchPlayer(player.id))
            )
        }

        if (isDropTarget) {
            Text(
                if (highlightedSlotId != null) "Release to snap into position" else "Release to place on pitch",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun PitchLines(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val line = Color.White.copy(alpha = 0.72f)
        val stroke = 3f
        drawRect(line, style = Stroke(stroke))
        drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
        drawCircle(line, radius = size.width * 0.13f, center = center, style = Stroke(stroke))
        drawCircle(line, radius = 5f, center = center)

        val boxWidth = size.width * 0.58f
        val boxHeight = size.height * 0.16f
        drawRect(
            line,
            topLeft = Offset((size.width - boxWidth) / 2, 0f),
            size = androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
            style = Stroke(stroke)
        )
        drawRect(
            line,
            topLeft = Offset((size.width - boxWidth) / 2, size.height - boxHeight),
            size = androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
            style = Stroke(stroke)
        )
        val goalWidth = size.width * 0.28f
        val goalDepth = size.height * 0.025f
        drawRect(
            line,
            topLeft = Offset((size.width - goalWidth) / 2, 0f),
            size = androidx.compose.ui.geometry.Size(goalWidth, goalDepth),
            style = Stroke(stroke)
        )
        drawRect(
            line,
            topLeft = Offset((size.width - goalWidth) / 2, size.height - goalDepth),
            size = androidx.compose.ui.geometry.Size(goalWidth, goalDepth),
            style = Stroke(stroke)
        )
    }
}

@Composable
private fun PlayerMarker(
    player: Player,
    role: String,
    dragging: Boolean,
    onClick: () -> Unit,
    onNudge: (Float, Float) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(54.dp)
            .alpha(if (dragging) 0.18f else 1f)
            .longPressPlayerDrag(
                playerId = player.id,
                onStart = onDragStart,
                onMove = onDragMove,
                onEnd = onDragEnd,
                onCancel = onDragCancel
            )
            .semantics {
                contentDescription =
                    "${player.name}, number ${player.shirtNumber}, ${role.ifBlank { "on pitch" }}. Drag to move."
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { onNudge(-0.05f, 0f); true },
                    CustomAccessibilityAction("Move right") { onNudge(0.05f, 0f); true },
                    CustomAccessibilityAction("Move forward") { onNudge(0f, -0.05f); true },
                    CustomAccessibilityAction("Move back") { onNudge(0f, 0.05f); true }
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerBadge(player)
        Text(
            player.name.substringBefore(" ").uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerBadge(player: Player, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(46.dp)
            .shadow(5.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFFF5F7FA)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (player.shirtNumber > 0) player.shirtNumber.toString()
            else player.name.take(1).uppercase(),
            color = Color(0xFF1C3D6E),
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun PlayerDragGhost(player: Player, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerBadge(player, Modifier.size(52.dp))
        Text(
            player.name.substringBefore(" ").uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(7.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun BenchPanel(
    players: List<Player>,
    draggingPlayerId: Long?,
    isDropTarget: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onPlayerClick: (Long) -> Unit,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: () -> Unit
) {
    Surface(
        color = if (isDropTarget) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .testTag(LineupTestTags.BENCH)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(
                if (isDropTarget) "RELEASE TO MOVE TO BENCH" else "BENCH • ${players.size}",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (players.isEmpty()) {
                Text(
                    if (draggingPlayerId != null) "Drop the player here." else "All selected players are on the pitch.",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(players, key = { it.id }) { player ->
                        Column(
                            Modifier
                                .testTag(LineupTestTags.benchPlayer(player.id))
                                .width(54.dp)
                                .alpha(if (draggingPlayerId == player.id) 0.18f else 1f)
                                .longPressPlayerDrag(
                                    playerId = player.id,
                                    onStart = { onDragStart(player.id, it) },
                                    onMove = onDragMove,
                                    onEnd = { onDragEnd(player.id, it) },
                                    onCancel = onDragCancel
                                )
                                .semantics {
                                    contentDescription =
                                        "${player.name}, substitute. Tap for placement options or drag onto the pitch."
                                    customActions = listOf(
                                        CustomAccessibilityAction("Open placement options") {
                                            onPlayerClick(player.id)
                                            true
                                        }
                                    )
                                }
                                .clickable { onPlayerClick(player.id) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (player.shirtNumber > 0) player.shirtNumber.toString()
                                    else player.name.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                player.name.substringBefore(" "),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Long-press, then drag a player. Reports the pointer in root coordinates.
 *
 * The gesture detector is keyed only on the player: callbacks are read through
 * [rememberUpdatedState], so recompositions caused by the drag itself (the lifted
 * ghost, the faded source marker, drop-target highlights) never restart the
 * detector and cancel the gesture half-way.
 */
private fun Modifier.longPressPlayerDrag(
    playerId: Long,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (Offset) -> Unit,
    onCancel: () -> Unit
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnCancel by rememberUpdatedState(onCancel)

    fun toRoot(local: Offset): Offset =
        coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local

    this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(playerId) {
            var pointerInRoot = Offset.Zero
            var dragging = false
            try {
                detectDragGesturesAfterLongPress(
                    onDragStart = { localPointer ->
                        dragging = true
                        pointerInRoot = toRoot(localPointer)
                        currentOnStart(pointerInRoot)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // Use the pointer's absolute position rather than summed deltas so
                        // the drop point stays exact even if the source item moves.
                        pointerInRoot = toRoot(change.position)
                        currentOnMove(pointerInRoot)
                    },
                    onDragEnd = {
                        dragging = false
                        currentOnEnd(pointerInRoot)
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnCancel()
                    }
                )
            } catch (cancelled: CancellationException) {
                // The item left composition mid-drag; never leave the screen stuck in drag mode.
                if (dragging) currentOnCancel()
                throw cancelled
            }
        }
}

internal object LineupTestTags {
    const val PITCH = "lineup-pitch"
    const val BENCH = "lineup-bench"
    fun pitchPlayer(playerId: Long) = "lineup-pitch-player-$playerId"
    fun benchPlayer(playerId: Long) = "lineup-bench-player-$playerId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPlacementSheet(
    player: Player,
    placement: MatchLineupPlacement,
    pitchFull: Boolean,
    slots: List<FormationSlot>,
    occupiedPlacements: List<MatchLineupPlacement>,
    onDismiss: () -> Unit,
    onMoveToPitch: (FormationSlot) -> Unit,
    onMoveToBench: () -> Unit,
    onNudge: (Float, Float) -> Unit
) {
    val occupiedSlotIds = occupiedPlacements.mapNotNullTo(mutableSetOf()) {
        it.formationSlot.takeIf(String::isNotBlank)
    }
    val nearestAvailable = slots.firstOrNull { it.id !in occupiedSlotIds } ?: slots.first()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(player.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(
                    player.shirtNumber.takeIf { it > 0 }?.let { "#$it" },
                    player.position.takeIf { it.isNotBlank() },
                    placement.role.takeIf { placement.onPitch && it.isNotBlank() }
                ).joinToString(" • ")
            )

            if (!placement.onPitch) {
                Button(
                    onClick = { onMoveToPitch(nearestAvailable) },
                    enabled = !pitchFull,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (pitchFull) "Pitch is full" else "Place in next free formation slot") }
            } else {
                Text("Fine position", style = MaterialTheme.typography.titleMedium)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { onNudge(0f, -0.05f) }) { Text("↑ Forward") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onNudge(-0.05f, 0f) }) { Text("← Left") }
                        FilledTonalButton(onClick = { onNudge(0.05f, 0f) }) { Text("Right →") }
                    }
                    FilledTonalButton(onClick = { onNudge(0f, 0.05f) }) { Text("↓ Back") }
                }
                OutlinedButton(onClick = onMoveToBench, modifier = Modifier.fillMaxWidth()) {
                    Text("Move to bench")
                }
            }

            Text("Formation slots", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("GK", "DEF", "MID", "FWD").forEach { role ->
                    val slot = slots.firstOrNull { it.label == role && it.id !in occupiedSlotIds }
                    AssistChip(
                        onClick = { if (slot != null) onMoveToPitch(slot) },
                        enabled = slot != null && (!pitchFull || placement.onPitch),
                        label = { Text(role) }
                    )
                }
            }
        }
    }
}
