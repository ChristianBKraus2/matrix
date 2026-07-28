package com.shadowrun.matrix.integration.utility

import com.shadowrun.matrix.decker.LogoffResult
import com.shadowrun.matrix.decker.LogonResult
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.LTG
import com.shadowrun.matrix.network.Matrix
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.PLTG
import com.shadowrun.matrix.network.RTG
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

    fun logonToHost(host: Host, name: String = "logon to ${host.name}") = step(name) {
        val r = currentDecker().logonToHost(host, roller)
        assertIs<LogonResult.Success>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    fun logonToHost(path: String) {
        val (rtg, ltg, host) = path.split("/")
        logonToHost(matrix.getHost(rtg, ltg, host)!!)
    }

    fun gracefulLogoff(name: String = "graceful logoff") = step(name) {
        val r = currentDecker().gracefulLogoff(roller)
        assertIs<LogoffResult.GracefulSuccess>(r, "$name failed")
        updateCurrentDecker(r.decker)
    }

    internal fun build(): List<StepAction> = steps.toList()
}
