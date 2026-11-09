package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButtons
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import be.matchreview.app.domain.GoalkeeperRules

@Composable
fun MatchReadinessScreen(
    matchId: Long,
    vm: MainViewModel,
    nav: NavHostController
) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val squad by vm.squad(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val lineup by vm.lineup(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val players by vm.players.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = match
    var showDetailsEditor by remember { mutableStateOf(false) }

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val selectedIds = squad.filter { it.selected }.mapTo(mutableSetOf()) { it.playerId }
    val starters = lineup.filter { it.onPitch && it.playerId in selectedIds }
    val selectedPlayers = players.filter { it.id in selectedIds }
    // Formation slots label the keeper "GK", which a plain "goal" text check never matched.
    val goalkeeperReady = GoalkeeperRules.onPitchGoalkeepers(starters, squad, players).isNotEmpty()
    val freeBytes = remember {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }
    val freeStorageLabel = when {
        freeBytes >= 1024L * 1024L * 1024L ->
            tr("{size} GB free", "size" to (freeBytes / (1024L * 1024L * 1024L)))
        else -> tr("{size} MB free", "size" to (freeBytes / (1024L * 1024L)))
    }
    val fullLineup = starters.size == current.playersOnPitch

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("{selectedPlayersCount} selected • {startersCount}/{playersOnPitch} starting", "selectedPlayersCount" to selectedPlayers.size, "startersCount" to starters.size, "playersOnPitch" to current.playersOnPitch),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showDetailsEditor = true }) { Text(tr("Edit")) }
                }
                ReadinessRow(tr("Opponent"), current.opponent)
                ReadinessRow(tr("Format"), "${current.playersOnPitch}v${current.playersOnPitch}")
                ReadinessRow(tr("Formation"), current.formation)
                ReadinessRow(tr("Timing"), tr("{periodCount} × {minutes} min", "periodCount" to current.periodCount, "minutes" to current.periodDurationMinutes))
                ReadinessRow(tr("Venue"), current.venue.ifBlank { tr("Not specified") })
                ReadinessRow(tr("Date"), current.matchDate)
                ReadinessRow(tr("Home or away"), if (current.isHome) tr("Home") else tr("Away"))
                ReadinessRow(tr("Competition"), current.competition.ifBlank { tr("Not specified") })
                ReadinessRow(tr("Storage"), freeStorageLabel)
            }
        }

        listOfNotNull(
            tr("The starting lineup is not full").takeIf { !fullLineup },
            tr("No goalkeeper in the starting lineup").takeIf { !goalkeeperReady }
        ).forEach { warning ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(warning, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nav.navigate("lineup/$matchId") }) { Text(tr("Fix")) }
                }
            }
        }

        AppOutlinedButton(
            onClick = { nav.navigate("lineup/$matchId") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Review lineup")) }
        AppButton(
            onClick = {
                nav.navigate("live/$matchId") {
                    popUpTo("ready/$matchId") { inclusive = true }
                }
            },
            enabled = starters.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = AppButtons.LargeHeight)
        ) { Text(tr("Open match day")) }
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
}

@Composable
private fun ReadinessRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
