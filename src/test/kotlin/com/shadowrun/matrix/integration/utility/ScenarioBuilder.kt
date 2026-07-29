package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.common.SubsystemType
import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.Matrix
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.operations.HostInfoItem
import com.shadowrun.matrix.operations.OperationResult
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@DslMarker
annotation class ScenarioDsl

@ScenarioDsl
class ScenarioBuilder(private val matrix: Matrix) {

    private val steps = mutableListOf<StepAction>()

    fun step(name: String, block: StepContext.() -> Unit) {
        steps += block
    }

    fun jackInToLtg(ltg: LTG, name: String = "jack in to ${ltg.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().jackInToLtg(ltg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun jackInToLtg(path: String, succeed: Boolean = true) {
        val (rtg, ltg) = path.split("/")
        jackInToLtg(matrix.getLTG(rtg, ltg)!!, succeed = succeed)
    }

    fun jackInToHost(host: Host, name: String = "jack in to ${host.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().jackInToHost(host, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToParentRtg(name: String = "move to parent RTG", succeed: Boolean = true) = step(name) {
        val currentLtg = (currentDecker().currentLocation as MatrixLocation.OnLTG).ltg
        val r = currentDecker().logonToRtg(currentLtg.parentRtg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToRtg(rtg: RTG, name: String = "logon to ${rtg.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().logonToRtg(rtg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToRtg(path: String, succeed: Boolean = true) = step("logon to RTG $path") {
        val targetName = path.split("/").last()
        val rtg = when (val loc = currentDecker().currentLocation) {
            is MatrixLocation.OnLTG  -> loc.ltg.parentRtg.let {
                if (it.name == targetName) it
                else it.connectedRtgs.first { r -> r.name == targetName }
            }
            is MatrixLocation.OnRTG  -> loc.rtg.connectedRtgs.first { it.name == targetName }
            else -> matrix.getRTG(targetName)!!
        }
        val r = currentDecker().logonToRtg(rtg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "logon to RTG $path failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "logon to RTG $path should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToLtg(ltg: LTG, name: String = "logon to ${ltg.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().logonToLtg(ltg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToLtg(path: String, succeed: Boolean = true) = step("logon to LTG $path") {
        val ltgName = path.split("/").last()
        val ltg = when (val loc = currentDecker().currentLocation) {
            is MatrixLocation.OnRTG  -> loc.rtg.ltgs.first { it.name == ltgName }
            is MatrixLocation.OnPLTG -> loc.pltg.parentLtg.parentRtg.ltgs.first { it.name == ltgName }
            else -> matrix.getLTG(path.split("/")[0], ltgName)!!
        }
        val r = currentDecker().logonToLtg(ltg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "logon to LTG $path failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "logon to LTG $path should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToPltg(pltg: PLTG, name: String = "logon to ${pltg.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().logonToPltg(pltg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToPltg(ltgPath: String, succeed: Boolean = true) = step("logon to PLTG under $ltgPath") {
        val ltgName = ltgPath.split("/").last()
        val ltg = when (val loc = currentDecker().currentLocation) {
            is MatrixLocation.OnLTG -> loc.ltg
            else -> matrix.getLTG(ltgPath.split("/")[0], ltgName)!!
        }
        val pltg = ltg.pltgs.first()
        val r = currentDecker().logonToPltg(pltg, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "logon to PLTG under $ltgPath failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "logon to PLTG under $ltgPath should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToHost(host: Host, name: String = "logon to ${host.name}", succeed: Boolean = true) = step(name) {
        val r = currentDecker().logonToHost(host, roller)
        if (succeed) {
            assertIs<LogonResult.Success>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogonResult.Failure>(r, "$name should have failed")
            updateCurrentDecker(r.decker)
        }
    }

    fun logonToHost(path: String, succeed: Boolean = true) {
        val (rtg, ltg, host) = path.split("/")
        logonToHost(matrix.getHost(rtg, ltg, host)!!, succeed = succeed)
    }

    fun gracefulLogoff(name: String = "graceful logoff", succeed: Boolean = true) = step(name) {
        val r = currentDecker().gracefulLogoff(roller)
        if (succeed) {
            assertIs<LogoffResult.GracefulSuccess>(r, "$name failed")
            updateCurrentDecker(r.decker)
        } else {
            assertIs<LogoffResult.JackOut>(r, "$name should have fallen back to jack-out")
            updateCurrentDecker(r.decker)
        }
    }

    fun jackOut(name: String = "jack out") = step(name) {
        val r = currentDecker().jackOut()
        assertIs<LogoffResult.JackOut>(r, "$name failed")
        assertTrue(r.dumpShock, "Expected dumpShock=true for non-immune cyberdeck")
        updateCurrentDecker(r.decker)
    }

    fun analyzeSubsystem(
        subsystem: SubsystemType,
        name: String = "analyzeSubsystem $subsystem",
        succeed: Boolean = true
    ) = step(name) {
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.analyzeSubsystem(host, subsystem, roller)
        if (succeed) assertIs<OperationResult.Success>(result, "$name should succeed")
        else assertIs<OperationResult.Failure>(result, "$name should fail")
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
    }

    fun navigateToNode(
        subsystem: SubsystemType,
        name: String = "navigate to $subsystem node",
        succeed: Boolean = true
    ) = step(name) {
        val node = context.host.nodes.firstOrNull { it.subsystemType == subsystem }
            ?: error("No $subsystem node found on host ${context.host.name}")
        val d = currentDecker()
        updateCurrentDecker(d.copy(persona = d.persona!!.copy(currentNode = if (succeed) node else null)))
    }

    fun decryptAccess(
        name: String = "decryptAccess",
        succeed: Boolean = true
    ) = step(name) {
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.decryptAccess(host, roller)
        if (succeed) assertIs<OperationResult.Success>(result, "$name should succeed")
        else assertIs<OperationResult.Failure>(result, "$name should fail")
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
    }

    fun analyzeSecurity(
        name: String = "analyzeSecurity",
        assertTallyAtLeast: Int? = null
    ) = step(name) {
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.analyzeSecurity(host, roller)
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
        if (assertTallyAtLeast != null)
            assertTrue(result.currentTally >= assertTallyAtLeast, "Expected tally >= $assertTallyAtLeast but was ${result.currentTally}")
    }

    fun analyzeHost(
        items: List<HostInfoItem> = listOf(HostInfoItem.SecurityRating),
        name: String = "analyzeHost",
        succeed: Boolean = true
    ) = step(name) {
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.analyzeHost(host, items, roller)
        if (succeed) assertTrue(result.outcome.deckerWins, "$name: decker should win the System Test")
        else assertFalse(result.outcome.deckerWins, "$name: host should win the System Test")
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
    }

    fun analyzeIc(
        ic: IC,
        name: String = "analyzeIc ${ic.name}",
        succeed: Boolean = true
    ) = step(name) {
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.analyzeIc(ic, host, roller)
        if (succeed) assertIs<OperationResult.Success>(result, "$name should succeed")
        else assertIs<OperationResult.Failure>(result, "$name should fail")
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
    }

    fun analyzeFirstActiveIc(
        name: String = "analyzeFirstActiveIc",
        succeed: Boolean = true
    ) = step(name) {
        val ic = context.activeIc.first()
        val host = (currentDecker().currentLocation as MatrixLocation.OnHost).host
        val old = currentDecker()
        val result = old.analyzeIc(ic, host, roller)
        if (succeed) assertIs<OperationResult.Success>(result, "$name should succeed")
        else assertIs<OperationResult.Failure>(result, "$name should fail")
        updateCurrentDecker(result.decker)
        context.applyDeckerOperationResult(old, result.decker)
    }

    internal fun build(): List<StepAction> = steps.toList()
}
