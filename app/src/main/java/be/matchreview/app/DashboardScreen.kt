package be.matchreview.app

import be.matchreview.app.i18n.AppLanguage
import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.ui.ThemeMode
import be.matchreview.app.domain.SeasonSummaryRules
import be.matchreview.app.domain.PracticeMatchRules
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val activeMatch by vm.activeMatch.collectAsStateWithLifecycle()
    val lastBackupEpochMs by vm.lastBackupEpochMs.collectAsStateWithLifecycle()
    val backupDue by vm.backupDue.collectAsStateWithLifecycle()
    val autoBackup by vm.autoBackupState.collectAsStateWithLifecycle()
    var summaryTeamId by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(teams) {
        if (teams.isNotEmpty() && summaryTeamId != 0L && teams.none { it.id == summaryTeamId }) summaryTeamId = 0L
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        icon = {},
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(mode.label, maxLines = 1) }
                }
            }
        }
        item {
            val language by vm.language.collectAsStateWithLifecycle()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = language == choice,
                        onClick = { vm.setLanguage(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                        icon = {},
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(choice.label, maxLines = 1) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(tr("Teams"), teams.size.toString(), Modifier.weight(1f))
                StatCard(tr("Matches"), matches.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            // 0 = all real teams; the practice team only counts when picked explicitly.
            val realTeams = teams.filterNot(PracticeMatchRules::isPracticeTeam)
            val summaryTeamIds = if (summaryTeamId == 0L) {
                realTeams.mapTo(mutableSetOf()) { it.id }
            } else setOf(summaryTeamId)
            val summary = SeasonSummaryRules.summarize(matches, summaryTeamIds)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(tr("Completed matches"), style = MaterialTheme.typography.titleMedium)
                    if (teams.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = summaryTeamId == 0L,
                                    onClick = { summaryTeamId = 0L },
                                    label = { Text(if (realTeams.size == teams.size) tr("All teams") else tr("All real teams")) }
                                )
                            }
                            items(teams, key = { it.id }) { team ->
                                FilterChip(
                                    selected = summaryTeamId == team.id,
                                    onClick = { summaryTeamId = team.id },
                                    label = { Text(team.name) }
                                )
                            }
                        }
                    }
                    Text(tr("{played} played • {won} won • {drawn} drawn • {lost} lost", "played" to summary.played, "won" to summary.won, "drawn" to summary.drawn, "lost" to summary.lost))
                    Text(tr("Goals {goalsFor}-{goalsAgainst}", "goalsFor" to summary.goalsFor, "goalsAgainst" to summary.goalsAgainst), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val backupText = when {
                autoBackup.running -> tr("Automatic backup running…")
                autoBackup.lastError != null -> tr("Automatic backup failed: {lastError}", "lastError" to autoBackup.lastError)
                backupDue -> tr("A match finished since your last backup. Back up now to keep it safe.")
                else -> lastBackupEpochMs?.let {
                    tr("Last backup {date}", "date" to (SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(it))))
                } ?: tr("No successful backup yet")
            }
            AppCard(
                modifier = Modifier.fillMaxWidth().clickable { nav.navigate("backup") },
                colors = CardDefaults.cardColors(
                    containerColor = if (lastBackupEpochMs == null || backupDue) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Data protection"), style = MaterialTheme.typography.titleMedium)
                        Text(backupText, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("Open"))
                }
            }
        }
        activeMatch?.let { live ->
            item {
                AppCard(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("live/${live.id}") }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Resume live match"), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(tr("vs {opponent} • {status}", "opponent" to live.opponent, "status" to tr(live.status.name.lowercase().replaceFirstChar { it.uppercase() })))
                        AppButton(
                            onClick = { nav.navigate("live/${live.id}") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text(tr("Open match day")) }
                    }
                }
            }
        }
        item {
            // Teams and backups have their own tab and card; only match actions are repeated here.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(onClick = { nav.navigate("match/new") }, modifier = Modifier.weight(1f)) {
                    Text(tr("New match"), maxLines = 1)
                }
                if (activeMatch == null) {
                    AppOutlinedButton(
                        onClick = { vm.createPracticeMatch { matchId -> nav.navigate("squad/$matchId") } },
                        modifier = Modifier.weight(1f)
                    ) { Text(tr("Practice match"), maxLines = 1) }
                }
            }
        }
        item { Text(tr("Recent matches"), style = MaterialTheme.typography.titleLarge) }
        if (matches.isEmpty()) item { EmptyCard(tr("No matches yet.")) }
        items(matches.take(5)) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: tr("Team")) {
                nav.navigate(matchDestination(match))
            }
        }
    }
}


@Composable
internal fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
