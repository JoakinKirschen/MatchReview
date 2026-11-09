package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import be.matchreview.app.ui.isLandscape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.text.style.TextAlign
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
import be.matchreview.app.domain.PitchOrientation
import be.matchreview.app.domain.SubstitutionDrop
import be.matchreview.app.domain.SubstitutionPlanRules
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
    var showAutoPlaceConfirmation by remember { mutableStateOf(false) }
    var dragState by remember { mutableStateOf<LineupDragState?>(null) }
    var pitchBounds by remember { mutableStateOf<Rect?>(null) }
    var benchBounds by remember { mutableStateOf<Rect?>(null) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    // A drop can be followed by a click on the same item; it must not open the player sheet.
    var lastDropAt by remember { mutableLongStateOf(0L) }
    fun openPlayer(playerId: Long) {
        if (android.os.SystemClock.uptimeMillis() - lastDropAt > 400L) selectedPlayerId = playerId
    }

    val haptics = LocalHapticFeedback.current
    val playerHitRadiusPx = with(LocalDensity.current) { 40.dp.toPx() }
    val landscape = isLandscape()

    /**
     * Where [playerId] lands if released at [pointer], using the same rules as substitution
     * mode: on another player swaps them, a full pitch swaps with the nearest player, and
     * otherwise the player snaps to a free slot nearby. Null when not over the pitch.
     */
    fun dropAt(playerId: Long, pointer: Offset): SubstitutionDrop? {
        val pitch = pitchBounds ?: return null
        if (!pitch.contains(pointer)) return null
        val (x, y) = PitchOrientation.normalizePointer(
            pointer.x, pointer.y, pitch.left, pitch.top, pitch.width, pitch.height, landscape
        )
        val (acrossPx, alongPx) = PitchOrientation.lineupAxes(pitch.width, pitch.height, landscape)
        val existing = placementByPlayer[playerId]
            ?: MatchLineupPlacement(matchId = matchId, playerId = playerId)
        return SubstitutionPlanRules.resolveDrop(
            placementByPlayer + (playerId to existing), playerId, x, y, slots,
            current.playersOnPitch, acrossPx, alongPx, playerHitRadiusPx
        )
    }

    // Moves are confirmed by the lineup itself and a haptic tick. No snackbars: they cover
    // the bench and block the next long-press for several seconds.
    fun finishDrag(playerId: Long, pointer: Offset) {
        lastDropAt = android.os.SystemClock.uptimeMillis()
        dragState = null
        val existing = placementByPlayer[playerId]
            ?: MatchLineupPlacement(matchId = matchId, playerId = playerId)
        val benchArea = benchBounds
        when (val drop = dropAt(playerId, pointer)) {
            is SubstitutionDrop.Swap -> placementByPlayer[drop.targetPlayerId]?.let { target ->
                vm.swapLineupPlacements(existing, target)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            is SubstitutionDrop.Place -> {
                vm.setLineupPlacement(
                    existing.copy(
                        normalizedX = drop.drop.normalizedX,
                        normalizedY = drop.drop.normalizedY,
                        formationSlot = drop.drop.formationSlot,
                        role = drop.drop.role.ifBlank { existing.role },
                        onPitch = true
                    )
                )
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            null -> if (benchArea != null && benchArea.contains(pointer) && existing.onPitch) {
                vm.setLineupPlacement(existing.copy(onPitch = false, formationSlot = "", role = ""))
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val dragPointer = dragState?.pointerInRoot
    val pitchIsDropTarget = dragPointer?.let { pitchBounds?.contains(it) } == true
    val benchIsDropTarget = dragPointer?.let { benchBounds?.contains(it) } == true
    val dropPreview = dragState?.let { dropAt(it.playerId, it.pointerInRoot) }
    val hoveredSlotId = (dropPreview as? SubstitutionDrop.Place)?.drop?.takeIf { it.snapped }?.formationSlot
    val swapTargetId = (dropPreview as? SubstitutionDrop.Swap)?.targetPlayerId

    val pitchView: @Composable (Modifier) -> Unit = { modifier ->
        PitchView(
            slots = slots,
            placements = onPitch,
            playersById = playersById,
            draggingPlayerId = dragState?.playerId,
            highlightedSlotId = hoveredSlotId,
            isDropTarget = pitchIsDropTarget,
            onBoundsChanged = { pitchBounds = it },
            onPlayerClick = ::openPlayer,
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
            highlightedPlayerId = swapTargetId,
            dropHint = swapTargetId?.let { tr("Swap with {it}", "it" to (playersById[it]?.name ?: "player")) },
            horizontal = landscape,
            modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
    val benchPanel: @Composable (Modifier) -> Unit = { modifier ->
        BenchPanel(
            players = bench,
            draggingPlayerId = dragState?.playerId,
            isDropTarget = benchIsDropTarget,
            onBoundsChanged = { benchBounds = it },
            onPlayerClick = ::openPlayer,
            onDragStart = { playerId, pointer ->
                dragState = LineupDragState(playerId, false, pointer)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragMove = { pointer ->
                dragState = dragState?.copy(pointerInRoot = pointer)
            },
            onDragEnd = ::finishDrag,
            onDragCancel = { dragState = null },
            vertical = landscape,
            modifier = modifier
        )
    }
    val summary: @Composable () -> Unit = {
        Text(
            tr("{onPitchCount}/{playersOnPitch} on pitch  •  vs {opponent}  •  {formation}", "onPitchCount" to onPitch.size, "playersOnPitch" to current.playersOnPitch, "opponent" to current.opponent, "formation" to current.formation),
            color = if (onPitch.size == current.playersOnPitch) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    val autoPlaceButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = { showAutoPlaceConfirmation = true },
            enabled = selectedPlayers.isNotEmpty() && dragState == null,
            modifier = modifier
        ) { Text(tr("Auto-place"), maxLines = 1) }
    }
    val squadButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = { nav.navigate("squad/$matchId") },
            enabled = dragState == null,
            modifier = modifier
        ) { Text(tr("Squad"), maxLines = 1) }
    }
    val matchDayButton: @Composable (Modifier) -> Unit = { modifier ->
        AppButton(
            enabled = onPitch.isNotEmpty() && dragState == null,
            onClick = {
                if (onPitch.size < current.playersOnPitch) {
                    showUnderfilledConfirmation = true
                } else {
                    vm.markLineupReady(matchId) {
                        nav.navigate("ready/$matchId") {
                            popUpTo("lineup/$matchId") { inclusive = true }
                        }
                    }
                }
            },
            modifier = modifier
        ) { Text(tr("Match day"), maxLines = 1) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { editorOrigin = it.boundsInRoot().topLeft }
    ) {
        if (landscape) {
            // Held sideways: the pitch lies on its side and the bench and buttons sit beside it.
            Row(Modifier.fillMaxSize()) {
                pitchView(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(212.dp).fillMaxHeight().padding(end = 6.dp, top = 2.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    benchPanel(Modifier.weight(1f).fillMaxWidth())
                    summary()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        autoPlaceButton(Modifier.weight(1f))
                        squadButton(Modifier.weight(1f))
                    }
                    matchDayButton(Modifier.fillMaxWidth())
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            pitchView(Modifier.weight(1f).fillMaxWidth())
            benchPanel(Modifier)
            Surface(shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    summary()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        autoPlaceButton(Modifier.weight(1f))
                        squadButton(Modifier.weight(1f))
                        matchDayButton(Modifier.weight(1.2f))
                    }
                }
            }
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
            playersById = playersById,
            onDismiss = { selectedPlayerId = null },
            onMoveToPitch = { slot ->
                if (onPitch.size < current.playersOnPitch || existing.onPitch) {
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
            onSwapWith = { occupied ->
                vm.swapLineupPlacements(existing, occupied)
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

    if (showAutoPlaceConfirmation) {
        AlertDialog(
            onDismissRequest = { showAutoPlaceConfirmation = false },
            title = { Text(tr("Auto-place lineup?")) },
            text = {
                Text(
                    if (onPitch.isEmpty()) {
                        tr("Players will be placed into the available {formation} formation slots.", "formation" to current.formation)
                    } else {
                        tr("This will replace the current manual placement with an automatic {formation} lineup.", "formation" to current.formation)
                    }
                )
            },
            confirmButton = {
                AppButton(
                    onClick = {
                        showAutoPlaceConfirmation = false
                        vm.autoPlaceLineup(matchId, selectedPlayers, slots)
                    }
                ) { Text(tr("Auto-place")) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoPlaceConfirmation = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showUnderfilledConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnderfilledConfirmation = false },
            title = { Text(tr("Save a smaller starting lineup?")) },
            text = {
                Text(
                    tr("The match is configured for {playersOnPitch} players, but {onPitchCount} are currently on the pitch.", "playersOnPitch" to current.playersOnPitch, "onPitchCount" to onPitch.size)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnderfilledConfirmation = false
                    vm.markLineupReady(matchId) {
                        nav.navigate("ready/$matchId") {
                            popUpTo("lineup/$matchId") { inclusive = true }
                        }
                    }
                }) { Text(tr("Save anyway")) }
            },
            dismissButton = {
                TextButton(onClick = { showUnderfilledConfirmation = false }) {
                    Text(tr("Keep editing"))
                }
            }
        )
    }
}

@Composable
internal fun PitchView(
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
    modifier: Modifier = Modifier,
    subtitleFor: (Long) -> String? = { null },
    highlightedPlayerId: Long? = null,
    dropHint: String? = null,
    /** Lays the pitch on its side, our goal on the left, to fill a landscape screen. */
    horizontal: Boolean = false
) {
    BoxWithConstraints(
        modifier = modifier
            .testTag(LineupTestTags.PITCH)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDropTarget) PitchColors.dropTarget else PitchColors.grass)
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val markerPx = with(density) { 54.dp.toPx() }
        val slotRadiusPx = with(density) { 13.dp.toPx() }

        PitchLines(Modifier.fillMaxSize(), horizontal = horizontal)

        slots.forEach { slot ->
            val (slotX, slotY) = PitchOrientation.toDisplay(slot.normalizedX, slot.normalizedY, horizontal)
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (slotX * widthPx - slotRadiusPx).roundToInt(),
                            (slotY * heightPx - slotRadiusPx).roundToInt()
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
            // Keyed by player, not list position: when another player leaves the pitch the
            // list shifts, and a positional slot would hand this marker (and its running drag
            // gesture) to a different player, cancelling the drag mid-way.
            key(player.id) {
                // Name and minutes hang below the badge; keep the whole marker on the grass.
                val markerHeightPx = with(density) {
                    (if (subtitleFor(player.id) != null) 84.dp else 68.dp).toPx()
                }
                val (shownX, shownY) = PitchOrientation.toDisplay(
                    placement.normalizedX, placement.normalizedY, horizontal
                )
                PlayerMarker(
                    player = player,
                    role = placement.role,
                    dragging = draggingPlayerId == player.id,
                    subtitle = subtitleFor(player.id),
                    highlighted = highlightedPlayerId == player.id,
                    onClick = { onPlayerClick(player.id) },
                    onNudge = { dx, dy -> onNudge(player.id, dx, dy) },
                    onDragStart = { pointer -> onDragStart(player.id, pointer) },
                    onDragMove = onDragMove,
                    onDragEnd = { pointer -> onDragEnd(player.id, pointer) },
                    onDragCancel = onDragCancel,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (shownX * widthPx - markerPx / 2)
                                    .coerceIn(0f, (widthPx - markerPx).coerceAtLeast(0f)).roundToInt(),
                                (shownY * heightPx - markerPx / 2)
                                    .coerceIn(0f, (heightPx - markerHeightPx).coerceAtLeast(0f)).roundToInt()
                            )
                        }
                        .testTag(LineupTestTags.pitchPlayer(player.id))
                )
            }
        }

        if (isDropTarget) {
            Text(
                dropHint ?: if (highlightedSlotId != null) tr("Release to snap into position") else tr("Release to place on pitch"),
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

/** The same grass on every pitch in the app. */
internal object PitchColors {
    val grass = Brush.verticalGradient(listOf(Color(0xFF79B94A), Color(0xFF5FA63B), Color(0xFF4A9134)))
    val dropTarget = Brush.verticalGradient(listOf(Color(0xFF8BCB56), Color(0xFF69B442), Color(0xFF55A23A)))
}

/**
 * Pitch markings. [horizontal] lays the pitch on its side (goals left and right), which
 * fills wide spaces such as timeline cards.
 */
@Composable
internal fun PitchLines(modifier: Modifier = Modifier, horizontal: Boolean = false) {
    if (horizontal) {
        Canvas(modifier) {
            val line = Color.White.copy(alpha = 0.72f)
            val stroke = 3f
            drawRect(line, style = Stroke(stroke))
            drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
            drawCircle(line, radius = size.height * 0.13f, center = center, style = Stroke(stroke))
            drawCircle(line, radius = 5f, center = center)
            val boxDepth = size.width * 0.16f
            val boxWidth = size.height * 0.58f
            drawRect(
                line,
                topLeft = Offset(0f, (size.height - boxWidth) / 2),
                size = androidx.compose.ui.geometry.Size(boxDepth, boxWidth),
                style = Stroke(stroke)
            )
            drawRect(
                line,
                topLeft = Offset(size.width - boxDepth, (size.height - boxWidth) / 2),
                size = androidx.compose.ui.geometry.Size(boxDepth, boxWidth),
                style = Stroke(stroke)
            )
        }
        return
    }
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
    subtitle: String?,
    highlighted: Boolean,
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
                    tr("{playerName}, number {shirtNumber}, {pitch}. Drag to move.", "playerName" to player.name, "shirtNumber" to player.shirtNumber, "pitch" to (role.ifBlank { tr("on pitch") }))
                customActions = listOf(
                    CustomAccessibilityAction(tr("Move left")) { onNudge(-0.05f, 0f); true },
                    CustomAccessibilityAction(tr("Move right")) { onNudge(0.05f, 0f); true },
                    CustomAccessibilityAction(tr("Move forward")) { onNudge(0f, -0.05f); true },
                    CustomAccessibilityAction(tr("Move back")) { onNudge(0f, 0.05f); true }
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlayerBadge(
            player,
            if (highlighted) Modifier.border(3.dp, Color(0xFFFFD54F), CircleShape) else Modifier
        )
        Text(
            player.name.substringBefore(" ").uppercase(),
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        subtitle?.let {
            Text(
                it,
                color = Color(0xFF16330E),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
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
internal fun PlayerDragGhost(player: Player, modifier: Modifier = Modifier) {
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
internal fun BenchPanel(
    players: List<Player>,
    draggingPlayerId: Long?,
    isDropTarget: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onPlayerClick: (Long) -> Unit,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: () -> Unit,
    subtitleFor: (Long) -> String? = { null },
    canDrag: (Long) -> Boolean = { true },
    /** Marks players who should get time on the pitch next. */
    highlightFor: (Long) -> Boolean = { false },
    /** A column of substitutes beside the pitch, for landscape, instead of a row below it. */
    vertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isDropTarget) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = if (vertical) RoundedCornerShape(12.dp) else RectangleShape,
        modifier = modifier
            .testTag(LineupTestTags.BENCH)
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(
                if (isDropTarget) tr("RELEASE TO MOVE TO BENCH") else tr("BENCH • {playersCount}", "playersCount" to players.size),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (players.isEmpty()) {
                Text(
                    if (draggingPlayerId != null) tr("Drop the player here.") else tr("All selected players are on the pitch."),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (vertical) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(60.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(players, key = { it.id }) { player ->
                        BenchPlayer(
                            player, draggingPlayerId, onPlayerClick, onDragStart, onDragMove, onDragEnd,
                            onDragCancel, subtitleFor, canDrag, highlightFor
                        )
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    // A short bench sits centred under the pitch instead of hugging the left edge.
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    items(players, key = { it.id }) { player ->
                        BenchPlayer(
                            player, draggingPlayerId, onPlayerClick, onDragStart, onDragMove, onDragEnd,
                            onDragCancel, subtitleFor, canDrag, highlightFor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchPlayer(
    player: Player,
    draggingPlayerId: Long?,
    onPlayerClick: (Long) -> Unit,
    onDragStart: (Long, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Long, Offset) -> Unit,
    onDragCancel: () -> Unit,
    subtitleFor: (Long) -> String?,
    canDrag: (Long) -> Boolean,
    highlightFor: (Long) -> Boolean
) {
    Column(
        Modifier
            .testTag(LineupTestTags.benchPlayer(player.id))
            .width(60.dp)
            .alpha(
                when {
                    draggingPlayerId == player.id -> 0.18f
                    !canDrag(player.id) -> 0.45f
                    else -> 1f
                }
            )
            .then(
                if (canDrag(player.id)) {
                    Modifier.longPressPlayerDrag(
                        playerId = player.id,
                        onStart = { onDragStart(player.id, it) },
                        onMove = onDragMove,
                        onEnd = { onDragEnd(player.id, it) },
                        onCancel = onDragCancel
                    )
                } else Modifier
            )
            .semantics {
                contentDescription =
                    tr("{playerName}, substitute. Tap for placement options or drag onto the pitch.", "playerName" to player.name)
                customActions = listOf(
                    CustomAccessibilityAction(tr("Open placement options")) {
                        onPlayerClick(player.id)
                        true
                    }
                )
            }
            .clickable { onPlayerClick(player.id) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(
                    if (highlightFor(player.id)) Color(0xFFFFC247)
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
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
        subtitleFor(player.id)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (highlightFor(player.id)) FontWeight.Bold else null,
                maxLines = 1
            )
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
    playersById: Map<Long, Player>,
    onDismiss: () -> Unit,
    onMoveToPitch: (FormationSlot) -> Unit,
    onMoveToBench: () -> Unit,
    onSwapWith: (MatchLineupPlacement) -> Unit,
    onNudge: (Float, Float) -> Unit
) {
    val occupiedSlotIds = LineupDragDropRules.occupiedSlotIds(slots, occupiedPlacements)
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
                AppButton(
                    onClick = { onMoveToPitch(nearestAvailable) },
                    enabled = !pitchFull,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (pitchFull) tr("Pitch is full") else tr("Place in next free formation slot")) }
            } else {
                Text(tr("Fine position"), style = MaterialTheme.typography.titleMedium)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    AppTonalButton(onClick = { onNudge(0f, -0.05f) }) { Text(tr("↑ Forward")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTonalButton(onClick = { onNudge(-0.05f, 0f) }) { Text(tr("← Left")) }
                        AppTonalButton(onClick = { onNudge(0.05f, 0f) }) { Text(tr("Right →")) }
                    }
                    AppTonalButton(onClick = { onNudge(0f, 0.05f) }) { Text(tr("↓ Back")) }
                }
                AppOutlinedButton(onClick = onMoveToBench, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Move to bench"))
                }
            }

            if (occupiedPlacements.isNotEmpty()) {
                Text(tr("Swap with a player"), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(occupiedPlacements, key = { it.playerId }) { occupied ->
                        val other = playersById[occupied.playerId]
                        AssistChip(
                            onClick = { onSwapWith(occupied) },
                            label = {
                                Text(
                                    listOfNotNull(
                                        other?.shirtNumber?.takeIf { it > 0 }?.let { "#$it" },
                                        other?.name
                                    ).joinToString(" ").ifBlank { tr("Player") }
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            Text(tr("Choose a formation slot"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Tap a free slot below. Long-press dragging remains available on the lineup screen."),
                style = MaterialTheme.typography.bodySmall
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(slots, key = { it.id }) { slot ->
                    val available = slot.id !in occupiedSlotIds
                    FilterChip(
                        selected = placement.onPitch && placement.formationSlot == slot.id,
                        onClick = { if (available || placement.formationSlot == slot.id) onMoveToPitch(slot) },
                        enabled = (available || placement.formationSlot == slot.id) &&
                            (!pitchFull || placement.onPitch),
                        label = { Text("${slot.label} ${slot.id.substringAfterLast('-')}") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}
