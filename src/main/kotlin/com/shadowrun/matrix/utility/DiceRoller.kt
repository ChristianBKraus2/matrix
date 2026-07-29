package com.shadowrun.matrix.utility

import kotlin.random.Random
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

data class DiceResult(
    val dice: List<Int>,
    val targetNumber: Int,
    val successes: Int
)

class DiceRoller(private val random: Random = Random.Default) {

    fun roll(numberOfDice: Int, targetNumber: Int): DiceResult {
        require(numberOfDice > 0) { "numberOfDice must be positive" }
        require(targetNumber >= 2) { "targetNumber must be at least 2" }

        val dice = List(numberOfDice) { rollOne() }
        val successes = dice.count { it >= targetNumber }
        logger.debug { "roll(${numberOfDice}d, TN=$targetNumber) → dice=$dice successes=$successes" }
        return DiceResult(dice, targetNumber, successes)
    }

    private fun rollOne(): Int {
        var total = 0
        var face: Int
        do {
            face = random.nextInt(1, 7)
            total += face
        } while (face == 6)
        return total
    }
}
