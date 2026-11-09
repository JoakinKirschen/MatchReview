package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.isLandscape
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.Player
import be.matchreview.app.domain.FormationLayout
import be.matchreview.app.domain.PitchOrientation
import be.matchreview.app.domain.PlayingTimeRules
import be.matchreview.app.domain.SubstitutionDrop
import be.matchreview.app.domain.SubstitutionPlanRules
import kotlin.math.roundToInt

private data class SubstitutionDrag(val playerId: Long, val pointerInRoot: Offset)

/** Taps that arrive this soon after a drop belong to the drag gesture, not to a new tap. */
private const val TAP_AFTER_DROP_GRACE_MS = 400L

/**
 * Full-screen substitution mode: the live lineup is edited with the same long-press drag
 * and drop as the starting lineup. Changes stay local until the coach confirms, so the
 * players on the pitch keep collecting minutes until the whole round is applied at one
 * match time.
 *
 * Release a substitute on a pitch player to swap them; while the pitch is full a
 * substitute always replaces the nearest player. Drag a pitch player to the bench to take
 * them off, or onto free grass to move them. Tapping two players swaps them too.
 */
@Composable
internal fun SubstitutionModePanel(
    match: GameMatch,
    livePlacements: List<MatchLineupPlacement>,
    /** A previously saved, unconfirmed round to continue; null starts from the live lineup. */
    restoredPlan: List<MatchLineupPlacement>?,
    playersById: Map<Long, Player>,
    eligibleBenchIds: Set<Long>,
    clockLabel: String,
    scoreLabel: String,
    minutesLabel: (Long) -> String,
    playedMs: (Long) -> Long,
    /** Called with the current plan, or null when it no longer differs from the live lineup. */
    onDraftChanged: (List<MatchLineupPlacement>?) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (List<MatchLineupPlacement>) -> Unit,
    modifier: Modifier = Modifier
) {
    val original = remember(match.id) { livePlacements }
    val originalById = remember(original) { original.associateBy { it.playerId } }
    var plan by remember(match.id) {
        mutableStateOf(SubstitutionPlanRules.planOf(restoredPlan ?: original))
    }
    var drag by remember { mutableStateOf<SubstitutionDrag?>(null) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var lastDropAt by remember { mutableLongStateOf(0L) }
    var pitchBounds by remember { mutableStateOf<Rect?>(null) }
    var benchBounds by remember { mutableStateOf<Rect?>(null) }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val playerHitRadiusPx = with(density) { 40.dp.toPx() }
    val landscape = isLandscape()

    val slots = remember(match.formation, match.playersOnPitch) {
        FormationLayout.slots(match.formation, match.playersOnPitch)
    }
    val onPitch = plan.values.filter { it.onPitch }
    fun canMove(playerId: Long): Boolean =
        playerId in eligibleBenchIds || originalById[playerId]?.onPitch == true

    // Substitutes who played the least come first and are marked, so time is shared fairly.
    val bench = plan.values.filterNot { it.onPitch }
        .mapNotNull { playersById[it.playerId] }
        .sortedWith(
            compareBy<Player>({ !canMove(it.id) }, { playedMs(it.id) }, { it.shirtNumber <= 0 }, { it.shirtNumber }, { it.name })
        )
    val leastPlayedBench = PlayingTimeRules.leastPlayed(bench.map { it.id }.filter(::canMove), playedMs)
    val changes = SubstitutionPlanRules.changes(original, plan)
    val currentOnDraftChanged by rememberUpdatedState(onDraftChanged)
    LaunchedEffect(plan) {
        currentOnDraftChanged(if (changes.isEmpty) null else plan.values.toList())
    }

    /** Where [playerId] would go if released at [pointer]; null when not over the pitch. */
    fun dropAt(playerId: Long, pointer: Offset): SubstitutionDrop? {
        val pitch = pitchBounds ?: return null
        if (!pitch.contains(pointer)) return null
        val (x, y) = PitchOrientation.normalizePointer(
            pointer.x, pointer.y, pitch.left, pitch.top, pitch.width, pitch.height, landscape
        )
        val (acrossPx, alongPx) = PitchOrientation.lineupAxes(pitch.width, pitch.height, landscape)
        return SubstitutionPlanRules.resolveDrop(
            plan, playerId, x, y, slots, match.playersOnPitch,
            acrossPx, alongPx, playerHitRadiusPx
        )
    }

    fun swap(first: Long, second: Long) {
        if (!canMove(first) || !canMove(second)) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }
        plan = SubstitutionPlanRules.swap(plan, first, second)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun finishDrag(playerId: Long, pointer: Offset) {
        drag = null
        selectedId = null
        lastDropAt = SystemClock.uptimeMillis()
        val benchArea = benchBounds
        when (val drop = dropAt(playerId, pointer)) {
            is SubstitutionDrop.Swap -> swap(playerId, drop.targetPlayerId)
            is SubstitutionDrop.Place -> {
                SubstitutionPlanRules.moveToPitch(plan, playerId, drop.drop, match.playersOnPitch)
                    ?.let { plan = it }
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            null -> if (benchArea != null && benchArea.contains(pointer)) {
                plan = SubstitutionPlanRules.moveToBench(plan, playerId)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun tapPlayer(playerId: Long) {
        if (SystemClock.uptimeMillis() - lastDropAt < TAP_AFTER_DROP_GRACE_MS) return
        val selected = selectedId
        when {
            selected == null -> if (canMove(playerId)) selectedId = playerId
            selected == playerId -> selectedId = null
            else -> {
                swap(selected, playerId)
                selectedId = null
            }
        }
    }

    val preview = drag?.let { dropAt(it.playerId, it.pointerInRoot) }
    val dragPointer = drag?.pointerInRoot
    val pitchIsTarget = dragPointer?.let { pitchBounds?.contains(it) } == true
    val benchIsTarget = dragPointer?.let { benchBounds?.contains(it) } == true
    val swapTarget = (preview as? SubstitutionDrop.Swap)?.targetPlayerId
    val snapSlot = (preview as? SubstitutionDrop.Place)?.drop?.takeIf { it.snapped }?.formationSlot

    val pitchView: @Composable (Modifier) -> Unit = { modifier ->
        PitchView(
            slots = slots,
            placements = onPitch,
            playersById = playersById,
            draggingPlayerId = drag?.playerId,
            highlightedSlotId = snapSlot,
            isDropTarget = pitchIsTarget,
            onBoundsChanged = { pitchBounds = it },
            onPlayerClick = ::tapPlayer,
            onNudge = { playerId, dx, dy ->
                plan[playerId]?.let { existing ->
                    plan = plan + (playerId to existing.copy(
                        normalizedX = (existing.normalizedX + dx).coerceIn(0.08f, 0.92f),
                        normalizedY = (existing.normalizedY + dy).coerceIn(0.08f, 0.92f),
                        formationSlot = ""
                    ))
                }
            },
            onDragStart = { playerId, pointer ->
                selectedId = null
                drag = SubstitutionDrag(playerId, pointer)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragMove = { pointer -> drag = drag?.copy(pointerInRoot = pointer) },
            onDragEnd = ::finishDrag,
            onDragCancel = { drag = null },
            subtitleFor = minutesLabel,
            highlightedPlayerId = swapTarget ?: selectedId,
            dropHint = swapTarget?.let { tr("Swap with {it}", "it" to (playersById[it]?.name ?: "player")) },
            horizontal = landscape,
            modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
    val benchPanel: @Composable (Modifier) -> Unit = { modifier ->
        BenchPanel(
            players = bench,
            draggingPlayerId = drag?.playerId,
            isDropTarget = benchIsTarget,
            onBoundsChanged = { benchBounds = it },
            onPlayerClick = ::tapPlayer,
            onDragStart = { playerId, pointer ->
                selectedId = null
                drag = SubstitutionDrag(playerId, pointer)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragMove = { pointer -> drag = drag?.copy(pointerInRoot = pointer) },
            onDragEnd = ::finishDrag,
            onDragCancel = { drag = null },
            subtitleFor = { id -> if (canMove(id)) minutesLabel(id) else tr("Out") },
            canDrag = ::canMove,
            highlightFor = { it in leastPlayedBench },
            vertical = landscape,
            modifier = modifier
        )
    }
    val cancelButton: @Composable (Modifier) -> Unit = { modifier ->
        AppOutlinedButton(
            onClick = onCancel,
            enabled = drag == null,
            modifier = modifier.heightIn(min = 48.dp)
        ) { Text(if (changes.isEmpty) tr("Close") else tr("Discard")) }
    }
    val confirmButton: @Composable (Modifier) -> Unit = { modifier ->
        AppButton(
            onClick = { if (changes.isEmpty) onCancel() else onConfirm(plan.values.toList()) },
            enabled = drag == null,
            modifier = modifier.heightIn(min = 48.dp)
        ) {
            Text(
                when {
                    changes.isEmpty -> tr("Done")
                    changes.hasSubstitutions ->
                        tr("Confirm {outgoing} change(s)", "outgoing" to (maxOf(changes.incoming.size, changes.outgoing.size)))
                    else -> tr("Confirm positions")
                },
                maxLines = 1
            )
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Substitutions"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                when {
                    selectedId != null -> tr("Tap a player to swap with {selected}", "selected" to (playersById[selectedId]?.name ?: "player"))
                    changes.isEmpty -> tr("Drag a substitute onto a player")
                    else -> listOfNotNull(
                        changes.incoming.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "▲ ") { playersById[it]?.name?.substringBefore(" ") ?: tr("Player") },
                        changes.outgoing.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "▼ ") { playersById[it]?.name?.substringBefore(" ") ?: tr("Player") },
                        changes.moved.takeIf { it.isNotEmpty() }?.let { tr("{count} moved", "count" to it.size) }
                    ).joinToString("  ")
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("$clockLabel • $scoreLabel", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { panelOrigin = it.boundsInRoot().topLeft }
        ) {
            if (landscape) {
                // Held sideways: the pitch lies on its side with the bench and buttons beside it.
                Row(Modifier.fillMaxSize()) {
                    pitchView(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(212.dp).fillMaxHeight().padding(end = 6.dp, top = 2.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        benchPanel(Modifier.weight(1f).fillMaxWidth())
                        confirmButton(Modifier.fillMaxWidth())
                        cancelButton(Modifier.fillMaxWidth())
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                pitchView(Modifier.weight(1f).fillMaxWidth())
                benchPanel(Modifier)
            }

            drag?.let { current ->
                playersById[current.playerId]?.let { player ->
                    PlayerDragGhost(
                        player = player,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (current.pointerInRoot.x - panelOrigin.x - 29.dp.toPx()).roundToInt(),
                                    (current.pointerInRoot.y - panelOrigin.y - 29.dp.toPx()).roundToInt()
                                )
                            }
                            .zIndex(20f)
                    )
                }
            }
        }

        if (!landscape) Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cancelButton(Modifier.weight(1f))
            confirmButton(Modifier.weight(1.4f))
        }
    }
}
