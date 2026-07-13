package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import kotlin.test.Test
import kotlin.test.assertEquals

class CombatTest {

    @Test
    fun `DumpShock power equals security value`() {
        val ds = DumpShock(SecurityRating(SecurityCode.ORANGE, 9))
        assertEquals(9, ds.power)
    }

    @Test
    fun `DumpShock level maps correctly to security code`() {
        assertEquals(DamageLevel.LIGHT,   DumpShock(SecurityRating(SecurityCode.BLUE,   4)).level)
        assertEquals(DamageLevel.MODERATE, DumpShock(SecurityRating(SecurityCode.GREEN,  6)).level)
        assertEquals(DamageLevel.SERIOUS,  DumpShock(SecurityRating(SecurityCode.ORANGE, 8)).level)
        assertEquals(DamageLevel.DEADLY,   DumpShock(SecurityRating(SecurityCode.RED,    10)).level)
    }
}
