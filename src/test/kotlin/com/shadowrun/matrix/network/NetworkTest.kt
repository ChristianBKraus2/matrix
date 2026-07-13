package com.shadowrun.matrix.network

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTest {

    private fun ratings() = SubsystemRatings(4, 5, 6, 7, 8)
    private fun secRating() = SecurityRating(SecurityCode.GREEN, 6)

    private fun rtg() = RTG("UCAS", "North America", secRating(), ratings())
    private fun ltg() = LTG("Seattle", rtg(), secRating(), ratings())
    private fun host() = Host("Corp Host", secRating(), ratings(), IntrusionDifficulty.HARD, TopologyType.OPEN_ACCESS)

    // ── Jackpoint ──────────────────────────────────────────────────────────────

    @Test
    fun `Jackpoint requires exactly one target`() {
        assertFailsWith<IllegalArgumentException> {
            Jackpoint(JackpointType.WORKSTATION, connectsToLtg = ltg(), connectsToHost = host())
        }
        assertFailsWith<IllegalArgumentException> {
            Jackpoint(JackpointType.WORKSTATION)
        }
    }

    @Test
    fun `Jackpoint connects to LTG only`() {
        val jp = Jackpoint(JackpointType.LEGAL_ACCESS, connectsToLtg = ltg())
        assertEquals(JackpointType.LEGAL_ACCESS, jp.type)
    }

    @Test
    fun `Jackpoint workstation connects directly to host`() {
        val jp = Jackpoint(JackpointType.WORKSTATION, connectsToHost = host())
        assertEquals(host(), jp.connectsToHost)
        assertNull(jp.connectsToLtg)
    }

    // ── Grid hierarchy ─────────────────────────────────────────────────────────

    @Test
    fun `LTG references its parent RTG`() {
        val r = rtg()
        val l = LTG("Seattle", r, secRating(), ratings())
        assertEquals(r, l.parentRtg)
    }

    @Test
    fun `RTG can carry child LTGs`() {
        val seattle = ltg()
        val r = rtg().copy(ltgs = listOf(seattle))
        assertEquals(1, r.ltgs.size)
        assertEquals("Seattle", r.ltgs[0].name)
    }

    @Test
    fun `RTG can reference connected RTGs`() {
        val r1 = rtg()
        val r2 = RTG("Aztlan", "Mexico", secRating(), ratings())
        val r1WithLink = r1.copy(connectedRtgs = listOf(r2))
        assertTrue(r1WithLink.connectedRtgs.contains(r2))
    }

    @Test
    fun `LTG can carry child PLTGs`() {
        val l = ltg()
        val pltg = PLTG("Aztechnology PLTG", "Aztechnology", l, secRating(), ratings())
        val lWithPltg = l.copy(pltgs = listOf(pltg))
        assertEquals(1, lWithPltg.pltgs.size)
    }

    @Test
    fun `PLTG references its parent LTG and carries hosts`() {
        val l = ltg()
        val h = host()
        val pltg = PLTG("Corp PLTG", "MegaCorp", l, secRating(), ratings(), hosts = listOf(h))
        assertEquals(l, pltg.parentLtg)
        assertEquals(1, pltg.hosts.size)
    }

    @Test
    fun `Grid carries a SecuritySheaf`() {
        val step = TriggerStep(3, "Activate Probe", alertTransition = AlertStatus.PASSIVE_ALERT)
        val sheaf = SecuritySheaf(listOf(step))
        val r = rtg().copy(securitySheaf = sheaf)
        assertEquals(1, r.securitySheaf.triggerSteps.size)
        assertEquals(AlertStatus.PASSIVE_ALERT, r.securitySheaf.triggerSteps[0].alertTransition)
    }

    // ── Host ───────────────────────────────────────────────────────────────────

    @Test
    fun `Host default alert status is NO_ALERT`() {
        assertEquals(AlertStatus.NO_ALERT, host().alertStatus)
    }

    @Test
    fun `Host default nodes cover all five subsystem types`() {
        val h = host()
        assertEquals(5, h.nodes.size)
        val types = h.nodes.map { it.subsystemType }.toSet()
        assertEquals(SubsystemType.entries.toSet(), types)
    }

    @Test
    fun `Host rejects duplicate node subsystem types`() {
        assertFailsWith<IllegalArgumentException> {
            Host(
                "Bad Host", secRating(), ratings(), IntrusionDifficulty.EASY, TopologyType.OPEN_ACCESS,
                nodes = listOf(Node(SubsystemType.ACCESS), Node(SubsystemType.ACCESS),
                    Node(SubsystemType.CONTROL), Node(SubsystemType.INDEX), Node(SubsystemType.FILES))
            )
        }
    }

    @Test
    fun `Host holds SANs`() {
        val san = SAN("Main Gate")
        val h = host().copy(sans = listOf(san))
        assertEquals(1, h.sans.size)
        assertEquals("Main Gate", h.sans[0].name)
    }

    @Test
    fun `Host holds IC programs`() {
        val ic = Killer(rating = 6)
        val h = host().copy(icPrograms = listOf(ic))
        assertEquals(1, h.icPrograms.size)
    }

    @Test
    fun `Host holds DataFiles`() {
        val file = DataFile("paydata.txt")
        val h = host().copy(dataFiles = listOf(file))
        assertEquals(1, h.dataFiles.size)
    }

    @Test
    fun `Host holds RemoteDevices`() {
        val device = RemoteDevice("Camera-01", "slave://cam1")
        val h = host().copy(remoteDevices = listOf(device))
        assertEquals(1, h.remoteDevices.size)
    }

    @Test
    fun `Host holds connected hosts for tiered topology`() {
        val firstTier = host()
        val secondTier = Host("Inner Host", secRating(), ratings(), IntrusionDifficulty.HARD, TopologyType.TIERED)
        val linked = firstTier.copy(connectedHosts = listOf(secondTier))
        assertEquals(1, linked.connectedHosts.size)
    }

    // ── SAN ────────────────────────────────────────────────────────────────────

    @Test
    fun `SAN is scramble-protected when configured`() {
        val san = SAN("Scrambled Gate", isScrambleProtected = true)
        assertTrue(san.isScrambleProtected)
    }

    // ── SecuritySheaf ──────────────────────────────────────────────────────────

    @Test
    fun `TriggerStep holds threshold and description`() {
        val step = TriggerStep(6, "Activate Killer IC")
        assertEquals(6, step.tallyThreshold)
    }

    @Test
    fun `TriggerStep activates IC at threshold`() {
        val killer = Killer(rating = 8)
        val step = TriggerStep(10, "Release the hound", activatedIc = listOf(killer))
        assertEquals(1, step.activatedIc.size)
        assertEquals(8, step.activatedIc[0].rating)
    }

    @Test
    fun `TriggerStep without alertTransition has null transition`() {
        val step = TriggerStep(3, "Probe only")
        assertNull(step.alertTransition)
    }

    @Test
    fun `TriggerStep can signal active alert`() {
        val step = TriggerStep(13, "Active Alert", alertTransition = AlertStatus.ACTIVE_ALERT)
        assertEquals(AlertStatus.ACTIVE_ALERT, step.alertTransition)
    }

    // ── DataFile pointer chain ─────────────────────────────────────────────────

    @Test
    fun `DataFile pointer references another host`() {
        val targetHost = host()
        val pointer = DataFile("ref.ptr", pointerToHost = targetHost)
        assertEquals(targetHost, pointer.pointerToHost)
    }

    @Test
    fun `DataFile can be scramble-protected`() {
        val file = DataFile("secret.data", isScrambleProtected = true)
        assertTrue(file.isScrambleProtected)
    }

    // ── Matrix root ────────────────────────────────────────────────────────────

    @Test
    fun `Matrix holds RTGs`() {
        val r = rtg()
        val matrix = Matrix(listOf(r))
        assertEquals(1, matrix.rtgs.size)
    }

    @Test
    fun `Probe IC is reactive and can guard a Files node`() {
        val filesNode = Node(SubsystemType.FILES, "Files subsystem")
        val probe = Probe(rating = 5, guardedNode = filesNode)
        assertEquals(filesNode, probe.guardedNode)
    }
}

