package be.matchreview.app

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
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.domain.PracticeMatchRules
import be.matchreview.app.domain.MatchFormatMemory
import be.matchreview.app.domain.MatchSetupRules
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun MatchesScreen(vm: MainViewModel, nav: NavHostController) {
    val matches by vm.matches.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("ALL") }
    val visibleMatches = remember(matches, teams, search, statusFilter) {
        matches.filter { match ->
            val teamName = teams.firstOrNull { it.id == match.teamId }?.name.orEmpty()
            val matchesSearch = search.isBlank() ||
                match.opponent.contains(search, true) ||
                teamName.contains(search, true) ||
                match.competition.contains(search, true) ||
                match.venue.contains(search, true)
            val matchesStatus = when (statusFilter) {
                "ACTIVE" -> match.status in setOf(
                    MatchStatus.LINEUP_READY,
                    MatchStatus.LIVE,
                    MatchStatus.PAUSED,
                    MatchStatus.PERIOD_ENDED
                )
                "FINISHED" -> match.status == MatchStatus.FINISHED
                "DRAFT" -> match.status == MatchStatus.DRAFT
                else -> true
            }
            matchesSearch && matchesStatus
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppButton(onClick = { nav.navigate("match/new") }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("New match"))
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(tr("Search matches")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("ALL" to tr("All"), "ACTIVE" to tr("Active"), "DRAFT" to tr("Drafts"), "FINISHED" to tr("Finished"))) {
                    FilterChip(
                        selected = statusFilter == it.first,
                        onClick = { statusFilter = it.first },
                        label = { Text(it.second) }
                    )
                }
            }
        }
        if (matches.isEmpty()) item { EmptyCard(tr("No matches yet.")) }
        else if (visibleMatches.isEmpty()) item { EmptyCard(tr("No matches match these filters.")) }
        items(visibleMatches, key = { it.id }) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: tr("Team")) {
                nav.navigate(matchDestination(match))
            }
        }
    }
}

internal fun matchDestination(match: GameMatch): String = when (match.status) {
    MatchStatus.DRAFT -> "squad/${match.id}"
    MatchStatus.LINEUP_READY -> "ready/${match.id}"
    MatchStatus.LIVE,
    MatchStatus.PAUSED,
    MatchStatus.PERIOD_ENDED -> "live/${match.id}"
    MatchStatus.FINISHED,
    MatchStatus.ABANDONED,
    MatchStatus.CANCELLED -> "review/${match.id}"
}

internal fun matchNextAction(match: GameMatch): String = when (match.status) {
    MatchStatus.DRAFT -> tr("Continue squad setup")
    MatchStatus.LINEUP_READY -> tr("Open match day")
    MatchStatus.LIVE -> tr("Resume live match")
    MatchStatus.PAUSED -> tr("Resume paused match")
    MatchStatus.PERIOD_ENDED ->
        if (match.currentPeriod < match.periodCount) tr("Start next period") else tr("Finish match")
    MatchStatus.FINISHED -> tr("Review match")
    MatchStatus.ABANDONED -> tr("Review abandoned match")
    MatchStatus.CANCELLED -> tr("Review cancelled match")
}

@Composable
internal fun MatchCard(match: GameMatch, teamName: String, open: () -> Unit) {
    val started = match.status !in setOf(MatchStatus.DRAFT, MatchStatus.LINEUP_READY, MatchStatus.CANCELLED)
    AppCard(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr("{teamName} vs {opponent}", "teamName" to teamName, "opponent" to match.opponent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${match.matchDate} • ${if (match.isHome) "Home" else "Away"} • " +
                        "${match.playersOnPitch}v${match.playersOnPitch} • ${match.periodCount}×${match.periodDurationMinutes}′",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    matchNextAction(match),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (started) {
                    Text(
                        "${match.ourScore}–${match.opponentScore}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                MatchStatusBadge(match.status)
            }
        }
    }
}

@Composable
internal fun MatchStatusBadge(status: MatchStatus) {
    val (label, color) = when (status) {
        MatchStatus.DRAFT -> tr("Draft") to MaterialTheme.colorScheme.surfaceVariant
        MatchStatus.LINEUP_READY -> tr("Ready") to MaterialTheme.colorScheme.tertiaryContainer
        MatchStatus.LIVE -> tr("Live") to MaterialTheme.colorScheme.errorContainer
        MatchStatus.PAUSED -> tr("Paused") to MaterialTheme.colorScheme.errorContainer
        MatchStatus.PERIOD_ENDED -> tr("Break") to MaterialTheme.colorScheme.errorContainer
        MatchStatus.FINISHED -> tr("Finished") to MaterialTheme.colorScheme.primaryContainer
        MatchStatus.ABANDONED -> tr("Abandoned") to MaterialTheme.colorScheme.surfaceVariant
        MatchStatus.CANCELLED -> tr("Cancelled") to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewMatchScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    var teamId by remember { mutableLongStateOf(0L) }
    var opponent by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var venue by remember { mutableStateOf("") }
    var competition by remember { mutableStateOf("") }
    var formation by remember { mutableStateOf(MatchSetupRules.defaultFormation(11)) }
    var periodCount by remember { mutableStateOf("2") }
    var periodDuration by remember { mutableStateOf("45") }
    var playersOnPitch by remember { mutableStateOf("11") }
    var rollingSubstitutions by remember { mutableStateOf(true) }
    var home by remember { mutableStateOf(true) }
    var teamMenu by remember { mutableStateOf(false) }
    var formationMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var previousSettingsApplied by remember { mutableStateOf(false) }

    // The format (size, formation, periods, minutes, rolling subs, competition) and team of
    // the last created match are reused. Opponent, date, venue and home/away stay fresh.
    var rememberedTeamId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(matches, teams) {
        if (previousSettingsApplied) return@LaunchedEffect
        val practiceTeamIds = teams.filter(PracticeMatchRules::isPracticeTeam).mapTo(mutableSetOf()) { it.id }
        val format = vm.lastMatchFormat()
            ?: MatchFormatMemory.fromLatestMatch(matches, practiceTeamIds)
            ?: return@LaunchedEffect
        competition = format.competition
        playersOnPitch = format.playersOnPitch.toString()
        formation = format.formation
        periodCount = format.periodCount.toString()
        periodDuration = format.periodDurationMinutes.toString()
        rollingSubstitutions = format.rollingSubstitutions
        rememberedTeamId = format.teamId
        previousSettingsApplied = true
    }

    LaunchedEffect(teams, rememberedTeamId) {
        if (teamId != 0L) return@LaunchedEffect
        teamId = when {
            teams.size == 1 -> teams.single().id
            teams.any { it.id == rememberedTeamId } -> rememberedTeamId
            else -> 0L
        }
    }

    val selectedSize = playersOnPitch.toIntOrNull() ?: 11
    val formationOptions = MatchSetupRules.formationsFor(selectedSize)

    FormColumn {
        if (teams.isEmpty()) {
            EmptyCard(tr("Create a team before adding a match."))
            AppButton(onClick = { nav.navigate("team/new") }) { Text(tr("Create team")) }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    AppOutlinedButton(
                        onClick = { teamMenu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(
                            teams.firstOrNull { it.id == teamId }?.name ?: tr("Choose team"),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = teamMenu, onDismissRequest = { teamMenu = false }) {
                        teams.forEach { team ->
                            DropdownMenuItem(
                                text = { Text(team.name) },
                                onClick = { teamId = team.id; teamMenu = false }
                            )
                        }
                    }
                }
                HomeAwaySelector(home, { home = it })
            }
            Field(opponent, { opponent = it }, tr("Opponent"))
            AppOutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(tr("Match date"), style = MaterialTheme.typography.labelSmall)
                    Text(date)
                }
            }
            Field(venue, { venue = it }, tr("Venue"))
            Field(competition, { competition = it }, tr("Competition"))
            Text(tr("Match size"), style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val sizes = MatchSetupRules.supportedMatchSizes
                    sizes.forEachIndexed { index, size ->
                        SegmentedButton(
                            selected = selectedSize == size,
                            onClick = {
                                playersOnPitch = size.toString()
                                formation = MatchSetupRules.defaultFormation(size)
                                formationMenu = false
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, sizes.size),
                            icon = {},
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("${size}v${size}") }
                    }
                }
            }
            Text(tr("Formation"), style = MaterialTheme.typography.titleMedium)
            Box {
                AppOutlinedButton(
                    onClick = { formationMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formation.ifBlank { tr("Choose formation") })
                }
                DropdownMenu(
                    expanded = formationMenu,
                    onDismissRequest = { formationMenu = false }
                ) {
                    formationOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                formation = option
                                formationMenu = false
                            }
                        )
                    }
                }
            }
            Text(tr("Match timing"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    periodCount,
                    { periodCount = it.filter(Char::isDigit).take(1) },
                    tr("Periods"),
                    Modifier.weight(1f)
                )
                Field(
                    periodDuration,
                    { periodDuration = it.filter(Char::isDigit).take(3) },
                    tr("Minutes each"),
                    Modifier.weight(1f)
                )
            }
            Text(
                tr("{selectedSize} players per team", "selectedSize" to selectedSize),
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(rollingSubstitutions, { rollingSubstitutions = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(tr("Rolling substitutions"))
                    Text(
                        if (rollingSubstitutions) tr("Players may return after substitution") else tr("Substituted players cannot return"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            AppButton(
                enabled = teamId != 0L &&
                    opponent.isNotBlank() &&
                    date.isNotBlank() &&
                    MatchSetupRules.isLegalFormation(selectedSize, formation),
                onClick = {
                    vm.addMatch(
                        teamId = teamId,
                        opponent = opponent,
                        date = date,
                        venue = venue,
                        competition = competition,
                        home = home,
                        formation = formation,
                        periodCount = periodCount.toIntOrNull() ?: 2,
                        periodDurationMinutes = periodDuration.toIntOrNull() ?: 45,
                        playersOnPitch = selectedSize,
                        rollingSubstitutions = rollingSubstitutions
                    ) {
                        nav.navigate("squad/$it") {
                            popUpTo("match/new") { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("Save match setup")) }
        }
    }

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
