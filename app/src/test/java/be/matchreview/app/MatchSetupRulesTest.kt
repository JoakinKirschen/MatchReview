package be.matchreview.app

import be.matchreview.app.domain.MatchSetupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSetupRulesTest {
    @Test
    fun everyOfferedFormationMatchesSelectedTeamSize() {
        MatchSetupRules.supportedMatchSizes.forEach { size ->
            MatchSetupRules.formationsFor(size).forEach { formation ->
                val outfieldPlayers = formation.split("-").sumOf { it.toInt() }
                assertEquals(size - 1, outfieldPlayers)
                assertTrue(MatchSetupRules.isLegalFormation(size, formation))
            }
        }
    }


    @Test
    fun eightPlayerMatchesOfferTwoFourOne() {
        assertTrue("2-4-1" in MatchSetupRules.formationsFor(8))
        assertTrue(MatchSetupRules.isLegalFormation(8, "2-4-1"))
    }

    @Test
    fun formationFromAnotherMatchSizeIsRejected() {
        assertFalse(MatchSetupRules.isLegalFormation(8, "4-3-3"))
        assertEquals("3-3-1", MatchSetupRules.legalFormationOrDefault(8, "4-3-3"))
    }
}
