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
import com.shadowrun.matrix.operations.OperationResult
import kotlin.test.assertIs

@DslMarker
annotation class ScenarioDsl

@ScenarioDsl
class ScenarioBuilder(private val matrix: Matrix) {

    private val steps = mutableListOf<StepAction>()

    fun step(name: String, block: StepContext.() -> Unit) {
        steps += block
    }

    fun jackInToLtg(ltg: LTG, name: String = "jack in to ${ltg.name}") = step(name) {
        val r = currentDecker().jackInToLtg(ltg, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    fun jackInToLtg(path: String) {
        val (rtg, ltg) = path.split("/")
        jackInToLtg(matrix.getLTG(rtg, ltg)!!)
    }

    fun logonToParentRtg(name: String = "move to parent RTG") = step(name) {
        val currentLtg = (currentDecker().currentLocation as MatrixLocation.OnLTG).ltg
        val r = currentDecker().logonToRtg(currentLtg.parentRtg, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    fun logonToRtg(rtg: RTG, name: String = "logon to ${rtg.name}") = step(name) {
        val r = currentDecker().logonToRtg(rtg, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    fun logonToRtg(path: String) = logonToRtg(matrix.getRTG(path)!!)

    fun logonToLtg(ltg: LTG, name: String = "logon to ${ltg.name}") = step(name) {
        val r = currentDecker().logonToLtg(ltg, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    fun logonToLtg(path: String) {
        val (rtg, ltg) = path.split("/")
        logonToLtg(matrix.getLTG(rtg, ltg)!!)
    }

    fun logonToPltg(pltg: PLTG, name: String = "logon to ${pltg.name}") = step(name) {
        val r = currentDecker().logonToPltg(pltg, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
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

    fun gracefulLogoff(name: String = "graceful logoff") = step(name) {
        val r = currentDecker().gracefulLogoff(roller)
        assertIs<LogoffResult.GracefulSuccess>(r, "$name failed")
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

    internal fun build(): List<StepAction> = steps.toList()
}
