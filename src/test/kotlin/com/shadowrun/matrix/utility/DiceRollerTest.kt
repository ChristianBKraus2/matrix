package com.shadowrun.matrix.utility

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiceRollerTest {

    private fun stubRandom(vararg values: Int): Random = object : Random() {
        private val iter = values.iterator()
        override fun nextBits(bitCount: Int): Int = iter.nextInt()
        override fun nextInt(from: Int, until: Int): Int = iter.nextInt()
    }

    @Test
    fun `all dice below target yields zero successes`() {
        val roller = DiceRoller(stubRandom(1, 1, 1))
        val result = roller.roll(numberOfDice = 3, targetNumber = 4)
        assertEquals(0, result.successes)
        assertEquals(listOf(1, 1, 1), result.dice)
    }

    @Test
    fun `all dice meet target yields full successes`() {
        val roller = DiceRoller(stubRandom(4, 4, 4))
        val result = roller.roll(numberOfDice = 3, targetNumber = 4)
        assertEquals(3, result.successes)
        assertEquals(listOf(4, 4, 4), result.dice)
    }

    @Test
    fun `exploding six adds extra roll and may exceed target`() {
        // die 1: rolls 6, then 3 → total 9; die 2: rolls 2 → total 2; target 8
        val roller = DiceRoller(stubRandom(6, 3, 2))
        val result = roller.roll(numberOfDice = 2, targetNumber = 8)
        assertEquals(listOf(9, 2), result.dice)
        assertEquals(1, result.successes)
    }

    @Test
    fun `exploding six chains multiple times`() {
        // die 1: rolls 6, 6, 2 → total 14; target 10
        val roller = DiceRoller(stubRandom(6, 6, 2))
        val result = roller.roll(numberOfDice = 1, targetNumber = 10)
        assertEquals(listOf(14), result.dice)
        assertEquals(1, result.successes)
    }

    @Test
    fun `invalid dice count throws`() {
        assertFailsWith<IllegalArgumentException> {
            DiceRoller().roll(numberOfDice = 0, targetNumber = 4)
        }
    }

    @Test
    fun `invalid target number throws`() {
        assertFailsWith<IllegalArgumentException> {
            DiceRoller().roll(numberOfDice = 1, targetNumber = 1)
        }
    }
}
