package com.shadowrun.matrix.ic

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IcBehavior
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.UtilityCategory
import com.shadowrun.matrix.network.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IcTest {

    @Test
    fun `initiativeDice returns correct N for each security code`() {
        val ic = Killer(rating = 5)
        assertEquals(1, ic.initiativeDice(SecurityCode.BLUE))
        assertEquals(2, ic.initiativeDice(SecurityCode.GREEN))
        assertEquals(3, ic.initiativeDice(SecurityCode.ORANGE))
        assertEquals(4, ic.initiativeDice(SecurityCode.RED))
    }

    @Test
    fun `Probe is reactive white IC`() {
        val probe = Probe(rating = 4)
        assertIs<WhiteIC>(probe)
        assertEquals(IcBehavior.REACTIVE, probe.behavior)
    }

    @Test
    fun `Crippler targets correct persona attribute`() {
        val crippler = Crippler(rating = 6, targetAttribute = PersonaAttributeType.BOD)
        assertEquals(PersonaAttributeType.BOD, crippler.targetAttribute)
        assertEquals(IcBehavior.PROACTIVE, crippler.behavior)
    }

    @Test
    fun `BlackIC is always proactive`() {
        val lethal = LethalBlackIC(rating = 8)
        val nonLethal = NonLethalBlackIC(rating = 6)
        assertEquals(IcBehavior.PROACTIVE, lethal.behavior)
        assertEquals(IcBehavior.PROACTIVE, nonLethal.behavior)
    }

    @Test
    fun `GrayIC Sparky is proactive`() {
        val sparky = Sparky(rating = 5)
        assertIs<GrayIC>(sparky)
        assertEquals(IcBehavior.PROACTIVE, sparky.behavior)
    }

    @Test
    fun `IC guardedNode is null by default`() {
        val killer = Killer(rating = 6)
        assertNull(killer.guardedNode)
    }

    @Test
    fun `Reactive IC can guard a specific node`() {
        val slaveNode = Node(SubsystemType.SLAVE, "Slave subsystem")
        val tarBaby = TarBaby(rating = 5, targetCategory = UtilityCategory.OPERATIONAL, guardedNode = slaveNode)
        assertEquals(slaveNode, tarBaby.guardedNode)
        assertEquals(SubsystemType.SLAVE, tarBaby.guardedNode?.subsystemType)
    }

    @Test
    fun `Scramble IC can guard a Files node`() {
        val filesNode = Node(SubsystemType.FILES, "Files subsystem")
        val scramble = Scramble(rating = 7, guardedNode = filesNode)
        assertIs<WhiteIC>(scramble)
        assertEquals(filesNode, scramble.guardedNode)
    }

    @Test
    fun `IC conditionMonitor defaults to empty ConditionMonitor`() {
        val killer = Killer(rating = 6)
        assertEquals(ConditionMonitor(), killer.conditionMonitor)
    }

    @Test
    fun `withConditionMonitor returns new IC with updated conditionMonitor preserving other fields`() {
        val killer = Killer(rating = 6)
        val cm = ConditionMonitor(damage = 3)
        val wounded = killer.withConditionMonitor(cm)
        assertIs<Killer>(wounded)
        assertEquals(cm, wounded.conditionMonitor)
        assertEquals(6, wounded.rating)
    }
}

