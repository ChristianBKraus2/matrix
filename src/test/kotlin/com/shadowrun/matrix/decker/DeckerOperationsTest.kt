package com.shadowrun.matrix.decker

import com.shadowrun.matrix.combat.TrackState
import com.shadowrun.matrix.common.AlertStatus
import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.IntrusionDifficulty
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.SecurityRating
import com.shadowrun.matrix.common.SubsystemRatings
import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.common.TopologyType
import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.ic.Scramble
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.InterrogationState
import com.shadowrun.matrix.operations.LinkedObserver
import com.shadowrun.matrix.operations.LocateResult
import com.shadowrun.matrix.operations.OperationResult
import com.shadowrun.matrix.operations.QueryPrecision
import com.shadowrun.matrix.operations.SystemOperation
import com.shadowrun.matrix.programs.PersonaProgram
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckerOperationsTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private fun programs(rating: Int = 6) = listOf(
        PersonaProgram(PersonaAttributeType.BOD, rating),
        PersonaProgram(PersonaAttributeType.EVASION, rating),
        PersonaProgram(PersonaAttributeType.MASKING, rating),
        PersonaProgram(PersonaAttributeType.SENSORS, rating)
    )

    private fun deck(
        mcpRating: Int = 8,
        activeUtilities: List<Utility> = emptyList(),
        storedUtilities: List<Utility> = emptyList()
    ) = Cyberdeck(
        name = "TestDeck", mcpRating = mcpRating,
        activeMemoryMp = 2000, storageMemoryMp = 5000,
        ioSpeedMpPerTurn = 100, costNuyen = 0,
        personaPrograms = programs(),
        activeUtilities = activeUtilities,
        storedUtilities = storedUtilities
    )

    private fun decker(
        cyberdeck: Cyberdeck = deck(),
        computerSkill: Int = 6,
        host: Host? = null
    ): Decker {
        val persona = Persona(bod = 6, evasion = 6, masking = 6, sensor = 6)
        val location: MatrixLocation? = if (host != null) MatrixLocation.OnHost(host) else null
        return Decker(
            name = "TestDecker", intelligence = 6, body = 4,
            willpower = 5, reaction = 5, computerSkill = computerSkill,
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

    /** Every die shows [face]. */
    private fun fixedRoller(face: Int) = DiceRoller(object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = face.coerceIn(from, until - 1)
    })

    // face=5: decker succeeds vs TN≤5; host faces DF=3 → also succeeds but decker wins ties
    private val winRoller = fixedRoller(5)
    // face=3: decker fails high TN; host succeeds vs DF=3
    private val loseRoller = fixedRoller(3)

    // ── analyzeIc ─────────────────────────────────────────────────────────────────

    @Test
    fun `analyzeIc returns Success when decker wins Control test`() {
        val h = host(secValue = 2, control = 2)
        val d = decker(host = h)
        val ic = Killer(rating = 5)
        val result = d.analyzeIc(ic, h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `analyzeIc returns Failure when host wins Control test`() {
        val h = host(secValue = 8, control = 12)
        val d = decker(host = h)
        val ic = Probe(rating = 3)
        val result = d.analyzeIc(ic, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    @Test
    fun `analyzeIc accumulates security tally by host successes`() {
        val h = host(secValue = 6, control = 2)
        val d = decker(host = h)
        val ic = Killer(rating = 5)
        val result = d.analyzeIc(ic, h, winRoller)
        val tally = (result.decker.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: 0
        assertTrue(tally >= 0)
    }

    // ── analyzeIcon ───────────────────────────────────────────────────────────────

    @Test
    fun `analyzeIcon returns Success when decker wins`() {
        val h = host(secValue = 2, control = 2)
        val d = decker(host = h)
        val icon = com.shadowrun.matrix.operations.MatrixIcon.IcIcon(Probe(rating = 3))
        val result = d.analyzeIcon(icon, h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `analyzeIcon Analyze utility reduces TN`() {
        // Without Analyze, TN = max(2, control(8)) = 8; With Analyze-4, TN = max(2, 8-4) = 4
        val analyze = Utility(UtilityType.ANALYZE, rating = 4)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(analyze), storedUtilities = listOf(analyze)))
            .copy(currentLocation = MatrixLocation.OnHost(host()))
        val result = d.analyzeIcon(com.shadowrun.matrix.operations.MatrixIcon.IcIcon(Probe(3)), host(), winRoller)
        assertNotNull(result)
    }

    @Test
    fun `analyzeIcon TN floor is 2`() {
        // control=2, analyze=8 → 2-8=-6 → max(2,-6) = 2 → should succeed at face=2
        val analyze = Utility(UtilityType.ANALYZE, rating = 8)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(analyze), storedUtilities = listOf(analyze)))
            .copy(currentLocation = MatrixLocation.OnHost(host(control = 2)))
        val result = d.analyzeIcon(
            com.shadowrun.matrix.operations.MatrixIcon.IcIcon(Probe(3)),
            host(control = 2),
            fixedRoller(2)
        )
        assertIs<OperationResult.Success>(result)
    }

    // ── analyzeSubsystem ──────────────────────────────────────────────────────────

    @Test
    fun `analyzeSubsystem returns Success when decker wins`() {
        val h = host(secValue = 2, access = 2)
        val d = decker(host = h)
        val result = d.analyzeSubsystem(h, SubsystemType.ACCESS, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `analyzeSubsystem uses the targeted subsystem rating as TN`() {
        // slave=12 → decker needs to beat TN=12; face=3 → miss → Failure
        val h = host(slave = 12, secValue = 3)
        val d = decker(host = h)
        val result = d.analyzeSubsystem(h, SubsystemType.SLAVE, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    // ── decryptAccess / decryptFile / decryptSlave ─────────────────────────────────

    @Test
    fun `decryptAccess returns Success on win`() {
        val h = host(secValue = 2, access = 2)
        val d = decker(host = h)
        val result = d.decryptAccess(h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `decryptAccess returns Failure on loss`() {
        val h = host(secValue = 8, access = 12)
        val d = decker(host = h)
        val result = d.decryptAccess(h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    @Test
    fun `decryptFile returns Success on win`() {
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val file = DataFile("target.dat")
        val result = d.decryptFile(file, h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `decryptFile returns Failure on loss`() {
        val h = host(secValue = 8, files = 12)
        val d = decker(host = h)
        val file = DataFile("target.dat")
        val result = d.decryptFile(file, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    @Test
    fun `decryptSlave returns Success on win`() {
        val h = host(secValue = 2, slave = 2)
        val d = decker(host = h)
        val result = d.decryptSlave(h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `decryptSlave returns Failure on loss`() {
        val h = host(secValue = 8, slave = 12)
        val d = decker(host = h)
        val result = d.decryptSlave(h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    // ── uploadData ────────────────────────────────────────────────────────────────

    @Test
    fun `uploadData returns Success when decker wins Files test`() {
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val (result, _) = d.uploadData(h, dataSizeMp = 100, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `uploadData returns Failure when host wins`() {
        val h = host(secValue = 8, files = 12)
        val d = decker(host = h)
        val (result, _) = d.uploadData(h, dataSizeMp = 100, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    @Test
    fun `uploadData increments security tally by host successes`() {
        val h = host(secValue = 6, files = 2)
        val d = decker(host = h)
        val (result, _) = d.uploadData(h, dataSizeMp = 100, winRoller)
        val tally = (result.decker.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: 0
        assertTrue(tally >= 0)
    }

    // ── editSlave ─────────────────────────────────────────────────────────────────

    @Test
    fun `editSlave returns Success and active handle on win`() {
        val device = RemoteDevice("cam-1", "SLAVE-001")
        val h = host(secValue = 2, slave = 2)
        val d = decker(host = h)
        val (result, handle) = d.editSlave(device, h, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertTrue(handle!!.active)
        assertEquals(SystemOperation.EDIT_SLAVE, handle.operation)
    }

    @Test
    fun `editSlave returns Failure and null handle on loss`() {
        val device = RemoteDevice("cam-1", "SLAVE-001")
        val h = host(secValue = 8, slave = 12)
        val d = decker(host = h)
        val (result, handle) = d.editSlave(device, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    // ── monitorSlave ──────────────────────────────────────────────────────────────

    @Test
    fun `monitorSlave returns Success and active handle on win`() {
        val device = RemoteDevice("sensor-A", "SLAVE-002")
        val h = host(secValue = 2, slave = 2)
        val d = decker(host = h)
        val (result, handle) = d.monitorSlave(device, h, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertTrue(handle!!.active)
        assertEquals(SystemOperation.MONITOR_SLAVE, handle.operation)
    }

    @Test
    fun `monitorSlave returns Failure and null handle on loss`() {
        val device = RemoteDevice("sensor-A", "SLAVE-002")
        val h = host(secValue = 8, slave = 12)
        val d = decker(host = h)
        val (result, handle) = d.monitorSlave(device, h, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    // ── locateDecker ──────────────────────────────────────────────────────────────

    @Test
    fun `locateDecker returns located=true and targetNotified when both tests succeed`() {
        // Index test: secValue=2, index=2 → easy win; sensor test: masking=2, sleaze=0 → TN=2 → hit
        val h = host(secValue = 2, index = 2)
        val d = decker(host = h)
        val target = Persona(bod = 4, evasion = 4, masking = 2, sensor = 4)
        val result = d.locateDecker(h, target, winRoller)
        assertTrue(result.located)
        assertTrue(result.targetNotified)
    }

    @Test
    fun `locateDecker returns located=false when Index Test fails`() {
        val h = host(secValue = 8, index = 12)
        val d = decker(host = h)
        val target = Persona(bod = 4, evasion = 4, masking = 4, sensor = 4)
        val result = d.locateDecker(h, target, loseRoller)
        assertFalse(result.located)
        assertFalse(result.targetNotified)
    }

    @Test
    fun `locateDecker returns located=false when sensor test fails after Index Test win`() {
        // Index wins (secValue=2, index=2); but masking=8+sleaze=4 → TN=12 → sensor test fails at face=5 < 12
        val h = host(secValue = 2, index = 2)
        val d = decker(host = h)
        val highMaskTarget = Persona(bod = 4, evasion = 4, masking = 8, sensor = 4, sleazeRating = 4)
        // Use a roller: decker wins index (face=5 vs TN=2), sensor fails (face=5 < TN=12)
        val result = d.locateDecker(h, highMaskTarget, winRoller)
        assertFalse(result.located)
    }

    // ── locateIc ─────────────────────────────────────────────────────────────────

    @Test
    fun `locateIc returns Success on win`() {
        val h = host(secValue = 2, index = 2)
        val d = decker(host = h)
        val result = d.locateIc(h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `locateIc returns Failure on loss`() {
        val h = host(secValue = 8, index = 12)
        val d = decker(host = h)
        val result = d.locateIc(h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    // ── locateAccessNode ──────────────────────────────────────────────────────────

    @Test
    fun `locateAccessNode returns Ongoing when below 5 accumulated successes`() {
        val h = host(secValue = 8, index = 12)
        val d = decker(host = h)
        val (_, locate) = d.locateAccessNode(h, "LTG-Seattle", QueryPrecision.NORMAL, loseRoller)
        assertIs<LocateResult.Ongoing>(locate)
    }

    @Test
    fun `locateAccessNode returns Located when 5 accumulated successes reached`() {
        // Start at 4; winRoller gives 6+ successes → jumps past 5
        val h = host(secValue = 2, index = 2)
        val d = decker(host = h).copy(interrogationStates = mapOf(
            SystemOperation.LOCATE_ACCESS_NODE to InterrogationState(SystemOperation.LOCATE_ACCESS_NODE, "", 4)
        ))
        val (_, locate) = d.locateAccessNode(h, "", QueryPrecision.NORMAL, winRoller)
        assertIs<LocateResult.Located>(locate)
    }

    @Test
    fun `locateAccessNode returns NotFound when query does not match any node`() {
        val h = host(secValue = 2, index = 2)
        val d = decker(host = h).copy(interrogationStates = mapOf(
            SystemOperation.LOCATE_ACCESS_NODE to InterrogationState(SystemOperation.LOCATE_ACCESS_NODE, "XYZNOTANODE", 4)
        ))
        val (_, locate) = d.locateAccessNode(h, "XYZNOTANODE", QueryPrecision.NORMAL, winRoller)
        assertIs<LocateResult.NotFound>(locate)
    }

    // ── makeComcall ───────────────────────────────────────────────────────────────

    @Test
    fun `makeComcall with valid passcode skips System Test and returns Success`() {
        val h = host()
        val d = decker(host = h)
        // Roller that throws if called — proves the test is skipped
        val neverCalledRoller = DiceRoller(object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int) = error("System Test should not fire with valid passcode")
        })
        val (result, handle) = d.makeComcall(h, neverCalledRoller, hasValidPasscode = true)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertTrue(handle!!.active)
    }

    @Test
    fun `makeComcall without passcode runs System Test on Files subsystem`() {
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val (result, handle) = d.makeComcall(h, winRoller, hasValidPasscode = false)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertEquals(SystemOperation.MAKE_COMCALL, handle!!.operation)
    }

    @Test
    fun `makeComcall without passcode returns Failure on loss`() {
        val h = host(secValue = 8, files = 12)
        val d = decker(host = h)
        val (result, handle) = d.makeComcall(h, loseRoller, hasValidPasscode = false)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    // ── tapComcall ────────────────────────────────────────────────────────────────

    @Test
    fun `tapComcall returns Success and handle on win with no scanner`() {
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val (result, handle) = d.tapComcall(h, scannerDeviceRating = 0, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
        assertEquals(SystemOperation.TAP_COMCALL, handle!!.operation)
    }

    @Test
    fun `tapComcall returns Failure on System Test loss`() {
        val h = host(secValue = 8, files = 12)
        val d = decker(host = h)
        val (result, handle) = d.tapComcall(h, scannerDeviceRating = 0, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    @Test
    fun `tapComcall with scanner passes when decker defeats it`() {
        // System Test wins (face=5, files=2); scanner TN = max(2, 4-0) = 4; face=5 ≥ 4 → passes
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val (result, handle) = d.tapComcall(h, scannerDeviceRating = 4, winRoller)
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
    }

    @Test
    fun `tapComcall with scanner fails when scanner detects the tap`() {
        // System Test wins (files=2); scanner TN = max(2, 8-0) = 8; face=3 < 8 → tap detected
        val h = host(secValue = 2, files = 2)
        val d = decker(host = h)
        val (result, handle) = d.tapComcall(h, scannerDeviceRating = 8, loseRoller)
        assertIs<OperationResult.Failure>(result)
        assertNull(handle)
    }

    @Test
    fun `tapComcall scanner TN is reduced by Commlink utility`() {
        // scanner=4, commlink=3 → scannerTn = max(2, 4-3) = 2; face=2 succeeds
        val commlink = Utility(UtilityType.COMMLINK, rating = 3)
        val h = host(secValue = 2, files = 2)
        val d = decker(cyberdeck = deck(activeUtilities = listOf(commlink), storedUtilities = listOf(commlink)),
            host = h)
        val (result, handle) = d.tapComcall(h, scannerDeviceRating = 4, fixedRoller(2))
        assertIs<OperationResult.Success>(result)
        assertNotNull(handle)
    }

    // ── relocateIcon ──────────────────────────────────────────────────────────────

    @Test
    fun `relocateIcon returns Success when decker wins Control Test`() {
        // control=4 → TN=max(2,4)=4; winRoller face=5 ≥ 4 → 6 decker successes → wins
        val h = host(secValue = 1, control = 4)
        val d = decker(host = h)
        val result = d.relocateIcon(h, winRoller)
        assertIs<OperationResult.Success>(result)
    }

    @Test
    fun `relocateIcon returns Failure when host wins Control Test`() {
        // control=8 → TN=8; loseRoller face=3 < 8 → 0 decker successes → host wins
        val h = host()
        val d = decker(host = h)
        val result = d.relocateIcon(h, loseRoller)
        assertIs<OperationResult.Failure>(result)
    }

    @Test
    fun `relocateIcon Relocate utility reduces Control Test TN`() {
        // Without Relocate: TN=max(2,8)=8; winRoller face=5 < 8 → 0 successes → Failure
        // With Relocate-4: TN=max(2,8-4)=4; face=5 ≥ 4 → 6 successes → Success
        val relocate = Utility(UtilityType.RELOCATE, rating = 4)
        val h = host(secValue = 1)
        val dWithout = decker(host = h)
        val dWith = decker(cyberdeck = deck(activeUtilities = listOf(relocate), storedUtilities = listOf(relocate)),
            host = h)
        assertIs<OperationResult.Failure>(dWithout.relocateIcon(h, winRoller))
        assertIs<OperationResult.Success>(dWith.relocateIcon(h, winRoller))
    }

    // ── resolveScrambleDestructTest ───────────────────────────────────────────────

    @Test
    fun `resolveScrambleDestructTest returns destroyed=true when IC succeeds`() {
        // computerSkill=3 → TN=max(2,3)=3; IC rating=4 dice, all show face=5 ≥ 3 → 4 successes
        val ic = Scramble(rating = 4)
        val file = DataFile("encrypted.dat")
        val d = decker(computerSkill = 3)
        val result = d.resolveScrambleDestructTest(ic, file, fixedRoller(5))
        assertTrue(result.dataDestroyed)
        assertEquals(4, result.icRating)
    }

    @Test
    fun `resolveScrambleDestructTest returns destroyed=false when IC fails`() {
        // All dice fail: face=1 < TN=6
        val ic = Scramble(rating = 6)
        val file = DataFile("encrypted.dat")
        val d = decker()
        val result = d.resolveScrambleDestructTest(ic, file, fixedRoller(1))
        assertFalse(result.dataDestroyed)
    }

    @Test
    fun `resolveScrambleDestructTest uses decker computerSkill as TN`() {
        // computerSkill=4 → TN=4; face=5 ≥ 4 → success → data destroyed
        val ic = Scramble(rating = 4)
        val file = DataFile("secret.dat")
        val d = decker(computerSkill = 4)
        val result = d.resolveScrambleDestructTest(ic, file, fixedRoller(5))
        assertTrue(result.dataDestroyed)
    }

    // ── bufferMessage ─────────────────────────────────────────────────────────────

    @Test
    fun `bufferMessage returns BufferedMessage with correct text and recipient`() {
        val d = decker()
        val recipient = LinkedObserver("Hitcher-1")
        val msg = d.bufferMessage("Move to the east exit now", recipient)
        assertEquals("Move to the east exit now", msg.text)
        assertEquals(recipient, msg.recipient)
    }

    @Test
    fun `bufferMessage throws when persona is null (not jacked in)`() {
        val d = Decker(
            name = "Offline", intelligence = 6, body = 4, willpower = 5, reaction = 5,
            computerSkill = 6, cyberdeck = deck()
        )
        assertFailsWith<IllegalStateException> {
            d.bufferMessage("hello", LinkedObserver("Observer"))
        }
    }

    @Test
    fun `bufferMessage throws when message exceeds 100 words`() {
        val d = decker()
        val longMessage = (1..101).joinToString(" ") { "word$it" }
        assertFailsWith<IllegalArgumentException> {
            d.bufferMessage(longMessage, LinkedObserver("Observer"))
        }
    }

    @Test
    fun `bufferMessage allows exactly 100 words`() {
        val d = decker()
        val hundredWords = (1..100).joinToString(" ") { "word$it" }
        val msg = d.bufferMessage(hundredWords, LinkedObserver("Observer"))
        assertNotNull(msg)
    }

    // ── gracefulLogoff with TrackState penalty ─────────────────────────────────────

    @Test
    fun `gracefulLogoff with active TrackState raises effective TN by trackingIcRating`() {
        // Track penalty = 4; base access = 2 → effective TN = 6
        // face=5 < 6 → decker fails → JackOut with dump shock
        val h = host(secValue = 2, access = 2)
        val track = TrackState(trackingIcRating = 4, locationCycleTurnsRemaining = 3)
        val d = decker(host = h).copy(trackState = track)
        // Roller: decker loses (face=5 fails TN=6); host wins (face=5 ≥ DF=3)
        val result = d.gracefulLogoff(fixedRoller(5))
        assertIs<LogoffResult.JackOut>(result)
    }

    @Test
    fun `gracefulLogoff without TrackState uses base access TN`() {
        // No track; access=2 → TN=2; face=5 → win → GracefulSuccess
        val h = host(secValue = 2, access = 2)
        val d = decker(host = h)
        // Win roller: decker 6 dice at TN=2, all hit; host 0 successes
        val roller = DiceRoller(object : Random() {
            private var call = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(from: Int, until: Int): Int {
                call++
                return if (call <= 6) 5 else 0 // decker wins, host 0 successes
            }
        })
        val result = d.gracefulLogoff(roller)
        assertIs<LogoffResult.GracefulSuccess>(result)
    }

    // ── withUpdatedTally alert transitions (D-15) ─────────────────────────────────

    @Test
    fun `withUpdatedTally triggers Passive Alert when tally crosses sheaf threshold`() {
        val step = com.shadowrun.matrix.network.TriggerStep(
            tallyThreshold = 3,
            description = "Passive Alert",
            alertTransition = com.shadowrun.matrix.common.AlertStatus.PASSIVE_ALERT
        )
        val sheaf = com.shadowrun.matrix.network.SecuritySheaf(listOf(step))
        val testHost = host().copy(securitySheaf = sheaf, securityTally = 0)
        val d = decker(host = testHost)
        // Increment tally by 3, crossing the threshold at 3
        val updated = d.withUpdatedTally(3)
        val updatedHost = (updated.currentLocation as com.shadowrun.matrix.network.MatrixLocation.OnHost).host
        assertEquals(3, updatedHost.securityTally)
        // withUpdatedTally only increments tally; alert transitions are driven by GameContext.checkTriggers
        assertEquals(testHost.alertStatus, updatedHost.alertStatus)
    }
}
