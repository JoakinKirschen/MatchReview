package be.matchreview.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.matchreview.app.domain.FormationLayout
import be.matchreview.app.ui.MatchReviewTheme
import be.matchreview.app.ui.ThemeMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNG files in build/screenshots so layout changes can be
 * checked without a device. Runs only when the SCREENSHOTS environment variable is 1;
 * the CI workflow sets it when started with the "screenshots" input.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h760dp-hdpi")
open class ScreenshotTest {
    /** Added to every file name, so other screen shapes do not overwrite these. */
    protected open val namePrefix = ""

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: MatchReviewApplication
        get() = ApplicationProvider.getApplicationContext()

    private data class Ids(val teamId: Long, val finishedId: Long, val liveId: Long, val upcomingId: Long)

    @Before
    fun onlyWhenRequested() {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
    }

    private fun fixture(): Ids = runBlocking {
        val repository = app.repository
        val dao = app.database.matchDao()
        val teamId = repository.addTeam("U11 Lions", "FC Example", "U11", "2026-27")
        listOf(
            "Noah Peeters", "Lucas Janssens", "Liam Maes", "Louis Jacobs", "Arthur Mertens",
            "Jules Willems", "Adam Claes", "Victor Goossens", "Finn Wouters", "Mila De Smet"
        ).forEachIndexed { index, name -> repository.addPlayer(teamId, name, index + 1, "") }
        val players = dao.getPlayersForTeam(teamId)

        suspend fun match(opponent: String, date: String): Long {
            val id = repository.addMatch(
                teamId, opponent, date, "Sportpark", "League", true, "2-4-1",
                periodCount = 4, periodDurationMinutes = 15, playersOnPitch = 8, rollingSubstitutions = true
            )
            repository.ensureMatchSquad(id, teamId)
            players.forEach { repository.setSquadSelected(id, it.id, true) }
            repository.ensureLineup(id)
            repository.autoPlaceLineup(id, players, FormationLayout.slots("2-4-1", 8))
            return id
        }

        val start = 3_600_000L
        val wall = 1_790_000_000_000L
        val finishedId = match("Rivals FC", "2026-09-26")
        repository.markLineupReady(finishedId)
        repository.kickOffMatch(finishedId, start, wall)
        repository.recordOurGoal(finishedId, players[7].id, players[5].id, start + 300_000, wall + 300_000)
        repository.recordKeeperSave(finishedId, players[0].id, start + 500_000, wall + 500_000)
        repository.recordOpponentGoal(finishedId, start + 700_000, wall + 700_000)
        repository.recordOurGoal(finishedId, players[6].id, null, start + 820_000, wall + 820_000)
        // Two saved clips, so the full match video has something to list.
        listOf(0L to 420_000L, 420_000L to 480_000L).forEach { (clockStart, duration) ->
            val clip = repository.beginRecordingSegment(finishedId, clockStart, 90, audioEnabled = false)
            repository.markRecordingStarted(clip, wall + clockStart)
            repository.completeRecordingSegment(
                clip, "content://media/external/video/media/$clip", clockStart + duration, duration, 50_000_000L
            )
        }
        repository.finishLiveMatch(finishedId, start + 900_000, wall + 900_000)

        val liveId = match("City Juniors", "2026-10-03")
        repository.markLineupReady(liveId)
        repository.kickOffMatch(liveId, start, wall)
        repository.recordOurGoal(liveId, players[7].id, players[4].id, start + 400_000, wall + 400_000)
        repository.pauseMatchClock(liveId, start + 754_000, wall + 754_000)

        val upcomingId = match("Northside", "2026-10-10")
        Ids(teamId, finishedId, liveId, upcomingId)
    }

    private fun show(route: String?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val vm = remember { MainViewModel(app) }
            MatchReviewTheme(ThemeMode.LIGHT) { MatchReviewApp(vm, initialRoute = route) }
        }
        settle()
    }

    /** Lets database queries arrive and navigation transitions finish. */
    private fun settle(frames: Int = 90) {
        repeat(frames) {
            Thread.sleep(15)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun click(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        settle(40)
    }

    private fun hideTextureViews(view: android.view.View) {
        if (view is android.view.TextureView) view.visibility = android.view.View.INVISIBLE
        if (view is android.view.ViewGroup) (0 until view.childCount).forEach { hideTextureViews(view.getChildAt(it)) }
    }

    /** The newest window on screen, such as an open dialog. */
    private fun topmostWindow(): android.view.View {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        return views.last() as android.view.View
    }

    /** [topmost] captures the newest window, such as an open dialog. */
    private fun capture(name: String, topmost: Boolean = false) {
        settle(20)
        val bitmap = runCatching {
            (if (topmost) compose.onAllNodes(isRoot()).onLast() else compose.onRoot())
                .captureToImage().asAndroidBitmap()
        }.getOrElse {
            val view = if (topmost) topmostWindow() else compose.activity.window.decorView
            // A TextureView (the video picture) only draws on the GPU; leave it blank here.
            hideTextureViews(view)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$namePrefix$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dashboard() { fixture(); show(null); capture("01-dashboard") }

    @Test fun teams() { fixture(); show("teams"); capture("02-teams") }

    @Test fun team() { val ids = fixture(); show("team/${ids.teamId}"); capture("03-team") }

    @Test fun matches() { fixture(); show("matches"); capture("04-matches") }

    @Test fun newMatch() { fixture(); show("match/new"); capture("05-new-match") }

    @Test fun squad() { val ids = fixture(); show("squad/${ids.upcomingId}"); capture("06-squad") }

    @Test fun lineup() { val ids = fixture(); show("lineup/${ids.upcomingId}"); capture("07-lineup") }

    @Test fun readiness() { val ids = fixture(); show("ready/${ids.upcomingId}"); capture("08-ready") }

    @Test fun liveMatch() { val ids = fixture(); show("live/${ids.liveId}"); capture("09-live-match") }

    @Test
    fun liveCamera() {
        val ids = fixture()
        show("live/${ids.liveId}")
        click(app.getString(R.string.camera_tab))
        capture("10-live-camera")
    }

    @Test
    fun liveTimeline() {
        val ids = fixture()
        show("live/${ids.liveId}")
        click(app.getString(R.string.timeline_tab))
        capture("11-live-timeline")
    }

    @Test
    fun substitutionMode() {
        val ids = fixture()
        show("live/${ids.liveId}")
        click("Substitution mode")
        capture("12-substitution-mode")
    }

    @Test fun review() { val ids = fixture(); show("review/${ids.finishedId}"); capture("13-review") }

    @Test fun backup() { fixture(); show("backup"); capture("14-backup") }

    @Test
    fun fullMatchVideo() {
        val ids = fixture()
        show("review/${ids.finishedId}")
        click("Video")
        capture("15-review-video")
        click("Watch full match")
        capture("16-full-match-video", topmost = true)
    }
}

/** The same screens on a phone held sideways. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w760dp-h360dp-land-hdpi")
class LandscapeScreenshotTest : ScreenshotTest() {
    override val namePrefix = "land-"
}
