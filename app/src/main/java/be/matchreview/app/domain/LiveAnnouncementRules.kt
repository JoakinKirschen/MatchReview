package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

/** Avoids announcing a ticking clock every second to screen-reader users. */
object LiveAnnouncementRules {
    fun shouldAnnounceClock(previousMs: Long, currentMs: Long): Boolean {
        if (currentMs < previousMs) return true
        return previousMs / 60_000L != currentMs / 60_000L
    }

    fun scoreAnnouncement(ourScore: Int, opponentScore: Int): String =
        tr("Score {ourScore} to {opponentScore}", "ourScore" to ourScore, "opponentScore" to opponentScore)
}
