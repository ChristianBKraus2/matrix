package com.shadowrun.matrix.server.dto

import com.shadowrun.matrix.ic.Killer
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.integration.utility.DeckerMock
import com.shadowrun.matrix.integration.utility.GridMock
import com.shadowrun.matrix.network.DataFile
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RemoteDevice
import com.shadowrun.matrix.operations.AvailableAction
import com.shadowrun.matrix.operations.MatrixObject
import com.shadowrun.matrix.operations.SystemOperation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DtoMappingTest {

    // ── Decker.toDto() ─────────────────────────────────────────────────────────

    @Test
    fun `Decker toDto with null location produces not jacked in`() {
        val decker = DeckerMock.build(GridMock.getDefaultJackpoint())
        assertEquals("not jacked in", decker.toDto().location)
    }

    @Test
    fun `Decker toDto with OnRTG location`() {
        val rtg = GridMock.matrix.rtgs.first()
        val decker = DeckerMock.build(GridMock.getDefaultJackpoint())
            .copy(currentLocation = MatrixLocation.OnRTG(rtg))
        assertEquals("RTG: ${rtg.name}", decker.toDto().location)
    }

    @Test
    fun `Decker toDto with OnLTG location`() {
        val ltg = GridMock.matrix.rtgs.first().ltgs.first()
        val decker = DeckerMock.build(GridMock.getDefaultJackpoint())
            .copy(currentLocation = MatrixLocation.OnLTG(ltg))
        assertEquals("LTG: ${ltg.name}", decker.toDto().location)
    }

    @Test
    fun `Decker toDto with OnHost location`() {
        val host = GridMock.getDefaultHost()
        val decker = DeckerMock.build(GridMock.getDefaultJackpoint())
            .copy(currentLocation = MatrixLocation.OnHost(host))
        assertEquals("Host: ${host.name}", decker.toDto().location)
    }

    // ── MatrixObject.toDto(index) ──────────────────────────────────────────────

    @Test
    fun `MatrixObject GridNode toDto`() {
        val rtg = GridMock.matrix.rtgs.first()
        val dto = MatrixObject.GridNode(rtg).toDto(0)
        assertIs<MatrixObjectDto.GridNode>(dto)
        assertEquals(0, dto.index)
        assertEquals(rtg.name, dto.name)
        assertEquals(rtg.securityRating.code.name, dto.securityCode)
        assertEquals(rtg.ltgs.size, dto.ltgCount)
    }

    @Test
    fun `MatrixObject LocalGrid toDto`() {
        val ltg = GridMock.matrix.rtgs.first().ltgs.first()
        val dto = MatrixObject.LocalGrid(ltg).toDto(1)
        assertIs<MatrixObjectDto.LocalGrid>(dto)
        assertEquals(1, dto.index)
        assertEquals(ltg.name, dto.name)
    }

    @Test
    fun `MatrixObject HostNode toDto`() {
        val host = GridMock.getDefaultHost()
        val dto = MatrixObject.HostNode(host).toDto(2)
        assertIs<MatrixObjectDto.HostNode>(dto)
        assertEquals(2, dto.index)
        assertEquals(host.name, dto.name)
    }

    @Test
    fun `MatrixObject HostSubsystem toDto`() {
        val node = GridMock.getDefaultHost().nodes.first()
        val dto = MatrixObject.HostSubsystem(node).toDto(3)
        assertIs<MatrixObjectDto.HostSubsystem>(dto)
        assertEquals(3, dto.index)
        assertEquals(node.subsystemType.name, dto.subsystemType)
        assertEquals(node.description, dto.description)
    }

    @Test
    fun `MatrixObject IcProgram with null guardedNode toDto unanalyzed`() {
        val ic = Killer(rating = 4)
        val dto = MatrixObject.IcProgram(ic, analyzed = false).toDto(4)
        assertIs<MatrixObjectDto.IcProgram>(dto)
        assertEquals(4, dto.index)
        assertEquals(ic.name, dto.name)
        assertFalse(dto.analyzed)
        assertNull(dto.rating)
        assertNull(dto.behavior)
        assertNull(dto.guardedNodeType)
    }

    @Test
    fun `MatrixObject IcProgram with non-null guardedNode toDto analyzed`() {
        val node = GridMock.getDefaultHost().nodes.first()
        val ic = Killer(rating = 4, guardedNode = node)
        val dto = MatrixObject.IcProgram(ic, analyzed = true).toDto(5)
        assertIs<MatrixObjectDto.IcProgram>(dto)
        assertEquals(node.subsystemType.name, dto.guardedNodeType)
        assertTrue(dto.analyzed)
        assertEquals(ic.rating, dto.rating)
    }

    @Test
    fun `MatrixObject IcProgram with non-null guardedNode toDto unanalyzed hides fields`() {
        val node = GridMock.getDefaultHost().nodes.first()
        val ic = Killer(rating = 4, guardedNode = node)
        val dto = MatrixObject.IcProgram(ic, analyzed = false).toDto(5)
        assertIs<MatrixObjectDto.IcProgram>(dto)
        assertFalse(dto.analyzed)
        assertNull(dto.rating)
        assertNull(dto.behavior)
        assertNull(dto.guardedNodeType)
    }

    @Test
    fun `MatrixObject File toDto`() {
        val file = DataFile("payroll.txt", sizeMp = 5)
        val dto = MatrixObject.File(file).toDto(6)
        assertIs<MatrixObjectDto.File>(dto)
        assertEquals(6, dto.index)
        assertEquals("payroll.txt", dto.name)
        assertEquals(5, dto.sizeMp)
    }

    @Test
    fun `MatrixObject Device toDto`() {
        val device = RemoteDevice("cam-01", "LTG-9882")
        val dto = MatrixObject.Device(device).toDto(7)
        assertIs<MatrixObjectDto.Device>(dto)
        assertEquals(7, dto.index)
        assertEquals("cam-01", dto.name)
        assertEquals("LTG-9882", dto.systemAddress)
    }

    // ── AvailableAction.toDto(index) ───────────────────────────────────────────

    @Test
    fun `AvailableAction Operation with null target has null targetKind and targetName`() {
        val dto = AvailableAction.Operation(SystemOperation.NULL_OPERATION).toDto(0)
        assertIs<AvailableActionDto.Operation>(dto)
        assertNull(dto.targetKind)
        assertNull(dto.targetName)
    }

    @Test
    fun `AvailableAction Operation with IcProgram target`() {
        val ic = Probe(rating = 3)
        val target = MatrixObject.IcProgram(ic)
        val dto = AvailableAction.Operation(SystemOperation.ANALYZE_IC, target).toDto(1)
        assertIs<AvailableActionDto.Operation>(dto)
        assertEquals("IcProgram", dto.targetKind)
        assertEquals(ic.name, dto.targetName)
    }

    @Test
    fun `AvailableAction LogonToRtg toDto`() {
        val rtg = GridMock.matrix.rtgs.first()
        val dto = AvailableAction.LogonToRtg(rtg).toDto(2)
        assertIs<AvailableActionDto.LogonToRtg>(dto)
        assertEquals(rtg.name, dto.rtgName)
    }

    @Test
    fun `AvailableAction JackOut toDto`() {
        val dto = AvailableAction.JackOut().toDto(3)
        assertIs<AvailableActionDto.JackOut>(dto)
    }

    @Test
    fun `AvailableAction GracefulLogoff toDto`() {
        val dto = AvailableAction.GracefulLogoff().toDto(4)
        assertIs<AvailableActionDto.GracefulLogoff>(dto)
    }

    @Test
    fun `Decker toDto with OnPLTG location`() {
        val ltg = GridMock.matrix.rtgs.first().ltgs.first()
        val pltg = PLTG("Corp-PLTG", "Renraku", ltg,
            ltg.securityRating, ltg.subsystemRatings)
        val decker = DeckerMock.build(GridMock.getDefaultJackpoint())
            .copy(currentLocation = MatrixLocation.OnPLTG(pltg))
        assertEquals("PLTG: Corp-PLTG", decker.toDto().location)
    }

    @Test
    fun `MatrixObject PrivateGrid toDto`() {
        val ltg = GridMock.matrix.rtgs.first().ltgs.first()
        val pltg = PLTG("Corp-PLTG", "Renraku", ltg,
            ltg.securityRating, ltg.subsystemRatings)
        val dto = MatrixObject.PrivateGrid(pltg).toDto(8)
        assertIs<MatrixObjectDto.PrivateGrid>(dto)
        assertEquals(8, dto.index)
        assertEquals("Corp-PLTG", dto.name)
        assertEquals("Renraku", dto.owner)
        assertEquals(pltg.securityRating.code.name, dto.securityCode)
    }

    @Test
    fun `AvailableAction LogonToPltg toDto`() {
        val ltg = GridMock.matrix.rtgs.first().ltgs.first()
        val pltg = PLTG("Corp-PLTG", "Renraku", ltg,
            ltg.securityRating, ltg.subsystemRatings)
        val dto = AvailableAction.LogonToPltg(pltg).toDto(9)
        assertIs<AvailableActionDto.LogonToPltg>(dto)
        assertEquals("Corp-PLTG", dto.pltgName)
    }

    @Test
    fun `AvailableAction LogonToHost toDto`() {
        val host = GridMock.getDefaultHost()
        val dto = AvailableAction.LogonToHost(host).toDto(10)
        assertIs<AvailableActionDto.LogonToHost>(dto)
        assertEquals(host.name, dto.hostName)
    }
}
