package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.Game
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.ActiveIconState
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Receiver for step lambdas — provides the decker helpers and roller, keeping step bodies param-free
class StepContext(val context: GameContext, val roller: DiceRoller) {
    fun currentDecker() = context.deckers.first()
    fun updateCurrentDecker(d: Decker) = context.updateDecker(context.deckers.first(), d)
}

typealias StepAction = StepContext.() -> Unit

open class IntegrationTestBase {

    val matrix = GridMock.matrix

    protected fun winRoller() = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = 0
    })

    protected fun failRoller() = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = 3
    })

    /** Returns 0 for the first [zeroCalls] dice calls, then [thenValue] for all subsequent calls. */
    protected fun winThenRoller(zeroCalls: Int, thenValue: Int) = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= zeroCalls) 0 else thenValue
        }
    })

    /** Returns 0 for the first [winCalls] calls, then [failValue] for the next [failCalls], then 0 again. */
    protected fun winFailWinRoller(winCalls: Int, failCalls: Int, failValue: Int = 3) = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= winCalls) 0
                   else if (call <= winCalls + failCalls) failValue
                   else 0
        }
    })
    protected fun buildDefaultContext(decker: Decker) = GameContext(
        host = HostMock.build("placeholder"),
        securityCode = SecurityCode.GREEN,
        deckers = mutableListOf(decker),
        activeIc = mutableListOf()
    )

    protected fun scenario(
        jackpointPath: String = "UCAS/UCAS-SEA",
        diceRoller: DiceRoller = winRoller(),
        deckerTier: String = DeckerMock.HIGH_END,
        init: ScenarioBuilder.() -> Unit
    ): ScriptedDeckerIcon {
        val (rtg, ltg) = jackpointPath.split("/")
        val decker = DeckerMock.build(GridMock.jackpoint(rtg, ltg), deckerTier)
        val context = buildDefaultContext(decker)
        val steps = ScenarioBuilder(matrix).apply(init).build()
        val icon = ScriptedDeckerIcon(decker, context, steps)
        runActions(icon, context, steps.size, diceRoller)
        assertEquals(steps.size, icon.stepResults.size)
        assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
        return icon
    }

    protected fun scenario(
        jackpoint: Jackpoint,
        diceRoller: DiceRoller = winRoller(),
        deckerTier: String = DeckerMock.HIGH_END,
        init: ScenarioBuilder.() -> Unit
    ): ScriptedDeckerIcon {
        val decker = DeckerMock.build(jackpoint, deckerTier)
        val context = buildDefaultContext(decker)
        val steps = ScenarioBuilder(matrix).apply(init).build()
        val icon = ScriptedDeckerIcon(decker, context, steps)
        runActions(icon, context, steps.size, diceRoller)
        assertEquals(steps.size, icon.stepResults.size)
        assertTrue(icon.stepResults.all { it is ActionResult.DeckerAction })
        return icon
    }

    protected fun runActions(icon: ActiveIcon, context: GameContext, count: Int, diceRoller: DiceRoller) {
        val states = mutableListOf(ActiveIconState(icon, count * 10))
        while (states.any { it.currentInitiative > 0 }) {
            val state = states.filter { it.currentInitiative > 0 }.maxByOrNull { it.currentInitiative }!!
            val idx = states.indexOf(state)
            state.icon.action(context, diceRoller)
            states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)
        }
    }

    protected fun ScriptedDeckerIcon.runCombatTurn(diceRoller: DiceRoller): Int {
        val damageBefore = currentDecker().persona!!.conditionMonitor.damage
        Game(context = context, diceRoller = diceRoller, inCombat = true).runCombatTurn()
        return currentDecker().persona!!.conditionMonitor.damage - damageBefore
    }

    protected fun ScriptedDeckerIcon.assertOnHost(name: String) {
        val loc = assertIs<MatrixLocation.OnHost>(currentDecker().currentLocation)
        assertEquals(name, loc.host.name)
    }

    protected fun ScriptedDeckerIcon.assertOnLtg(name: String) {
        val loc = assertIs<MatrixLocation.OnLTG>(currentDecker().currentLocation)
        assertEquals(name, loc.ltg.name)
    }

    protected fun ScriptedDeckerIcon.assertOnRtg(name: String) {
        val loc = assertIs<MatrixLocation.OnRTG>(currentDecker().currentLocation)
        assertEquals(name, loc.rtg.name)
    }

    protected fun ScriptedDeckerIcon.assertOnPltg(name: String) {
        val loc = assertIs<MatrixLocation.OnPLTG>(currentDecker().currentLocation)
        assertEquals(name, loc.pltg.name)
    }

    protected fun ScriptedDeckerIcon.assertLoggedOff() {
        assertNull(currentDecker().persona,         "Persona should be null after logoff")
        assertNull(currentDecker().currentLocation, "Location should be null after logoff")
    }

    protected fun ScriptedDeckerIcon.assertNotJackedIn() {
        assertNull(currentDecker().persona,         "Persona should be null")
        assertNull(currentDecker().currentLocation, "Location should be null")
    }

    protected fun ScriptedDeckerIcon.assertNoActiveIc() {
        assertTrue(context.activeIc.isEmpty(), "Expected no active IC but found: ${context.activeIc.map { it.name }}")
    }

    protected fun ScriptedDeckerIcon.assertActiveIcCount(count: Int) {
        assertEquals(count, context.activeIc.size, "Expected $count active IC but found ${context.activeIc.size}: ${context.activeIc.map { it.name }}")
    }

    protected fun ScriptedDeckerIcon.assertAlertStatus(expected: AlertStatus) {
        assertEquals(expected, context.host.alertStatus, "Expected alert status $expected but was ${context.host.alertStatus}")
    }

    protected inner class ScriptedDeckerIcon(
        initialDecker: Decker,
        val context: GameContext,
        private val steps: List<StepAction>
    ) : ActiveIcon {

        val stepResults = mutableListOf<ActionResult>()
        private var step = 0

        fun currentDecker() = context.deckers.first()

        init {
            context.deckers.clear()
            context.deckers.add(initialDecker)
        }

        override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
            if (step < steps.size) steps[step].invoke(StepContext(context, diceRoller))
            step++
            return ActionResult.DeckerAction.also { stepResults += it }
        }
    }
}
