package be.matchreview.app

import be.matchreview.app.ui.isLandscape
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.ui.MatchReviewTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import be.matchreview.app.domain.LiveClockRules
import java.util.*

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
