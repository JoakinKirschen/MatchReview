package be.matchreview.app

import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.ui.MatchReviewTheme
import be.matchreview.app.domain.MatchExportFormatter
import be.matchreview.app.domain.MatchSetupRules
import be.matchreview.app.domain.VideoEventRules
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.activeMatchId
import be.matchreview.app.recording.isBusy
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MatchReviewTheme { MatchReviewApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchReviewApp(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val immersiveMatchDay = route.startsWith("live/")
    Scaffold(
        topBar = {
            if (!immersiveMatchDay) {
                TopAppBar(
                    title = { Text("MatchReview") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        },
        bottomBar = {
            if (!immersiveMatchDay) {
                NavigationBar {
                    listOf("home" to "Home", "teams" to "Teams", "matches" to "Matches").forEach { (itemRoute, label) ->
                        NavigationBarItem(
                            selected = route == itemRoute,
                            onClick = {
                                nav.navigate(itemRoute) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(if (itemRoute == "home") "●" else if (itemRoute == "teams") "◆" else "▶") },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { DashboardScreen(vm, nav) }
            composable("teams") { TeamsScreen(vm, nav) }
            composable("team/new") { NewTeamScreen(vm, nav) }
            composable("team/{id}") { entry ->
                TeamScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
            }
            composable("matches") { MatchesScreen(vm, nav) }
            composable("backup") { BackupRestoreScreen(vm) }
            composable("match/new") { NewMatchScreen(vm, nav) }
            composable("squad/{id}") { entry ->
                SquadSelectionScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
            }
            composable("lineup/{id}") { entry ->
                LineupBuilderScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
            }
            composable("live/{id}") { entry ->
                LiveMatchScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
            }
            composable("review/{id}") { entry ->
                ReviewScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
            }
        }
    }
}

@Composable
private fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val activeMatch by vm.activeMatch.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Coach dashboard", style = MaterialTheme.typography.headlineMedium)
            Text("Offline match analysis and player feedback")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Teams", teams.size.toString(), Modifier.weight(1f))
                StatCard("Matches", matches.size.toString(), Modifier.weight(1f))
            }
        }
        activeMatch?.let { live ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("live/${live.id}") }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Resume live match", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("vs ${live.opponent} • ${live.status.name.lowercase().replaceFirstChar { it.uppercase() }}")
                        Button(
                            onClick = { nav.navigate("live/${live.id}") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Open match day") }
                    }
                }
            }
        }
        item {
            Button(onClick = { nav.navigate("match/new") }, modifier = Modifier.fillMaxWidth()) {
                Text("Create a match")
            }
            OutlinedButton(onClick = { nav.navigate("team/new") }, modifier = Modifier.fillMaxWidth()) {
                Text("Create a team")
            }
            OutlinedButton(onClick = { nav.navigate("backup") }, modifier = Modifier.fillMaxWidth()) {
                Text("Backup & restore")
            }
        }
        item { Text("Recent matches", style = MaterialTheme.typography.titleLarge) }
        if (matches.isEmpty()) item { EmptyCard("No matches yet.") }
        items(matches.take(5)) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: "Team") {
                nav.navigate("review/${match.id}")
            }
        }
    }
}

@Composable
private fun BackupRestoreScreen(vm: MainViewModel) {
    val operation by vm.backupOperation.collectAsStateWithLifecycle()
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordAgain by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var includeMedia by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri, exportPassword, includeMedia)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedRestoreUri = uri
            confirmRestore = true
        }
    }

    val working = operation is BackupOperationState.Working
    val passwordValid = exportPassword.length >= 6 && exportPassword == exportPasswordAgain

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Backup & restore", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Backups are encrypted on this device. Save the .mrbak file through Android's " +
                "document picker to local storage, Google Drive, OneDrive, or another provider."
        )
        Text(
            "App ${BuildConfig.VERSION_NAME} • database ${AppDatabase.DATABASE_VERSION} • backup format 1",
            style = MaterialTheme.typography.bodySmall
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Create backup", style = MaterialTheme.typography.titleLarge)
                Text("Includes teams, players, matches, lineups, events, ratings and match timing.")
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text("Password (minimum 6 characters)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exportPasswordAgain,
                    onValueChange = { exportPasswordAgain = it },
                    label = { Text("Repeat password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = exportPasswordAgain.isNotEmpty() &&
                        exportPassword != exportPasswordAgain,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeMedia,
                        onCheckedChange = { includeMedia = it }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Include video files")
                        Text(
                            "Optional; backups can become very large. Use only with the club's consent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = {
                        exportLauncher.launch(
                            "MatchReview-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.mrbak"
                        )
                    },
                    enabled = passwordValid && !working,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose backup location") }
                if (!passwordValid && (exportPassword.isNotEmpty() || exportPasswordAgain.isNotEmpty())) {
                    Text(
                        "Passwords must match and contain at least 6 characters.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Restore backup", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Restore replaces all current teams and match data. Existing local videos " +
                        "are not deleted, but their database links are replaced."
                )
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                    enabled = restorePassword.length >= 6 && !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Select backup to restore") }
                Text(
                    "Restore is blocked while a match or recording is active.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        when (val state = operation) {
            BackupOperationState.Idle -> Unit
            is BackupOperationState.Working -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message)
            }
            is BackupOperationState.Success -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(state.message, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.result.teams} teams, ${state.result.players} players, " +
                                "${state.result.matches} matches, ${state.result.mediaFiles} media files"
                        )
                        state.result.warnings.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = vm::clearBackupOperation) { Text("Dismiss") }
                    }
                }
            }
            is BackupOperationState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Backup operation failed", style = MaterialTheme.typography.titleMedium)
                        Text(state.message)
                        TextButton(onClick = vm::clearBackupOperation) { Text("Dismiss") }
                    }
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = {
                confirmRestore = false
                selectedRestoreUri = null
            },
            title = { Text("Replace all current data?") },
            text = {
                Text(
                    "Teams, players, matches and events currently in MatchReview will be replaced " +
                        "by this backup. This cannot be undone unless you first create another backup."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedRestoreUri
                        confirmRestore = false
                        selectedRestoreUri = null
                        if (uri != null) vm.restoreBackup(uri, restorePassword)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Restore and replace") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        selectedRestoreUri = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(18.dp)) {
            Text(value, style = MaterialTheme.typography.headlineLarge)
            Text(label)
        }
    }
}

@Composable
private fun TeamsScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(onClick = { nav.navigate("team/new") }, modifier = Modifier.fillMaxWidth()) {
                Text("Add team")
            }
        }
        if (teams.isEmpty()) item { EmptyCard("Create your first team.") }
        items(teams) { team ->
            Card(Modifier.fillMaxWidth().clickable { nav.navigate("team/${team.id}") }) {
                Column(Modifier.padding(16.dp)) {
                    Text(team.name, style = MaterialTheme.typography.titleLarge)
                    Text(listOf(team.club, team.ageGroup, team.season).filter { it.isNotBlank() }.joinToString(" • "))
                }
            }
        }
    }
}

@Composable
private fun NewTeamScreen(vm: MainViewModel, nav: NavHostController) {
    var name by remember { mutableStateOf("") }
    var club by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("2026/27") }
    FormColumn("New team") {
        Field(name, { name = it }, "Team name")
        Field(club, { club = it }, "Club")
        Field(age, { age = it }, "Age group")
        Field(season, { season = it }, "Season")
        Button(
            onClick = { vm.addTeam(name, club, age, season) { nav.popBackStack() } },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save team") }
    }
}

@Composable
private fun TeamScreen(teamId: Long, vm: MainViewModel, nav: NavHostController) {
    val team = vm.teams.collectAsStateWithLifecycle().value.firstOrNull { it.id == teamId }
    val players by vm.playersForTeam(teamId).collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var editingPlayer by remember { mutableStateOf<Player?>(null) }
    var showDeleteTeam by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipDeleter = rememberClipDeleter()
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(team?.name ?: "Team", style = MaterialTheme.typography.headlineMedium)
            Text("Tap a player to edit their details.", style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showAdd = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Add player") }
                OutlinedButton(
                    onClick = { showDeleteTeam = true },
                    enabled = team != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Remove team") }
            }
        }
        if (players.isEmpty()) item { EmptyCard("No players yet.") }
        items(players, key = { it.id }) { player ->
            Card(
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
                        Text(player.position.ifBlank { "Position not set" })
                        if (player.preferredFoot.isNotBlank()) {
                            Text(
                                "Preferred foot: ${player.preferredFoot}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
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
            title = { Text("Add player") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(name, { name = it }, "Name")
                    Field(number, { number = it.filter(Char::isDigit) }, "Shirt number")
                    Field(position, { position = it }, "Position")
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
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
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
                title = { Text("Remove ${selectedTeam.name}?") },
                text = {
                    Text(
                        if (cameraState.isBusy)
                            "A camera recording is still running or being saved. Stop it before removing the team."
                        else
                            "This permanently removes the team, its players, matches, lineups, saved database records and the clips recorded in this app. Imported videos stay on the device. This cannot be undone."
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
                    ) { Text("Remove permanently") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteTeam = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun EditPlayerDialog(
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
        title = { Text("Edit player") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(name, { name = it }, "Name")
                Field(number, { number = it.filter(Char::isDigit).take(3) }, "Shirt number")
                Field(position, { position = it }, "Position")
                Field(preferredFoot, { preferredFoot = it }, "Preferred foot")
                Field(notes, { notes = it }, "Notes")
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove player") }
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
            ) { Text("Save changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${player.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remove from team: hides the player from the team and upcoming lineups. Goals, minutes and other match history are kept.")
                    Text("Delete permanently: also erases their lineups, minutes played and match links. Past events remain without a player name. This cannot be undone.")
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        confirmDelete = false
                        onArchive()
                    }) { Text("Remove from team") }
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDeletePermanently()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Delete permanently") }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MatchesScreen(vm: MainViewModel, nav: NavHostController) {
    val matches by vm.matches.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(onClick = { nav.navigate("match/new") }, modifier = Modifier.fillMaxWidth()) {
                Text("New match")
            }
        }
        if (matches.isEmpty()) item { EmptyCard("No matches yet.") }
        items(matches) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: "Team") {
                nav.navigate("review/${match.id}")
            }
        }
    }
}

@Composable
private fun MatchCard(match: GameMatch, teamName: String, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Column(Modifier.padding(16.dp)) {
            Text("$teamName vs ${match.opponent}", style = MaterialTheme.typography.titleMedium)
            Text("${match.matchDate} • ${match.formation} • ${if (match.isHome) "Home" else "Away"}")
            Text("${match.periodCount} × ${match.periodDurationMinutes} min • ${match.playersOnPitch} players")
            Text("${match.status.name.replace('_', ' ')} • Score ${match.ourScore}–${match.opponentScore}")
        }
    }
}

@Composable
private fun NewMatchScreen(vm: MainViewModel, nav: NavHostController) {
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
    var previousSettingsApplied by remember { mutableStateOf(false) }

    // Match settings are copied once from the most recently created match. Opponent, date,
    // venue and home/away intentionally remain fresh for every new match.
    LaunchedEffect(matches) {
        if (!previousSettingsApplied) {
            matches.maxByOrNull { it.id }?.let { previous ->
                competition = previous.competition
                val rememberedSize = previous.playersOnPitch
                    .takeIf { it in MatchSetupRules.supportedMatchSizes }
                    ?: 11
                playersOnPitch = rememberedSize.toString()
                formation = MatchSetupRules.legalFormationOrDefault(
                    rememberedSize,
                    previous.formation
                )
                periodCount = previous.periodCount.toString()
                periodDuration = previous.periodDurationMinutes.toString()
                previousSettingsApplied = true
            }
        }
    }

    val selectedSize = playersOnPitch.toIntOrNull() ?: 11
    val formationOptions = MatchSetupRules.formationsFor(selectedSize)

    FormColumn("New match") {
        if (teams.isEmpty()) {
            EmptyCard("Create a team before adding a match.")
            Button(onClick = { nav.navigate("team/new") }) { Text("Create team") }
        } else {
            Box {
                OutlinedButton(onClick = { teamMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(teams.firstOrNull { it.id == teamId }?.name ?: "Choose team")
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
            Field(opponent, { opponent = it }, "Opponent")
            Field(date, { date = it }, "Date (YYYY-MM-DD)")
            Field(venue, { venue = it }, "Venue")
            Field(competition, { competition = it }, "Competition")
            Text("Match size", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MatchSetupRules.supportedMatchSizes.forEach { size ->
                    FilterChip(
                        selected = selectedSize == size,
                        onClick = {
                            playersOnPitch = size.toString()
                            formation = MatchSetupRules.defaultFormation(size)
                            formationMenu = false
                        },
                        label = { Text("${size}v${size}") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text("Formation", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(
                    onClick = { formationMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formation.ifBlank { "Choose formation" })
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
            Text(
                "Only formations with ${selectedSize - 1} outfield players are available.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Match timing", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    periodCount,
                    { periodCount = it.filter(Char::isDigit).take(1) },
                    "Periods",
                    Modifier.weight(1f)
                )
                Field(
                    periodDuration,
                    { periodDuration = it.filter(Char::isDigit).take(3) },
                    "Minutes each",
                    Modifier.weight(1f)
                )
            }
            Text(
                "$selectedSize players per team",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(home, { home = it })
                Spacer(Modifier.width(8.dp))
                Text(if (home) "Home match" else "Away match")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(rollingSubstitutions, { rollingSubstitutions = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Rolling substitutions")
                    Text(
                        if (rollingSubstitutions) "Players may return after substitution" else "Substituted players cannot return",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Button(
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
            ) { Text("Save match setup") }
        }
    }
}

@Composable
private fun ReviewScreen(matchId: Long, vm: MainViewModel, nav: NavHostController) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val events by vm.events(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val participations by vm.participations(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val recordings by vm.recordings(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val allPlayers by vm.players.collectAsStateWithLifecycle()
    val current = match ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val teamPlayers = allPlayers.filter { it.teamId == current.teamId }
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var showTag by remember { mutableStateOf(false) }
    var tagPosition by remember { mutableLongStateOf(0L) }
    var showDeleteMatch by remember { mutableStateOf(false) }
    var pendingEventDeletion by remember { mutableStateOf<MatchEvent?>(null) }
    var selectedClipEvent by remember { mutableStateOf<MatchEvent?>(null) }
    val clipDeleter = rememberClipDeleter()
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()
    val recordingThisMatch = cameraState.activeMatchId == matchId
    val deleteBlocked = current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED) || recordingThisMatch
    var exportContent by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) {
            exportMessage = "Export cancelled"
        } else {
            exportMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                    it.write(exportContent)
                }
                "Export saved"
            }.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
        }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) {
            exportMessage = "PDF download cancelled"
        } else {
            exportMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    MatchPdfExporter.write(
                        output,
                        current,
                        teamPlayers,
                        events,
                        participations,
                        recordings
                    )
                }
                "PDF summary saved"
            }.getOrElse { "PDF export failed: ${it.message ?: "unknown error"}" }
        }
    }

    DisposableEffect(current.videoUri) {
        val exo = current.videoUri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(it)))
                prepare()
            }
        }
        player = exo
        onDispose { exo?.release(); player = null }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setVideo(current.id, uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("vs ${current.opponent}", style = MaterialTheme.typography.headlineMedium)
        Text("${current.matchDate} • ${current.formation}")
        if (current.status in setOf(MatchStatus.LINEUP_READY, MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) {
            Button(
                onClick = { nav.navigate("live/$matchId") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (current.status == MatchStatus.LINEUP_READY) "Open match day" else "Resume live match")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val setupEditable = current.status in setOf(MatchStatus.DRAFT, MatchStatus.LINEUP_READY)
            FilledTonalButton(
                onClick = { nav.navigate("squad/$matchId") },
                enabled = setupEditable,
                modifier = Modifier.weight(1f)
            ) {
                Text("Edit squad")
            }
            FilledTonalButton(
                onClick = { nav.navigate("lineup/$matchId") },
                enabled = setupEditable,
                modifier = Modifier.weight(1f)
            ) {
                Text("Starting lineup")
            }
        }

        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )
        } else {
            Card(Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Import match video") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { tagPosition = player?.currentPosition ?: 0L; showTag = true },
                modifier = Modifier.weight(1f)
            ) { Text("Tag moment") }
            OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                Text("Change video")
            }
        }

        Text("Event timeline", style = MaterialTheme.typography.titleLarge)
        if (events.isEmpty()) EmptyCard("Play the video and tag important moments.")
        events.forEach { event ->
            val playerName = teamPlayers.firstOrNull { it.id == event.playerId }?.name
            // Tags made on the imported video store the video position; live events store
            // match time and can only be shown from the clip recorded in the app.
            val videoTag = VideoEventRules.isImportedVideoTag(event)
            val clipPlayable = !videoTag && VideoEventRules.isPlayable(event, recordings)
            Card(
                Modifier.fillMaxWidth().clickable(enabled = (videoTag && player != null) || clipPlayable) {
                    if (videoTag) {
                        player?.seekTo(event.timestampMs)
                        player?.play()
                    } else {
                        selectedClipEvent = event
                    }
                }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatTime(event.timestampMs)} • ${event.type}", style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(playerName, event.sentiment, event.note.takeIf { it.isNotBlank() }).joinToString(" • "))
                        Text(
                            when {
                                videoTag -> "Imported video position"
                                clipPlayable -> "Match time • tap to play the recorded clip"
                                else -> "Match time • no recorded clip"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { pendingEventDeletion = event }) { Text("Delete") }
                }
            }
        }

        ReviewEditor(current, vm)

        HorizontalDivider()
        Text("Data and privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "Exports contain player names and match details. Share them only with people who are authorised to receive this data.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = {
                exportContent = MatchExportFormatter.toCsv(
                    current,
                    teamPlayers,
                    events,
                    participations,
                    recordings
                )
                exportLauncher.launch(MatchExportFormatter.fileName(current))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export match data (CSV)") }
        FilledTonalButton(
            onClick = {
                pdfExportLauncher.launch(MatchPdfExporter.fileName(current))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Download match summary (PDF)") }
        OutlinedButton(
            onClick = { nav.navigate("backup") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back up all app data") }
        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(
            onClick = { showDeleteMatch = true },
            enabled = !deleteBlocked,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Delete match and recorded clips") }
        if (recordingThisMatch) {
            Text(
                "A camera recording for this match is still running or being saved. Stop it before deleting the match.",
                style = MaterialTheme.typography.bodySmall
            )
        } else if (current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED)) {
            Text(
                "Finish or pause/end the active match before deleting it.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showDeleteMatch) {
        AlertDialog(
            onDismissRequest = { showDeleteMatch = false },
            title = { Text("Permanently delete match?") },
            text = {
                Text(
                    "This removes the match, timeline, player-minute history and ${recordings.count { it.uri != null }} recorded clip(s). " +
                        "This cannot be undone. Imported videos are not deleted."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteBlocked,
                    onClick = {
                        showDeleteMatch = false
                        clipDeleter.delete(recordings.mapNotNull { it.uri }) { remaining ->
                            current.videoUri?.let { context.contentResolver.releaseImportedVideo(it) }
                            vm.deleteMatch(current) {
                                if (remaining > 0) showClipsRemainingToast(context, remaining)
                                nav.popBackStack()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMatch = false }) { Text("Cancel") }
            }
        )
    }

    pendingEventDeletion?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingEventDeletion = null },
            title = { Text("Delete ${event.type.lowercase().replace('_', ' ')}?") },
            text = {
                Text(
                    if (event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL")
                        "The event is removed from the timeline and the score is updated."
                    else "The event is removed from the timeline."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEvent(event)
                    pendingEventDeletion = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingEventDeletion = null }) { Text("Cancel") }
            }
        )
    }

    selectedClipEvent?.let { event ->
        VideoEventPlaybackSheet(
            initialEvent = event,
            events = events,
            recordings = recordings,
            playersById = allPlayers.associateBy { it.id },
            onDismiss = { selectedClipEvent = null }
        )
    }

    if (showTag) {
        EventDialog(
            players = teamPlayers.filterNot { it.archived },
            timestamp = tagPosition,
            onDismiss = { showTag = false },
            onSave = { playerId, type, sentiment, note ->
                vm.addEvent(matchId, playerId, tagPosition, type, sentiment, note)
                showTag = false
            }
        )
    }
}

@Composable
private fun EventDialog(
    players: List<Player>,
    timestamp: Long,
    onDismiss: () -> Unit,
    onSave: (Long?, String, String, String) -> Unit
) {
    val types = listOf("Goal", "Shot", "Chance", "Pass", "Cross", "Corner", "Free kick", "Foul", "Save", "Turnover", "Pressing", "Defensive error", "Set piece", "Custom")
    var type by remember { mutableStateOf("Chance") }
    var sentiment by remember { mutableStateOf("Neutral") }
    var note by remember { mutableStateOf("") }
    var playerId by remember { mutableStateOf<Long?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var playerMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag ${formatTime(timestamp)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(type) }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        types.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { type = item; typeMenu = false })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { playerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(players.firstOrNull { it.id == playerId }?.name ?: "No player")
                    }
                    DropdownMenu(playerMenu, { playerMenu = false }) {
                        DropdownMenuItem(text = { Text("No player") }, onClick = { playerId = null; playerMenu = false })
                        players.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = { playerId = item.id; playerMenu = false })
                        }
                    }
                }
                Row {
                    listOf("Positive", "Neutral", "Improve").forEach {
                        FilterChip(selected = sentiment == it, onClick = { sentiment = it }, label = { Text(it) })
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Field(note, { note = it }, "Note")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(playerId, type, sentiment, note) }) { Text("Save tag") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReviewEditor(match: GameMatch, vm: MainViewModel) {
    // Keyed on the match id so live updates (for example a new goal) never wipe typed notes.
    var ourScore by remember(match.id) { mutableStateOf(match.ourScore.toString()) }
    var theirScore by remember(match.id) { mutableStateOf(match.opponentScore.toString()) }
    var scoreEdited by remember(match.id) { mutableStateOf(false) }
    var rating by remember(match.id) { mutableStateOf(match.teamRating.toString()) }
    var notes by remember(match.id) { mutableStateOf(match.reviewNotes) }
    var saved by remember(match.id) { mutableStateOf(false) }

    // Until the coach edits the score, keep showing the event-derived score.
    LaunchedEffect(match.ourScore, match.opponentScore) {
        if (!scoreEdited) {
            ourScore = match.ourScore.toString()
            theirScore = match.opponentScore.toString()
        }
    }

    HorizontalDivider()
    Text("Match summary", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(ourScore, { ourScore = it.filter(Char::isDigit).take(2); scoreEdited = true; saved = false }, "Our score", Modifier.weight(1f))
        Field(theirScore, { theirScore = it.filter(Char::isDigit).take(2); scoreEdited = true; saved = false }, "Opponent", Modifier.weight(1f))
        Field(rating, { rating = it.filter(Char::isDigit).take(2); saved = false }, "Rating /10", Modifier.weight(1f))
    }
    Text(
        "The score follows the goals on the timeline. Changing it here adds or removes goal events.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it; saved = false },
        label = { Text("What went well, improvements, next training focus") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = {
            val ours = ourScore.toIntOrNull() ?: match.ourScore
            val theirs = theirScore.toIntOrNull() ?: match.opponentScore
            val correction = (ours to theirs).takeIf {
                scoreEdited && (ours != match.ourScore || theirs != match.opponentScore)
            }
            vm.saveReview(match.id, correction, rating.toIntOrNull() ?: 0, notes)
            scoreEdited = false
            saved = true
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (saved) "Saved" else "Save review") }
}

@Composable
private fun FormColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth())
}

@Composable
private fun EmptyCard(message: String) {
    Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(20.dp)) }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun showClipsRemainingToast(context: android.content.Context, remaining: Int) {
    Toast.makeText(
        context,
        "$remaining recorded clip(s) could not be deleted. Remove them from Movies/MatchReview in your gallery.",
        Toast.LENGTH_LONG
    ).show()
}
