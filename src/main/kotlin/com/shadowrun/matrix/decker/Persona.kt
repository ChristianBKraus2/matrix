package com.shadowrun.matrix.decker

import com.shadowrun.matrix.common.ConditionMonitor
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.network.Node

data class Persona(
    val bod: Int,
    val evasion: Int,
    val masking: Int,
    val sensor: Int,
    /**
     * Augmented Reaction for this persona = base Decker Reaction + (Response Increase × 2).
     * Used to compute [Decker.actionsPerTurn] (SO-01, SO-02).
     */
    val reaction: Int = 0,
    val conditionMonitor: ConditionMonitor = ConditionMonitor(),
    val status: PersonaStatus = PersonaStatus.LEGITIMATE,
    // The node (or null when on a grid / not yet logged into a host) where this persona is currently located
    val currentNode: Node? = null
)
