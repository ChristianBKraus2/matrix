package com.shadowrun.matrix.programs

import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.PersonaAttributeType
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramTest {

    @Test
    fun `PersonaProgram mpSize equals rating squared`() {
        val prog = PersonaProgram(PersonaAttributeType.BOD, rating = 4)
        assertEquals(16, prog.mpSize)  // 4*4*1
    }

    @Test
    fun `Utility mpSize uses type multiplier`() {
        val analyze = Utility(UtilityType.ANALYZE, rating = 3)
        assertEquals(27, analyze.mpSize)  // 3*3*3
    }

    @Test
    fun `Attack utility multiplier scales by damage level`() {
        val lightAttack = Utility(UtilityType.ATTACK, rating = 4, attackDamageLevel = DamageLevel.LIGHT)
        val deadlyAttack = Utility(UtilityType.ATTACK, rating = 4, attackDamageLevel = DamageLevel.DEADLY)
        assertEquals(4 * 4 * 2, lightAttack.mpSize)   // multiplier = 0+2 = 2
        assertEquals(4 * 4 * 5, deadlyAttack.mpSize)  // multiplier = 3+2 = 5
    }
}
