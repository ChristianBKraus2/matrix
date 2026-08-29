package com.shadowrun.matrix.operations

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NullOperationModifierTest {

    @Test
    fun `forDuration under 10 seconds returns UNDER_TEN_SECONDS`() {
        assertEquals(NullOperationModifier.UNDER_TEN_SECONDS, NullOperationModifier.forDuration(0))
        assertEquals(NullOperationModifier.UNDER_TEN_SECONDS, NullOperationModifier.forDuration(9))
    }

    @Test
    fun `forDuration 10 to 59 seconds returns TEN_SECONDS_TO_ONE_MINUTE`() {
        assertEquals(NullOperationModifier.TEN_SECONDS_TO_ONE_MINUTE, NullOperationModifier.forDuration(10))
        assertEquals(NullOperationModifier.TEN_SECONDS_TO_ONE_MINUTE, NullOperationModifier.forDuration(59))
    }

    @Test
    fun `forDuration 60 to 3599 seconds returns ONE_MINUTE_TO_ONE_HOUR`() {
        assertEquals(NullOperationModifier.ONE_MINUTE_TO_ONE_HOUR, NullOperationModifier.forDuration(60))
        assertEquals(NullOperationModifier.ONE_MINUTE_TO_ONE_HOUR, NullOperationModifier.forDuration(3599))
    }

    @Test
    fun `forDuration 3600 and above returns ONE_HOUR_TO_TWELVE_HOURS`() {
        assertEquals(NullOperationModifier.ONE_HOUR_TO_TWELVE_HOURS, NullOperationModifier.forDuration(3600))
        assertEquals(NullOperationModifier.ONE_HOUR_TO_TWELVE_HOURS, NullOperationModifier.forDuration(100000))
    }

    @Test
    fun `totalBonusForDuration under 10 seconds is 0`() {
        assertEquals(0, NullOperationModifier.totalBonusForDuration(5))
    }

    @Test
    fun `totalBonusForDuration 10 to 59 seconds is 1`() {
        assertEquals(1, NullOperationModifier.totalBonusForDuration(30))
    }

    @Test
    fun `totalBonusForDuration 60 to 3599 seconds is 2`() {
        assertEquals(2, NullOperationModifier.totalBonusForDuration(600))
    }

    @Test
    fun `totalBonusForDuration 3600 to 43199 seconds is 4 with no extra increment`() {
        assertEquals(4, NullOperationModifier.totalBonusForDuration(3600))
        assertEquals(4, NullOperationModifier.totalBonusForDuration(43199))
    }

    @Test
    fun `totalBonusForDuration accumulates 1 extra per 12-hour window beyond first hour`() {
        // exactly 1 extra 12h block after the first hour: 3600 + 43200 = 46800
        assertEquals(5, NullOperationModifier.totalBonusForDuration(46800))
        // 2 extra blocks: 3600 + 43200*2 = 90000
        assertEquals(6, NullOperationModifier.totalBonusForDuration(90000))
    }
}
