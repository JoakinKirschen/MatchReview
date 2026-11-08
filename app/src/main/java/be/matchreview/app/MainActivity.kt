package be.matchreview.app

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import be.matchreview.app.ui.isLandscape
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import be.matchreview.app.ui.ThemeMode
import be.matchreview.app.domain.MatchActions
import be.matchreview.app.domain.MatchExportFormatter
import be.matchreview.app.domain.MatchStatsRules
import be.matchreview.app.domain.HighlightClip
import be.matchreview.app.domain.HighlightRules
import be.matchreview.app.domain.MatchVideoRules
import be.matchreview.app.recording.HighlightReel
import be.matchreview.app.recording.HighlightReelMaker
import be.matchreview.app.domain.SeasonReportRules
import be.matchreview.app.domain.ExportPrivacyOptions
import be.matchreview.app.domain.MediaIntegrityRules
import be.matchreview.app.domain.MatchIntegrityRules
import be.matchreview.app.domain.SeasonSummaryRules
import be.matchreview.app.domain.PracticeMatchRules
import be.matchreview.app.domain.MatchFormatMemory
import be.matchreview.app.domain.BackupEstimate
import be.matchreview.app.backup.AutoBackupRules
import be.matchreview.app.domain.MatchSetupRules
import be.matchreview.app.domain.VideoEventRules
import be.matchreview.app.domain.GoalMouthGeometry
import be.matchreview.app.domain.GoalSummaryRules
import be.matchreview.app.domain.StartingLineupRules
import be.matchreview.app.domain.TimelineGrouping
import be.matchreview.app.domain.TimelineItem
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import be.matchreview.app.domain.LiveClockRules
import kotlinx.coroutines.withContext
import be.matchreview.app.recording.CameraRecordingController
import be.matchreview.app.recording.activeMatchId
import be.matchreview.app.recording.activeRecordingId
import be.matchreview.app.recording.isBusy
import java.text.SimpleDateFormat
import java.util.*

private enum class ReviewSection(val label: String) {
    SUMMARY("Report"),
    TIMELINE("Timeline"),
    VIDEO("Video"),
    DATA("Data")
}

class MainActivity : ComponentActivity() {
    /** A live match to open, from a tap on the match clock notification. */
    private val openLiveMatch = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val vm: MainViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val openLiveMatchId by openLiveMatch.collectAsStateWithLifecycle()
            MatchReviewTheme(themeMode) {
                MatchReviewApp(vm, openLiveMatchId, onLiveMatchOpened = { openLiveMatch.value = null })
            }
        }
        // Bring the notification clock back when the app reopens during a match.
        lifecycleScope.launch {
            val active = (application as MatchReviewApplication).repository.activeMatch.first()
            if (active != null && active.status in LiveClockRules.ACTIVE_STATUSES) {
                MatchClockService.start(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val matchId = intent?.getLongExtra(EXTRA_OPEN_LIVE_MATCH, 0L) ?: 0L
        if (matchId > 0L) openLiveMatch.value = matchId
        intent?.removeExtra(EXTRA_OPEN_LIVE_MATCH)
    }

    companion object {
        const val EXTRA_OPEN_LIVE_MATCH = "open_live_match_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchReviewApp(
    vm: MainViewModel = viewModel(),
    openLiveMatchId: Long? = null,
    onLiveMatchOpened: () -> Unit = {},
    /** A screen to open right away, for example "lineup/3"; used by the screenshot test. */
    initialRoute: String? = null
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val immersiveMatchDay = route.startsWith("live/")
    val topLevelRoutes = setOf("home", "teams", "matches")
    val isTopLevel = route in topLevelRoutes
    val screenTitle = when {
        route == "home" -> "MatchReview"
        route == "teams" -> "Teams"
        route == "matches" -> "Matches"
        route == "team/new" -> "New team"
        route.startsWith("team/") -> "Team details"
        route == "match/new" -> "New match"
        route.startsWith("squad/") -> "Select squad"
        route.startsWith("lineup/") -> "Starting lineup"
        route.startsWith("ready/") -> "Match-day check"
        route.startsWith("review/") -> "Match review"
        route == "backup" -> "Backup & restore"
        else -> "MatchReview"
    }
    val landscape = isLandscape()
    val topLevelItems = listOf(
        Triple("home", R.string.nav_home, Icons.Default.Home),
        Triple("teams", R.string.nav_teams, Icons.Default.Groups),
        Triple("matches", R.string.nav_matches, Icons.Default.SportsSoccer)
    )
    fun openTopLevel(itemRoute: String) {
        nav.navigate(itemRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        topBar = {
            if (!immersiveMatchDay) {
                TopAppBar(
                    title = { Text(screenTitle) },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { nav.navigateUp() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.navigate_back)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        },
        bottomBar = {
            if (!immersiveMatchDay && isTopLevel && !landscape) {
                NavigationBar {
                    topLevelItems.forEach { (itemRoute, labelResource, icon) ->
                        val label = stringResource(labelResource)
                        NavigationBarItem(
                            selected = route == itemRoute,
                            onClick = { openTopLevel(itemRoute) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            // Held sideways, the sections move to a rail on the left so the screens keep their height.
            if (!immersiveMatchDay && isTopLevel && landscape) {
                NavigationRail {
                    topLevelItems.forEach { (itemRoute, labelResource, icon) ->
                        val label = stringResource(labelResource)
                        NavigationRailItem(
                            selected = route == itemRoute,
                            onClick = { openTopLevel(itemRoute) },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                composable("ready/{id}") { entry ->
                    MatchReadinessScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
                }
                composable("live/{id}") { entry ->
                    LiveMatchScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
                }
                composable("review/{id}") { entry ->
                    ReviewScreen(entry.arguments?.getString("id")!!.toLong(), vm, nav)
                }
            }
        }
        // Only after NavHost has set up its graph; navigating earlier throws.
        LaunchedEffect(initialRoute) {
            initialRoute?.let { nav.navigate(it) }
        }
        LaunchedEffect(openLiveMatchId) {
            openLiveMatchId?.let { matchId ->
                nav.navigate("live/$matchId") { launchSingleTop = true }
                onLiveMatchOpened()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(vm: MainViewModel, nav: NavHostController) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Teams", teams.size.toString(), Modifier.weight(1f))
                StatCard("Matches", matches.size.toString(), Modifier.weight(1f))
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
                    Text("Completed matches", style = MaterialTheme.typography.titleMedium)
                    if (teams.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = summaryTeamId == 0L,
                                    onClick = { summaryTeamId = 0L },
                                    label = { Text(if (realTeams.size == teams.size) "All teams" else "All real teams") }
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
                    Text("${summary.played} played • ${summary.won} won • ${summary.drawn} drawn • ${summary.lost} lost")
                    Text("Goals ${summary.goalsFor}-${summary.goalsAgainst}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val backupText = when {
                autoBackup.running -> "Automatic backup running…"
                autoBackup.lastError != null -> "Automatic backup failed: ${autoBackup.lastError}"
                backupDue -> "A match finished since your last backup. Back up now to keep it safe."
                else -> lastBackupEpochMs?.let {
                    "Last backup ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(it))}"
                } ?: "No successful backup yet"
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
                        Text("Data protection", style = MaterialTheme.typography.titleMedium)
                        Text(backupText, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Open")
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
                        Text("Resume live match", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("vs ${live.opponent} • ${live.status.name.lowercase().replaceFirstChar { it.uppercase() }}")
                        AppButton(
                            onClick = { nav.navigate("live/${live.id}") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("Open match day") }
                    }
                }
            }
        }
        item {
            // Teams and backups have their own tab and card; only match actions are repeated here.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(onClick = { nav.navigate("match/new") }, modifier = Modifier.weight(1f)) {
                    Text("New match", maxLines = 1)
                }
                if (activeMatch == null) {
                    AppOutlinedButton(
                        onClick = { vm.createPracticeMatch { matchId -> nav.navigate("squad/$matchId") } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Practice match", maxLines = 1) }
                }
            }
        }
        item { Text("Recent matches", style = MaterialTheme.typography.titleLarge) }
        if (matches.isEmpty()) item { EmptyCard("No matches yet.") }
        items(matches.take(5)) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: "Team") {
                nav.navigate(matchDestination(match))
            }
        }
    }
}

@Composable
private fun BackupRestoreScreen(vm: MainViewModel) {
    val operation by vm.backupOperation.collectAsStateWithLifecycle()
    val previewState by vm.restorePreview.collectAsStateWithLifecycle()
    val lastBackupEpochMs by vm.lastBackupEpochMs.collectAsStateWithLifecycle()
    val lastBackupIncludedMedia by vm.lastBackupIncludedMedia.collectAsStateWithLifecycle()
    val recordedVideoBytes by vm.totalRecordingBytes.collectAsStateWithLifecycle()
    val recordingCount by vm.recordingCount.collectAsStateWithLifecycle()

    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordAgain by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var includeMedia by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showExportPassword by remember { mutableStateOf(false) }
    var showRestorePassword by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri, exportPassword, includeMedia)
    }
    val autoBackup by vm.autoBackupState.collectAsStateWithLifecycle()
    var autoBackupMessage by remember { mutableStateOf<String?>(null) }
    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder ->
        if (folder != null) {
            autoBackupMessage = vm.enableAutoBackup(folder, exportPassword, includeMedia)
            if (autoBackupMessage == null) {
                exportPassword = ""
                exportPasswordAgain = ""
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedRestoreUri = uri
            vm.inspectBackup(uri, restorePassword)
        }
    }

    LaunchedEffect(operation) {
        if (operation is BackupOperationState.Success) {
            exportPassword = ""
            exportPasswordAgain = ""
            restorePassword = ""
        }
    }

    val working = operation is BackupOperationState.Working ||
        previewState is RestorePreviewState.Working
    val passwordValid = exportPassword.length >= 6 && exportPassword == exportPasswordAgain
    val lastBackupLabel = lastBackupEpochMs?.let {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "Never backed up"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Encrypted backups to this phone, Google Drive or another storage app.")
        Text(
            "App ${BuildConfig.VERSION_NAME} • database ${AppDatabase.DATABASE_VERSION} • backup format ${be.matchreview.app.backup.MatchBackupManager.BACKUP_FORMAT_VERSION}",
            style = MaterialTheme.typography.bodySmall
        )

        AppCard(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Backup status", style = MaterialTheme.typography.titleMedium)
                Text(lastBackupLabel)
                if (lastBackupEpochMs != null) {
                    Text(
                        if (lastBackupIncludedMedia) "Last backup included videos" else "Last backup excluded videos",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Create backup", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text("Password (6+ characters)") },
                    visualTransformation = if (showExportPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showExportPassword = !showExportPassword }) {
                            Text(if (showExportPassword) "Hide" else "Show")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exportPasswordAgain,
                    onValueChange = { exportPasswordAgain = it },
                    label = { Text("Repeat password") },
                    visualTransformation = if (showExportPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    isError = exportPasswordAgain.isNotEmpty() &&
                        exportPassword != exportPasswordAgain,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The password cannot be recovered by MatchReview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeMedia,
                        onCheckedChange = { includeMedia = it }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Include video files")
                        Text(
                            "Much larger; only with the club's consent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    if (includeMedia) {
                        "Estimated backup: ${BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, true))} " +
                            "including $recordingCount recorded clip(s)."
                    } else {
                        "Estimated backup: ${BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, false))}; videos excluded."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                AppButton(
                    onClick = {
                        exportLauncher.launch(
                            "MatchReview-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.mrbak"
                        )
                    },
                    enabled = passwordValid && !working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
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

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Automatic backup", style = MaterialTheme.typography.titleMedium)
                if (!autoBackup.enabled) {
                    Text(
                        "After every finished match, into a folder you pick once (for example on Google Drive). " +
                            "Uses the password above; the newest ${AutoBackupRules.KEEP} are kept.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppButton(
                        onClick = { autoBackupFolderLauncher.launch(null) },
                        enabled = passwordValid && !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Choose folder and turn on") }
                    if (!passwordValid) {
                        Text("Enter and repeat a password above first.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Folder: ${autoBackup.folderName ?: "chosen folder"}")
                    Text(
                        when {
                            autoBackup.running -> "Backing up…"
                            autoBackup.lastError != null -> "Last automatic backup failed: ${autoBackup.lastError}"
                            autoBackup.lastSuccessEpochMs != null -> "Last automatic backup " +
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(autoBackup.lastSuccessEpochMs!!))
                            else -> "Runs after the next finished match."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoBackup.lastError != null && !autoBackup.running) {
                            MaterialTheme.colorScheme.error
                        } else LocalContentColor.current
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoBackup.includeMedia,
                            onCheckedChange = vm::setAutoBackupIncludesMedia
                        )
                        Text("Include video files", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTonalButton(
                            onClick = vm::runAutoBackupNow,
                            enabled = !autoBackup.running,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Back up now") }
                        AppOutlinedButton(
                            onClick = vm::disableAutoBackup,
                            enabled = !autoBackup.running,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Turn off") }
                    }
                }
                autoBackupMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Restore backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The selected backup is decrypted and inspected before you are asked to replace current data."
                )
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = { Text("Backup password") },
                    visualTransformation = if (showRestorePassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showRestorePassword = !showRestorePassword }) {
                            Text(if (showRestorePassword) "Hide" else "Show")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = restorePassword.length >= 6 && !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Select and inspect backup") }
                Text(
                    "Restore is blocked while a match or recording is active.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (previewState is RestorePreviewState.Working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Decrypting and validating backup…")
        }
        if (previewState is RestorePreviewState.Error) {
            val error = previewState as RestorePreviewState.Error
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Backup could not be inspected", style = MaterialTheme.typography.titleMedium)
                    Text(error.message)
                    TextButton(
                        onClick = {
                            selectedRestoreUri = null
                            vm.clearRestorePreview()
                        }
                    ) { Text("Dismiss") }
                }
            }
        }

        when (val state = operation) {
            BackupOperationState.Idle -> Unit
            is BackupOperationState.Working -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message)
            }
            is BackupOperationState.Success -> {
                AppCard(
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
                AppCard(
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

    val readyPreview = (previewState as? RestorePreviewState.Ready)?.preview
    if (readyPreview != null) {
        AlertDialog(
            onDismissRequest = {
                selectedRestoreUri = null
                vm.clearRestorePreview()
            },
            title = { Text("Review backup before restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Created: ${readyPreview.createdAtUtc}")
                    Text("Source app: ${readyPreview.appVersionName} (${readyPreview.appVersionCode})")
                    Text(
                        "Versions: database ${readyPreview.databaseVersion}, " +
                            "backup ${readyPreview.backupFormatVersion}"
                    )
                    Text(
                        "${readyPreview.teams} teams • ${readyPreview.players} players • " +
                            "${readyPreview.matches} matches"
                    )
                    Text(
                        if (readyPreview.includesMedia) {
                            "${readyPreview.mediaFiles} video files included"
                        } else {
                            "Videos are not included"
                        }
                    )
                    HorizontalDivider()
                    Text(
                        "Restoring replaces all current teams and match data. Cancel now and create " +
                            "a safety backup first if needed.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                AppButton(
                    onClick = {
                        val uri = selectedRestoreUri
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
                        selectedRestoreUri = null
                        vm.clearRestorePreview()
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TeamsScreen(vm: MainViewModel, nav: NavHostController) {
    val teams by vm.teams.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppButton(onClick = { nav.navigate("team/new") }, modifier = Modifier.fillMaxWidth()) {
                Text("Add team")
            }
        }
        if (teams.isEmpty()) item { EmptyCard("Create your first team.") }
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
private fun NewTeamScreen(vm: MainViewModel, nav: NavHostController) {
    var name by remember { mutableStateOf("") }
    var club by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("2026/27") }
    FormColumn {
        Field(name, { name = it }, "Team name")
        Field(club, { club = it }, "Club")
        Field(age, { age = it }, "Age group")
        Field(season, { season = it }, "Season")
        AppButton(
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
                logoMessage = "That file could not be read as an image. Try a PNG or JPEG file."
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
            else "Season report saved. Install a PDF viewer to open it."
        }.getOrElse { "Season report failed: ${it.message ?: "unknown error"}" }
    }
    fun shareSeasonReport(pdf: Boolean) {
        val report = seasonReport ?: return
        reportMessage = runCatching {
            if (pdf) {
                ExternalApps.shareNewFile(
                    context, SeasonReportRules.fileName(report.teamName, "pdf"), "application/pdf",
                    "Season report ${report.teamName}"
                ) { SeasonReportPdfExporter.write(it, report, team) }
            } else {
                ExternalApps.shareNewFile(
                    context, SeasonReportRules.fileName(report.teamName, "csv"), "text/csv",
                    "Season report ${report.teamName}"
                ) { it.write(SeasonReportRules.toCsv(report).toByteArray()) }
            }
            null
        }.getOrElse { "Sharing failed: ${it.message ?: "unknown error"}" }
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
                            team?.name ?: "Team",
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
                                ) { Text(if (team?.logoPng != null) "Change logo" else "Add logo") }
                                DropdownMenu(
                                    expanded = logoSourceMenu,
                                    onDismissRequest = { logoSourceMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Downloads & files") },
                                        onClick = {
                                            logoSourceMenu = false
                                            logoFilePicker.launch(arrayOf("image/*"))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Photos") },
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
                                TextButton(onClick = { vm.setTeamLogo(teamId, null) }) { Text("Remove") }
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
                ) { Text("Add player") }
                AppOutlinedButton(
                    onClick = { showDeleteTeam = true },
                    enabled = team != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Remove team") }
            }
        }
        if (seasonStats.isNotEmpty()) {
            item { SeasonStatsCard(seasonStats, onEdit = { editingStats = it }) }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Season report", style = MaterialTheme.typography.titleMedium)
                        seasonReport?.summary?.let {
                            Text(
                                "${it.played} played • ${it.won}W ${it.drawn}D ${it.lost}L • goals ${it.goalsFor}–${it.goalsAgainst}",
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
                            ) { Text("Download PDF") }
                            AppOutlinedButton(
                                onClick = { shareSeasonReport(pdf = true) },
                                enabled = seasonReport != null,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Share") }
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
        if (players.isEmpty()) item { EmptyCard("No players yet.") }
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

/** Minutes, goals, assists and saves per player; tap a row to correct it. */
@Composable
private fun SeasonStatsCard(
    stats: List<be.matchreview.app.domain.PlayerSeasonStats>,
    onEdit: (be.matchreview.app.domain.PlayerSeasonStats) -> Unit
) {
    val fewestMinutes = stats.minOfOrNull { it.wholeMinutes } ?: 0L
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                "Season stats",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text("Player", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                listOf("M", "Min", "G", "A", "S").forEach {
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
                "M matches • Min minutes • G goals • A assists • S saves • ✎ corrected",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

/** Lets the coach correct a player's season totals; the app keeps adding tracked matches on top. */
@Composable
private fun PlayerStatsDialog(
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
                    Field(matches, { matches = digits(it) }, "Matches", Modifier.weight(1f))
                    Field(minutes, { minutes = digits(it) }, "Minutes", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(goals, { goals = digits(it) }, "Goals", Modifier.weight(1f))
                    Field(assists, { assists = digits(it) }, "Assists", Modifier.weight(1f))
                    Field(saves, { saves = digits(it) }, "Saves", Modifier.weight(1f))
                }
                Text(
                    "Tracked: ${stats.tracked.matches} M • ${stats.tracked.minutes} min • " +
                        "${stats.tracked.goals} G • ${stats.tracked.assists} A • ${stats.tracked.saves} S",
                    style = MaterialTheme.typography.bodySmall
                )
                if (stats.isCorrected) {
                    TextButton(onClick = onReset) { Text("Reset to tracked") }
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
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
                Text("New match")
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search matches") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("ALL" to "All", "ACTIVE" to "Active", "DRAFT" to "Drafts", "FINISHED" to "Finished")) {
                    FilterChip(
                        selected = statusFilter == it.first,
                        onClick = { statusFilter = it.first },
                        label = { Text(it.second) }
                    )
                }
            }
        }
        if (matches.isEmpty()) item { EmptyCard("No matches yet.") }
        else if (visibleMatches.isEmpty()) item { EmptyCard("No matches match these filters.") }
        items(visibleMatches, key = { it.id }) { match ->
            MatchCard(match, teams.firstOrNull { it.id == match.teamId }?.name ?: "Team") {
                nav.navigate(matchDestination(match))
            }
        }
    }
}

private fun matchDestination(match: GameMatch): String = when (match.status) {
    MatchStatus.DRAFT -> "squad/${match.id}"
    MatchStatus.LINEUP_READY -> "ready/${match.id}"
    MatchStatus.LIVE,
    MatchStatus.PAUSED,
    MatchStatus.PERIOD_ENDED -> "live/${match.id}"
    MatchStatus.FINISHED,
    MatchStatus.ABANDONED,
    MatchStatus.CANCELLED -> "review/${match.id}"
}

private fun matchNextAction(match: GameMatch): String = when (match.status) {
    MatchStatus.DRAFT -> "Continue squad setup"
    MatchStatus.LINEUP_READY -> "Open match day"
    MatchStatus.LIVE -> "Resume live match"
    MatchStatus.PAUSED -> "Resume paused match"
    MatchStatus.PERIOD_ENDED ->
        if (match.currentPeriod < match.periodCount) "Start next period" else "Finish match"
    MatchStatus.FINISHED -> "Review match"
    MatchStatus.ABANDONED -> "Review abandoned match"
    MatchStatus.CANCELLED -> "Review cancelled match"
}

@Composable
private fun MatchCard(match: GameMatch, teamName: String, open: () -> Unit) {
    val started = match.status !in setOf(MatchStatus.DRAFT, MatchStatus.LINEUP_READY, MatchStatus.CANCELLED)
    AppCard(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "$teamName vs ${match.opponent}",
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
private fun MatchStatusBadge(status: MatchStatus) {
    val (label, color) = when (status) {
        MatchStatus.DRAFT -> "Draft" to MaterialTheme.colorScheme.surfaceVariant
        MatchStatus.LINEUP_READY -> "Ready" to MaterialTheme.colorScheme.tertiaryContainer
        MatchStatus.LIVE -> "Live" to MaterialTheme.colorScheme.errorContainer
        MatchStatus.PAUSED -> "Paused" to MaterialTheme.colorScheme.errorContainer
        MatchStatus.PERIOD_ENDED -> "Break" to MaterialTheme.colorScheme.errorContainer
        MatchStatus.FINISHED -> "Finished" to MaterialTheme.colorScheme.primaryContainer
        MatchStatus.ABANDONED -> "Abandoned" to MaterialTheme.colorScheme.surfaceVariant
        MatchStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.surfaceVariant
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
            EmptyCard("Create a team before adding a match.")
            AppButton(onClick = { nav.navigate("team/new") }) { Text("Create team") }
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
                            teams.firstOrNull { it.id == teamId }?.name ?: "Choose team",
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
            Field(opponent, { opponent = it }, "Opponent")
            AppOutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Match date", style = MaterialTheme.typography.labelSmall)
                    Text(date)
                }
            }
            Field(venue, { venue = it }, "Venue")
            Field(competition, { competition = it }, "Competition")
            Text("Match size", style = MaterialTheme.typography.titleMedium)
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
            Text("Formation", style = MaterialTheme.typography.titleMedium)
            Box {
                AppOutlinedButton(
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
            ) { Text("Save match setup") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(matchId: Long, vm: MainViewModel, nav: NavHostController) {
    val match by vm.match(matchId).collectAsStateWithLifecycle(initialValue = null)
    val events by vm.events(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val participations by vm.participations(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val lineup by vm.lineup(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val recordings by vm.recordings(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val periods by vm.periods(matchId).collectAsStateWithLifecycle(initialValue = emptyList())
    val allPlayers by vm.players.collectAsStateWithLifecycle()
    val teams by vm.teams.collectAsStateWithLifecycle()
    val backupDue by vm.backupDue.collectAsStateWithLifecycle()
    var showDetailsEditor by remember { mutableStateOf(false) }
    var showFullVideo by rememberSaveable { mutableStateOf(false) }
    var goalPositionEvent by remember { mutableStateOf<MatchEvent?>(null) }
    val current = match ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val teamPlayers = allPlayers.filter { it.teamId == current.teamId }
    val team = teams.firstOrNull { it.id == current.teamId }
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var showTag by remember { mutableStateOf(false) }
    var videoImportMessage by remember { mutableStateOf<String?>(null) }
    var tagPosition by remember { mutableLongStateOf(0L) }
    var showDeleteMatch by remember { mutableStateOf(false) }
    var pendingEventDeletion by remember { mutableStateOf<MatchEvent?>(null) }
    var selectedClipEvent by remember { mutableStateOf<MatchEvent?>(null) }
    var reviewSection by rememberSaveable { mutableStateOf(ReviewSection.SUMMARY) }
    val clipDeleter = rememberClipDeleter()
    val cameraState by CameraRecordingController.state.collectAsStateWithLifecycle()
    val recordingThisMatch = cameraState.activeMatchId == matchId
    val mediaIssues = remember(recordings, cameraState.activeRecordingId) {
        MediaIntegrityRules.inspect(recordings, cameraState.activeRecordingId, cameraState.activeMatchId)
    }
    val integrityIssues = remember(current, events, participations, recordings) {
        MatchIntegrityRules.inspect(current, events, emptyList(), participations, recordings)
    }
    val deleteBlocked = current.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED) || recordingThisMatch
    var exportContent by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var redactedExport by rememberSaveable { mutableStateOf(true) }
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
    fun writeSummaryPdf(output: java.io.OutputStream) = MatchPdfExporter.write(
        output, current, team, teamPlayers, events, participations, recordings, lineup
    )
    val pdfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) {
            exportMessage = "PDF download cancelled"
        } else {
            exportMessage = runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use(::writeSummaryPdf)
                // Open the saved summary straight away so it can be checked and shared.
                if (ExternalApps.open(context, uri, "application/pdf")) "PDF summary saved"
                else "PDF summary saved. Install a PDF viewer to open it."
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
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (persisted.isSuccess) {
                vm.setVideo(current.id, uri.toString())
                videoImportMessage = "Video linked for future review."
            } else {
                videoImportMessage =
                    "This provider did not grant lasting access. Choose a local file or another provider."
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogoImage(team?.logoPng, 48.dp, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (current.isHome) "${team?.name ?: "Home"} vs ${current.opponent}"
                    else "${current.opponent} vs ${team?.name ?: "Away"}",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    listOf(
                        current.matchDate,
                        if (current.isHome) "Home" else "Away",
                        current.venue,
                        current.competition,
                        current.formation
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { showDetailsEditor = true }) { Text("Edit") }
        }
        if (current.status == MatchStatus.FINISHED && backupDue) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "This match is not in a backup yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { nav.navigate("backup") }) { Text("Back up now") }
                }
            }
        }
        if (integrityIssues.isNotEmpty()) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Data integrity check", style = MaterialTheme.typography.titleMedium)
                    integrityIssues.take(3).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (mediaIssues.isNotEmpty()) {
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Recording check", style = MaterialTheme.typography.titleMedium)
                    mediaIssues.take(3).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (current.status in setOf(MatchStatus.LINEUP_READY, MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED)) {
            AppButton(
                onClick = {
                    nav.navigate(
                        if (current.status == MatchStatus.LINEUP_READY) "ready/$matchId"
                        else "live/$matchId"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (current.status == MatchStatus.LINEUP_READY) "Check match readiness" else "Resume live match")
            }
        }
        // Squad and lineup can only change before kick-off; afterwards the buttons are hidden.
        if (current.status in setOf(MatchStatus.DRAFT, MatchStatus.LINEUP_READY)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { nav.navigate("squad/$matchId") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit squad")
                }
                AppTonalButton(
                    onClick = { nav.navigate("lineup/$matchId") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Starting lineup")
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReviewSection.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = reviewSection == section,
                    onClick = { reviewSection = section },
                    shape = SegmentedButtonDefaults.itemShape(index, ReviewSection.entries.size),
                    icon = {},
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(section.label, maxLines = 1) }
            }
        }

        if (reviewSection == ReviewSection.VIDEO) {
        val matchClips = remember(recordings, periods) { MatchVideoRules.clips(recordings, periods) }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Match recording", style = MaterialTheme.typography.titleMedium)
                if (matchClips.isEmpty()) {
                    Text(
                        "Record the match with the camera in the live screen to watch it here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "${matchClips.size} clip(s) • ${formatTime(MatchVideoRules.totalDurationMs(matchClips))}. " +
                            "Plays all clips one after another; jump to any period or event.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppButton(
                        onClick = { showFullVideo = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Watch full match") }
                }
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
            AppCard(Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Import match video") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                onClick = { tagPosition = player?.currentPosition ?: 0L; showTag = true },
                enabled = player != null,
                modifier = Modifier.weight(1f)
            ) { Text("Tag moment") }
            AppOutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) {
                Text("Change video")
            }
        }
        HighlightsCard(current, events, recordings)
        videoImportMessage?.let { message ->
            AppCard(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.startsWith("Video linked")) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { videoImportMessage = null }) { Text("Dismiss") }
                }
            }
        }
        }

        if (reviewSection == ReviewSection.TIMELINE) {
        Text("Event timeline", style = MaterialTheme.typography.titleLarge)
        if (events.isEmpty()) EmptyCard("Play the video and tag important moments.")
        val playersById = allPlayers.associateBy { it.id }
        // Live events use match time while imported-video tags use the video position, so
        // they are listed separately instead of being sorted into one misleading order.
        val (videoTags, matchEvents) = StartingLineupRules
            .withStartingLineup(current.id, events, participations, lineup)
            .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
            .partition(VideoEventRules::isImportedVideoTag)
        listOf(
            Triple("Match events", "Times are match time.", TimelineGrouping.group(matchEvents)),
            Triple(
                "Imported video tags",
                "Times are positions in the imported video.",
                videoTags.map { TimelineItem.Single(it) }
            )
        ).filter { it.third.isNotEmpty() }.forEach { (sectionTitle, sectionHint, sectionItems) ->
        Text(sectionTitle, style = MaterialTheme.typography.titleMedium)
        Text(sectionHint, style = MaterialTheme.typography.bodySmall)
        sectionItems.forEach { item ->
            if (item is TimelineItem.LineupChange) {
                val first = item.events.first()
                LineupChangeCard(
                    change = item,
                    playersById = playersById,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (VideoEventRules.isPlayable(first, recordings)) {
                                TextButton(onClick = { selectedClipEvent = first }) { Text("▶ Clip") }
                            }
                        }
                    }
                )
                return@forEach
            }
            val event = (item as TimelineItem.Single).event
            val playerName = teamPlayers.firstOrNull { it.id == event.playerId }?.name
            // Tags made on the imported video store the video position; live events store
            // match time and can only be shown from the clip recorded in the app.
            val videoTag = VideoEventRules.isImportedVideoTag(event)
            val clipPlayable = !videoTag && VideoEventRules.isPlayable(event, recordings)
            val scoring = event.type == "OUR_GOAL" || event.type == "OPPONENT_GOAL"
            AppCard(
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
                        Text("${formatTime(event.timestampMs)} • ${reviewEventLabel(event.type)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                playerName,
                                event.sentiment,
                                event.note.takeIf { it.isNotBlank() },
                                GoalMouthGeometry.describe(event.goalX, event.goalY)?.let { "goal position: $it" }
                            ).joinToString(" • ")
                        )
                        Text(
                            when {
                                videoTag -> "Imported video position"
                                clipPlayable -> "Match time • tap to play the recorded clip"
                                else -> "Match time • no recorded clip"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (scoring) {
                            TextButton(onClick = { goalPositionEvent = event }) { Text("Position") }
                        }
                        TextButton(onClick = { pendingEventDeletion = event }) { Text("Delete") }
                    }
                }
            }
        }
        }
        }

        if (reviewSection == ReviewSection.SUMMARY) {
            val goalLines = GoalSummaryRules.lines(
                events,
                allPlayers.associate { it.id to it.name },
                team?.name ?: "Our team",
                current.opponent
            )
            if (goalLines.isNotEmpty()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Goals • ${current.ourScore}–${current.opponentScore}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        goalLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            val matchStats = MatchStatsRules.compute(events)
            if (matchStats.hasDetail) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Match stats", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1.4f))
                            Text(team?.name ?: "Our team", Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.opponent, Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MatchStatsRules.rows(matchStats).forEach { (label, ours, theirs) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(label, Modifier.weight(1.4f))
                                Text("$ours", Modifier.weight(1f), textAlign = TextAlign.End)
                                Text("$theirs", Modifier.weight(1f), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            val saves = events.filter { it.type == "KEEPER_SAVE" }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Goalkeeping", style = MaterialTheme.typography.titleMedium)
                    Text("${saves.size} save(s) • ${current.opponentScore} goal(s) conceded")
                    saves.groupBy { it.playerId }.forEach { (keeperId, keeperSaves) ->
                        Text(
                            "${teamPlayers.firstOrNull { it.id == keeperId }?.name ?: "Keeper not assigned"}: ${keeperSaves.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            GoalMap(events, current.opponent, Modifier.fillMaxWidth())
            ReviewEditor(current, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { pdfExportLauncher.launch(MatchPdfExporter.fileName(current)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Download PDF summary") }
                AppOutlinedButton(
                    onClick = {
                        exportMessage = runCatching {
                            ExternalApps.shareNewFile(
                                context,
                                MatchPdfExporter.fileName(current),
                                "application/pdf",
                                "Match summary vs ${current.opponent}",
                                ::writeSummaryPdf
                            )
                            null
                        }.getOrElse { "PDF sharing failed: ${it.message ?: "unknown error"}" }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Share") }
            }
            exportMessage?.takeIf { it.startsWith("PDF") }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (reviewSection == ReviewSection.DATA) {
        HorizontalDivider()
        Text("Data and privacy", style = MaterialTheme.typography.titleLarge)
        Text(
            "Exports contain player names and match details. Share them only with people who are authorised to receive this data.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Privacy-safe CSV", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (redactedExport) "Player names, notes, and media locations are removed."
                    else "Identifiable player data will be included.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = redactedExport, onCheckedChange = { redactedExport = it })
        }
        AppButton(
            onClick = {
                exportContent = MatchExportFormatter.toCsv(
                    current,
                    teamPlayers,
                    events,
                    participations,
                    recordings,
                    ExportPrivacyOptions(
                        includePlayerNames = !redactedExport,
                        includeNotes = !redactedExport,
                        includeMediaUris = false
                    )
                )
                exportLauncher.launch(MatchExportFormatter.fileName(current))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export match data (CSV)") }
        AppOutlinedButton(
            onClick = { nav.navigate("backup") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back up all app data") }
        exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        AppOutlinedButton(
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

    goalPositionEvent?.let { event ->
        GoalPlacementDialog(
            title = if (event.type == "OPPONENT_GOAL") "Where did ${current.opponent} score?"
            else "Where did ${team?.name ?: "your team"} score?",
            initialX = event.goalX,
            initialY = event.goalY,
            onSkip = { goalPositionEvent = null },
            onSave = { x, y ->
                vm.setGoalPlacement(event.id, x, y)
                goalPositionEvent = null
            }
        )
    }

    if (showFullVideo) {
        MatchVideoDialog(
            title = if (current.isHome) "${team?.name ?: "Home"} vs ${current.opponent}"
            else "${current.opponent} vs ${team?.name ?: "Away"}",
            recordings = recordings,
            periods = periods,
            events = events,
            playersById = allPlayers.associateBy { it.id },
            onDismiss = { showFullVideo = false }
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

/** Joins the recorded goal clips into one video to watch or share. */
@Composable
private fun HighlightsCard(match: GameMatch, events: List<MatchEvent>, recordings: List<RecordingSegment>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ourClips = remember(events, recordings) { HighlightRules.clips(events, recordings, HighlightRules.OUR_GOALS) }
    val allClips = remember(events, recordings) { HighlightRules.clips(events, recordings, HighlightRules.ALL_GOALS) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var reel by remember { mutableStateOf<HighlightReel?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun make(clips: List<HighlightClip>, label: String) {
        progress = 0f
        message = null
        reel = null
        scope.launch {
            runCatching {
                val opponent = match.opponent.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                HighlightReelMaker.make(
                    context,
                    clips,
                    "matchreview-$label-${match.matchDate}-${opponent.ifBlank { "match" }}.mp4"
                ) { progress = it }
            }.onSuccess {
                reel = it
                message = buildString {
                    append("${formatTime(it.durationMs)} video saved")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) append(" to Movies/MatchReview")
                    if (it.skipped > 0) append(". ${it.skipped} clip(s) recorded with other settings were left out")
                }
            }.onFailure {
                message = "Highlights failed: ${it.message ?: "unknown error"}"
            }
            progress = null
        }
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Goal highlights", style = MaterialTheme.typography.titleMedium)
            if (allClips.isEmpty()) {
                Text(
                    "Record the match with the camera in the live screen to make a video of all the goals.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }
            Text(
                "Joins about ${HighlightRules.BEFORE_MS / 1000} s before and ${HighlightRules.AFTER_MS / 1000} s after " +
                    "each recorded goal into one video.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTonalButton(
                    onClick = { make(ourClips, "our-goals") },
                    enabled = progress == null && ourClips.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Our goals (${ourClips.sumOf { it.eventIds.size }})") }
                AppOutlinedButton(
                    onClick = { make(allClips, "goals") },
                    enabled = progress == null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("All goals (${allClips.sumOf { it.eventIds.size }})") }
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            reel?.let { saved ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        onClick = {
                            if (!ExternalApps.open(context, saved.uri, "video/mp4")) message = "Install a video player to watch it."
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Play") }
                    AppOutlinedButton(
                        onClick = { ExternalApps.share(context, saved.uri, "video/mp4", "Goals vs ${match.opponent}") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Share") }
                }
            }
        }
    }
}

private fun reviewEventLabel(type: String): String = MatchActions.label(type)
    ?.let { if (MatchActions.isOpponent(type)) "Opponent ${it.lowercase()}" else it }
    ?: when (type) {
    "OUR_GOAL" -> "Our goal"
    "OPPONENT_GOAL" -> "Opponent goal"
    "SUBSTITUTION" -> "Substitution"
    "KICK_OFF" -> "Kick-off"
    "KEEPER_SAVE" -> "Keeper save"
    "POSITION_CHANGE" -> "Positions changed"
    "PLAYER_ON" -> "Player on"
    "PLAYER_OFF" -> "Player off"
    "INJURY_OFF" -> "Injury"
    "DISMISSAL" -> "Red card (sent off)"
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
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
                    AppOutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(type) }
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        types.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { type = item; typeMenu = false })
                        }
                    }
                }
                Box {
                    AppOutlinedButton(onClick = { playerMenu = true }, modifier = Modifier.fillMaxWidth()) {
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
    var saving by remember(match.id) { mutableStateOf(false) }

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
    AppButton(
        onClick = {
            val ours = ourScore.toIntOrNull() ?: match.ourScore
            val theirs = theirScore.toIntOrNull() ?: match.opponentScore
            val correction = (ours to theirs).takeIf {
                scoreEdited && (ours != match.ourScore || theirs != match.opponentScore)
            }
            saving = true
            vm.saveReview(match.id, correction, rating.toIntOrNull() ?: 0, notes) {
                scoreEdited = false
                saving = false
                saved = true
            }
        },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(if (saving) "Saving…" else if (saved) "Saved" else "Save review") }
}

/** A scrolling form; the screen title is already in the top bar. */
@Composable
private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth())
}

@Composable
private fun EmptyCard(message: String) {
    AppCard(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(16.dp)) }
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
