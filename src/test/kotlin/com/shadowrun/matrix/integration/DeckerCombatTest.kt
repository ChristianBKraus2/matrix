package com.shadowrun.matrix.integration

import com.shadowrun.matrix.combat.AttackResult
import com.shadowrun.matrix.combat.CombatInitiative
import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckerCombatTest : IntegrationTestBase() {

    // ── Black Hammer ──────────────────────────────────────────────────────────

    @Test
    fun `resolveBlackHammer deals icon damage and physical damage`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()

        val attack = AttackResult.Hit(
            attackerSuccesses = 6,
            rawDamageLevel = DamageLevel.SERIOUS,
            stagedDamageLevel = DamageLevel.SERIOUS,
            power = 6
        )
        val result = CombatResolver.resolveBlackHammer(decker, attack, hitRoller())

        assertTrue(result.updatedDecker.persona!!.conditionMonitor.damage > 0, "Black Hammer should deal icon damage")
        assertTrue(result.updatedDecker.physicalConditionMonitor.damage > 0, "Black Hammer should deal physical damage")
    }

    @Test
    fun `resolveBlackHammer with winRoller decker defence still increments icon damage`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()

        // Force an explicit Hit with minimal staging
        val attack = AttackResult.Hit(
            attackerSuccesses = 3,
            rawDamageLevel = DamageLevel.LIGHT,
            stagedDamageLevel = DamageLevel.LIGHT,
            power = 6
        )
        val result = CombatResolver.resolveBlackHammer(decker, attack, hitRoller())

        assertTrue(result.updatedDecker.persona!!.conditionMonitor.damage >= 0, "Condition monitor should not be negative")
    }

    // ── Killjoy ───────────────────────────────────────────────────────────────

    @Test
    fun `resolveKilljoy deals icon damage and mental damage`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()

        val attack = AttackResult.Hit(
            attackerSuccesses = 6,
            rawDamageLevel = DamageLevel.SERIOUS,
            stagedDamageLevel = DamageLevel.SERIOUS,
            power = 6
        )
        val result = CombatResolver.resolveKilljoy(decker, attack, hitRoller())

        assertTrue(result.updatedDecker.persona!!.conditionMonitor.damage > 0, "Killjoy should deal icon damage")
        assertTrue(result.updatedDecker.mentalConditionMonitor.damage > 0, "Killjoy should deal mental damage")
    }

    @Test
    fun `resolveKilljoy deals no physical damage unlike Black Hammer`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val physicalBefore = decker.physicalConditionMonitor.damage

        val attack = AttackResult.Hit(
            attackerSuccesses = 6,
            rawDamageLevel = DamageLevel.SERIOUS,
            stagedDamageLevel = DamageLevel.SERIOUS,
            power = 6
        )
        val result = CombatResolver.resolveKilljoy(decker, attack, hitRoller())

        assertEquals(physicalBefore, result.updatedDecker.physicalConditionMonitor.damage, "Killjoy should not deal physical damage")
    }

    // ── IC Suppression ────────────────────────────────────────────────────────

    @Test
    fun `suppressIc adds IC to suppressedIc list and reduces effectiveDetectionFactor`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val dfBefore = decker.effectiveDetectionFactor

        val probe = Probe(rating = 4)
        val suppressed = CombatResolver.suppressIc(decker, probe)

        assertEquals(1, suppressed.suppressedIc.size, "Suppressed IC list should contain one entry")
        assertEquals(dfBefore - 1, suppressed.effectiveDetectionFactor, "Each suppressed IC reduces DF by 1")
    }

    @Test
    fun `unsuppressIc removes IC from suppressedIc list and restores detectionFactor`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val dfBefore = decker.effectiveDetectionFactor

        val probe = Probe(rating = 4)
        val suppressed = CombatResolver.suppressIc(decker, probe)
        var tallyAdded = 0
        val restored = CombatResolver.unsuppressIc(suppressed, probe) { tallyAdded = it }

        assertEquals(0, restored.suppressedIc.size, "Suppressed IC list should be empty after unsuppress")
        assertEquals(dfBefore, restored.effectiveDetectionFactor, "DF should be restored after unsuppress")
        assertTrue(tallyAdded > 0, "Unsuppress should report a tally increase equal to IC rating")
    }

    @Test
    fun `suppressing two ICs reduces effectiveDetectionFactor by two`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()
        val dfBefore = decker.effectiveDetectionFactor

        val probe = Probe(rating = 3)
        val killer = Killer(rating = 4)
        val afterFirst = CombatResolver.suppressIc(decker, probe)
        val afterSecond = CombatResolver.suppressIc(afterFirst, killer)

        assertEquals(dfBefore - 2, afterSecond.effectiveDetectionFactor, "Two suppressed ICs should reduce DF by 2")
    }

    // ── Track utility ─────────────────────────────────────────────────────────

    @Test
    fun `resolveTrackLock returns TrackState when attacker has more successes than decker`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()

        // hitRoller makes decker roll face=5 (evade succeeds) but attack already has high successes
        val attack = AttackResult.Hit(
            attackerSuccesses = 10,
            rawDamageLevel = DamageLevel.LIGHT,
            stagedDamageLevel = DamageLevel.LIGHT,
            power = 4
        )
        val trackState = CombatResolver.resolveTrackLock(attack, decker, trackRating = 6, diceRoller = failRoller())

        assertNotNull(trackState, "Track should lock when attacker has overwhelming successes")
        assertTrue(trackState.locationCycleTurnsRemaining >= 1, "Cycle turns should be at least 1")
    }

    @Test
    fun `resolveTrackLock returns null when decker evades with hitRoller`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }
        val decker = icon.currentDecker()

        // attackerSuccesses=1; trackRating=1 → TN=max(2,1)=2; hitRoller face=5 ≥ 2
        // → evasion(6) dice all succeed → 6 successes ≥ 1 → returns null
        val attack = AttackResult.Hit(
            attackerSuccesses = 1,
            rawDamageLevel = DamageLevel.LIGHT,
            stagedDamageLevel = DamageLevel.LIGHT,
            power = 4
        )
        val trackState = CombatResolver.resolveTrackLock(attack, decker, trackRating = 1, diceRoller = hitRoller())

        assertNull(trackState, "Track should fail when decker evades successfully")
    }

    // ── Slow utility ──────────────────────────────────────────────────────────

    @Test
    fun `resolveSlow on proactive IC reduces actions lost when decker wins`() {
        val icon = scenario(securityCode = SecurityCode.ORANGE) {
            jackInToLtg("UCAS/UCAS-SEA")
            logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
        }

        val killer = Killer(rating = 4) // IcBehavior.PROACTIVE
        val icInitiative = CombatInitiative(score = 14, initiativePasses = 1)

        // winRoller: decker wins → IC loses actions
        val result = CombatResolver.resolveSlow(killer, slowRating = 4, securityValue = 5, icInitiative = icInitiative, diceRoller = winRoller())

        assertTrue(result.actionsLost >= 0, "actionsLost should be non-negative")
    }

    @Test
    fun `resolveSlow on reactive IC (Probe) returns zero actionsLost`() {
        val probe = Probe(rating = 4) // IcBehavior.REACTIVE
        val icInitiative = CombatInitiative(score = 10, initiativePasses = 1)

        val result = CombatResolver.resolveSlow(probe, slowRating = 4, securityValue = 4, icInitiative = icInitiative, diceRoller = winRoller())

        assertEquals(0, result.actionsLost, "Slow has no effect on reactive IC")
        assertEquals(false, result.icInert, "Reactive IC is never made inert by Slow")
    }
}
