package be.matchreview.app

import be.matchreview.app.i18n.tr

import android.graphics.pdf.PdfDocument
import be.matchreview.app.data.Team
import be.matchreview.app.domain.SeasonReport
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** A printable season overview: record, results and per-player numbers. */
object SeasonReportPdfExporter {
    fun write(output: OutputStream, report: SeasonReport, team: Team?) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            TeamLogoCodec.decode(team?.logoPng)?.let { writer.logo(it) }
            writer.heading(report.teamName)
            writer.text(tr("Season report"), emphasized = true)
            writer.text(tr("Created {date}", "date" to (DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()))), small = true)
            writer.spacer(8f)
            with(report.summary) {
                writer.labelValue(tr("Played"), "$played")
                writer.labelValue(tr("Record"), tr("{won} won, {drawn} drawn, {lost} lost", "won" to won, "drawn" to drawn, "lost" to lost))
                writer.labelValue(tr("Goals"), tr("{goalsFor} for, {goalsAgainst} against", "goalsFor" to goalsFor, "goalsAgainst" to goalsAgainst))
            }

            writer.section(tr("Matches"))
            if (report.matches.isEmpty()) {
                writer.text(tr("No finished matches yet."))
            } else {
                writer.table(
                    headers = listOf(tr("Date"), tr("Opponent"), "H/A", tr("Score"), tr("Result")),
                    rows = report.matches.map {
                        listOf(
                            it.date,
                            listOf(it.opponent, it.competition).filter(String::isNotBlank).joinToString(" • "),
                            if (it.isHome) "H" else "A",
                            "${it.ourScore}-${it.opponentScore}",
                            it.result
                        )
                    },
                    weights = listOf(1.3f, 3.4f, 0.6f, 0.9f, 0.8f)
                )
            }

            writer.section(tr("Players"))
            if (report.players.isEmpty()) {
                writer.text(tr("No players yet."))
            } else {
                writer.table(
                    headers = listOf(tr("Player"), "M", tr("Min"), "G", "A", "S", "YC", "RC"),
                    rows = report.players.map {
                        listOf(
                            listOfNotNull(it.shirtNumber.takeIf { n -> n > 0 }?.let { n -> "#$n" }, it.name).joinToString(" "),
                            "${it.matches}", "${it.minutes}", "${it.goals}", "${it.assists}",
                            "${it.saves}", "${it.yellowCards}", "${it.redCards}"
                        )
                    },
                    weights = listOf(3.2f, 0.7f, 0.9f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f)
                )
                writer.text(
                    tr("M matches, Min minutes, G goals, A assists, S saves, YC yellow cards, RC red cards. ") +
                        tr("Totals include corrections made in the app."),
                    small = true
                )
            }

            writer.spacer(10f)
            writer.text(
                tr("Privacy notice: this report contains player names and match data. ") +
                    tr("Share and store it only with appropriate permission."),
                small = true
            )
            writer.finish()
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
