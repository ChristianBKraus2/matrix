package com.shadowrun.matrix.game

import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.network.Node

class GameContext(
    val host: Host,
    val securityCode: SecurityCode,
    val deckers: MutableList<Decker>,
    val activeIc: MutableList<IC>
) {
    fun unauthorizedDeckerInNode(node: Node): Decker? =
        deckers.firstOrNull { it.persona?.currentNode == node && it.persona.status == PersonaStatus.INTRUDING }

    fun unauthorizedDeckerInHost(): Decker? =
        deckers.firstOrNull { it.persona?.status == PersonaStatus.INTRUDING }

    fun updateDecker(old: Decker, new: Decker) {
        val idx = deckers.indexOf(old)
        if (idx >= 0) deckers[idx] = new
    }

    fun removeIc(ic: IC) {
        activeIc.remove(ic)
    }
}
