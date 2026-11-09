package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchStatus
import java.util.Locale

/** One finished match in the season report. */
data class SeasonMatchLine(
    val date: String,
    val opponent: String,
    val isHome: Boolean,
    val competition: String,
    val ourScore: Int,
    val opponentScore: Int
) {
    val result: String
        get() = when {
            ourScore > opponentScore -> "W"
            ourScore < opponentScore -> "L"
            else -> "D"
        }
}

/** One player's season line, including cards. */
data class SeasonPlayerLine(
    val shirtNumber: Int,
    val name: String,
    val matches: Int,
    val minutes: Long,
    val goals: Int,
    val assists: Int,
    val saves: Int,
    val yellowCards: Int,
    val redCards: Int
)

data class SeasonReport(
    val teamName: String,
    val summary: SeasonSummary,
    val matches: List<SeasonMatchLine>,
    val players: List<SeasonPlayerLine>
)

object SeasonReportRules {
    fun build(
        teamName: String,
        matches: List<GameMatch>,
        playerStats: List<PlayerSeasonStats>,
        events: List<MatchEvent>
    ): SeasonReport {
        val finished = matches
            .filter { it.status == MatchStatus.FINISHED }
            .sortedWith(compareBy<GameMatch> { it.matchDate }.thenBy { it.id })
        // Cards count for the same matches as the player stats: played or being played.
        val countedMatchIds = matches
            .filter { it.status in setOf(MatchStatus.LIVE, MatchStatus.PAUSED, MatchStatus.PERIOD_ENDED, MatchStatus.FINISHED) }
            .mapTo(mutableSetOf()) { it.id }
        val cardEvents = events.filter { it.matchId in countedMatchIds }
        return SeasonReport(
            teamName = teamName,
            summary = SeasonSummaryRules.summarize(finished),
            matches = finished.map {
                SeasonMatchLine(it.matchDate, it.opponent, it.isHome, it.competition, it.ourScore, it.opponentScore)
            },
            players = playerStats.map { stats ->
                val own = cardEvents.filter { it.playerId == stats.player.id }
                SeasonPlayerLine(
                    shirtNumber = stats.player.shirtNumber,
                    name = stats.player.name,
                    matches = stats.matchesPlayed,
                    minutes = stats.wholeMinutes,
                    goals = stats.goals,
                    assists = stats.assists,
                    saves = stats.saves,
                    yellowCards = own.count { it.type == MatchActions.YELLOW_CARD },
                    redCards = own.count { it.type == MatchActions.RED_CARD || it.type == MatchActions.DISMISSAL }
                )
            }
        )
    }

    fun fileName(teamName: String, extension: String): String {
        val slug = teamName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "team" }
        return "matchreview-season-$slug.$extension"
    }

    /** The report as CSV with three blocks: record, matches and players. */
    fun toCsv(report: SeasonReport): String = buildList {
        add(row(tr("Season report"), report.teamName))
        add("")
        add(row(tr("Played"), tr("Won"), tr("Drawn"), tr("Lost"), tr("Goals for"), tr("Goals against")))
        with(report.summary) {
            add(row("$played", "$won", "$drawn", "$lost", "$goalsFor", "$goalsAgainst"))
        }
        add("")
        add(row(tr("Date"), tr("Opponent"), "Home/Away", tr("Competition"), tr("Score"), tr("Result")))
        report.matches.forEach {
            add(row(it.date, it.opponent, if (it.isHome) tr("Home") else tr("Away"), it.competition,
                "${it.ourScore}-${it.opponentScore}", it.result))
        }
        add("")
        add(row(tr("Number"), tr("Player"), tr("Matches"), tr("Minutes"), tr("Goals"), tr("Assists"), tr("Saves"), tr("Yellow cards"), tr("Red cards")))
        report.players.forEach {
            add(row(it.shirtNumber.takeIf { number -> number > 0 }?.toString().orEmpty(), it.name,
                "${it.matches}", "${it.minutes}", "${it.goals}", "${it.assists}", "${it.saves}",
                "${it.yellowCards}", "${it.redCards}"))
        }
    }.joinToString("\r\n", postfix = "\r\n")

    private fun row(vararg cells: String) = cells.joinToString(",") { csv(it) }

    /** Quotes every cell and stops spreadsheet apps from running names as formulas. */
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@') && value.toIntOrNull() == null) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
    }
}
