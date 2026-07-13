package com.shadowrun.matrix.utility

import kotlin.random.Random

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
