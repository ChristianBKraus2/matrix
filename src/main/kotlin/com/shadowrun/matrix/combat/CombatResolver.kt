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
import com.shadowrun.matrix.network.MatrixLocation
import com.shadowrun.matrix.programs.Utility
import com.shadowrun.matrix.programs.UtilityType
import com.shadowrun.matrix.utility.DiceRoller
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object CombatResolver {

    // ── Initiative ────────────────────────────────────────────────────────────────

    fun rollDeckerInitiative(decker: Decker, meatworldComm: Boolean, diceRoller: DiceRoller): CombatInitiative {
        val commPenalty = if (meatworldComm) 1 else 0
        val numDice = max(1, 1 + decker.cyberdeck.responseIncrease - commPenalty)
        val roll = diceRoller.roll(numDice, 2)
        val score = roll.dice.sum() + requireNotNull(decker.persona) { "rollDeckerInitiative: decker has no active persona" }.reaction
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
        val power = attacker.weaponPower + attacker.modifiers.positionAttackPowerBonus
        val effectivePower = max(0, power - defender.armorCurrentRating)
        val attackerSuccesses = diceRoller.roll(attacker.attackDicePool + attacker.hackingPool, max(2, tn)).successes
        if (attackerSuccesses == 0) return AttackResult.Miss
        val defenderSuccesses = diceRoller.roll(defender.bod, max(2, effectivePower)).successes
        val net = attackerSuccesses - defenderSuccesses
        val staged = stage(attacker.rawDamageLevel, net)
        return AttackResult.Hit(attackerSuccesses, attacker.rawDamageLevel, staged, power, effectivePower)
    }

    // ── Icon Damage and Secondary Effects ─────────────────────────────────────────

    fun applyIcDamage(decker: Decker, attack: AttackResult.Hit, ic: IC, diceRoller: DiceRoller): IcDamageResult {
        val persona = requireNotNull(decker.persona) { "applyIcDamage: decker has no active persona" }
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        var updatedDecker = decker.copy(persona = persona.copy(conditionMonitor = newCm))
        updatedDecker = degradeArmor(updatedDecker, damageBledThrough = attack.effectivePower > 0) // CD-19

        var dumpShockTriggered = false
        var simsense: SimsenseOverloadResult? = null

        when {
            ic is BlackIC -> {
                // No simsense overload for Black IC (CC-28)
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

    fun resolveDumpShock(decker: Decker, host: Host, diceRoller: DiceRoller): Decker =
        resolveDumpShock(decker, host.securityRating, diceRoller)

    fun resolveDumpShock(decker: Decker, securityRating: com.shadowrun.matrix.common.SecurityRating, diceRoller: DiceRoller): Decker {
        val shock = DumpShock(securityRating)
        val successes = diceRoller.roll(decker.body, max(2, shock.power)).successes
        val actualLevel = stage(shock.level, -successes)
        return decker.copy(
            mentalConditionMonitor = decker.mentalConditionMonitor.applyDamage(actualLevel)
        )
    }

    // ── Black IC Pin ──────────────────────────────────────────────────────────────

    fun resolveJackOutWithPin(decker: Decker, diceRoller: DiceRoller): JackOutPinResult {
        require(decker.isPinnedByBlackIc) { "Decker is not pinned by Black IC" }
        val pin = requireNotNull(decker.blackIcPin) { "resolveJackOutWithPin: decker.blackIcPin is null despite isPinnedByBlackIc guard" }
        val successes = diceRoller.roll(decker.willpower, maxOf(2, pin.pinningIc.rating)).successes
        return if (successes >= 1) {
            JackOutPinResult(succeeded = true, finalIcAttackTriggered = true)
        } else {
            JackOutPinResult(succeeded = false, finalIcAttackTriggered = false)
        }
    }

    // ── White IC ──────────────────────────────────────────────────────────────────

    fun resolveCrippler(decker: Decker, ic: Crippler, securityValue: Int, diceRoller: DiceRoller): CripplerResult {
        val icSuccesses = diceRoller.roll(securityValue, max(2, decker.effectiveDetectionFactor)).successes
        val persona = requireNotNull(decker.persona) { "resolveCrippler: decker has no active persona" }
        val currentAttr = persona.attribute(ic.targetAttribute)
        val deckerSuccesses = diceRoller.roll(currentAttr, maxOf(2, ic.rating)).successes
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
        return diceRoller.roll(ic.rating, max(2, decker.effectiveDetectionFactor)).successes
    }

    fun resolveTarBaby(decker: Decker, ic: TarBaby, utility: Utility, diceRoller: DiceRoller): TarBabyResult =
        resolveTarContest(decker, ic.rating, utility, "resolveTarBaby", diceRoller)

    // ── Gray IC ───────────────────────────────────────────────────────────────────

    fun resolveBlaster(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult =
        resolveAttack(attacker, defender, diceRoller)

    fun resolveBlasterMpcpTest(decker: Decker, ic: Blaster, diceRoller: DiceRoller, ratingOverride: Int? = null): Decker =
        reduceMcpRating(decker, ratingOverride ?: ic.rating, diceRoller)

    fun resolveRipper(decker: Decker, ic: Ripper, securityValue: Int, diceRoller: DiceRoller): CripplerResult {
        val icSuccesses = diceRoller.roll(securityValue, max(2, decker.effectiveDetectionFactor)).successes
        val persona = requireNotNull(decker.persona) { "resolveRipper: decker has no active persona" }
        val currentAttr = persona.attribute(ic.targetAttribute)
        val deckerSuccesses = if (currentAttr > 0) diceRoller.roll(currentAttr, maxOf(2, ic.rating)).successes else 0
        val net = icSuccesses - deckerSuccesses
        val reduction = max(0, net / 2)
        val newValue = max(0, currentAttr - reduction)
        val updatedDecker = decker.copy(
            persona = persona.withAttribute(ic.targetAttribute, newValue)
        )
        return CripplerResult(updatedDecker, ic.targetAttribute, reduction)
    }

    fun resolveRipperMpcpTest(decker: Decker, ic: Ripper, diceRoller: DiceRoller): Decker =
        reduceMcpRating(decker, ic.rating, diceRoller)

    fun resolveSparky(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult =
        resolveAttack(attacker, defender, diceRoller)

    fun resolveSparkyMpcpTest(decker: Decker, ic: Sparky, diceRoller: DiceRoller): Pair<Decker, Int> {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating + 2
        val successes = diceRoller.roll(ic.rating, max(2, tn)).successes
        val reduction = successes / 2
        val newMcp = max(0, decker.cyberdeck.mcpRating - reduction)
        val newRi = min(decker.cyberdeck.responseIncrease, newMcp / 4)
        val updatedDecker = decker.copy(
            cyberdeck = decker.cyberdeck.copy(mcpRating = newMcp, responseIncrease = newRi)
        )
        return Pair(updatedDecker, successes)
    }

    fun resolveSparkyBodyDamage(decker: Decker, ic: Sparky, sparkySuccesses: Int, diceRoller: DiceRoller): Decker {
        val staged = stage(DamageLevel.MODERATE, sparkySuccesses)
        val effectivePower = max(0, ic.rating - decker.cyberdeck.hardening)
        val bodySuccesses = diceRoller.roll(decker.body, max(2, effectivePower)).successes
        val actual = stage(staged, -bodySuccesses)
        return decker.copy(
            physicalConditionMonitor = decker.physicalConditionMonitor.applyDamage(actual)
        )
    }

    fun resolveTarPit(decker: Decker, ic: TarPit, utility: Utility, diceRoller: DiceRoller): TarBabyResult =
        resolveTarContest(decker, ic.rating, utility, "resolveTarPit", diceRoller)

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

    /**
     * Resolves a Lethal Black IC attack on [decker].
     *
     * `attackerSuccesses = 1` in the returned [AttackResult.Hit] is a sentinel value representing
     * a hit; Black IC does not use the standard attack roll mechanism. Callers (e.g. TrackLock)
     * must not use this field for cycling calculations.
     */
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

        val armorRating = decker.cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }?.currentRating ?: 0
        val persona = requireNotNull(decker.persona) { "resolveLethalBlackIc: decker has no active persona" }
        val iconDefSuccesses = diceRoller.roll(persona.bod, max(2, power - armorRating)).successes
        val iconStaged = stage(rawLevel, -iconDefSuccesses)
        val newCm = persona.conditionMonitor.applyDamage(iconStaged)

        val bodySuccesses = diceRoller.roll(decker.body, max(2, effectivePower)).successes
        val bodyStaged = stage(rawLevel, -bodySuccesses)
        val newPhysicalCm = decker.physicalConditionMonitor.applyDamage(bodyStaged)

        var updatedDecker = decker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            physicalConditionMonitor = newPhysicalCm,
            blackIcPin = decker.blackIcPin ?: BlackIcPinState(ic)
        )
        updatedDecker = degradeArmor(updatedDecker, damageBledThrough = power > armorRating) // CD-19

        val dumpShockTriggered = newCm.isCrashed || newPhysicalCm.isCrashed

        // Rules p. 230: on kill, Black IC makes a final Blaster attack on MPCP at double rating.
        var mpcpReduction = 0
        if (newPhysicalCm.isCrashed) {
            val mpcpTn = updatedDecker.cyberdeck.hardening + updatedDecker.cyberdeck.mcpRating
            val mpcpSuccesses = diceRoller.roll(ic.rating * 2, max(2, mpcpTn)).successes
            mpcpReduction = mpcpSuccesses / 2
            if (mpcpReduction > 0) {
                val newMcp = max(0, updatedDecker.cyberdeck.mcpRating - mpcpReduction)
                val newRi = min(updatedDecker.cyberdeck.responseIncrease, newMcp / 4)
                updatedDecker = updatedDecker.copy(
                    cyberdeck = updatedDecker.cyberdeck.copy(mcpRating = newMcp, responseIncrease = newRi)
                )
                if (newMcp == 0) updatedDecker = updatedDecker.copy(runDownloadedFiles = emptyList())
            }
        }

        val attack = AttackResult.Hit(1, rawLevel, iconStaged, power, power)
        val personaOnlyCrashed = newCm.isCrashed && !newPhysicalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered,
            mpcpReductionOnKill = mpcpReduction, personaOnlyCrashed = personaOnlyCrashed)
    }

    /**
     * Resolves a Non-Lethal Black IC attack on [decker].
     *
     * `attackerSuccesses = 1` in the returned [AttackResult.Hit] is a sentinel value representing
     * a hit; Black IC does not use the standard attack roll mechanism. Callers (e.g. TrackLock)
     * must not use this field for cycling calculations.
     */
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

        val armorRating = decker.cyberdeck.activeUtilities.firstOrNull { it.type == UtilityType.ARMOR }?.currentRating ?: 0
        val persona = requireNotNull(decker.persona) { "resolveNonLethalBlackIc: decker has no active persona" }
        val iconDefSuccesses = diceRoller.roll(persona.bod, max(2, power - armorRating)).successes
        val iconStaged = stage(rawLevel, -iconDefSuccesses)
        val newCm = persona.conditionMonitor.applyDamage(iconStaged)

        // Mental damage via Willpower resistance
        val mentalSuccesses = diceRoller.roll(decker.willpower, max(2, effectivePower)).successes
        val mentalStaged = stage(rawLevel, -mentalSuccesses)
        val newMentalCm = decker.mentalConditionMonitor.applyDamage(mentalStaged)

        var updatedDecker = decker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            mentalConditionMonitor = newMentalCm,
            blackIcPin = decker.blackIcPin ?: BlackIcPinState(ic)
        )
        updatedDecker = degradeArmor(updatedDecker, damageBledThrough = power > armorRating) // CD-19

        val dumpShockTriggered = newCm.isCrashed || newMentalCm.isCrashed

        // Rules p. 230: on unconsciousness, non-lethal Black IC gets a final shot at the MPCP at double rating.
        var mpcpReduction = 0
        if (newMentalCm.isCrashed) {
            val mpcpTn = updatedDecker.cyberdeck.hardening + updatedDecker.cyberdeck.mcpRating
            val mpcpSuccesses = diceRoller.roll(ic.rating * 2, max(2, mpcpTn)).successes
            mpcpReduction = mpcpSuccesses / 2
            if (mpcpReduction > 0) {
                val newMcp = max(0, updatedDecker.cyberdeck.mcpRating - mpcpReduction)
                val newRi = min(updatedDecker.cyberdeck.responseIncrease, newMcp / 4)
                updatedDecker = updatedDecker.copy(
                    cyberdeck = updatedDecker.cyberdeck.copy(mcpRating = newMcp, responseIncrease = newRi)
                )
                if (newMcp == 0) updatedDecker = updatedDecker.copy(runDownloadedFiles = emptyList())
            }
        }

        val attack = AttackResult.Hit(1, rawLevel, iconStaged, power, power)
        val personaOnlyCrashed = newCm.isCrashed && !newMentalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered,
            mpcpReductionOnKill = mpcpReduction, personaOnlyCrashed = personaOnlyCrashed)
    }

    // ── Black Hammer and Killjoy ──────────────────────────────────────────────────

    fun resolveBlackHammer(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult {
        require(!targetDecker.cyberdeck.isCyberterminal) {
            "resolveBlackHammer: cyberterminal users are immune to Black Hammer (ICC-13, CT-04)"
        }
        val power = attack.rawWeaponPower
        val effectivePower = max(0, power - targetDecker.cyberdeck.hardening)
        val persona = requireNotNull(targetDecker.persona) { "resolveBlackHammer: decker has no active persona" }
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        val bodySuccesses = diceRoller.roll(targetDecker.body, max(2, effectivePower)).successes
        val bodyStaged = stage(attack.rawDamageLevel, -bodySuccesses)
        val newPhysicalCm = targetDecker.physicalConditionMonitor.applyDamage(bodyStaged)
        var updatedDecker = targetDecker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            physicalConditionMonitor = newPhysicalCm
        )
        updatedDecker = degradeArmor(updatedDecker, damageBledThrough = effectivePower > 0) // CD-19
        val dumpShockTriggered = newCm.isCrashed || newPhysicalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered)
    }

    fun resolveKilljoy(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult {
        require(!targetDecker.cyberdeck.isCyberterminal) {
            "resolveKilljoy: cyberterminal users are immune to Killjoy (ICC-14, CT-04)"
        }
        val power = attack.rawWeaponPower
        val effectivePower = max(0, power - targetDecker.cyberdeck.hardening)
        val persona = requireNotNull(targetDecker.persona) { "resolveKilljoy: decker has no active persona" }
        val newCm = persona.conditionMonitor.applyDamage(attack.stagedDamageLevel)
        val mentalSuccesses = diceRoller.roll(targetDecker.willpower, max(2, effectivePower)).successes
        val mentalStaged = stage(attack.rawDamageLevel, -mentalSuccesses)
        val newMentalCm = targetDecker.mentalConditionMonitor.applyDamage(mentalStaged)
        var updatedDecker = targetDecker.copy(
            persona = persona.copy(conditionMonitor = newCm),
            mentalConditionMonitor = newMentalCm
        )
        updatedDecker = degradeArmor(updatedDecker, damageBledThrough = effectivePower > 0) // CD-19
        val dumpShockTriggered = newCm.isCrashed || newMentalCm.isCrashed
        return IcDamageResult(updatedDecker, attack, simsenseOverload = null, dumpShockTriggered)
    }

    // ── Track Utility ─────────────────────────────────────────────────────────────

    fun resolveTrackLock(attack: AttackResult.Hit, targetDecker: Decker, trackRating: Int, diceRoller: DiceRoller): TrackState? {
        val persona = requireNotNull(targetDecker.persona) { "resolveTrackLock: targetDecker has no persona" }
        val evadeSuccesses = diceRoller.roll(persona.evasion, max(2, trackRating)).successes
        if (evadeSuccesses >= attack.attackerSuccesses) return null
        val net = attack.attackerSuccesses - evadeSuccesses
        val cycleTurns = ceil(10.0 / net).toInt()
        return TrackState(trackingIcRating = trackRating, locationCycleTurnsRemaining = cycleTurns,
            opponentSensorRating = trackRating, trackerMcpRating = trackRating)
    }

    // ── IC Suppression ────────────────────────────────────────────────────────────

    /**
     * Decker declares suppression of a crashed IC (CC-22).
     * The tally increase for the crash is NOT applied; the IC is held in the suppressed list.
     * The decker's effective Detection Factor drops by 1 for each suppressed IC.
     * Returns the updated Decker (does NOT modify tally).
     * Requires the decker to be jacked in — a decker who has left the system cannot suppress IC.
     */
    fun suppressIc(decker: Decker, ic: IC, host: Host): Decker {
        val onHost = decker.currentLocation as? MatrixLocation.OnHost
        require(decker.persona != null && onHost?.host == host) {
            "Cannot suppress IC after leaving the system"
        }
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
    fun icAttackParticipant(ic: IC, securityCode: SecurityCode, securityValue: Int): AttackParticipant {
        // CC-27: Blue/Green = Moderate; Orange/Red = Serious
        val rawLevel = when (securityCode) {
            SecurityCode.BLUE, SecurityCode.GREEN -> DamageLevel.MODERATE
            SecurityCode.ORANGE, SecurityCode.RED -> DamageLevel.SERIOUS
        }
        return AttackParticipant(
            attackDicePool = securityValue,
            weaponPower = ic.rating,
            hackingPool = 0,
            rawDamageLevel = rawLevel
        )
    }

    // ── Slow Utility ──────────────────────────────────────────────────────────────

    fun resolveSlow(
        ic: IC,
        slowRating: Int,
        securityValue: Int,
        icInitiative: CombatInitiative,
        diceRoller: DiceRoller
    ): SlowResult {
        if (ic.behavior != IcBehavior.PROACTIVE) return SlowResult(0, false)
        val icSuccesses = diceRoller.roll(securityValue, max(2, slowRating)).successes
        val slowSuccesses = diceRoller.roll(slowRating, max(2, securityValue)).successes
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

    private fun reduceMcpRating(decker: Decker, icRating: Int, diceRoller: DiceRoller): Decker {
        val tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating
        val successes = diceRoller.roll(icRating, max(2, tn)).successes
        val reduction = successes / 2
        if (reduction == 0) return decker
        val newMcp = max(0, decker.cyberdeck.mcpRating - reduction)
        val newRi = min(decker.cyberdeck.responseIncrease, newMcp / 4)
        val updatedDecker = decker.copy(cyberdeck = decker.cyberdeck.copy(mcpRating = newMcp, responseIncrease = newRi))
        return if (newMcp == 0) updatedDecker.copy(runDownloadedFiles = emptyList()) else updatedDecker
    }

    private fun resolveTarContest(decker: Decker, icRating: Int, utility: Utility, context: String, diceRoller: DiceRoller): TarBabyResult {
        val icSuccesses = diceRoller.roll(icRating, max(2, utility.currentRating)).successes
        val utilitySuccesses = diceRoller.roll(utility.currentRating, max(2, icRating)).successes
        return if (icSuccesses >= utilitySuccesses) {
            val updatedDeck = decker.cyberdeck.copy(
                activeUtilities = decker.cyberdeck.activeUtilities.filterNot { it.type == utility.type }
            )
            TarBabyResult(decker.copy(cyberdeck = updatedDeck), bothCrashed = true, deckerNoticed = false)
        } else {
            val noticed = diceRoller.roll(requireNotNull(decker.persona) { "$context: decker has no active persona" }.sensor, icRating).successes >= 1
            TarBabyResult(decker, bothCrashed = false, deckerNoticed = noticed)
        }
    }

    private fun degradeArmor(decker: Decker, damageBledThrough: Boolean): Decker {
        if (!damageBledThrough) return decker
        val armorIdx = decker.cyberdeck.activeUtilities.indexOfFirst { it.type == UtilityType.ARMOR }
        if (armorIdx == -1) return decker
        val armorUtil = decker.cyberdeck.activeUtilities[armorIdx]
        if (armorUtil.currentRating <= 0) return decker
        val updatedUtilities = decker.cyberdeck.activeUtilities.toMutableList()
        updatedUtilities[armorIdx] = Utility(armorUtil.type, armorUtil.rating, armorUtil.attackDamageLevel, armorUtil.currentRating - 1, armorUtil.sourceCode)
        return decker.copy(cyberdeck = decker.cyberdeck.copy(activeUtilities = updatedUtilities))
    }
}

