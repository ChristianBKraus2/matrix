package com.shadowrun.matrix.game

import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Matrix
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.network.Node
import com.shadowrun.matrix.network.applyAlertTransition

class GameContext(
    host: Host,
    val securityCode: SecurityCode,
    deckers: List<Decker>,
    activeIc: List<IC> = emptyList(),
    val matrix: Matrix = Matrix()
) {
    var host: Host = host
        private set

    private val _deckers: MutableList<Decker> = deckers.toMutableList()
    /** Read-only view of deckers in this context. Use updateDecker() to mutate. */
    val deckers: List<Decker> get() = _deckers

    private val _activeIc: MutableList<IC> = activeIc.toMutableList()
    /** Read-only view of active IC programs. Use addIc()/removeIc() to mutate. */
    val activeIc: List<IC> get() = _activeIc

    fun addIc(ic: IC) { _activeIc.add(ic) }

    fun removeIc(ic: IC) { _activeIc.remove(ic) }

    fun resetToSingleDecker(decker: Decker) { _deckers.clear(); _deckers.add(decker) }

    fun deckerByName(name: String): Decker? = _deckers.firstOrNull { it.name == name }

    fun unauthorizedDeckerInNode(node: Node): Decker? =
        _deckers.firstOrNull { it.persona?.currentNode == node && it.persona.status == PersonaStatus.INTRUDING }

    fun unauthorizedDeckerInHost(): Decker? =
        _deckers.firstOrNull { it.persona?.status == PersonaStatus.INTRUDING }

    fun updateDecker(old: Decker, new: Decker) {
        val idx = _deckers.indexOf(old)
        check(idx >= 0) {
            "GameContext.updateDecker: decker '${old.name}' not found in context — this is a programming error"
        }
        _deckers[idx] = new
    }

    fun updateHost(new: Host) {
        val old = host
        host = new
        _deckers.replaceAll { decker ->
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
            _activeIc.addAll(step.activatedIc)
            step.alertTransition?.let { transition ->
                if (transition != host.alertStatus)
                    updateHost(applyAlertTransition(host, transition))
            }
        }
    }

    fun applyDeckerOperationResult(old: Decker, new: Decker) {
        val oldTally = host.securityTally
        val newTally = (new.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: oldTally
        updateDecker(old, new)
        if (newTally > oldTally) {
            val newHost = (new.currentLocation as? MatrixLocation.OnHost)?.host ?: host.copy(securityTally = newTally)
            updateHost(newHost)
            checkTriggers(oldTally, newTally)
        }
    }

    fun addToSecurityTally(points: Int) {
        require(points >= 0) { "Security tally points must be non-negative (got $points)" }
        val old = host.securityTally
        val new = old + points
        updateHost(host.copy(securityTally = new))
        checkTriggers(old, new)
    }
}
