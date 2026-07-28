package com.shadowrun.matrix.game

import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.applyAlertTransition

class GameContext(
    host: Host,
    val securityCode: SecurityCode,
    val deckers: MutableList<Decker>,
    val activeIc: MutableList<IC>
) {
    var host: Host = host
        private set

    fun unauthorizedDeckerInNode(node: Node): Decker? =
        deckers.firstOrNull { it.persona?.currentNode == node && it.persona.status == PersonaStatus.INTRUDING }

    fun unauthorizedDeckerInHost(): Decker? =
        deckers.firstOrNull { it.persona?.status == PersonaStatus.INTRUDING }

    fun updateDecker(old: Decker, new: Decker) {
        val idx = deckers.indexOf(old)
        if (idx >= 0) deckers[idx] = new
    }

    fun updateHost(new: Host) {
        val old = host
        host = new
        deckers.replaceAll { decker ->
            val loc = decker.currentLocation
            if (loc is MatrixLocation.OnHost && loc.host == old)
                decker.copy(currentLocation = MatrixLocation.OnHost(new))
            else decker
        }
    }

    fun checkTriggers(oldTally: Int, newTally: Int) {
        val newlyTriggered = host.securitySheaf.triggerSteps
            .filter { it.tallyThreshold in (oldTally + 1)..newTally }
        for (step in newlyTriggered) {
            activeIc.addAll(step.activatedIc)
            step.alertTransition?.let { transition ->
                if (transition.ordinal > host.alertStatus.ordinal)
                    updateHost(applyAlertTransition(host, transition))
            }
        }
    }

    fun applyDeckerOperationResult(old: Decker, new: Decker) {
        val oldTally = (old.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: 0
        val newTally = (new.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: 0
        updateDecker(old, new)
        if (newTally > oldTally) {
            updateHost(host.copy(securityTally = newTally))
            checkTriggers(oldTally, newTally)
        }
    }

    fun removeIc(ic: IC) {
        activeIc.remove(ic)
    }
}
