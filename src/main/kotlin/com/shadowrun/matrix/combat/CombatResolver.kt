package com.shadowrun.matrix.combat

import com.shadowrun.matrix.common.CombatManeuverType
import com.shadowrun.matrix.common.DamageLevel
import com.shadowrun.matrix.common.IcBehavior
import com.shadowrun.matrix.common.PersonaAttributeType
import com.shadowrun.matrix.common.PersonaStatus
import com.shadowrun.matrix.common.SecurityCode
import com.shadowrun.matrix.common.boxes
import com.shadowrun.matrix.decker.Decker
import com.shadowrun.matrix.ic.Blaster
import com.shadowrun.matrix.ic.BlackIC
import com.shadowrun.matrix.ic.Crippler
import com.shadowrun.matrix.ic.GrayIC
import com.shadowrun.matrix.ic.IC
import com.shadowrun.matrix.ic.LethalBlackIC
import com.shadowrun.matrix.ic.NonLethalBlackIC
import com.shadowrun.matrix.ic.Probe
import com.shadowrun.matrix.ic.Ripper
import com.shadowrun.matrix.ic.Sparky
import com.shadowrun.matrix.ic.TarBaby
import com.shadowrun.matrix.ic.TarPit
import com.shadowrun.matrix.ic.WhiteIC
import com.shadowrun.matrix.network.Host
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.math.ceil
import kotlin.math.max

object CombatResolver {

    // ── Initiative ────────────────────────────────────────────────────────────────

    fun rollDeckerInitiative(decker: Decker, meatworldComm: Boolean, diceRoller: DiceRoller): CombatInitiative {
        val commPenalty = if (meatworldComm) 1 else 0
        val numDice = max(1, 1 + decker.cyberdeck.responseIncrease - commPenalty)
        val roll = diceRoller.roll(numDice, 2)
        val score = roll.dice.sum() + decker.persona!!.reaction
        return CombatInitiative(score, numDice)
    }

    fun rollIcInitiative(ic: IC, securityCode: SecurityCode, diceRoller: DiceRoller): CombatInitiative {
        val numDice = ic.initiativeDice(securityCode)
        val roll = diceRoller.roll(numDice, 2)
        val score = roll.dice.sum() + ic.rating
        return CombatInitiative(score, numDice)
    }

    // ── Combat Maneuvers ──────────────────────────────────────────────────────────

    fun resolveManeuver(
        maneuver: CombatManeuverType,
        mover: ManeuverParticipant,
        opponent: ManeuverParticipant,
        diceRoller: DiceRoller
    ): ManeuverResult {
        val moverTn = max(2, opponent.sensor - mover.cloakRating)
        val opponentTn = max(2, mover.evasion - opponent.lockOnRating)
        val moverSuccesses = diceRoller.roll(mover.evasion + mover.hackingPool, moverTn).successes
        val opponentSuccesses = diceRoller.roll(opponent.sensor + opponent.hackingPool, opponentTn).successes
        val net = moverSuccesses - opponentSuccesses
        return if (net > 0) ManeuverResult.Success(net) else ManeuverResult.Failure
    }

    // ── Attack Resolution ─────────────────────────────────────────────────────────

    fun resolveAttack(
        attacker: AttackParticipant,
        defender: DefenderParticipant,
        diceRoller: DiceRoller
    ): AttackResult {
        var tn = attackTn(defender.personaStatus, defender.securityCode)
        tn += attacker.modifiers.parryAttackBonus
        tn -= attacker.modifiers.positionAttackTnBonus
        val power = attacker.utilityRating + attacker.modifiers.positionAttackPowerBonus
        val effectivePower = max(0, power - defender.armorCurrentRating)
        val attackerSuccesses = diceRoller.roll(attacker.utilityRating + attacker.hackingPool, max(2, tn)).successes
        if (attackerSuccesses == 0) return AttackResult.Miss
        val defenderSuccesses = if (effectivePower >= 2) diceRoller.roll(defender.bod, effectivePower).successes else 0
        val net = attackerSuccesses - defenderSuccesses
        val staged = stage(attacker.rawDamageLevel, net)
        return AttackResult.Hit(attackerSuccesses, attacker.rawDamageLevel, staged, effectivePower)
    }

    // ── Icon Damage and Secondary Effects ─────────────────────────────────────────

    fun applyIcDamage(decker: Decker, attack: AttackResult.Hit, ic: IC, diceRoller: DiceRoller): IcDamageResult {
        val persona = decker.persona!!
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        var updatedDecker = decker.copy(persona = persona.copy(conditionMonitor = newCm))

        var dumpShockTriggered = false
        var simsense: SimsenseOverloadResult? = null

        when {
            ic is BlackIC -> {
                // No simsense overload for Black IC (CC-28)
                if (attack.attackerSuccesses > 0) {
                    updatedDecker = updatedDecker.copy(blackIcPin = BlackIcPinState(ic))
                }
            }
            attack.stagedDamageLevel == DamageLevel.DEADLY -> {
                // Auto-crash on Deadly; no Willpower test
                dumpShockTriggered = true
            }
            else -> {
                val overloadTn = when (attack.stagedDamageLevel) {
                    DamageLevel.LIGHT    -> 2
                    DamageLevel.MODERATE -> 3
                    DamageLevel.SERIOUS  -> 5
                    DamageLevel.DEADLY   -> error("handled above")
                }
                val successes = diceRoller.roll(decker.willpower, overloadTn).successes
                val passed = successes > 0
                val stressBoxes = if (passed) 0 else 1
                if (!passed) {
                    updatedDecker = updatedDecker.copy(
                        mentalConditionMonitor = updatedDecker.mentalConditionMonitor.applyDamage(stressBoxes)
                    )
                }
                simsense = SimsenseOverloadResult(passed, stressBoxes)
            }
        }

        if (newCm.isCrashed) dumpShockTriggered = true

        return IcDamageResult(updatedDecker, attack, simsense, dumpShockTriggered)
    }

    fun resolveDumpShock(decker: Decker, host: Host, diceRoller: DiceRoller): Decker {
        val shock = DumpShock(host.securityRating)
        val successes = diceRoller.roll(decker.body, max(2, shock.power)).successes
        val actualLevel = stage(shock.level, -successes)
        return decker.copy(
            physicalConditionMonitor = decker.physicalConditionMonitor.applyDamage(actualLevel)
        )
    }

    // ── Black IC Pin ──────────────────────────────────────────────────────────────

    fun resolveJackOutWithPin(decker: Decker, diceRoller: DiceRoller): JackOutPinResult {
        require(decker.isPinnedByBlackIc) { "Decker is not pinned by Black IC" }
        val successes = diceRoller.roll(decker.willpower, decker.blackIcPin!!.pinningIc.rating).successes
        return if (successes >= 1) {
            JackOutPinResult(succeeded = true, finalIcAttackTriggered = true)
        } else {
            JackOutPinResult(succeeded = false, finalIcAttackTriggered = false)
        }
    }

    // ── White IC ──────────────────────────────────────────────────────────────────

    fun resolveCrippler(decker: Decker, ic: Crippler, securityCode: SecurityCode, diceRoller: DiceRoller): CripplerResult {
        val sv = securityCode.securityValue
        val icSuccesses = diceRoller.roll(sv, decker.effectiveDetectionFactor).successes
        val persona = decker.persona!!
        val currentAttr = persona.attribute(ic.targetAttribute)
        val deckerSuccesses = diceRoller.roll(currentAttr, ic.rating).successes
        val net = icSuccesses - deckerSuccesses
        val reduction = max(0, net / 2)
        val newValue = max(1, currentAttr - reduction)
        val updatedDecker = decker.copy(
            persona = persona.withAttribute(ic.targetAttribute, newValue)
        )
        return CripplerResult(updatedDecker, ic.targetAttribute, reduction)
    }

    fun resolveKiller(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult =
        resolveAttack(attacker, defender, diceRoller)

    fun resolveProbe(ic: Probe, decker: Decker, diceRoller: DiceRoller): Int {
        return diceRoller.roll(ic.rating, decker.effectiveDetectionFactor).successes
    }

    fun resolveTarBaby(decker: Decker, ic: TarBaby, utility: Utility, diceRoller: DiceRoller): TarBabyResult {
        val icSuccesses = diceRoller.roll(ic.rating, utility.currentRating).successes
        val utilitySuccesses = diceRoller.roll(utility.currentRating, ic.rating).successes
        return if (icSuccesses >= utilitySuccesses) {
            val updatedDeck = decker.cyberdeck.copy(
                activeUtilities = decker.cyberdeck.activeUtilities.filterNot { it.type == utility.type }
            )
            TarBabyResult(decker.copy(cyberdeck = updatedDeck), bothCrashed = true, deckerNoticed = false)
        } else {
            val noticed = diceRoller.roll(decker.persona!!.sensor, ic.rating).successes >= 1
            TarBabyResult(decker, bothCrashed = false, deckerNoticed = noticed)
        }
    }

    // ── Gray IC ───────────────────────────────────────────────────────────────────

    fun resolveBlaster(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult =
        resolveAttack(attacker, defender, diceRoller)

    fun resolveBlasterMpcpTest(decker: Decker, ic: Blaster, diceRoller: DiceRoller): Decker {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating
        val successes = diceRoller.roll(ic.rating, max(2, tn)).successes
        val reduction = successes / 2
        return decker.copy(
            cyberdeck = decker.cyberdeck.copy(mcpRating = max(0, decker.cyberdeck.mcpRating - reduction))
        )
    }

    fun resolveRipper(decker: Decker, ic: Ripper, securityCode: SecurityCode, diceRoller: DiceRoller): CripplerResult {
        val sv = securityCode.securityValue
        val icSuccesses = diceRoller.roll(sv, decker.effectiveDetectionFactor).successes
        val persona = decker.persona!!
        val currentAttr = persona.attribute(ic.targetAttribute)
        val deckerSuccesses = diceRoller.roll(currentAttr, ic.rating).successes
        val net = icSuccesses - deckerSuccesses
        val reduction = max(0, net / 2)
        val newValue = max(0, currentAttr - reduction)
        val updatedDecker = decker.copy(
            persona = persona.withAttribute(ic.targetAttribute, newValue)
        )
        return CripplerResult(updatedDecker, ic.targetAttribute, reduction)
    }

    fun resolveRipperMpcpTest(decker: Decker, ic: Ripper, diceRoller: DiceRoller): Decker {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating
        val successes = diceRoller.roll(ic.rating, max(2, tn)).successes
        val reduction = successes / 2
        return decker.copy(
            cyberdeck = decker.cyberdeck.copy(mcpRating = max(0, decker.cyberdeck.mcpRating - reduction))
        )
    }

    fun resolveSparky(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult =
        resolveAttack(attacker, defender, diceRoller)

    fun resolveSparkyMpcpTest(decker: Decker, ic: Sparky, diceRoller: DiceRoller): Pair<Decker, Int> {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating + 2
        val successes = diceRoller.roll(ic.rating, max(2, tn)).successes
        val reduction = successes / 2
        val updatedDecker = decker.copy(
            cyberdeck = decker.cyberdeck.copy(mcpRating = max(0, decker.cyberdeck.mcpRating - reduction))
        )
        return Pair(updatedDecker, successes)
    }

    fun resolveSparkyBodyDamage(decker: Decker, ic: Sparky, sparkySuccesses: Int, diceRoller: DiceRoller): Decker {
        val staged = stage(DamageLevel.MODERATE, sparkySuccesses)
        val effectivePower = max(0, ic.rating - decker.cyberdeck.hardening)
        val bodySuccesses = if (effectivePower >= 2) diceRoller.roll(decker.body, effectivePower).successes else 0
        val actual = stage(staged, -bodySuccesses)
        return decker.copy(
            physicalConditionMonitor = decker.physicalConditionMonitor.applyDamage(actual)
        )
    }

    fun resolveTarPit(decker: Decker, ic: TarPit, utility: Utility, diceRoller: DiceRoller): TarBabyResult {
        val icSuccesses = diceRoller.roll(ic.rating, utility.currentRating).successes
        val utilitySuccesses = diceRoller.roll(utility.currentRating, ic.rating).successes
        return if (icSuccesses >= utilitySuccesses) {
            val updatedDeck = decker.cyberdeck.copy(
                activeUtilities = decker.cyberdeck.activeUtilities.filterNot { it.type == utility.type }
            )
            TarBabyResult(decker.copy(cyberdeck = updatedDeck), bothCrashed = true, deckerNoticed = false)
        } else {
            val noticed = diceRoller.roll(decker.persona!!.sensor, ic.rating).successes >= 1
            TarBabyResult(decker, bothCrashed = false, deckerNoticed = noticed)
        }
    }

    fun resolveTarPitMpcpTest(decker: Decker, ic: TarPit, utility: Utility, diceRoller: DiceRoller): Decker {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating
        val successes = diceRoller.roll(ic.rating, max(2, tn)).successes
        if (successes == 0) return decker
        // Corrupt all copies of utility in both active and stored memory
        val updatedDeck = decker.cyberdeck.copy(
            activeUtilities = decker.cyberdeck.activeUtilities.filterNot { it.type == utility.type },
            storedUtilities = decker.cyberdeck.storedUtilities.filterNot { it.type == utility.type }
        )
        return decker.copy(cyberdeck = updatedDeck)
    }

    // ── Black IC ──────────────────────────────────────────────────────────────────

    fun resolveLethalBlackIc(
        decker: Decker,
        ic: LethalBlackIC,
        securityCode: SecurityCode,
        diceRoller: DiceRoller
    ): IcDamageResult {
        val rawLevel = if (securityCode == SecurityCode.BLUE || securityCode == SecurityCode.GREEN)
            DamageLevel.MODERATE else DamageLevel.SERIOUS
        val power = ic.rating
        val effectivePower = max(0, power - decker.cyberdeck.hardening)

        val persona = decker.persona!!
        val iconDefSuccesses = if (power >= 2) diceRoller.roll(persona.bod, power).successes else 0
        val iconStaged = stage(rawLevel, -iconDefSuccesses)
        val newCm = persona.conditionMonitor.applyDamage(iconStaged)

        val bodySuccesses = if (effectivePower >= 2) diceRoller.roll(decker.body, effectivePower).successes else 0
        val bodyStaged = stage(rawLevel, -bodySuccesses)
        val newPhysicalCm = decker.physicalConditionMonitor.applyDamage(bodyStaged)

        var updatedDecker = decker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            physicalConditionMonitor = newPhysicalCm,
            blackIcPin = BlackIcPinState(ic)
        )

        val dumpShockTriggered = newCm.isCrashed || newPhysicalCm.isCrashed

        // Rules p. 230: on kill, Black IC makes a final Blaster attack on MPCP at double rating.
        var mpcpReduction = 0
        if (dumpShockTriggered) {
            val mpcpTn = updatedDecker.cyberdeck.hardening + updatedDecker.cyberdeck.mcpRating
            val mpcpSuccesses = diceRoller.roll(ic.rating * 2, max(2, mpcpTn)).successes
            mpcpReduction = mpcpSuccesses / 2
            if (mpcpReduction > 0) {
                updatedDecker = updatedDecker.copy(
                    cyberdeck = updatedDecker.cyberdeck.copy(
                        mcpRating = max(0, updatedDecker.cyberdeck.mcpRating - mpcpReduction)
                    )
                )
            }
        }

        val attack = AttackResult.Hit(1, rawLevel, iconStaged, power)
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered, mpcpReductionOnKill = mpcpReduction)
    }

    fun resolveNonLethalBlackIc(
        decker: Decker,
        ic: NonLethalBlackIC,
        securityCode: SecurityCode,
        diceRoller: DiceRoller
    ): IcDamageResult {
        val rawLevel = if (securityCode == SecurityCode.BLUE || securityCode == SecurityCode.GREEN)
            DamageLevel.MODERATE else DamageLevel.SERIOUS
        val power = ic.rating
        val effectivePower = max(0, power - decker.cyberdeck.hardening)

        val persona = decker.persona!!
        val iconDefSuccesses = if (power >= 2) diceRoller.roll(persona.bod, power).successes else 0
        val iconStaged = stage(rawLevel, -iconDefSuccesses)
        val newCm = persona.conditionMonitor.applyDamage(iconStaged)

        // Mental damage via Willpower resistance
        val mentalSuccesses = if (effectivePower >= 2) diceRoller.roll(decker.willpower, effectivePower).successes else 0
        val mentalStaged = stage(rawLevel, -mentalSuccesses)
        val newMentalCm = decker.mentalConditionMonitor.applyDamage(mentalStaged)

        var updatedDecker = decker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            mentalConditionMonitor = newMentalCm,
            blackIcPin = BlackIcPinState(ic)
        )

        val dumpShockTriggered = newCm.isCrashed || newMentalCm.isCrashed

        // Rules p. 230: on unconsciousness, non-lethal Black IC gets a final shot at the MPCP at double rating.
        var mpcpReduction = 0
        if (dumpShockTriggered) {
            val mpcpTn = updatedDecker.cyberdeck.hardening + updatedDecker.cyberdeck.mcpRating
            val mpcpSuccesses = diceRoller.roll(ic.rating * 2, max(2, mpcpTn)).successes
            mpcpReduction = mpcpSuccesses / 2
            if (mpcpReduction > 0) {
                updatedDecker = updatedDecker.copy(
                    cyberdeck = updatedDecker.cyberdeck.copy(
                        mcpRating = max(0, updatedDecker.cyberdeck.mcpRating - mpcpReduction)
                    )
                )
            }
        }

        val attack = AttackResult.Hit(1, rawLevel, iconStaged, power)
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered, mpcpReductionOnKill = mpcpReduction)
    }

    // ── Black Hammer and Killjoy ──────────────────────────────────────────────────

    fun resolveBlackHammer(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult {
        val power = attack.power
        val effectivePower = max(0, power - targetDecker.cyberdeck.hardening)
        val persona = targetDecker.persona!!
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        val bodySuccesses = if (effectivePower >= 2) diceRoller.roll(targetDecker.body, effectivePower).successes else 0
        val bodyStaged = stage(attack.stagedDamageLevel, -bodySuccesses)
        val newPhysicalCm = targetDecker.physicalConditionMonitor.applyDamage(bodyStaged)
        val updatedDecker = targetDecker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            physicalConditionMonitor = newPhysicalCm
        )
        val dumpShockTriggered = newCm.isCrashed || newPhysicalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered)
    }

    fun resolveKilljoy(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult {
        val power = attack.power
        val effectivePower = max(0, power - targetDecker.cyberdeck.hardening)
        val persona = targetDecker.persona!!
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        val mentalSuccesses = if (effectivePower >= 2) diceRoller.roll(targetDecker.willpower, effectivePower).successes else 0
        val mentalStaged = stage(attack.stagedDamageLevel, -mentalSuccesses)
        val newMentalCm = targetDecker.mentalConditionMonitor.applyDamage(mentalStaged)
        val updatedDecker = targetDecker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            mentalConditionMonitor = newMentalCm
        )
        val dumpShockTriggered = newCm.isCrashed || newMentalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered)
    }

    // ── Track Utility ─────────────────────────────────────────────────────────────

    fun resolveTrackLock(attack: AttackResult.Hit, targetDecker: Decker, trackRating: Int, diceRoller: DiceRoller): TrackState? {
        val evadeSuccesses = diceRoller.roll(targetDecker.persona!!.evasion, max(2, trackRating)).successes
        if (evadeSuccesses >= attack.attackerSuccesses) return null
        val net = attack.attackerSuccesses - evadeSuccesses
        val cycleTurns = ceil(10.0 / net).toInt()
        return TrackState(trackRating, cycleTurns)
    }

    // ── IC Suppression ────────────────────────────────────────────────────────────

    /**
     * Decker declares suppression of a crashed IC (CC-22).
     * The tally increase for the crash is NOT applied; the IC is held in the suppressed list.
     * The decker's effective Detection Factor drops by 1 for each suppressed IC.
     * Returns the updated Decker (does NOT modify tally).
     * Requires the decker to be jacked in — a decker who has left the system cannot suppress IC.
     */
    fun suppressIc(decker: Decker, ic: IC): Decker {
        require(decker.persona != null) { "Cannot suppress IC after leaving the system" }
        val state = IcSuppressionState(ic, ic.rating)
        return decker.copy(suppressedIc = decker.suppressedIc + state)
    }

    /**
     * Decker releases a previously suppressed IC (CC-22).
     * Calls [onTallyIncrease] with the IC's original rating so the caller can raise the tally.
     * The decker's effective Detection Factor is restored by 1.
     * Returns the updated Decker.
     */
    fun unsuppressIc(decker: Decker, ic: IC, onTallyIncrease: (Int) -> Unit): Decker {
        val state = decker.suppressedIc.firstOrNull { it.ic == ic }
            ?: return decker
        onTallyIncrease(state.icRating)
        return decker.copy(suppressedIc = decker.suppressedIc - state)
    }

    // ── IC Attack Using Host Security Value ───────────────────────────────────────

    /**
     * Build an [AttackParticipant] for an IC program using its host's Security Value as the dice pool (CC-23).
     * The IC's rating is the weapon (determines Power and base DamageLevel).
     */
    fun icAttackParticipant(ic: IC, securityCode: SecurityCode): AttackParticipant {
        // CC-27: Blue/Green = Moderate; Orange/Red = Serious
        val rawLevel = when (securityCode) {
            SecurityCode.BLUE, SecurityCode.GREEN -> DamageLevel.MODERATE
            SecurityCode.ORANGE, SecurityCode.RED -> DamageLevel.SERIOUS
        }
        return AttackParticipant(
            utilityRating = ic.rating,
            hackingPool = securityCode.securityValue,
            rawDamageLevel = rawLevel
        )
    }

    // ── Slow Utility ──────────────────────────────────────────────────────────────

    fun resolveSlow(
        ic: IC,
        slowRating: Int,
        securityCode: SecurityCode,
        icInitiative: CombatInitiative,
        diceRoller: DiceRoller
    ): SlowResult {
        if (ic.behavior != IcBehavior.PROACTIVE) return SlowResult(0, false)
        val sv = securityCode.securityValue
        val icSuccesses = diceRoller.roll(sv, max(2, slowRating)).successes
        val slowSuccesses = diceRoller.roll(slowRating, max(2, sv)).successes
        val net = slowSuccesses - icSuccesses
        if (net <= 0) return SlowResult(0, false)
        val actionsLost = net / 2
        val icInert = (icInitiative.initiativePasses - actionsLost) <= 0
        return SlowResult(actionsLost, icInert)
    }

    // ── Private Helpers ───────────────────────────────────────────────────────────

    private fun attackTn(status: PersonaStatus, code: SecurityCode): Int = when (status) {
        PersonaStatus.INTRUDING -> when (code) {
            SecurityCode.BLUE   -> 6
            SecurityCode.GREEN  -> 5
            SecurityCode.ORANGE -> 4
            SecurityCode.RED    -> 3
        }
        PersonaStatus.LEGITIMATE -> when (code) {
            SecurityCode.BLUE   -> 3
            SecurityCode.GREEN  -> 4
            SecurityCode.ORANGE -> 5
            SecurityCode.RED    -> 6
        }
    }

    internal fun stage(base: DamageLevel, net: Int): DamageLevel {
        val shift = net / 2
        val ordinal = (base.ordinal + shift).coerceIn(0, DamageLevel.entries.size - 1)
        return DamageLevel.entries[ordinal]
    }
}

private val SecurityCode.securityValue: Int get() = when (this) {
    SecurityCode.BLUE   -> 3
    SecurityCode.GREEN  -> 4
    SecurityCode.ORANGE -> 5
    SecurityCode.RED    -> 6
}
