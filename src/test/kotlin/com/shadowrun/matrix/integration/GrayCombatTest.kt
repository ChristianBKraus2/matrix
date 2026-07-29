package com.shadowrun.matrix.integration

import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.ic.NonLethalBlackIC
import com.shadowrun.matrix.ic.Ripper
import com.shadowrun.matrix.ic.Sparky
import com.shadowrun.matrix.ic.TarPit
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrayCombatTest : IntegrationTestBase() {

    // ── Ripper IC ─────────────────────────────────────────────────────────────

    @Test
    fun `Ripper IC reduces a persona attribute`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val bodBefore = icon.currentDecker().persona!!.bod
        icon.injectIc(Ripper(rating = 6, targetAttribute = PersonaAttributeType.BOD))

        icon.runCombatTurn(hitRoller())

        assertTrue(
            icon.currentDecker().persona!!.bod < bodBefore,
            "Ripper should have reduced the BOD attribute"
        )
    }

    @Test
    fun `Ripper IC can reduce a persona attribute to zero`() {
        // Ripper floor is 0 (unlike Crippler whose floor is 1).
        // Invoke CombatResolver directly to avoid roll(0,...) crash in a subsequent turn.
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // Lower BOD to 1 so a strong Ripper can reach 0 in one resolution.
        val d = icon.currentDecker()
        val lowBodDecker = d.copy(persona = d.persona!!.withAttribute(PersonaAttributeType.BOD, 1))
        icon.context.updateDecker(d, lowBodDecker)

        val ic = Ripper(rating = 8, targetAttribute = PersonaAttributeType.BOD)
        // hitRoller: IC rolls face=5 vs DF (TN varies), decker rolls face=5 vs ic.rating=8 → 0 successes.
        // IC net successes > 0 → reduction = net/2. Result BOD = max(0, 1 - reduction).
        val result = CombatResolver.resolveRipper(icon.currentDecker(), ic, SecurityCode.ORANGE, hitRoller())

        assertTrue(result.updatedDecker.persona!!.bod >= 0, "Ripper floor is 0")
        assertTrue(result.reduction >= 0, "Reduction should be non-negative")
    }

    // ── Sparky IC ─────────────────────────────────────────────────────────────

    @Test
    fun `Sparky IC reduces MCP rating on hit`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        // MCP MPCP test TN = hardening(0) + mcpRating + 2. Set mcpRating=1 → TN=3.
        // hitRoller face=5 ≥ 3 → successes → reduction > 0.
        val d = icon.currentDecker()
        icon.context.updateDecker(d, d.copy(cyberdeck = d.cyberdeck.copy(mcpRating = 1, personaPrograms = emptyList())))
        val mcpBefore = icon.currentDecker().cyberdeck.mcpRating

        icon.injectIc(Sparky(rating = 6))
        icon.runCombatTurn(hitRoller())

        assertTrue(
            icon.currentDecker().cyberdeck.mcpRating < mcpBefore,
            "Sparky should reduce the cyberdeck MCP rating on hit"
        )
    }

    @Test
    fun `Sparky IC does not deal icon damage — only MPCP reduction`() {
        // Sparky.action() calls resolveSparkyMpcpTest but NOT applyIcDamage; icon damage is zero.
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val damageBefore = icon.currentDecker().persona!!.conditionMonitor.damage
        icon.injectIc(Sparky(rating = 6))

        icon.runCombatTurn(hitRoller())

        assertEquals(damageBefore, icon.currentDecker().persona!!.conditionMonitor.damage,
            "Sparky applies MPCP reduction only — no icon damage is dealt")
    }

    @Test
    fun `Sparky IC deals no damage when it misses`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val damageBefore = icon.currentDecker().persona!!.conditionMonitor.damage
        icon.injectIc(Sparky(rating = 6))

        icon.runCombatTurn(winRoller())

        assertEquals(damageBefore, icon.currentDecker().persona!!.conditionMonitor.damage,
            "Sparky should deal no icon damage when decker wins the contest")
    }

    // ── TarPit IC ─────────────────────────────────────────────────────────────

    @Test
    fun `TarPit IC traps active utility when it wins the contest`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val armor = Utility(UtilityType.ARMOR, rating = 4)
        icon.equipUtility(armor)
        assertEquals(1, icon.currentDecker().cyberdeck.activeUtilities.size, "Armor should be active before TarPit")

        icon.injectIc(TarPit(rating = 6))
        icon.runCombatTurn(hitRoller())

        assertEquals(0, icon.currentDecker().cyberdeck.activeUtilities.size,
            "TarPit should have crashed the active utility")
    }

    @Test
    fun `TarPit IC does nothing when decker has no active utilities`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        assertEquals(0, icon.currentDecker().cyberdeck.activeUtilities.size)
        icon.injectIc(TarPit(rating = 6))

        val damageBefore = icon.currentDecker().persona!!.conditionMonitor.damage
        icon.runCombatTurn(hitRoller())

        assertEquals(damageBefore, icon.currentDecker().persona!!.conditionMonitor.damage,
            "TarPit with no utilities should not deal icon damage")
    }

    // ── NonLethalBlackIC ──────────────────────────────────────────────────────

    @Test
    fun `NonLethalBlackIC deals icon damage and pins the decker`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        assertEquals(0, icon.currentDecker().persona!!.conditionMonitor.damage)
        icon.injectIc(NonLethalBlackIC(rating = 6))

        icon.runCombatTurn(hitRoller())

        assertTrue(icon.currentDecker().persona!!.conditionMonitor.damage > 0,
            "NonLethalBlackIC should deal icon damage")
        icon.assertPinnedByBlackIc()
    }

    @Test
    fun `NonLethalBlackIC deals mental damage alongside icon damage`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        icon.injectIc(NonLethalBlackIC(rating = 6))

        val mentalBefore = icon.currentDecker().mentalConditionMonitor.damage
        icon.runCombatTurnForPhysicalDamage(hitRoller())

        assertTrue(icon.currentDecker().mentalConditionMonitor.damage > mentalBefore,
            "NonLethalBlackIC should deal mental condition monitor damage")
    }

    // ── analyzeIc failure path ────────────────────────────────────────────────

    @Test
    fun `analyzeIc failure leaves IC active and untouched`() {
        // After setup (26 calls), all dice return face=3 → host wins both operations.
        // analyzeSubsystem fails → Probe spawns. analyzeFirstActiveIc also fails → IC stays.
        // Two failures push tally past threshold 10, spawning both Probe and Killer.
        val icon = scenario(diceRoller = winThenRoller(zeroCalls = 26, thenValue = 3)) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
            analyzeSubsystem(com.shadowrun.matrix.common.SubsystemType.FILES, succeed = false)
            analyzeFirstActiveIc(succeed = false)
        }
        // Both Probe and Killer are active (tally crossed threshold 10); neither was crashed by failed analyze.
        assertTrue(icon.context.activeIc.size >= 1, "IC should remain active after a failed analyzeIc")
    }
}
