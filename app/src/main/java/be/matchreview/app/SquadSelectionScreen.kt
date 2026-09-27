package be.matchreview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    val visiblePlayers = remember(roster, search) {
        roster.filter { player ->
            search.isBlank() ||
                player.name.contains(search, ignoreCase = true) ||
                player.shirtNumber.toString().contains(search) ||
                player.position.contains(search, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Select players",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "vs ${current.opponent} • ${current.matchDate}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryPill("${summary.selectedCount}", "selected", Modifier.weight(1f))
                    SummaryPill("${roster.size}", "in team", Modifier.weight(1f))
                    SummaryPill("${current.playersOnPitch}", "on pitch", Modifier.weight(1f))
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search player, number or position") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.markAndSelectAllAvailable(matchId) },
                    modifier = Modifier.weight(1f)
                ) { Text("Select all") }
                OutlinedButton(
                    onClick = { vm.clearSquadSelection(matchId) },
                    modifier = Modifier.weight(1f)
                ) { Text("Clear selection") }
            }
            Text(
                "Tap players to include or exclude them from this match.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()

        if (roster.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This team has no players. Add players to the team first.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
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
                        onSelectedChanged = {
                            vm.setSquadSelected(matchId, player.id, it)
                        }
                    )
                }
                if (visiblePlayers.isEmpty()) {
                    item {
                        Text(
                            "No players match your search.",
                            modifier = Modifier.fillMaxWidth().padding(24.dp)
                        )
                    }
                }
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when {
                        summary.selectedCount == 0 -> "Select at least one player."
                        summary.selectedCount < current.playersOnPitch ->
                            "Select ${current.playersOnPitch - summary.selectedCount} more for a full ${current.playersOnPitch}v${current.playersOnPitch} lineup, or continue with fewer."
                        else -> "${summary.selectedCount} players selected."
                    },
                    color = if (summary.selectedCount == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    enabled = summary.canContinue,
                    onClick = { nav.navigate("lineup/$matchId") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue to lineup")
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
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
    onSelectedChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectedChanged(!selected) },
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
                Text(
                    player.position.ifBlank { "Position not set" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChanged
            )
        }
    }
}
