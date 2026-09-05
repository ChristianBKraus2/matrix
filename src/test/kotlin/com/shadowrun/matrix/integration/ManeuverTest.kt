package com.shadowrun.matrix.integration

import com.shadowrun.matrix.combat.CombatResolver
import com.shadowrun.matrix.combat.ManeuverParticipant
import com.shadowrun.matrix.combat.ManeuverResult
import com.shadowrun.matrix.common.CombatManeuverType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.IntegrationTestBase
import com.shadowrun.matrix.operations.EvadeDetectionResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ManeuverTest : IntegrationTestBase() {

    // Mover TN = max(2, opponent.sensor - mover.cloakRating)
    // Opponent TN = max(2, mover.evasion - opponent.lockOnRating)
    //
    // hitRoller face=5. To guarantee mover wins:
    //   - opponent.sensor must be ≤ 5 so mover TN ≤ 5 → face=5 ≥ TN → mover gets successes
    //   - mover.evasion must be > 5 so opponent TN = max(2, evasion) ≥ 6 → face=5 < TN → 0 opponent successes
    //
    // HIGH_END decker: evasion=6. So with opponent.sensor=1: mover TN=2, face=5 ≥ 2 → hits.
    // Opponent TN=max(2,6)=6, face=5 < 6 → 0. Net=mover_successes > 0 → Success.
    //
    // To guarantee mover fails: opponent.sensor=10 → mover TN=10, face=5 < 10 → 0 mover successes.
    // Opponent TN=max(2,6)=6, face=5 < 6 → 0. Net=0 → Failure (tie favours opponent).

    private fun weakOpponent() = ManeuverParticipant(evasion = 1, sensor = 1)
    private fun strongOpponent() = ManeuverParticipant(evasion = 10, sensor = 10)

    private fun deckerParticipant(icon: ScriptedDeckerIcon): ManeuverParticipant {
        val decker = icon.currentDecker()
        return ManeuverParticipant(
            evasion = decker.persona!!.evasion,
            sensor = decker.persona.sensor,
            hackingPool = decker.hackingPool
        )
    }

    private fun deckerParticipantWithCloak(icon: ScriptedDeckerIcon, cloakRating: Int): ManeuverParticipant {
        val decker = icon.currentDecker()
        return ManeuverParticipant(
            evasion = decker.persona!!.evasion,
            sensor = decker.persona.sensor,
            cloakRating = cloakRating,
            hackingPool = decker.hackingPool
        )
    }

    private fun loggedInIcon() = scenario(securityCode = SecurityCode.ORANGE) {
        jackInToLtg("UCAS/UCAS-SEA")
        logonToHost("UCAS/UCAS-SEA/Mitsuhama Pagoda")
    }

    // ── Evade Detection ───────────────────────────────────────────────────────

    @Test
    fun `EVADE_DETECTION maneuver succeeds when decker wins the contested roll`() {
        val icon = loggedInIcon()
        val decker = deckerParticipant(icon)

        // weakOpponent: mover TN = max(2, 1) = 2; hitRoller face=5 ≥ 2 → successes
        // opponent TN = max(2, evasion=6) = 6; face=5 < 6 → 0 opponent successes → net > 0
        val result = CombatResolver.resolveManeuver(CombatManeuverType.EVADE_DETECTION, decker, weakOpponent(), hitRoller())

        assertIs<ManeuverResult.Success>(result, "Decker should succeed EVADE_DETECTION with weak opponent")
        assertTrue((result as ManeuverResult.Success).netSuccesses > 0)
    }

    @Test
    fun `EVADE_DETECTION maneuver fails when opponent wins`() {
        val icon = loggedInIcon()
        val decker = deckerParticipant(icon)

        // strongOpponent.sensor=10: mover TN=10, face=5 < 10 → 0 mover successes
        // opponent TN=max(2,6)=6, face=5 < 6 → 0; net=0 → Failure
        val result = CombatResolver.resolveManeuver(CombatManeuverType.EVADE_DETECTION, decker, strongOpponent(), hitRoller())

        assertIs<ManeuverResult.Failure>(result, "Decker should fail EVADE_DETECTION against strong opponent")
    }

    // ── Parry Attack ──────────────────────────────────────────────────────────

    @Test
    fun `PARRY_ATTACK maneuver succeeds when decker wins contested roll`() {
        val icon = loggedInIcon()
        val decker = deckerParticipant(icon)

        val result = CombatResolver.resolveManeuver(CombatManeuverType.PARRY_ATTACK, decker, weakOpponent(), hitRoller())

        assertIs<ManeuverResult.Success>(result, "Decker should win PARRY_ATTACK against weak opponent")
    }

    @Test
    fun `PARRY_ATTACK maneuver fails when opponent wins`() {
        val icon = loggedInIcon()
        val decker = deckerParticipant(icon)

        val result = CombatResolver.resolveManeuver(CombatManeuverType.PARRY_ATTACK, decker, strongOpponent(), hitRoller())

        assertIs<ManeuverResult.Failure>(result, "Decker should fail PARRY_ATTACK against strong opponent")
    }

    // ── Position Attack ───────────────────────────────────────────────────────

    @Test
    fun `POSITION_ATTACK maneuver succeeds when decker wins contested roll`() {
        val icon = loggedInIcon()
        val decker = deckerParticipant(icon)

        val result = CombatResolver.resolveManeuver(CombatManeuverType.POSITION_ATTACK, decker, weakOpponent(), hitRoller())

        assertIs<ManeuverResult.Success>(result, "Decker should win POSITION_ATTACK against weak opponent")
    }

    @Test
    fun `POSITION_ATTACK maneuver with Cloak lowers TN and succeeds against moderate opponent`() {
        val icon = loggedInIcon()
        // cloakRating=3: mover TN = max(2, opponent.sensor=4 - 3) = max(2,1) = 2; hitRoller face=5 ≥ 2 → wins
        // Without cloak: TN = max(2, 4-0) = 4; face=5 ≥ 4 → also wins, but cloak makes it even safer
        val deckerWithCloak = deckerParticipantWithCloak(icon, cloakRating = 3)
        val moderateOpponent = ManeuverParticipant(evasion = 4, sensor = 4)

        val result = CombatResolver.resolveManeuver(CombatManeuverType.POSITION_ATTACK, deckerWithCloak, moderateOpponent, hitRoller())

        assertIs<ManeuverResult.Success>(result, "Decker with Cloak should win POSITION_ATTACK")
        assertTrue((result as ManeuverResult.Success).netSuccesses > 0)
    }

    // ── evadeDetection ────────────────────────────────────────────────────────

    @Test
    fun `evadeDetection success sets countdown to netSuccesses`() {
        // hitRoller face=5; ic.rating=3 → mover TN=max(2,3)=3, face=5 ≥ 3 → mover successes
        // opponent TN=max(2, evasion=6)=6, face=5 < 6 → 0 opponent successes → net > 0
        val icon = loggedInIcon()
        val ic = Probe(rating = 3)
        val result = CombatResolver.evadeDetection(icon.currentDecker(), ic, hitRoller())
        assertIs<EvadeDetectionResult.Success>(result)
        val state = result.decker.evadeDetectionStates.single()
        assertEquals(ic.name, state.icName)
        assertTrue(state.turnsRemaining > 0)
        assertEquals(result.netSuccesses, state.turnsRemaining)
    }

    @Test
    fun `evadeDetection failure leaves evadeDetectionStates unchanged`() {
        // winRoller face=0; mover TN=max(2,3)=3, face=0 < 3 → 0 mover successes → net≤0 → Failure
        val icon = loggedInIcon()
        val ic = Probe(rating = 3)
        val result = CombatResolver.evadeDetection(icon.currentDecker(), ic, winRoller())
        assertIs<EvadeDetectionResult.Failure>(result)
        assertTrue(result.decker.evadeDetectionStates.isEmpty())
    }
}
