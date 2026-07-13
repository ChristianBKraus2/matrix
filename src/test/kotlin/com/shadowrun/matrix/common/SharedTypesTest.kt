package com.shadowrun.matrix.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedTypesTest {

    @Test
    fun `SubsystemRatings get returns correct value for each type`() {
        val ratings = SubsystemRatings(access = 4, control = 5, index = 6, files = 7, slave = 8)
        assertEquals(4, ratings.get(SubsystemType.ACCESS))
        assertEquals(5, ratings.get(SubsystemType.CONTROL))
        assertEquals(6, ratings.get(SubsystemType.INDEX))
        assertEquals(7, ratings.get(SubsystemType.FILES))
        assertEquals(8, ratings.get(SubsystemType.SLAVE))
    }

    @Test
    fun `ConditionMonitor remaining and isDestroyed`() {
        val cm = ConditionMonitor(maxBoxes = 10, damage = 3)
        assertEquals(7, cm.remaining)
        assertFalse(cm.isDestroyed)
    }

    @Test
    fun `ConditionMonitor applyDamage does not exceed maxBoxes`() {
        val cm = ConditionMonitor(maxBoxes = 10, damage = 8)
        val after = cm.applyDamage(5)
        assertEquals(10, after.damage)
        assertTrue(after.isDestroyed)
    }
}
