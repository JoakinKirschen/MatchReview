package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppOutlinedButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.Player
import be.matchreview.app.domain.GoalMouthGeometry
import be.matchreview.app.domain.MatchClockCalculator
import be.matchreview.app.domain.TimelineItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val IncomingHighlight = Color(0xFFFFD54F)
private val OurGoalColor = Color(0xFF2E7D32)
private val OpponentGoalColor = Color(0xFFC62828)

/**
 * A picture of the pitch after a lineup change, on its side so it fills the card width:
 * our goal on the left, attacking to the right. New players are highlighted.
 */
@Composable
fun LineupSnapshotPitch(
    change: TimelineItem.LineupChange,
    playersById: Map<Long, Player>,
    modifier: Modifier = Modifier
) {
    val incoming = change.incomingPlayerIds.toSet()
    val positions = change.snapshot
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .clip(RoundedCornerShape(12.dp))
            .background(PitchColors.grass)
            .semantics {
                contentDescription = tr("Lineup after the change: ") +
                    positions.joinToString { playersById[it.playerId]?.name ?: "player" }
            }
    ) {
        PitchLines(Modifier.fillMaxSize(), horizontal = true)
        val marker = 26.dp
        val label = 56.dp
        positions.forEach { position ->
            val player = playersById[position.playerId]
            // Depth on the pitch becomes left to right; the keeper ends up near the left goal.
            val centerX = maxWidth * (1f - position.normalizedY).coerceIn(0.06f, 0.94f)
            val centerY = maxHeight * position.normalizedX.coerceIn(0.1f, 0.9f)
            val top = (centerY - marker / 2).coerceIn(0.dp, (maxHeight - marker - 14.dp).coerceAtLeast(0.dp))
            Column(
                Modifier.offset(x = centerX - label / 2, y = top).width(label),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isNew = position.playerId in incoming
                Box(
                    Modifier
                        .size(marker)
                        .clip(CircleShape)
                        .background(if (isNew) IncomingHighlight else Color(0xFFF5F7FA))
                        .border(2.dp, if (isNew) Color(0xFF8D6E00) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        player?.shirtNumber?.takeIf { it > 0 }?.toString()
                            ?: player?.name?.take(1)?.uppercase() ?: "?",
                        color = Color(0xFF1C3D6E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    player?.name?.substringBefore(" ") ?: "",
                    color = Color.Black,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Timeline card for one substitution round: who came on and off plus the new shape. */
@Composable
fun LineupChangeCard(
    change: TimelineItem.LineupChange,
    playersById: Map<Long, Player>,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    fun names(ids: List<Long>) = ids.joinToString(", ") { playersById[it]?.name ?: tr("Player") }
    val title = when {
        change.isStartingLineup -> if (change.isApproximate) tr("Starting lineup (approximate)") else tr("Starting lineup")
        change.events.all { it.type == "POSITION_CHANGE" } -> tr("Positions changed")
        change.events.any { it.type == "DISMISSAL" } -> tr("Dismissal")
        change.events.any { it.type == "INJURY_OFF" } -> tr("Injury")
        else -> tr("Substitution") + if (change.events.size > 1) "s" else ""
    }
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        if (change.isStartingLineup) tr("Start") else MatchClockCalculator.formatClock(change.timestampMs),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (change.incomingPlayerIds.isNotEmpty()) {
                        Text(tr("▲ On: {incomingPlayerIds}", "incomingPlayerIds" to (names(change.incomingPlayerIds))), style = MaterialTheme.typography.bodySmall)
                    }
                    if (change.outgoingPlayerIds.isNotEmpty()) {
                        Text(tr("▼ Off: {outgoingPlayerIds}", "outgoingPlayerIds" to (names(change.outgoingPlayerIds))), style = MaterialTheme.typography.bodySmall)
                    }
                }
                trailing()
            }
            LineupSnapshotPitch(change = change, playersById = playersById)
        }
    }
}

private fun DrawScope.drawGoalFrame() {
    val frame = GoalMouthGeometry.frame(size.width, size.height)
    val frameWidth = frame.right - frame.left
    val frameHeight = frame.bottom - frame.top
    drawRect(
        Color(0xFFEFF3F6),
        topLeft = Offset(frame.left, frame.top),
        size = Size(frameWidth, frameHeight)
    )
    val net = Color(0xFFB8C4CC)
    for (i in 1 until 12) {
        val x = frame.left + frameWidth * i / 12f
        drawLine(net, Offset(x, frame.top), Offset(x, frame.bottom), 1.5f)
    }
    for (i in 1 until 5) {
        val y = frame.top + frameHeight * i / 5f
        drawLine(net, Offset(frame.left, y), Offset(frame.right, y), 1.5f)
    }
    drawLine(Color(0xFF4A9134), Offset(0f, frame.bottom), Offset(size.width, frame.bottom), 6f)
    val post = Color(0xFF37474F)
    val thickness = size.height * 0.05f
    drawLine(post, Offset(frame.left, frame.bottom), Offset(frame.left, frame.top), thickness)
    drawLine(post, Offset(frame.right, frame.bottom), Offset(frame.right, frame.top), thickness)
    drawLine(post, Offset(frame.left - thickness / 2, frame.top), Offset(frame.right + thickness / 2, frame.top), thickness)
}

private fun DrawScope.drawBall(goalX: Float, goalY: Float, color: Color, radius: Float) {
    val (x, y) = GoalMouthGeometry.toCanvas(goalX, goalY, size.width, size.height)
    drawCircle(Color.White, radius = radius + 3f, center = Offset(x, y))
    drawCircle(color, radius = radius, center = Offset(x, y))
}

/** Lets the coach tap where the ball crossed the goal line. */
@Composable
fun GoalPlacementDialog(
    title: String,
    initialX: Float?,
    initialY: Float?,
    onSkip: () -> Unit,
    onSave: (Float, Float) -> Unit
) {
    var point by remember {
        mutableStateOf<Pair<Float, Float>?>(
            if (initialX != null && initialY != null) initialX to initialY else null
        )
    }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Tap where the ball entered the goal."), style = MaterialTheme.typography.bodySmall)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(GoalMouthGeometry.ASPECT_RATIO)
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                point = GoalMouthGeometry.normalize(
                                    tap.x, tap.y, size.width.toFloat(), size.height.toFloat()
                                )
                            }
                        }
                        .semantics { contentDescription = tr("Goal mouth. Tap to place the ball.") }
                ) {
                    drawGoalFrame()
                    point?.let { (x, y) -> drawBall(x, y, Color(0xFF212121), size.height * 0.07f) }
                }
                Text(
                    point?.let { GoalMouthGeometry.describe(it.first, it.second) }
                        ?.replaceFirstChar { it.uppercase() } ?: tr("No position selected"),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = point != null,
                onClick = { point?.let { onSave(it.first, it.second) } }
            ) { Text(tr("Save position")) }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text(tr("Skip")) } }
    )
}

/** All placed goals of a match drawn on one goal mouth. */
@Composable
fun GoalMap(events: List<MatchEvent>, opponentName: String, modifier: Modifier = Modifier) {
    val placed = events.filter {
        (it.type == "OUR_GOAL" || it.type == "OPPONENT_GOAL") && it.goalX != null && it.goalY != null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Goal map"), style = MaterialTheme.typography.titleMedium)
        if (placed.isEmpty()) {
            Text(
                tr("No goal positions recorded. Add them while recording a goal or from the goal editor."),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(GoalMouthGeometry.ASPECT_RATIO)
                .semantics { contentDescription = tr("{placedCount} goals placed on the goal map", "placedCount" to placed.size) }
        ) {
            drawGoalFrame()
            placed.forEach {
                drawBall(
                    it.goalX!!, it.goalY!!,
                    if (it.type == "OUR_GOAL") OurGoalColor else OpponentGoalColor,
                    size.height * 0.05f
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(OurGoalColor, tr("Our goals ({count})", "count" to (placed.count { it.type == "OUR_GOAL" })))
            LegendDot(OpponentGoalColor, "$opponentName (${placed.count { it.type == "OPPONENT_GOAL" }})")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HomeAwaySelector(home: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = home,
            onClick = { onChange(true) },
            label = { Text(tr("Home")) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        FilterChip(
            selected = !home,
            onClick = { onChange(false) },
            label = { Text(tr("Away")) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

@Composable
fun TeamLogoImage(logoPng: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(logoPng) { TeamLogoCodec.decode(logoPng)?.asImageBitmap() } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = tr("Team logo"),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}

/** Edits the details that may change after a match is created. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailsDialog(
    match: GameMatch,
    onDismiss: () -> Unit,
    onSave: (opponent: String, date: String, venue: String, competition: String, home: Boolean) -> Unit
) {
    var opponent by remember(match.id) { mutableStateOf(match.opponent) }
    var date by remember(match.id) { mutableStateOf(match.matchDate) }
    var venue by remember(match.id) { mutableStateOf(match.venue) }
    var competition by remember(match.id) { mutableStateOf(match.competition) }
    var home by remember(match.id) { mutableStateOf(match.isHome) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit match details")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeAwaySelector(home, { home = it })
                OutlinedTextField(
                    value = opponent,
                    onValueChange = { opponent = it },
                    label = { Text(tr("Opponent")) },
                    singleLine = true,
                    isError = opponent.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                AppOutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(tr("Match date"), style = MaterialTheme.typography.labelSmall)
                        Text(date)
                    }
                }
                OutlinedTextField(
                    value = venue,
                    onValueChange = { venue = it },
                    label = { Text(tr("Venue")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = competition,
                    onValueChange = { competition = it },
                    label = { Text(tr("Competition")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = opponent.isNotBlank() && date.isNotBlank(),
                onClick = { onSave(opponent, date, venue, competition, home) }
            ) { Text(tr("Save details")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )

    if (showDatePicker) {
        MatchDatePickerDialog(
            date = date,
            onDismiss = { showDatePicker = false },
            onPicked = {
                date = it
                showDatePicker = false
            }
        )
    }
}

/** Date picker that reads and writes the yyyy-MM-dd strings stored on matches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDatePickerDialog(date: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val utcFormat = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    val initialMillis = remember(date) { runCatching { utcFormat.parse(date)?.time }.getOrNull() }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(utcFormat.format(Date(it))) } ?: onDismiss()
            }) { Text(tr("Use date")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    ) {
        DatePicker(state = state)
    }
}
