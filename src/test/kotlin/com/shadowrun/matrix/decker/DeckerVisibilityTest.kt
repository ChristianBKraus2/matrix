package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.JackpointType
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Jackpoint
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.Icon
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.programs.PersonaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeckerVisibilityTest {

    private val secRating = SecurityRating(SecurityCode.GREEN, 4)
    private val subsystems = SubsystemRatings(4, 4, 4, 4, 4)

    private val rtg = RTG("UCAS-RTG", "North America", secRating, subsystems)
    private val ltg = LTG("Seattle", rtg, secRating, subsystems)
    private val pltg = PLTG("Corp-PLTG", "Renraku", ltg, secRating, subsystems)

    private val connectedRtg = RTG("Euro-RTG", "Europe", secRating, subsystems)

    private val probe = Probe(rating = 4, guardedNode = null)
    private val dataFile = DataFile("SensitiveData.txt", isScrambleProtected = false, sizeMp = 10)
    private val scrambledFile = DataFile("Secret.txt", isScrambleProtected = true, sizeMp = 5)
    private val device = RemoteDevice("Security Camera 1", "10.0.0.1")

    private val host = Host(
        name = "Test Host",
        securityRating = secRating,
        subsystemRatings = subsystems,
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS,
        icPrograms = listOf(probe),
        dataFiles = listOf(dataFile, scrambledFile),
        remoteDevices = listOf(device)
    )

    private val ltgWithContent = LTG(
        "Seattle", rtg.copy(ltgs = listOf(ltg), connectedRtgs = listOf(connectedRtg)),
        secRating, subsystems,
        hosts = listOf(host),
        pltgs = listOf(pltg)
    )

    private val rtgWithLtgs = RTG(
        "UCAS-RTG", "North America", secRating, subsystems,
        ltgs = listOf(ltg),
        connectedRtgs = listOf(connectedRtg)
    )

    private val pltgWithHost = PLTG("Corp-PLTG", "Renraku", ltgWithContent, secRating, subsystems, hosts = listOf(host))

    private fun decker(location: MatrixLocation?): Decker {
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6, status = PersonaStatus.INTRUDING)
        val deck = Cyberdeck(
            name = "Test Deck", mcpRating = 10,
            activeMemoryMp = 500, storageMemoryMp = 1000, ioSpeedMpPerTurn = 100,
            costNuyen = 10000,
            personaPrograms = listOf(
                PersonaProgram(PersonaAttributeType.BOD, 6),
                PersonaProgram(PersonaAttributeType.EVASION, 6),
                PersonaProgram(PersonaAttributeType.MASKING, 6),
                PersonaProgram(PersonaAttributeType.SENSORS, 6)
            )
        )
        return Decker(
            name = "Test", intelligence = 6, body = 4, willpower = 5, reaction = 5, computerSkill = 6,
            cyberdeck = deck,
            persona = if (location != null) persona else null,
            jackpoint = Jackpoint(JackpointType.ILLEGAL_ACCESS, connectsToLtg = ltg),
            currentLocation = location
        )
    }

    // ── visibleObjects ────────────────────────────────────────────────────────────

    @Test
    fun `visibleObjects returns empty when not jacked in`() {
        assertTrue(decker(null).visibleObjects().isEmpty())
    }

    @Test
    fun `visibleObjects on RTG includes RTG, connected RTGs, and LTGs`() {
        val d = decker(MatrixLocation.OnRTG(rtgWithLtgs))
        val objects = d.visibleObjects()

        assertIs<MatrixObject.GridNode>(objects.first { it is MatrixObject.GridNode && (it as MatrixObject.GridNode).rtg == rtgWithLtgs })
        assertEquals(1, objects.filterIsInstance<MatrixObject.GridNode>().count { it.rtg == connectedRtg })
        assertEquals(1, objects.filterIsInstance<MatrixObject.LocalGrid>().count { it.ltg == ltg })
        assertEquals(3, objects.size) // own RTG + 1 connected RTG + 1 LTG
    }

    @Test
    fun `visibleObjects on LTG includes LTG, parent RTG, PLTGs, and hosts`() {
        val d = decker(MatrixLocation.OnLTG(ltgWithContent))
        val objects = d.visibleObjects()

        assertEquals(1, objects.filterIsInstance<MatrixObject.LocalGrid>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.GridNode>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.PrivateGrid>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.HostNode>().size)
        assertEquals(4, objects.size)
    }

    @Test
    fun `visibleObjects on PLTG includes PLTG, parent LTG, and hosts`() {
        val d = decker(MatrixLocation.OnPLTG(pltgWithHost))
        val objects = d.visibleObjects()

        assertEquals(1, objects.filterIsInstance<MatrixObject.PrivateGrid>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.LocalGrid>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.HostNode>().size)
        assertEquals(3, objects.size)
    }

    @Test
    fun `visibleObjects on Host includes host, nodes, IC, files, and devices`() {
        val d = decker(MatrixLocation.OnHost(host)).copy(detectedIcons = setOf(Icon.IcIcon(probe)))
        val objects = d.visibleObjects()

        assertEquals(1, objects.filterIsInstance<MatrixObject.HostNode>().size)
        assertEquals(SubsystemType.entries.size, objects.filterIsInstance<MatrixObject.HostSubsystem>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.IcProgram>().size)
        assertEquals(2, objects.filterIsInstance<MatrixObject.File>().size)
        assertEquals(1, objects.filterIsInstance<MatrixObject.Device>().size)
    }

    // ── availableActions ──────────────────────────────────────────────────────────

    @Test
    fun `availableActions returns empty when not jacked in`() {
        assertTrue(decker(null).availableActions().isEmpty())
    }

    @Test
    fun `availableActions on RTG includes logon navigation and grid system actions`() {
        val d = decker(MatrixLocation.OnRTG(rtgWithLtgs))
        val actions = d.availableActions()

        assertTrue(actions.any { it is AvailableAction.GracefulLogoff })
        assertTrue(actions.any { it is AvailableAction.JackOut })
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToLtg>().size)
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToRtg>().size)

        val ops = actions.filterIsInstance<AvailableAction.Operation>().map { it.operation }
        assertTrue(SystemOperation.NULL_OPERATION in ops)
        assertTrue(SystemOperation.RELOCATE_ICON !in ops)
        assertTrue(SystemOperation.LOCATE_ACCESS_NODE in ops)
    }

    @Test
    fun `availableActions on LTG includes logon to parent RTG, PLTGs, hosts, and grid system actions`() {
        val d = decker(MatrixLocation.OnLTG(ltgWithContent))
        val actions = d.availableActions()

        assertTrue(actions.any { it is AvailableAction.GracefulLogoff })
        assertTrue(actions.any { it is AvailableAction.JackOut })
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToRtg>().size)
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToPltg>().size)
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToHost>().size)

        val ops = actions.filterIsInstance<AvailableAction.Operation>().map { it.operation }
        assertTrue(SystemOperation.NULL_OPERATION in ops)
        assertTrue(SystemOperation.RELOCATE_ICON !in ops)
    }

    @Test
    fun `availableActions on PLTG includes logon to parent LTG, hosts, and grid system actions`() {
        val d = decker(MatrixLocation.OnPLTG(pltgWithHost))
        val actions = d.availableActions()

        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToLtg>().size)
        assertEquals(1, actions.filterIsInstance<AvailableAction.LogonToHost>().size)

        val ops = actions.filterIsInstance<AvailableAction.Operation>().map { it.operation }
        assertTrue(SystemOperation.NULL_OPERATION in ops)
        assertTrue(SystemOperation.RELOCATE_ICON !in ops)
    }

    @Test
    fun `availableActions on Host includes host system operations`() {
        val d = decker(MatrixLocation.OnHost(host))
        val actions = d.availableActions()

        val ops = actions.filterIsInstance<AvailableAction.Operation>().map { it.operation }
        assertTrue(SystemOperation.ANALYZE_HOST in ops)
        assertTrue(SystemOperation.ANALYZE_SECURITY in ops)
        assertTrue(SystemOperation.LOCATE_FILE in ops)
        assertTrue(SystemOperation.LOCATE_SLAVE in ops)
        assertTrue(SystemOperation.DOWNLOAD_DATA in ops)
        assertTrue(SystemOperation.EDIT_FILE in ops)
        assertTrue(SystemOperation.ANALYZE_IC in ops)
    }

    @Test
    fun `availableActions on Host lists DECRYPT_FILE only for scramble-protected files`() {
        val d = decker(MatrixLocation.OnHost(host))
        val actions = d.availableActions()

        val decryptFileActions = actions.filterIsInstance<AvailableAction.Operation>()
            .filter { it.operation == SystemOperation.DECRYPT_FILE }
        assertEquals(1, decryptFileActions.size)
        assertIs<MatrixObject.File>(decryptFileActions.first().target)
        assertEquals(scrambledFile, (decryptFileActions.first().target as MatrixObject.File).file)
    }

    @Test
    fun `availableActions on Host lists slave actions per device`() {
        val d = decker(MatrixLocation.OnHost(host))
        val actions = d.availableActions()

        val slaveActions = actions.filterIsInstance<AvailableAction.Operation>()
            .filter { it.operation in listOf(SystemOperation.CONTROL_SLAVE, SystemOperation.EDIT_SLAVE, SystemOperation.MONITOR_SLAVE) }
        assertEquals(3, slaveActions.size)
        slaveActions.forEach { assertIs<MatrixObject.Device>(it.target) }
    }

    @Test
    fun `availableActions on Host includes per-subsystem ANALYZE_SUBSYSTEM entries`() {
        val d = decker(MatrixLocation.OnHost(host))
        val actions = d.availableActions()

        val subsystemAnalyzeActions = actions.filterIsInstance<AvailableAction.Operation>()
            .filter { it.operation == SystemOperation.ANALYZE_SUBSYSTEM }
        assertEquals(SubsystemType.entries.size, subsystemAnalyzeActions.size)
    }

    @Test
    fun `availableActions on Host never includes LOCATE_DECKER`() {
        val d = decker(MatrixLocation.OnHost(host))
        val ops = d.availableActions().filterIsInstance<AvailableAction.Operation>().map { it.operation }
        assertFalse(SystemOperation.LOCATE_DECKER in ops, "LOCATE_DECKER must not appear in availableActions")
    }
}
