package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.ic.Blaster
import com.shadowrun.matrix.ic.Crippler
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.ic.TarBaby
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.decker.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CombatTest : IntegrationTestBase() {

    /**
     * Two failed analyzeSubsystem calls push the tally to 12, crossing threshold 10 and deploying
     * the Killer IC (guarded_node=FILES). The decker is then placed in the FILES node so that
     * Killer attacks rather than moves. The combat roller (thenValue=5) ensures the Killer hits
     * with a Deadly result, confirming White IC icon damage was applied.
     */
    @Test
    fun `Killer IC attacks the decker after crossing threshold 10 in FILES node`() {
        val setupRoller = winThenRoller(zeroCalls = 26, thenValue = 3)

        val icon = scenario(diceRoller = setupRoller) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            analyzeSubsystem(SubsystemType.FILES, succeed = false)
            navigateToNode(SubsystemType.FILES)
        }

        assertTrue(icon.context.activeIc.any { it is Killer }, "Killer IC should be active after tally crosses threshold 10")

        val deckerBeforeCombat = icon.currentDecker()
        assertEquals(0, deckerBeforeCombat.persona!!.conditionMonitor.damage, "No icon damage before combat")

        val combatRoller = winThenRoller(zeroCalls = 0, thenValue = 5)
        assertTrue(
            icon.runCombatTurn(combatRoller) > 0,
            "Killer IC (White IC) should have dealt icon damage to the decker"
        )
    }

    // --- Group: IC miss ---

    @Test
    fun `Killer IC misses when it rolls zero successes`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(Killer(rating = 6))

        assertEquals(0, icon.runCombatTurn(winRoller()), "Killer IC should deal no icon damage when it misses")
    }

    // --- Group: Crippler IC ---

    @Test
    fun `Crippler IC reduces a persona attribute`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val bodBefore = icon.currentDecker().persona!!.bod
        icon.injectIc(Crippler(rating = 6, targetAttribute = PersonaAttributeType.BOD))

        icon.runCombatTurn(hitRoller())

        assertTrue(
            icon.currentDecker().persona!!.bod < bodBefore,
            "Crippler should have reduced the BOD attribute"
        )
    }

    @Test
    fun `Crippler IC cannot reduce a persona attribute below 1`() {
        // LOW_END decker has BOD=2, Crippler with high rating should reduce to floor of 1
        val icon = scenario(deckerTier = DeckerMock.LOW_END, securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(Crippler(rating = 8, targetAttribute = PersonaAttributeType.BOD))

        icon.runCombatTurn(hitRoller())

        assertTrue(
            icon.currentDecker().persona!!.bod >= 1,
            "Crippler should not reduce BOD below 1"
        )
    }

    // --- Group: TarBaby IC ---

    @Test
    fun `TarBaby IC removes an active utility when it wins the contest`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val medic = Utility(UtilityType.MEDIC, rating = 4)
        icon.equipUtility(medic)
        assertEquals(1, icon.currentDecker().cyberdeck.activeUtilities.size, "Medic should be active before TarBaby")

        icon.injectIc(TarBaby(rating = 6, targetCategory = UtilityCategory.DEFENSIVE))
        icon.runCombatTurn(hitRoller())

        assertEquals(0, icon.currentDecker().cyberdeck.activeUtilities.size, "TarBaby should have crashed the active utility")
    }

    @Test
    fun `TarBaby IC does no damage when decker has no active utilities`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(TarBaby(rating = 6))

        assertEquals(0, icon.currentDecker().cyberdeck.activeUtilities.size, "No utilities present")
        // TarBaby fires but finds nothing to trap — decker state should be unchanged
        val iconDamageBefore = icon.currentDecker().persona!!.conditionMonitor.damage
        icon.runCombatTurn(hitRoller())
        assertEquals(iconDamageBefore, icon.currentDecker().persona!!.conditionMonitor.damage, "TarBaby with no utilities should not deal icon damage")
    }

    // --- Group: Blaster IC (MPCP reduction) ---

    @Test
    fun `Blaster IC reduces cyberdeck MCP rating on hit`() {
        // Need Blaster MPCP test TN = hardening(0) + mcpRating = 5 so hitRoller (face 5) beats it.
        // Start with HIGH_END decker, strip persona programs (so no constraint), set mcpRating=5.
        // Blaster(rating=2): main attack with 2 dice (face 5) vs TN 4 (ORANGE/INTRUDING) → 2 successes → hits.
        // MPCP test: 2 dice (face 5) vs TN 5 → 2 successes → reduction = 2/2 = 1 → new mcp = 4.
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // Replace cyberdeck: no persona programs, mcpRating=5 (constraint: 0 ≤ 5×3=15 ✓)
        val d = icon.currentDecker()
        val strippedDeck = d.cyberdeck.copy(mcpRating = 5, personaPrograms = emptyList())
        icon.context.updateDecker(d, d.copy(cyberdeck = strippedDeck))
        val mcpBefore = icon.currentDecker().cyberdeck.mcpRating
        assertEquals(5, mcpBefore)

        icon.injectIc(Blaster(rating = 2))
        icon.runCombatTurn(hitRoller())

        assertTrue(
            icon.currentDecker().cyberdeck.mcpRating < mcpBefore,
            "Blaster should have reduced the cyberdeck MCP rating"
        )
    }

    // --- Group: Lethal Black IC ---

    @Test
    fun `LethalBlackIC deals icon damage and pins the decker`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        assertEquals(0, icon.currentDecker().persona!!.conditionMonitor.damage, "No icon damage before Black IC")
        icon.injectIc(LethalBlackIC(rating = 6))

        icon.runCombatTurn(hitRoller())

        assertTrue(icon.currentDecker().persona!!.conditionMonitor.damage > 0, "LethalBlackIC should deal icon damage")
        icon.assertPinnedByBlackIc()
    }

    @Test
    fun `LethalBlackIC deals physical damage alongside icon damage`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(LethalBlackIC(rating = 6))

        val physicalDamage = icon.runCombatTurnForPhysicalDamage(hitRoller())
        assertTrue(physicalDamage > 0, "LethalBlackIC should deal physical body damage")
    }

    @Test
    fun `decker pinned by Black IC cannot jack out`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(LethalBlackIC(rating = 6))
        icon.runCombatTurn(hitRoller())
        icon.assertPinnedByBlackIc()

        assertFailsWith<IllegalStateException>("Pinned decker should not be able to jack out") {
            icon.currentDecker().jackOut(pinnedByBlackIc = true)
        }
    }

    // --- Group: invokeMediac ---

    @Test
    fun `invokeMediac repairs icon damage after combat`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // Apply 3 boxes of icon damage directly (equivalent to a MODERATE hit)
        val d = icon.currentDecker()
        val damagedPersona = d.persona!!.copy(conditionMonitor = d.persona.conditionMonitor.applyDamage(3))
        val damagedDecker = d.copy(persona = damagedPersona)
        icon.context.updateDecker(d, damagedDecker)
        assertEquals(3, icon.currentDecker().persona!!.conditionMonitor.damage, "Setup: decker must have icon damage")

        // Equip Medic and invoke — hitRoller (face 5) beats Medic TN 4 → repairs boxes
        icon.equipUtility(Utility(UtilityType.MEDIC, rating = 6))
        val medicResult = icon.currentDecker().invokeMediac(hitRoller())
        assertTrue(medicResult.boxesRepaired > 0, "Medic should repair at least one box")
        assertEquals(3 - medicResult.boxesRepaired, medicResult.updatedDecker.persona!!.conditionMonitor.damage)
    }
}
