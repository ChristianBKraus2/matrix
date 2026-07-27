package com.shadowrun.matrix.integration

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.config.GridInitializer
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.ActiveIconState
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameMovementIntegrationTest {

    // ── Setup ─────────────────────────────────────────────────────────────────────

    private val matrix = GridInitializer.initialize()

    private fun winRoller() = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 6) 5 else 0
        }
    })

    private fun buildDecker(jackpoint: Jackpoint): Decker {
        val programs = listOf(
            PersonaProgram(PersonaAttributeType.BOD, 6),
            PersonaProgram(PersonaAttributeType.EVASION, 6),
            PersonaProgram(PersonaAttributeType.MASKING, 6),
            PersonaProgram(PersonaAttributeType.SENSORS, 6)
        )
        val deck = Cyberdeck(
            name = "Fairlight Excalibur",
            mcpRating = 10,
            activeMemoryMp = 2000,
            storageMemoryMp = 5000,
            ioSpeedMpPerTurn = 300,
            costNuyen = 1_200_000,
            personaPrograms = programs
        )
        return Decker(
            name = "Quicksilver",
            intelligence = 7,
            body = 4,
            willpower = 5,
            reaction = 6,
            computerSkill = 8,
            cyberdeck = deck,
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor(),
            jackpoint = jackpoint
        )
    }

    // ── Drive n actions through the game loop ─────────────────────────────────────

    private fun runActions(icon: ActiveIcon, context: GameContext, count: Int, diceRoller: DiceRoller) {
        // Initiative = count * 10 + 5 so the icon acts exactly `count` times before initiative hits 0.
        val states = mutableListOf(ActiveIconState(icon, count * 10))
        while (states.any { it.currentInitiative > 0 }) {
            val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative }!!
            val idx = states.indexOf(state)
            state.icon.action(context, diceRoller)
            states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
        }
    }

    // ── Test implementation of the decker's action method ────────────────────────
    //
    // NavigatingDeckerIcon is the "test implementation of the decker's action method":
    // it performs one navigation step per action() call and keeps the decker in
    // context.deckers in sync. This replaces the placeholder Decker.action() that
    // returns DeckerAction, demonstrating how a real player callback would work.

    private inner class NavigatingDeckerIcon(
        initialDecker: Decker,
        private val context: GameContext
    ) : ActiveIcon {

        private var step = 0
        var lastResult: ActionResult = ActionResult.DeckerAction
        val stepResults = mutableListOf<ActionResult>()

        // resolve grid for the scenario inside the icon so each step has everything it needs
        private val ucasBase = matrix.rtgs.first { it.name == "UCAS" }
        private val aztlan = matrix.rtgs.first { it.name == "AZT" }
        private val mexicoCity = aztlan.ltgs.first { it.name == "AZT-MEX" }
        private val targetHost = mexicoCity.hosts.first { it.name == "Aztlan Ministry of Information" }

        private val ucas = ucasBase.copy(connectedRtgs = listOf(aztlan))
        private val seattleBase = ucasBase.ltgs.first { it.name == "UCAS-SEA" }
        private val seattle = seattleBase.copy(parentRtg = ucas)
        private val ucasWithLtgs = ucas.copy(ltgs = listOf(seattle) + ucas.ltgs.drop(1))

        init {
            context.deckers.clear()
            context.deckers.add(initialDecker)
        }

        private fun currentDecker() = context.deckers.first()

        private fun update(decker: Decker) = context.updateDecker(currentDecker(), decker)

        override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
            val result = when (step++) {
                0 -> {
                    val r = currentDecker().jackInToLtg(seattle, diceRoller)
                    assertIs<LogonResult.Success>(r, "Step 1 - jack in to LTG failed")
                    update(r.decker)
                    ActionResult.DeckerAction
                }
                1 -> {
                    val currentLtg = (currentDecker().currentLocation as MatrixLocation.OnLTG).ltg
                    val r = currentDecker().logonToRtg(currentLtg.parentRtg, diceRoller)
                    assertIs<LogonResult.Success>(r, "Step 2 - move to RTG failed")
                    val deckerOnUcasWithLtgs = r.decker.copy(
                        currentLocation = MatrixLocation.OnRTG(ucasWithLtgs)
                    )
                    update(deckerOnUcasWithLtgs)
                    ActionResult.DeckerAction
                }
                2 -> {
                    val r = currentDecker().logonToRtg(aztlan, diceRoller)
                    assertIs<LogonResult.Success>(r, "Step 3 - move to Aztlan RTG failed")
                    update(r.decker)
                    ActionResult.DeckerAction
                }
                3 -> {
                    val aztlanLocation = currentDecker().currentLocation as MatrixLocation.OnRTG
                    val aztlanWithLtgs = aztlanLocation.rtg.copy(ltgs = aztlan.ltgs)
                    update(currentDecker().copy(currentLocation = MatrixLocation.OnRTG(aztlanWithLtgs)))
                    val r = currentDecker().logonToLtg(mexicoCity, diceRoller)
                    assertIs<LogonResult.Success>(r, "Step 4 - enter Mexico City LTG failed")
                    update(r.decker)
                    ActionResult.DeckerAction
                }
                4 -> {
                    val mexCityWithHosts = (currentDecker().currentLocation as MatrixLocation.OnLTG).ltg
                        .copy(hosts = mexicoCity.hosts)
                    update(currentDecker().copy(currentLocation = MatrixLocation.OnLTG(mexCityWithHosts)))
                    val r = currentDecker().logonToHost(targetHost, diceRoller)
                    assertIs<LogonResult.Success>(r, "Step 5 - logon to host failed")
                    update(r.decker)
                    ActionResult.DeckerAction
                }
                5 -> {
                    val r = currentDecker().gracefulLogoff(diceRoller)
                    assertIs<LogoffResult.GracefulSuccess>(r, "Step 6 - logoff failed")
                    update(r.decker)
                    ActionResult.DeckerAction
                }
                else -> ActionResult.DeckerAction
            }
            lastResult = result
            stepResults += result
            return result
        }
    }

    // ── Movement integration via game layer ───────────────────────────────────────
    //
    // Scenario: same 6 steps as MovementIntegrationTest, but driven through the
    // ActiveIcon/ActiveIconState game loop instead of direct decker method calls.

    @Test
    fun `integration - jack in to LTG, traverse RTGs, enter host, logoff via game layer`() {
        val jackpoint = Jackpoint(
            JackpointType.ILLEGAL_ACCESS,
            connectsToLtg = matrix.rtgs.first { it.name == "UCAS" }.ltgs.first { it.name == "UCAS-SEA" }
        )
        val decker = buildDecker(jackpoint)

        val context = GameContext(
            host = Host(
                name = "placeholder",
                securityRating = com.shadowrun.matrix.common.SecurityRating(
                    com.shadowrun.matrix.common.SecurityCode.GREEN, 3
                ),
                subsystemRatings = com.shadowrun.matrix.common.SubsystemRatings(3, 3, 3, 3, 3),
                intrusionDifficulty = com.shadowrun.matrix.common.IntrusionDifficulty.AVERAGE,
                topologyType = com.shadowrun.matrix.common.TopologyType.TIERED
            ),
            securityCode = com.shadowrun.matrix.common.SecurityCode.GREEN,
            deckers = mutableListOf(decker),
            activeIc = mutableListOf()
        )

        val navIcon = NavigatingDeckerIcon(decker, context)

        // Drive 6 navigation steps through the game loop
        runActions(navIcon, context, count = 6, diceRoller = winRoller())

        // All 6 steps completed
        assertEquals(6, navIcon.stepResults.size)
        assertTrue(navIcon.stepResults.all { it is ActionResult.DeckerAction })

        // Final state: graceful logoff cleared persona and location
        val finalDecker = context.deckers.first()
        assertNull(finalDecker.persona, "Persona should be null after logoff")
        assertNull(finalDecker.currentLocation, "Location should be null after logoff")
    }
}
