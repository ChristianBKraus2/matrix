package com.shadowrun.matrix.ic

import com.shadowrun.matrix.common.IcBehavior
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.network.Node

sealed class IC(
    val name: String,
    val rating: Int,
    val behavior: IcBehavior,
    // Reactive IC may guard a specific node/subsystem; null means host-level / proactive
    val guardedNode: Node? = null
) {
    fun initiativeDice(securityCode: SecurityCode): Int = when (securityCode) {
        SecurityCode.BLUE   -> 1
        SecurityCode.GREEN  -> 2
        SecurityCode.ORANGE -> 3
        SecurityCode.RED    -> 4
    }
}

sealed class WhiteIC(name: String, rating: Int, behavior: IcBehavior, guardedNode: Node? = null) :
    IC(name, rating, behavior, guardedNode)

class Crippler(rating: Int, val targetAttribute: PersonaAttributeType, guardedNode: Node? = null) :
    WhiteIC("Crippler-${targetAttribute.name}", rating, IcBehavior.PROACTIVE, guardedNode)

class Killer(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Killer", rating, IcBehavior.PROACTIVE, guardedNode)

class Probe(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Probe", rating, IcBehavior.REACTIVE, guardedNode)

class Scramble(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Scramble", rating, IcBehavior.REACTIVE, guardedNode)

class TarBaby(rating: Int, guardedNode: Node? = null) :
    WhiteIC("Tar Baby", rating, IcBehavior.REACTIVE, guardedNode)

sealed class GrayIC(name: String, rating: Int, behavior: IcBehavior, guardedNode: Node? = null) :
    IC(name, rating, behavior, guardedNode)

class Blaster(rating: Int, guardedNode: Node? = null) :
    GrayIC("Blaster", rating, IcBehavior.PROACTIVE, guardedNode)

class Ripper(rating: Int, val targetAttribute: PersonaAttributeType, guardedNode: Node? = null) :
    GrayIC("Ripper-${targetAttribute.name}", rating, IcBehavior.PROACTIVE, guardedNode)

class Sparky(rating: Int, guardedNode: Node? = null) :
    GrayIC("Sparky", rating, IcBehavior.PROACTIVE, guardedNode)

class TarPit(rating: Int, guardedNode: Node? = null) :
    GrayIC("Tar Pit", rating, IcBehavior.REACTIVE, guardedNode)

sealed class BlackIC(name: String, rating: Int, guardedNode: Node? = null) :
    IC(name, rating, IcBehavior.PROACTIVE, guardedNode)

class LethalBlackIC(rating: Int, guardedNode: Node? = null) :
    BlackIC("Lethal Black IC", rating, guardedNode)

class NonLethalBlackIC(rating: Int, guardedNode: Node? = null) :
    BlackIC("Non-Lethal Black IC", rating, guardedNode)

