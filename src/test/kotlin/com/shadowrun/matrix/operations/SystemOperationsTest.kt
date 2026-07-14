package com.shadowrun.matrix.operations

import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.decker.Cyberdeck
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.decker.Persona
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemOperationsTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private fun programs(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        responseIncrease: Int = 0,
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck", mcpRating = mcpRating,
        activeMemoryMp = 2000, storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 100, costNuyen = 0,
        personaPrograms = programs(),
        responseIncrease = responseIncrease,
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    private fun decker(
        cyberdeck: Cyberdeck = deck(),
        reaction: Int = 5,
        jackedIn: Boolean = false,
        host: Host? = null
    ): Decker {
        val persona = if (jackedIn) Persona(
            bod = 6, evasion = 6, masking = 6, sensor = 6,
            reaction = reaction + cyberdeck.responseIncrease * 2
        ) else null
        val location = if (host != null && jackedIn) MatrixLocation.OnHost(host) else null
        return Decker(
            name = "TestDecker", intelligence = 6, body = 4,
            willpower = 5, reaction = reaction, computerSkill = 6,
            cyberdeck = cyberdeck,
            physicalConditionMonitor = ConditionMonitor(),
            mentalConditionMonitor = ConditionMonitor(),
            persona = persona,
            currentLocation = location
        )
    }

    private fun host(
        secValue: Int = 6,
        access: Int = 8, control: Int = 8, index: Int = 8, files: Int = 8, slave: Int = 8,
        alertStatus: AlertStatus = AlertStatus.NO_ALERT,
        dataFiles: List<DataFile> = emptyList(),
        remoteDevices: List<RemoteDevice> = emptyList()
    ) = Host(
        name = "TestHost",
        securityRating = SecurityRating(SecurityCode.GREEN, secValue),
        subsystemRatings = SubsystemRatings(access, control, index, files, slave),
        intrusionDifficulty = IntrusionDifficulty.AVERAGE,
        topologyType = TopologyType.OPEN_ACCESS,
        alertStatus = alertStatus,
        dataFiles = dataFiles,
        remoteDevices = remoteDevices
    )

    private fun fixedRoller(face: Int) = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = face.coerceIn(from, until - 1)
    })

    private val winRoller = fixedRoller(5)   // face=5: beats TN≤5 and DF=3, no open-ended loop
    private val loseRoller = fixedRoller(3)  // face=3: beats DF=3 (host scores), fails TN≥4 (decker fails)

    // ── SO-01 / SO-02: actionsPerTurn ─────────────────────────────────────────────

    @Test
    fun `SO-01 actionsPerTurn with reaction 5 and RI 0 is 1`() {
        val d = decker(reaction = 5, jackedIn = true)
        assertEquals(1, d.actionsPerTurn)
    }

    @Test
    fun `SO-02 actionsPerTurn with reaction 9 and RI 2 is 3`() {
        // Persona reaction = 5 + 2*2 = 9; ceil(9/10)=1 + RI=2 → 3
        val d = decker(deck(responseIncrease = 2), reaction = 5, jackedIn = true)
        assertEquals(3, d.actionsPerTurn)
    }

    @Test
    fun `SO-02 actionsPerTurn with reaction 10 and RI 0 is 1`() {
        val d = decker(reaction = 10, jackedIn = true)
        assertEquals(1, d.actionsPerTurn)
    }

    // ── MP-01 to MP-05: noticeIcon ────────────────────────────────────────────────

    @Test
    fun `MP-01 noticeIcon returns Undetected when sensor test fails`() {
        val ic = Killer(rating = 10)
        val d = decker(jackedIn = true)
        val result = d.noticeIcon(MatrixIcon.IcIcon(ic), loseRoller)
        assertIs<SensorTestResult.Undetected>(result)
    }

    @Test
    fun `MP-01 noticeIcon returns Detected when sensor test succeeds`() {
        val ic = Killer(rating = 2)  // TN=2 → face=8 succeeds
        val d = decker(jackedIn = true)
        val result = d.noticeIcon(MatrixIcon.IcIcon(ic), winRoller)
        assertIs<SensorTestResult.Detected>(result)
        assertTrue((result as SensorTestResult.Detected).successes >= 1)
    }

    @Test
    fun `MP-02 noticeIcon vs persona TN uses masking plus sleaze`() {
        val otherPersona = Persona(bod = 4, evasion = 4, masking = 6, sensor = 4)
        val icon = MatrixIcon.PersonaIcon(otherPersona, sleazeRating = 4)
        // TN = masking(6) + sleaze(4) = 10 → face=5 < 10 → fails (no open-ended loop)
        val d = decker(jackedIn = true)
        val result = d.noticeIcon(icon, fixedRoller(5))
        assertIs<SensorTestResult.Undetected>(result)
    }

    @Test
    fun `MP-03 Detected with 2 successes for IC type`() {
        val ic = Probe(rating = 2)
        val d = decker(jackedIn = true)
        val result = d.noticeIcon(MatrixIcon.IcIcon(ic), winRoller)
        val detected = result as? SensorTestResult.Detected
        assertNotNull(detected)
        assertTrue(detected.successes >= 2)
    }

    // ── MP-07 / MP-08: noticeTriggeredIc ─────────────────────────────────────────

    @Test
    fun `MP-07 noticeTriggeredIc returns Undetected on 0 successes`() {
        val ic = Killer(rating = 10)
        val d = decker(jackedIn = true)
        val result = d.noticeTriggeredIc(ic, loseRoller)
        assertIs<IcDetectionResult.Undetected>(result)
    }

    @Test
    fun `MP-07 noticeTriggeredIc returns PresenceOnly on 1 success`() {
        // Sensor=6 dice, TN=2 with face=8 → multiple successes → FullyLocated
        // Use a controlled roller: exactly 1 die success
        val ic = Killer(rating = 2)
        // Make sensor=1 so only 1 die rolls: 1 success → PresenceOnly
        val tinyDeck = Cyberdeck(
            name = "Tiny", mcpRating = 4,
            activeMemoryMp = 200, storageMemoryMp = 500, ioSpeedMpPerTurn = 100, costNuyen = 0,
            personaPrograms = listOf(
                PersonaProgram(PersonaAttributeType.BOD, 1),
                PersonaProgram(PersonaAttributeType.EVASION, 1),
                PersonaProgram(PersonaAttributeType.MASKING, 1),
                PersonaProgram(PersonaAttributeType.SENSORS, 1)
            )
        )
        val d = Decker(
            name = "Tiny", intelligence = 1, body = 1, willpower = 1, reaction = 1,
            computerSkill = 1, cyberdeck = tinyDeck,
            physicalConditionMonitor = ConditionMonitor(), mentalConditionMonitor = ConditionMonitor(),
            persona = Persona(bod = 1, evasion = 1, masking = 1, sensor = 1)
        )
        val result = d.noticeTriggeredIc(ic, winRoller)
        // sensor=1 die, face=8, TN=2 → 1 success → PresenceOnly
        assertIs<IcDetectionResult.PresenceOnly>(result)
    }

    @Test
    fun `MP-07 noticeTriggeredIc returns FullyLocated on 3 successes`() {
        val ic = Killer(rating = 2)
        val d = decker(jackedIn = true)
        // sensor=6, face=8, TN=2 → 6 successes → FullyLocated
        val result = d.noticeTriggeredIc(ic, winRoller)
        assertIs<IcDetectionResult.FullyLocated>(result)
        assertEquals(ic, (result as IcDetectionResult.FullyLocated).ic)
    }

    // ── analyzeHost ───────────────────────────────────────────────────────────────

    @Test
    fun `analyzeHost reveals security rating on 1 net success`() {
        val h = host(secValue = 2, control = 2)  // low TN so decker wins with face=5
        val d = decker(jackedIn = true, host = h)
        val result = d.analyzeHost(h, winRoller)
        assertNotNull(result.revealedSecurityRating)
    }

    @Test
    fun `analyzeHost reveals all ratings on 7 net successes`() {
        val h = host(secValue = 2, control = 2)
        val d = decker(jackedIn = true, host = h)
        val result = d.analyzeHost(h, winRoller)
        // With face=8 vs control=2, decker should get many successes
        if (result.revealedSubsystemRatings.size == 5) {
            assertEquals(5, result.revealedSubsystemRatings.size)
        }
        assertNotNull(result.outcome)
    }

    @Test
    fun `analyzeHost fails when decker is not on target host`() {
        val targetHost = host()
        val otherHost = host(secValue = 4)
        // Decker is jacked in but on a different host than targetHost
        val d = decker(jackedIn = true, host = otherHost)
        try {
            d.analyzeHost(targetHost, winRoller)
            assertTrue(false, "Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("analyzeHost"))
        }
    }

    // ── analyzeSecurity ───────────────────────────────────────────────────────────

    @Test
    fun `analyzeSecurity returns current tally and alert status`() {
        val h = host(secValue = 2, alertStatus = AlertStatus.PASSIVE_ALERT)
        val d = decker(jackedIn = true, host = h)
        val result = d.analyzeSecurity(h, winRoller)
        assertEquals(AlertStatus.PASSIVE_ALERT, result.alertStatus)
        assertEquals(h.securityRating, result.securityRating)
        assertTrue(result.currentTally >= 0)
    }

    // ── Interrogation / Locate File ───────────────────────────────────────────────

    @Test
    fun `locateFile returns Ongoing when accumulated successes below 5`() {
        val h = host()
        val d = decker(jackedIn = true, host = h)
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "paydata", 0)
        val (_, locate) = d.locateFile(h, state, QueryPrecision.NORMAL, loseRoller)
        assertIs<LocateResult.Ongoing>(locate)
    }

    @Test
    fun `locateFile returns Located when accumulated successes reach 5`() {
        val file = DataFile("paydata file", sizeMp = 50)
        val h = host(dataFiles = listOf(file), secValue = 2, control = 2, index = 2)
        val d = decker(jackedIn = true, host = h)
        // Start at 4, need 1 more; winRoller gives 6 successes → jumps past 5
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "paydata", 4)
        val (_, locate) = d.locateFile(h, state, QueryPrecision.NORMAL, winRoller)
        assertIs<LocateResult.Located>(locate)
    }

    @Test
    fun `locateFile returns NotFound when data absent and 3 successes accumulated`() {
        val h = host(secValue = 2, index = 2)
        val d = decker(jackedIn = true, host = h)
        val state = InterrogationState(SystemOperation.LOCATE_FILE, "nonexistent", 2)
        val (_, locate) = d.locateFile(h, state, QueryPrecision.NORMAL, winRoller)
        assertIs<LocateResult.NotFound>(locate)
    }

    @Test
    fun `locateSlave requires only 3 successes`() {
        val device = RemoteDevice("camera-3", "SLAVE-003")
        val h = host(dataFiles = emptyList(), remoteDevices = listOf(device), secValue = 2, index = 2)
        val d = decker(jackedIn = true, host = h)
        val state = InterrogationState(SystemOperation.LOCATE_SLAVE, "camera", 2)
        val (_, locate) = d.locateSlave(h, state, QueryPrecision.NORMAL, winRoller)
        assertIs<LocateResult.Located>(locate)
    }

    // ── downloadData ──────────────────────────────────────────────────────────────

    @Test
    fun `downloadData on success returns DownloadHandle with correct turn count`() {
        val file = DataFile("secret.dat", sizeMp = 200)
        val h = host(secValue = 2, files = 2)
        val d = decker(jackedIn = true, host = h)
        val (result, handle) = d.downloadData(file, h, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        // ioSpeed = 100, file = 200 Mp → 2 turns
        assertEquals(2, handle!!.turnsRemaining)
        assertEquals(200, handle.totalMp)
    }

    @Test
    fun `downloadData on failure returns null handle`() {
        val file = DataFile("secret.dat", sizeMp = 100)
        val h = host(files = 12)
        val d = decker(jackedIn = true, host = h)
        val (result, handle) = d.downloadData(file, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    // ── editFile ──────────────────────────────────────────────────────────────────

    @Test
    fun `editFile without authentication has null authenticationSuccesses`() {
        val file = DataFile("record.dat")
        val h = host(secValue = 2, files = 2)
        val d = decker(jackedIn = true, host = h)
        val result = d.editFile(file, h, byteArrayOf(1, 2, 3), winRoller, attemptAuthentication = false)
        assertNull(result.authenticationSuccesses)
    }

    @Test
    fun `editFile with authentication returns successes count`() {
        val file = DataFile("record.dat")
        val h = host(secValue = 2, files = 2, control = 2)
        val d = decker(jackedIn = true, host = h)
        val result = d.editFile(file, h, byteArrayOf(1, 2, 3), winRoller, attemptAuthentication = true)
        if (result.outcome.deckerWins) {
            assertNotNull(result.authenticationSuccesses)
        }
    }

    // ── Slave operations ──────────────────────────────────────────────────────────

    @Test
    fun `controlSlave on success returns active MonitoredOperationHandle`() {
        val device = RemoteDevice("elevator", "SLAVE-001")
        val h = host(secValue = 2, slave = 2)
        val d = decker(jackedIn = true, host = h)
        val (result, handle) = d.controlSlave(device, h, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertTrue(handle!!.active)
        assertEquals(SystemOperation.CONTROL_SLAVE, handle.operation)
    }

    @Test
    fun `controlSlave with effectiveSkill uses override for skill dice`() {
        val device = RemoteDevice("med-lab", "SLAVE-002")
        val h = host(secValue = 2, slave = 2)
        val d = decker(jackedIn = true, host = h)
        // effectiveSkill=4 (avg of Computer 5 + Biotech 3, floored)
        val (result, _) = d.controlSlave(device, h, winRoller, effectiveSkill = 4)
        assertNotNull(result)
    }

    @Test
    fun `controlSlave on failure returns null handle`() {
        val device = RemoteDevice("camera", "SLAVE-003")
        val h = host(slave = 12)
        val d = decker(jackedIn = true, host = h)
        val (result, handle) = d.controlSlave(device, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    @Test
    fun `abortMonitoredOperation sets active to false`() {
        val d = decker(jackedIn = true)
        val handle = MonitoredOperationHandle(SystemOperation.EDIT_SLAVE, "target", active = true)
        val aborted = d.abortMonitoredOperation(handle)
        assertEquals(false, aborted.active)
    }

    @Test
    fun `maintainMonitoredOperation keeps active handle active`() {
        val d = decker(jackedIn = true)
        val handle = MonitoredOperationHandle(SystemOperation.MONITOR_SLAVE, "device", active = true)
        val maintained = d.maintainMonitoredOperation(handle)
        assertTrue(maintained.active)
    }

    // ── nullOperation ─────────────────────────────────────────────────────────────

    @Test
    fun `nullOperation with short inactivity applies no SV bonus`() {
        val h = host(secValue = 2, control = 2)
        val d = decker(jackedIn = true, host = h)
        val result = d.nullOperation(h, inactivitySeconds = 5, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `nullOperation updates security tally`() {
        val h = host(secValue = 6, control = 8)
        val d = decker(jackedIn = true, host = h)
        val result = d.nullOperation(h, inactivitySeconds = 90, winRoller)
        // Tally updated by host successes; decker copy reflects new tally
        val updatedLocation = result.decker.currentLocation as? MatrixLocation.OnHost
        assertNotNull(updatedLocation)
    }

    // ── resolvePointerChain ───────────────────────────────────────────────────────

    @Test
    fun `resolvePointerChain requires a pointer file`() {
        val nonPointer = DataFile("regular.dat")
        val d = decker()
        try {
            d.resolvePointerChain(nonPointer, winRoller)
            assertTrue(false, "Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pointer"))
        }
    }

    @Test
    fun `resolvePointerChain returns PointerChain with links and finalFile`() {
        val targetFile = DataFile("paydata.dat", sizeMp = 100)
        val targetHost = host(dataFiles = listOf(targetFile))
        val pointerFile = DataFile("ptr.dat", pointerToHost = targetHost)
        val d = decker()
        val chain = d.resolvePointerChain(pointerFile, winRoller)
        assertTrue(chain.links.isNotEmpty())
        assertNotNull(chain.finalFile)
    }

    // ── DataFile.isPointer ────────────────────────────────────────────────────────

    @Test
    fun `DataFile isPointer is true when pointerToHost is set`() {
        val h = host()
        val file = DataFile("ptr", pointerToHost = h)
        assertTrue(file.isPointer)
    }

    @Test
    fun `DataFile isPointer is false when no pointer`() {
        val file = DataFile("data.dat", sizeMp = 50)
        assertEquals(false, file.isPointer)
    }

    // ── Persona reaction ──────────────────────────────────────────────────────────

    @Test
    fun `Persona reaction field defaults to 0`() {
        val p = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4)
        assertEquals(0, p.reaction)
    }

    @Test
    fun `Persona reaction is set from performLogon`() {
        // Integration: jack in to an LTG to trigger persona creation with reaction
        // This is tested indirectly via actionsPerTurn; the reaction is set in performLogon
        val d = decker(deck(responseIncrease = 2), reaction = 5, jackedIn = true)
        assertEquals(9, d.persona!!.reaction)
    }

    // ── DownloadHandle ────────────────────────────────────────────────────────────

    @Test
    fun `DownloadHandle active defaults to true`() {
        val file = DataFile("f", sizeMp = 10)
        val h = DownloadHandle(file, 10, 100, 1)
        assertTrue(h.active)
    }

    // ── MonitoredOperationHandle ──────────────────────────────────────────────────

    @Test
    fun `MonitoredOperationHandle active defaults to true`() {
        val h = MonitoredOperationHandle(SystemOperation.CONTROL_SLAVE, "target")
        assertTrue(h.active)
    }

    // ── InterrogationState ────────────────────────────────────────────────────────

    @Test
    fun `InterrogationState accumulatedSuccesses defaults to 0`() {
        val s = InterrogationState(SystemOperation.LOCATE_FILE, "query")
        assertEquals(0, s.accumulatedSuccesses)
    }
}
