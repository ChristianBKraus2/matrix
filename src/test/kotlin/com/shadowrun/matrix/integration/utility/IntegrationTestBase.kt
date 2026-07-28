package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.game.ActionResult
import com.shadowrun.matrix.game.ActiveIcon
import com.shadowrun.matrix.game.ActiveIconState
import com.shadowrun.matrix.game.GameContext
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.assertEquals
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
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 6) 5 else 0
        }
    })

    protected fun failRoller() = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 8) 0 else 3
        }
    })

    // Passes the first system test (both return 0 → tie → decker wins), then loses all subsequent
    // ones (host dice return 3 which beats host TN 3; decker TN is always ≥ 6 so decker gets 0).
    // The threshold of 12 covers jackInToLtg: 8 decker dice + 4 host dice.
    protected fun winThenFailRoller() = DiceRoller(object : Random() {
        private var call = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int): Int {
            call++
            return if (call <= 12) 0 else 3
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
        init: ScenarioBuilder.() -> Unit
    ): ScriptedDeckerIcon {
        val (rtg, ltg) = jackpointPath.split("/")
        val decker = DeckerMock.build(GridMock.jackpoint(rtg, ltg))
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
