package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import be.matchreview.app.ui.isLandscape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import be.matchreview.app.data.MatchSquadPlayer
import be.matchreview.app.data.AvailabilityStatus
import be.matchreview.app.data.Player
import be.matchreview.app.domain.SquadSelectionRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadSelectionScreen(
    matchId: Long,
    vm: MainViewModel,
    nav: NavHostController
) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val allPlayers by vm.players.collectAsStateWithLifecycle()
    val squad by vm.squad(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val current = match

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val roster = remember(allPlayers, current.teamId) {
        allPlayers.filter { it.teamId == current.teamId && !it.archived }
    }
    val squadByPlayer = remember(squad) { squad.associateBy { it.playerId } }
    val summary = SquadSelectionRules.summary(squad, roster.size, current.playersOnPitch)

    LaunchedEffect(matchId, current.teamId, roster.map { it.id }) {
        vm.ensureMatchSquad(matchId, current.teamId)
    }

    var search by rememberSaveable { mutableStateOf("") }
    var availabilityFilter by rememberSaveable { mutableStateOf("ALL") }
    val visiblePlayers = remember(roster, squadByPlayer, search, availabilityFilter) {
        roster.filter { player ->
            val matchesSearch = search.isBlank() ||
                player.name.contains(search, ignoreCase = true) ||
                player.shirtNumber.toString().contains(search) ||
                player.position.contains(search, ignoreCase = true)
            val status = squadByPlayer[player.id]?.availability ?: AvailabilityStatus.UNKNOWN
            matchesSearch && (availabilityFilter == "ALL" || status.name == availabilityFilter)
        }
    }

    val controls: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryPill("${summary.selectedCount}", "selected", Modifier.weight(1f))
            SummaryPill("${roster.size}", tr("in team"), Modifier.weight(1f))
            SummaryPill("${current.playersOnPitch}", tr("on pitch"), Modifier.weight(1f))
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(tr("Search player, number or position")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val filters = listOf(
                "ALL" to tr("All"),
                AvailabilityStatus.AVAILABLE.name to tr("Available"),
                AvailabilityStatus.INJURED.name to tr("Injured"),
                AvailabilityStatus.SUSPENDED.name to tr("Suspended"),
                AvailabilityStatus.UNAVAILABLE.name to tr("Unavailable")
            )
            items(filters, key = { it.first }) { filter ->
                FilterChip(
                    selected = availabilityFilter == filter.first,
                    onClick = { availabilityFilter = filter.first },
                    label = { Text(filter.second) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTonalButton(
                onClick = { vm.markAndSelectAllAvailable(matchId) },
                modifier = Modifier.weight(1f)
            ) { Text(tr("Select all")) }
            AppOutlinedButton(
                onClick = { vm.clearSquadSelection(matchId) },
                modifier = Modifier.weight(1f)
            ) { Text(tr("Clear selection")) }
        }
    }
    val playerList: @Composable (Modifier) -> Unit = { modifier ->
        if (roster.isEmpty()) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(tr("This team has no players. Add players to the team first."))
            }
        } else {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visiblePlayers, key = { it.id }) { player ->
                    val entry = squadByPlayer[player.id] ?: MatchSquadPlayer(
                        matchId = matchId,
                        playerId = player.id
                    )
                    SquadPlayerCard(
                        player = player,
                        selected = entry.selected,
                        availability = entry.availability,
                        onSelectedChanged = {
                            vm.setSquadSelected(matchId, player.id, it)
                        },
                        onAvailabilityChanged = {
                            vm.setSquadAvailability(matchId, player.id, it)
                        }
                    )
                }
                if (visiblePlayers.isEmpty()) {
                    item {
                        Text(
                            tr("No players match your search."),
                            modifier = Modifier.fillMaxWidth().padding(24.dp)
                        )
                    }
                }
            }
        }
    }
    val continueSection: @Composable () -> Unit = {
        // Only a warning; the count itself is in the summary at the top.
        when {
            summary.selectedCount == 0 -> tr("Select at least one player.")
            summary.selectedCount < current.playersOnPitch ->
                tr("Select {selectedCount} more for a full {playersOnPitch}v{playersOnPitch} lineup, or continue with fewer.", "selectedCount" to (current.playersOnPitch - summary.selectedCount), "playersOnPitch" to current.playersOnPitch)
            else -> null
        }?.let { warning ->
            Text(
                warning,
                color = if (summary.selectedCount == 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        AppButton(
            enabled = summary.canContinue,
            onClick = { nav.navigate("lineup/$matchId") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("Continue to lineup"))
        }
    }

    if (isLandscape()) {
        // Held sideways: filters and the continue button on the left, the players beside them.
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                controls()
                continueSection()
            }
            VerticalDivider()
            playerList(Modifier.weight(1f).fillMaxHeight())
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { controls() }

        HorizontalDivider()

        playerList(Modifier.weight(1f).fillMaxWidth())

        Surface(shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { continueSection() }
        }
    }
}

@Composable
private fun SummaryPill(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SquadPlayerCard(
    player: Player,
    selected: Boolean,
    availability: AvailabilityStatus,
    onSelectedChanged: (Boolean) -> Unit,
    onAvailabilityChanged: (AvailabilityStatus) -> Unit
) {
    var statusMenu by remember { mutableStateOf(false) }
    val selectable = availability == AvailabilityStatus.AVAILABLE
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable) { onSelectedChanged(!selected) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (player.shirtNumber > 0) player.shirtNumber.toString()
                    else player.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    player.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (player.position.isNotBlank()) {
                    Text(player.position, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                AssistChip(
                    onClick = { statusMenu = true },
                    label = {
                        Text(
                            availability.name.lowercase().replace('_', ' ')
                                .replaceFirstChar { it.uppercase() }
                        )
                    }
                )
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    listOf(
                        AvailabilityStatus.AVAILABLE,
                        AvailabilityStatus.INJURED,
                        AvailabilityStatus.SUSPENDED,
                        AvailabilityStatus.UNAVAILABLE
                    ).forEach { status ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    status.name.lowercase().replace('_', ' ')
                                        .replaceFirstChar { it.uppercase() }
                                )
                            },
                            onClick = {
                                onAvailabilityChanged(status)
                                statusMenu = false
                            }
                        )
                    }
                }
            }
            Checkbox(
                checked = selected,
                enabled = selectable,
                onCheckedChange = onSelectedChanged
            )
        }
    }
}
