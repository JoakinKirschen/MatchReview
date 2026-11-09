package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.domain.SeasonReportRules
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.isBusy
import java.util.*

@Composable
internal fun TeamsScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppButton(onClick = { nav.navigate("team/new") }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Add team"))
            }
        }
        if (teams.isEmpty()) item { EmptyCard(tr("Create your first team.")) }
        items(teams) { team ->
            AppCard(Modifier.fillMaxWidth().clickable { nav.navigate("team/${team.id}") }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamLogoImage(team.logoPng, 48.dp, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(team.name, style = MaterialTheme.typography.titleLarge)
                        Text(listOf(team.club, team.ageGroup, team.season).filter { it.isNotBlank() }.joinToString(" • "))
                    }
                }
            }
        }
    }
}

@Composable
internal fun NewTeamScreen(vm: MainViewModel, nav: NavHostController) {
    var name by remember { mutableStateOf("") }
    var club by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("2026/27") }
    FormColumn {
        Field(name, { name = it }, tr("Team name"))
        Field(club, { club = it }, tr("Club"))
        Field(age, { age = it }, tr("Age group"))
        Field(season, { season = it }, tr("Season"))
        AppButton(
            onClick = { vm.addTeam(name, club, age, season) { nav.popBackStack() } },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("Save team")) }
    }
}

@Composable
internal fun TeamScreen(teamId: Long, vm: MainViewModel, nav: NavHostController) {
    val team = vm.teams.collectAsStateWithLifecycle().value.firstOrNull { it.id == teamId }
    val players by vm.playersForTeam(teamId).collectAsStateWithLifecycle(initialValue = emptyList())
    val seasonStatsFlow = remember(teamId) { vm.seasonStats(teamId) }
    val seasonStats by seasonStatsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val seasonReportFlow = remember(teamId) { vm.seasonReport(teamId) }
    val seasonReport by seasonReportFlow.collectAsStateWithLifecycle(initialValue = null)
    var reportMessage by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editingPlayer by remember { mutableStateOf<Player?>(null) }
    var editingStats by remember { mutableStateOf<be.matchreview.app.domain.PlayerSeasonStats?>(null) }
    var showDeleteTeam by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipDeleter = rememberClipDeleter()
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var logoMessage by remember { mutableStateOf<String?>(null) }
    var logoSourceMenu by remember { mutableStateOf(false) }
    fun importLogo(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val encoded = withContext(Dispatchers.IO) { TeamLogoCodec.encodeFromUri(context, uri) }
            if (encoded == null) {
                logoMessage = tr("That file could not be read as an image. Try a PNG or JPEG file.")
            } else {
                vm.setTeamLogo(teamId, encoded)
                logoMessage = null
            }
        }
    }
    val logoFilePicker = rememberLauncherForActivityResult(OpenImageInDownloads(), ::importLogo)
    val reportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val report = seasonReport
        if (uri == null || report == null) return@rememberLauncherForActivityResult
        reportMessage = runCatching {
            context.contentResolver.openOutputStream(uri, "w")!!.use {
                SeasonReportPdfExporter.write(it, report, team)
            }
            if (ExternalApps.open(context, uri, "application/pdf")) null
            else tr("Season report saved. Install a PDF viewer to open it.")
        }.getOrElse { tr("Season report failed: {message}", "message" to (it.message ?: tr("unknown error"))) }
    }
    fun shareSeasonReport(pdf: Boolean) {
        val report = seasonReport ?: return
        reportMessage = runCatching {
            if (pdf) {
                ExternalApps.shareNewFile(
                    context, SeasonReportRules.fileName(report.teamName, "pdf"), "application/pdf",
                    tr("Season report {teamName}", "teamName" to report.teamName)
                ) { SeasonReportPdfExporter.write(it, report, team) }
            } else {
                ExternalApps.shareNewFile(
                    context, SeasonReportRules.fileName(report.teamName, "csv"), "text/csv",
                    tr("Season report {teamName}", "teamName" to report.teamName)
                ) { it.write(SeasonReportRules.toCsv(report).toByteArray()) }
            }
            null
        }.getOrElse { tr("Sharing failed: {message}", "message" to (it.message ?: tr("unknown error"))) }
    }
    val logoPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
        ::importLogo
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (team?.logoPng != null) {
                        TeamLogoImage(team.logoPng, 64.dp)
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(team?.name?.take(2)?.uppercase() ?: "", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            team?.name ?: tr("Team"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box {
                                TextButton(
                                    onClick = { logoSourceMenu = true },
                                    enabled = team != null
                                ) { Text(if (team?.logoPng != null) tr("Change logo") else tr("Add logo")) }
                                DropdownMenu(
                                    expanded = logoSourceMenu,
                                    onDismissRequest = { logoSourceMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(tr("Downloads & files")) },
                                        onClick = {
                                            logoSourceMenu = false
                                            logoFilePicker.launch(arrayOf("image/*"))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Photos")) },
                                        onClick = {
                                            logoSourceMenu = false
                                            logoPhotoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    )
                                }
                            }
                            if (team?.logoPng != null) {
                                TextButton(onClick = { vm.setTeamLogo(teamId, null) }) { Text(tr("Remove")) }
                            }
                        }
                        logoMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Add player")) }
                AppOutlinedButton(
                    onClick = { showDeleteTeam = true },
                    enabled = team != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(tr("Remove team")) }
            }
        }
        if (seasonStats.isNotEmpty()) {
            item { SeasonStatsCard(seasonStats, onEdit = { editingStats = it }) }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("Season report"), style = MaterialTheme.typography.titleMedium)
                        seasonReport?.summary?.let {
                            Text(
                                tr("{played} played • {won}W {drawn}D {lost}L • goals {goalsFor}–{goalsAgainst}", "played" to it.played, "won" to it.won, "drawn" to it.drawn, "lost" to it.lost, "goalsFor" to it.goalsFor, "goalsAgainst" to it.goalsAgainst),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTonalButton(
                                onClick = {
                                    seasonReport?.let { reportPdfLauncher.launch(SeasonReportRules.fileName(it.teamName, "pdf")) }
                                },
                                enabled = seasonReport != null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text(tr("Download PDF")) }
                            AppOutlinedButton(
                                onClick = { shareSeasonReport(pdf = true) },
                                enabled = seasonReport != null,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(tr("Share")) }
                            AppOutlinedButton(
                                onClick = { shareSeasonReport(pdf = false) },
                                enabled = seasonReport != null,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("CSV") }
                        }
                        reportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (players.isEmpty()) item { EmptyCard(tr("No players yet.")) }
        items(players, key = { it.id }) { player ->
            AppCard(
                Modifier
                    .fillMaxWidth()
                    .clickable { editingPlayer = player }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (player.shirtNumber > 0) "#${player.shirtNumber}" else "—",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(player.name, style = MaterialTheme.typography.titleMedium)
                        if (player.position.isNotBlank()) Text(player.position)
                        if (player.preferredFoot.isNotBlank()) {
                            Text(
                                tr("Preferred foot: {preferredFoot}", "preferredFoot" to player.preferredFoot),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(tr("Edit"), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var number by remember { mutableStateOf("") }
        var position by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(tr("Add player")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(name, { name = it }, tr("Name"))
                    Field(number, { number = it.filter(Char::isDigit) }, tr("Shirt number"))
                    Field(position, { position = it }, tr("Position|player"))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.addPlayer(teamId, name, number.toIntOrNull() ?: 0, position) {
                            showAdd = false
                        }
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(tr("Cancel")) }
            }
        )
    }

    editingStats?.let { stats ->
        PlayerStatsDialog(
            stats = stats,
            onDismiss = { editingStats = null },
            onSave = {
                vm.correctSeasonStats(stats, it)
                editingStats = null
            },
            onReset = {
                vm.correctSeasonStats(stats, null)
                editingStats = null
            }
        )
    }

    editingPlayer?.let { player ->
        EditPlayerDialog(
            player = player,
            onDismiss = { editingPlayer = null },
            onSave = {
                vm.updatePlayer(it) { editingPlayer = null }
            },
            onArchive = {
                vm.archivePlayer(player) { editingPlayer = null }
            },
            onDeletePermanently = {
                vm.deletePlayerPermanently(player) { editingPlayer = null }
            }
        )
    }

    if (showDeleteTeam) {
        team?.let { selectedTeam ->
            AlertDialog(
                onDismissRequest = { showDeleteTeam = false },
                title = { Text(tr("Remove {selectedTeamName}?", "selectedTeamName" to selectedTeam.name)) },
                text = {
                    Text(
                        if (cameraState.isBusy)
                            tr("A camera recording is still running or being saved. Stop it before removing the team.")
                        else
                            tr("This permanently removes the team, its players, matches, lineups, saved database records and the clips recorded in this app. Imported videos stay on the device. This cannot be undone.")
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !cameraState.isBusy,
                        onClick = {
                            showDeleteTeam = false
                            vm.loadTeamMedia(selectedTeam.id) { media ->
                                clipDeleter.delete(media.recordingUris) { remaining ->
                                    media.importedVideoUris.forEach {
                                        context.contentResolver.releaseImportedVideo(it)
                                    }
                                    vm.deleteTeam(selectedTeam) {
                                        if (remaining > 0) showClipsRemainingToast(context, remaining)
                                        nav.navigate("teams") {
                                            popUpTo("teams") { inclusive = true }
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(tr("Remove permanently")) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteTeam = false }) { Text(tr("Cancel")) }
                }
            )
        }
    }
}

/** Minutes, goals, assists and saves per player; tap a row to correct it. */
@Composable
internal fun SeasonStatsCard(
    stats: List<be.matchreview.app.domain.PlayerSeasonStats>,
    onEdit: (be.matchreview.app.domain.PlayerSeasonStats) -> Unit
) {
    val fewestMinutes = stats.minOfOrNull { it.wholeMinutes } ?: 0L
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                tr("Season stats"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(tr("Player"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                listOf("M", tr("Min"), "G", "A", "S").forEach {
                    Text(
                        it,
                        Modifier.width(40.dp),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
            HorizontalDivider()
            stats.forEach { row ->
                val fewest = stats.size > 1 && row.wholeMinutes == fewestMinutes
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(row) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        listOfNotNull(
                            row.player.shirtNumber.takeIf { it > 0 }?.let { "#$it" },
                            row.player.name,
                            "✎".takeIf { row.isCorrected }
                        ).joinToString(" "),
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (fewest) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        fontWeight = if (fewest) FontWeight.Bold else FontWeight.Normal
                    )
                    listOf(row.matchesPlayed.toLong(), row.wholeMinutes, row.goals.toLong(), row.assists.toLong(), row.saves.toLong())
                        .forEach {
                            Text(
                                it.toString(),
                                Modifier.width(40.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                }
            }
            Text(
                tr("M matches • Min minutes • G goals • A assists • S saves • ✎ corrected"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

/** Lets the coach correct a player's season totals; the app keeps adding tracked matches on top. */
@Composable
internal fun PlayerStatsDialog(
    stats: be.matchreview.app.domain.PlayerSeasonStats,
    onDismiss: () -> Unit,
    onSave: (be.matchreview.app.domain.StatLine) -> Unit,
    onReset: () -> Unit
) {
    val total = stats.total
    var matches by remember(stats.player.id) { mutableStateOf(total.matches.toString()) }
    var minutes by remember(stats.player.id) { mutableStateOf(total.minutes.toString()) }
    var goals by remember(stats.player.id) { mutableStateOf(total.goals.toString()) }
    var assists by remember(stats.player.id) { mutableStateOf(total.assists.toString()) }
    var saves by remember(stats.player.id) { mutableStateOf(total.saves.toString()) }
    fun digits(value: String) = value.filter(Char::isDigit).take(4)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stats.player.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(matches, { matches = digits(it) }, tr("Matches"), Modifier.weight(1f))
                    Field(minutes, { minutes = digits(it) }, tr("Minutes"), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(goals, { goals = digits(it) }, tr("Goals"), Modifier.weight(1f))
                    Field(assists, { assists = digits(it) }, tr("Assists"), Modifier.weight(1f))
                    Field(saves, { saves = digits(it) }, tr("Saves"), Modifier.weight(1f))
                }
                Text(
                    tr("Tracked: {matches} M • {minutes} min • ", "matches" to stats.tracked.matches, "minutes" to stats.tracked.minutes) +
                        "${stats.tracked.goals} G • ${stats.tracked.assists} A • ${stats.tracked.saves} S",
                    style = MaterialTheme.typography.bodySmall
                )
                if (stats.isCorrected) {
                    TextButton(onClick = onReset) { Text(tr("Reset to tracked")) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    be.matchreview.app.domain.StatLine(
                        matches = matches.toIntOrNull() ?: total.matches,
                        minutes = minutes.toLongOrNull() ?: total.minutes,
                        goals = goals.toIntOrNull() ?: total.goals,
                        assists = assists.toIntOrNull() ?: total.assists,
                        saves = saves.toIntOrNull() ?: total.saves
                    )
                )
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )
}

@Composable
internal fun EditPlayerDialog(
    player: Player,
    onDismiss: () -> Unit,
    onSave: (Player) -> Unit,
    onArchive: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    var name by remember(player.id) { mutableStateOf(player.name) }
    var number by remember(player.id) {
        mutableStateOf(player.shirtNumber.takeIf { it > 0 }?.toString().orEmpty())
    }
    var position by remember(player.id) { mutableStateOf(player.position) }
    var preferredFoot by remember(player.id) { mutableStateOf(player.preferredFoot) }
    var notes by remember(player.id) { mutableStateOf(player.notes) }
    var confirmDelete by remember(player.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit player")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(name, { name = it }, tr("Name"))
                Field(number, { number = it.filter(Char::isDigit).take(3) }, tr("Shirt number"))
                Field(position, { position = it }, tr("Position|player"))
                Field(preferredFoot, { preferredFoot = it }, tr("Preferred foot"))
                Field(notes, { notes = it }, tr("Notes"))
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(tr("Remove player")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        player.copy(
                            name = name,
                            shirtNumber = number.toIntOrNull() ?: 0,
                            position = position,
                            preferredFoot = preferredFoot,
                            notes = notes
                        )
                    )
                }
            ) { Text(tr("Save changes")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Remove {playerName}?", "playerName" to player.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Remove from team: hides the player from the team and upcoming lineups. Goals, minutes and other match history are kept."))
                    Text(tr("Delete permanently: also erases their lineups, minutes played and match links. Past events remain without a player name. This cannot be undone."))
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        confirmDelete = false
                        onArchive()
                    }) { Text(tr("Remove from team")) }
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDeletePermanently()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(tr("Delete permanently")) }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) }
            }
        )
    }
}
