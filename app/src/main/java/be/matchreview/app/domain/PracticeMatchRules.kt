package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.Team

data class PracticePlayerTemplate(val name: String, val shirtNumber: Int, val position: String)

object PracticeMatchRules {
    const val TEAM_NAME = "Practice team"
    const val SEASON = "Practice"

    fun isPracticeTeam(team: Team): Boolean = team.name == TEAM_NAME && team.season == SEASON

    // Built when asked, so the names are in the language active at that moment.
    val players: List<PracticePlayerTemplate> get() = listOf(
        PracticePlayerTemplate(tr("Practice goalkeeper"), 1, "GK"),
        PracticePlayerTemplate(tr("Practice defender 1"), 2, "DF"),
        PracticePlayerTemplate(tr("Practice defender 2"), 3, "DF"),
        PracticePlayerTemplate(tr("Practice midfielder 1"), 4, "MF"),
        PracticePlayerTemplate(tr("Practice midfielder 2"), 5, "MF"),
        PracticePlayerTemplate(tr("Practice winger 1"), 6, "MF"),
        PracticePlayerTemplate(tr("Practice winger 2"), 7, "MF"),
        PracticePlayerTemplate(tr("Practice striker"), 9, "FW")
    )
}
