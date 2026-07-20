# Cybercombat Design Document

## Purpose

This document specifies the design for implementing the cybercombat and intrusion countermeasure requirements defined in `prd.md` (CC-01 through CC-33 and ICC-01 through ICC-15). It covers:

- Initiative calculation for deckers and IC
- Combat maneuver resolution (Evade Detection, Parry Attack, Position Attack)
- Attack resolution and icon damage (staging, Condition Monitor, dump shock)
- Simsense overload and the Black IC pin mechanic
- All nine IC subtypes: White (Crippler, Killer, Probe, Scramble, Tar Baby), Gray (Blaster, Ripper, Sparky, Tar Pit), Black (Lethal, Non-Lethal)
- Offensive utility combat resolution: Black Hammer, Killjoy, Slow, Track

Cyberdeck and program mechanics are in `cyberdeck_and_program_mechanics.md`. Movement (jack-out, graceful logoff, dump shock trigger) is in `movement.md`. System operations are in `operations.md`. This document covers all combat resolution that those documents defer.

---

## New Types

### `CombatInitiative` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatInitiative.kt`

```kotlin
data class CombatInitiative(
    val score: Int,
    val initiativePasses: Int
)
```

PRD: CC-04, CC-06. `initiativePasses` equals the number of initiative dice rolled (1 + Response Increase for deckers; 1–4 for IC by Security Code). Constructed by `CombatResolver.rollDeckerInitiative()` and `CombatResolver.rollIcInitiative()`.

---

### `AttackResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt`

```kotlin
sealed class AttackResult {
    data class Hit(
        val attackerSuccesses: Int,
        val rawDamageLevel: DamageLevel,
        val stagedDamageLevel: DamageLevel,
        val power: Int
    ) : AttackResult()

    object Miss : AttackResult()
}
```

PRD: CC-20–CC-26. `rawDamageLevel` is the pre-staging base; `stagedDamageLevel` is what is applied to the Condition Monitor.

---

### `ManeuverResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/ManeuverResult.kt`

```kotlin
sealed class ManeuverResult {
    data class Success(val netSuccesses: Int) : ManeuverResult()
    object Failure : ManeuverResult()
}
```

PRD: CC-14–CC-19. The caller interprets `netSuccesses` according to which `CombatManeuverType` was attempted.

---

### `ManeuverParticipant` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/ManeuverParticipant.kt`

```kotlin
data class ManeuverParticipant(
    val evasion: Int,
    val sensor: Int,
    val cloakRating: Int = 0,
    val lockOnRating: Int = 0,
    val hackingPool: Int = 0
)
```

For IC, `evasion` and `sensor` are both the host's Security Value (CC-14). For deckers, they are the persona attributes. `cloakRating` reduces the maneuvering icon's TN; `lockOnRating` reduces the opposing icon's TN (CC-15).

---

### `AttackParticipant` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/AttackParticipant.kt`

```kotlin
data class AttackParticipant(
    val utilityRating: Int,
    val hackingPool: Int = 0,
    val rawDamageLevel: DamageLevel,
    val modifiers: CombatModifiers = CombatModifiers()
)
```

PRD: CC-20, CC-24. `utilityRating` is the offensive program's `currentRating`.

---

### `DefenderParticipant` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/DefenderParticipant.kt`

```kotlin
data class DefenderParticipant(
    val bod: Int,
    val armorCurrentRating: Int = 0,
    val personaStatus: PersonaStatus,
    val securityCode: SecurityCode
)
```

PRD: CC-21, CC-25. For IC defending against a decker's attack, `bod` is the host Security Value (CC-25). `armorCurrentRating` is 0 if no Armor utility is active.

---

### `CombatModifiers` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatModifiers.kt`

```kotlin
data class CombatModifiers(
    val parryAttackBonus: Int = 0,
    val positionAttackTnBonus: Int = 0,
    val positionAttackPowerBonus: Int = 0
) {
    init {
        require(positionAttackTnBonus == 0 || positionAttackPowerBonus == 0) {
            "Position Attack grants TN bonus OR Power bonus, not both"
        }
    }
}
```

PRD: CC-18, CC-19. Held by the caller between actions; cleared after the next attack by the owning icon. `parryAttackBonus` is added to the incoming TN; position bonuses modify the outgoing attack.

---

### `BlackIcPinState` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/BlackIcPinState.kt`

```kotlin
data class BlackIcPinState(val pinningIc: BlackIC)
```

PRD: ICC-10. Added to `Decker` as a nullable field. Non-null means the ASIST interface is being subverted; Jack Out requires a Complex Action + Willpower Test.

---

### `JackOutPinResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/JackOutPinResult.kt`

```kotlin
data class JackOutPinResult(
    val succeeded: Boolean,
    val finalIcAttackTriggered: Boolean
)
```

PRD: ICC-10. If `succeeded`, the caller must resolve one final IC attack before severing the connection.

---

### `IcDamageResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/IcDamageResult.kt`

```kotlin
data class IcDamageResult(
    val updatedDecker: Decker,
    val iconDamage: AttackResult,
    val simsenseOverload: SimsenseOverloadResult?,
    val dumpShockTriggered: Boolean
)
```

PRD: CC-27, CC-28. `simsenseOverload` is null for Black IC hits (CC-28 excludes Black IC). `dumpShockTriggered` is true when the persona crashes or Deadly damage lands.

---

### `SimsenseOverloadResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/SimsenseOverloadResult.kt`

```kotlin
data class SimsenseOverloadResult(
    val willpowerTestPassed: Boolean,
    val stressBoxesApplied: Int
)
```

PRD: CC-28. `stressBoxesApplied` is 1 on failure, 0 on success.

---

### `CripplerResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CripplerResult.kt`

```kotlin
data class CripplerResult(
    val updatedDecker: Decker,
    val targetAttribute: PersonaAttributeType,
    val reduction: Int
)
```

PRD: ICC-01, ICC-07. `reduction` is the number of attribute points lost (0 if the decker fully defended).

---

### `TarBabyResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/TarBabyResult.kt`

```kotlin
data class TarBabyResult(
    val updatedDecker: Decker,
    val bothCrashed: Boolean,
    val deckerNoticed: Boolean
)
```

PRD: ICC-05, ICC-09. `bothCrashed = true` means both the IC and the triggered utility are removed from active memory. `deckerNoticed` is the result of the secret Sensor test when the utility wins.

---

### `TrackState` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/TrackState.kt`

```kotlin
data class TrackState(
    val trackingIcRating: Int,
    val locationCycleTurnsRemaining: Int
)
```

PRD: CC-30. Added to `Decker` as a nullable field. Non-null means the decker's datatrail has been locked. `locationCycleTurnsRemaining` is decremented by `advanceCombatTurn()`.

---

### `SlowResult` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/SlowResult.kt`

```kotlin
data class SlowResult(
    val actionsLost: Int,
    val icInert: Boolean
)
```

PRD: ICC-15. `icInert = true` when `actionsLost >= icInitiative.initiativePasses`.

---

### `IcSuppressionState` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/IcSuppressionState.kt`

```kotlin
data class IcSuppressionState(
    val ic: IC,
    val icRating: Int   // rating at crash moment; used if the decker later unsuppresses
)
```

PRD: CC-22. Represents one suppressed (crashed but held) IC program. The decker holds this to prevent the security-tally increase. Unsuppressing restores 1 Detection Factor point and immediately adds `icRating` to the tally.

---

## Changes to Existing Types

### `Decker`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

Add two nullable fields:

```kotlin
val blackIcPin: BlackIcPinState? = null
val trackState: TrackState? = null
```

Add computed property:

```kotlin
val isPinnedByBlackIc: Boolean get() = blackIcPin != null
```

Add field for IC suppression:

```kotlin
val suppressedIc: List<IcSuppressionState> = emptyList()
```

Computed property for Detection Factor penalty (PRD: CC-22):

```kotlin
val suppressionDfPenalty: Int get() = suppressedIc.size
```

The effective Detection Factor used in System Tests = `detectionFactor - suppressionDfPenalty`.

`advanceCombatTurn()` (already defined in `cyberdeck_and_program_mechanics.md`) must also decrement `trackState?.locationCycleTurnsRemaining`, setting `trackState = null` when it reaches 0.

PRD: ICC-10, CC-22, CC-30.

---

### `ConditionMonitor`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/ConditionMonitor.kt`

The existing `applyDamage` stub must apply boxes per the SR3 damage scale:

```kotlin
fun applyDamage(damage: DamageLevel): ConditionMonitor = copy(
    filledBoxes = minOf(10, filledBoxes + damage.boxes)
)

val isCrashed: Boolean get() = filledBoxes >= 10
```

Where `DamageLevel.boxes` is an extension or property: `LIGHT = 1`, `MODERATE = 3`, `SERIOUS = 6`, `DEADLY = 10`.

PRD: CC-27.

---

### `DumpShock`

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/Combat.kt`

Already has `power` and `level` getters. No structural change — `CombatResolver.resolveDumpShock()` calls it directly. PRD: CC-29.

---

## Combat Resolution Object

### `CombatResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt`

A stateless `object`. All methods are pure — they accept a `DiceRoller` and return new value-type results or updated `Decker` copies via `.copy()`. No shared mutable state.

---

### Initiative

#### `rollDeckerInitiative(decker: Decker, meatworldComm: Boolean, diceRoller: DiceRoller): CombatInitiative`

PRD: CC-04, CC-05.

1. `responseDice = decker.cyberdeck.responseIncrease`
2. `commPenalty = if (meatworldComm) 1 else 0`
3. `numDice = max(1, 1 + responseDice - commPenalty)`
4. Roll `numDice` D6, sum result + `decker.persona!!.reaction` → `score`.
5. Return `CombatInitiative(score, initiativePasses = numDice)`.

**Physical enhancements do not affect Matrix initiative (rules p. 223):** Wired reflexes, magical augmentation, vehicle-control rigs, and any other enhancement that increases the decker's physical-world Reaction Attribute have no effect on Matrix Initiative. `decker.persona!!.reaction` is the *Persona* Reaction (base Reaction + Response Increase × 2), not the physical Reaction. The `rollDeckerInitiative` method must never read from a physical-augmentation attribute.

**Meatworld communications — action-timing displacement (rules p. 222–223):** A decker communicating directly by voice or datascreen with the meatworld not only rolls one fewer initiative die (the `commPenalty` above), but also has their Matrix actions resolved along with the *physical* actions of each Initiative Pass — even if the decker's initiative score would normally let them act earlier in the pass. The exception is communication via hitcher electrodes (`HitcherJackType.ELECTRODE_NET`) or datascreen-only contact with other personas on the same system; those do not impose the timing displacement. When `meatworldComm == true` and the decker's comms type is not an exempt channel, the game engine must insert the decker into the physical-action slot of the pass rather than the Matrix-action slot.

**Delayed Action with meatworld synchronization (rules p. 222):** If a decker declares a Delayed Action to wait for a physical-world event, their action is resolved in the physical-action slot of the pass in which the triggering event occurs. Matrix actions (including any IC acting in that pass) still precede physical actions, so the decker's delayed action fires *after* any IC that acts in the same pass.

---

#### `rollIcInitiative(ic: IC, securityCode: SecurityCode, diceRoller: DiceRoller): CombatInitiative`

PRD: CC-06.

1. `numDice = ic.initiativeDice(securityCode)` — already implemented on `IC`.
2. Roll `numDice` D6, sum + `ic.rating` → `score`.
3. Return `CombatInitiative(score, initiativePasses = numDice)`.

For IC triggered mid-turn (CC-08), the caller subtracts `10 × completedPasses` from `score` before inserting it into the initiative order.

**Reactive IC end-of-turn timing (CC-02):** Reactive IC programs that perform tasks at the end of a Combat Turn act after all deckers have completed their allotted actions for that turn. The game engine must not resolve reactive IC callbacks until the decker action phase for that turn is fully resolved.

---

### Combat Maneuvers

#### `resolveManeuver(maneuver: CombatManeuverType, mover: ManeuverParticipant, opponent: ManeuverParticipant, diceRoller: DiceRoller): ManeuverResult`

PRD: CC-14–CC-19.

1. `moverTn = max(2, opponent.sensor - mover.cloakRating)`
2. `opponentTn = max(2, mover.evasion - opponent.lockOnRating)`
3. Roll `mover.evasion + mover.hackingPool` dice vs. `moverTn` → `moverSuccesses`.
4. Roll `opponent.sensor + opponent.hackingPool` dice vs. `opponentTn` → `opponentSuccesses`.
5. `net = moverSuccesses - opponentSuccesses`
6. Return `ManeuverResult.Success(net)` if `net > 0`, else `ManeuverResult.Failure`.

The caller is responsible for interpreting `netSuccesses` by maneuver type (CC-17, CC-18, CC-19) and for updating `CombatModifiers` accordingly.

**Evade Detection — IC re-detection countdown (rules p. 224–225):** On a successful Evade Detection (`ManeuverResult.Success`), the evading icon is hidden from the IC for a number of Combat Turns equal to `netSuccesses`. The game engine holds this countdown. The countdown is shortened by 1 turn for each point added to the icon's security tally during the hidden period (e.g. from Probe IC or failed System Tests). When the countdown reaches 0, the IC detects the icon again automatically — no new Sensor Test is required. The `ManeuverResult.Success(netSuccesses)` return value provides the raw countdown; the engine applies tally-shortening externally.

---

### Attack Resolution

#### `resolveAttack(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult`

PRD: CC-20–CC-26.

1. `tn = attackTn(defender.personaStatus, defender.securityCode)` — CC-21 table (see below).
2. `tn += attacker.modifiers.parryAttackBonus` — from an opponent's prior Parry Attack (CC-18).
3. `tn -= attacker.modifiers.positionAttackTnBonus` — from own prior Position Attack (CC-19).
4. `power = attacker.utilityRating + attacker.modifiers.positionAttackPowerBonus`
5. `effectivePower = max(0, power - defender.armorCurrentRating)`
6. Roll `attacker.utilityRating + attacker.hackingPool` dice vs. `max(2, tn)` → `attackerSuccesses`.
7. If `attackerSuccesses == 0` → return `AttackResult.Miss`.
8. Roll `defender.bod` dice vs. `effectivePower` → `defenderSuccesses`.
9. `net = attackerSuccesses - defenderSuccesses`
10. `staged = stage(attacker.rawDamageLevel, net)`
11. Return `AttackResult.Hit(attackerSuccesses, attacker.rawDamageLevel, staged, effectivePower)`.

**`private fun attackTn(status: PersonaStatus, code: SecurityCode): Int`** encodes the CC-24 table:

| SecurityCode | INTRUDING | LEGITIMATE |
|---|---|---|
| BLUE | 6 | 3 |
| GREEN | 5 | 4 |
| ORANGE | 4 | 5 |
| RED | 3 | 6 |

**`private fun stage(base: DamageLevel, net: Int): DamageLevel`** — shift the damage level by `net / 2` (integer division, rounds toward zero), clamped to `[LIGHT, DEADLY]`. Positive net stages up; negative stages down. PRD: CC-29.

**Crashing IC raises security tally (CC-21):** When `resolveAttack` results in the target IC's `ConditionMonitor.isCrashed`, the caller must immediately add the IC's rating to the decker's security tally — unless the decker declares suppression (CC-22) via `suppressIc(ic)`. This logic lives in the game engine / caller, not inside `resolveAttack` itself. IC programs attack using the host's Security Value as the dice pool (CC-23).

---

### Icon Damage and Secondary Effects

#### `applyIcDamage(decker: Decker, attack: AttackResult.Hit, ic: IC, diceRoller: DiceRoller): IcDamageResult`

PRD: CC-27, CC-28, ICC-10.

1. Apply `attack.stagedDamageLevel` to `decker.persona!!.conditionMonitor` via `applyDamage`.
2. Determine simsense overload:
   - If `ic is BlackIC` → `simsenseOverload = null` (CC-28 excludes Black IC).
   - Else if `attack.stagedDamageLevel == DEADLY` → `simsenseOverload = null`; set `dumpShockTriggered = true` (auto-crash, no test).
   - Else → `overloadTn = when (attack.stagedDamageLevel) { LIGHT → 2; MODERATE → 3; SERIOUS → 5; else → error }`. Roll `decker.willpower` dice vs. `overloadTn`. If `successes == 0`: apply 1 Stun box to Mental Condition Monitor; `stressBoxesApplied = 1`.
3. If `conditionMonitor.isCrashed` → `dumpShockTriggered = true`.
4. If `ic is BlackIC && attack.attackerSuccesses > 0` → set `decker.blackIcPin = BlackIcPinState(ic as BlackIC)`.
5. Return `IcDamageResult(updatedDecker, attack, simsenseOverload, dumpShockTriggered)`.

---

#### `resolveDumpShock(decker: Decker, host: Host, diceRoller: DiceRoller): Decker`

PRD: CC-29.

1. `shock = DumpShock(host.securityRating)` — `shock.power` = Security Value; `shock.level` = Damage Level by Security Code.
2. Roll `decker.body` dice vs. `shock.power` → `successes`.
3. `actualLevel = stage(shock.level, -successes)` — defender successes stage down.
4. Apply `actualLevel` to `decker`'s Physical Condition Monitor.
5. Return updated `Decker`.

---

### Black IC Pin

#### `resolveJackOutWithPin(decker: Decker, diceRoller: DiceRoller): JackOutPinResult`

PRD: ICC-10.

**Precondition:** `decker.isPinnedByBlackIc == true`.

1. Roll `decker.willpower` dice vs. `decker.blackIcPin!!.pinningIc.rating` → `successes`.
2. If `successes >= 1` → return `JackOutPinResult(succeeded = true, finalIcAttackTriggered = true)`.
3. Else → return `JackOutPinResult(succeeded = false, finalIcAttackTriggered = false)`.

On success, the caller resolves one final IC attack (`resolveAttack`) before completing the jack-out.

---

### IC Suppression

#### `suppressIc(ic: IC): Decker`

PRD: CC-21, CC-22. Called on the decker at the moment an IC is crashed and the decker declares suppression.

1. Append `IcSuppressionState(ic, ic.rating)` to `decker.suppressedIc`.
2. Do **not** add `ic.rating` to the security tally (suppression prevents this).
3. Return updated `Decker`. The Detection Factor is now effectively reduced by 1 (via `suppressionDfPenalty`).

**Preconditions:**

- May only be called in the same action that crashed the IC.
- The decker must still be on the system where the IC was active. A decker who has left a system (logged off, jacked out, or moved to a different host) **cannot** suppress IC from that system. The game engine must enforce this: if `decker.currentLocation` no longer contains the system the IC belongs to, `suppressIc` must not be called and the tally increment is applied immediately.

---

#### `unsuppressIc(ic: IC, securityTallyIncrement: (Int) -> Unit): Decker`

PRD: CC-22. Called when a decker releases a suppressed IC (Free Action).

1. Find and remove the `IcSuppressionState` matching `ic` from `decker.suppressedIc`.
2. Call `securityTallyIncrement(state.icRating)` — restores the tally increase that suppression was holding.
3. Return updated `Decker`. The Detection Factor increases by 1 (penalty reduced by 1).

The caller (game engine) handles the tally update; the method's callback keeps `CombatResolver` decoupled from security-tally state.

---

#### `resolveCrippler(decker: Decker, ic: Crippler, securityCode: SecurityCode, diceRoller: DiceRoller): CripplerResult`

PRD: ICC-01.

1. `sv = securityValue(securityCode)` — from `SecurityRating.value`.
2. Roll `sv` dice vs. `decker.detectionFactor` → `icSuccesses`.
3. Roll `decker.persona!!.attribute(ic.targetAttribute)` dice vs. `ic.rating` → `deckerSuccesses`.
4. `net = icSuccesses - deckerSuccesses`
5. `reduction = max(0, net / 2)`
6. `newValue = max(1, currentAttribute - reduction)`
7. Return `CripplerResult(updatedDecker, ic.targetAttribute, reduction)`.

Armor and Hardening provide no protection (ICC-01).

---

#### `resolveKiller(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult`

PRD: ICC-02. Delegates directly to `resolveAttack`. The caller constructs `AttackParticipant` with `rawDamageLevel` determined by host Security Code (Blue/Green = MODERATE, Orange/Red = SERIOUS).

---

#### `resolveProbe(ic: Probe, decker: Decker, diceRoller: DiceRoller): Int`

PRD: ICC-03. Returns the number of security tally points to add immediately.

1. Roll `ic.rating` dice vs. `decker.detectionFactor` → `successes`.
2. Return `successes`.

Called by the game engine each time the decker performs a System Test while Probe is active.

---

#### `resolveTarBaby(decker: Decker, ic: TarBaby, utility: Utility, diceRoller: DiceRoller): TarBabyResult`

PRD: ICC-05.

1. Roll `ic.rating` dice vs. `utility.currentRating` → `icSuccesses`.
2. Roll `utility.currentRating` dice vs. `ic.rating` → `utilitySuccesses`.
3. If `icSuccesses >= utilitySuccesses`:
   - Remove both `ic` and `utility` from active memory; security tally unchanged.
   - Return `TarBabyResult(updatedDecker, bothCrashed = true, deckerNoticed = false)`.
4. Else:
   - Roll `decker.persona!!.sensor` dice vs. `ic.rating` → `noticed = successes >= 1`.
   - Return `TarBabyResult(updatedDecker, bothCrashed = false, deckerNoticed = noticed)`.

---

### Gray IC

#### `resolveBlaster(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult`

PRD: ICC-06. Identical to `resolveKiller`. The caller checks whether the resulting crash triggers the MPCP degradation test.

#### `resolveBlasterMpcpTest(decker: Decker, ic: Blaster, diceRoller: DiceRoller): Decker`

PRD: ICC-06.

1. `tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating`
2. Roll `ic.rating` dice vs. `tn` → `successes`.
3. `reduction = successes / 2`
4. Return updated `Decker` with `cyberdeck.mcpRating = max(0, mcpRating - reduction)`.

---

#### `resolveRipper(decker: Decker, ic: Ripper, securityCode: SecurityCode, diceRoller: DiceRoller): CripplerResult`

PRD: ICC-07. Identical algorithm to `resolveCrippler`. The caller checks whether the resulting attribute value is 0 and, if so, calls `resolveRipperMpcpTest`.

#### `resolveRipperMpcpTest(decker: Decker, ic: Ripper, diceRoller: DiceRoller): Decker`

PRD: ICC-07. Identical to `resolveBlasterMpcpTest` except `ic` is `Ripper`.

---

#### `resolveSparky(attacker: AttackParticipant, defender: DefenderParticipant, diceRoller: DiceRoller): AttackResult`

PRD: ICC-08. Identical to `resolveKiller`. On crash, the caller calls both `resolveSparkyMpcpTest` and `resolveSparkyBodyDamage`.

#### `resolveSparkyMpcpTest(decker: Decker, ic: Sparky, diceRoller: DiceRoller): Pair<Decker, Int>`

PRD: ICC-08.

1. `tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating + 2`
2. Roll `ic.rating` dice vs. `tn` → `successes`.
3. Return updated decker (MPCP reduced by `successes / 2`) and `successes` (needed by body damage step).

#### `resolveSparkyBodyDamage(decker: Decker, ic: Sparky, sparkySuccesses: Int, diceRoller: DiceRoller): Decker`

PRD: ICC-08.

1. `staged = stage(MODERATE, sparkySuccesses)` — Sparky success count stages up the Moderate base.
2. `effectivePower = max(0, ic.rating - decker.cyberdeck.hardening)` — Hardening reduces Power.
3. Roll `decker.body` dice vs. `effectivePower` → `bodySuccesses`.
4. `actual = stage(staged, -bodySuccesses)`
5. Apply `actual` to Physical Condition Monitor; return updated `Decker`.

---

#### `resolveTarPit(decker: Decker, ic: TarPit, utility: Utility, diceRoller: DiceRoller): TarBabyResult`

PRD: ICC-09. Same resolution as `resolveTarBaby`. On `bothCrashed = true`, the caller additionally calls `resolveTarPitMpcpTest`.

#### `resolveTarPitMpcpTest(decker: Decker, ic: TarPit, utility: Utility, diceRoller: DiceRoller): Decker`

PRD: ICC-09.

1. `tn = decker.cyberdeck.hardening + decker.cyberdeck.mcpRating`
2. Roll `ic.rating` dice vs. `tn` → `successes`.
3. If `successes == 0` → same effect as Tar Baby: decker may reload from storage (no further action).
4. Else → corrupt all copies of `utility` in both `activeUtilities` and `storedUtilities` (remove by type match); decker cannot reload until a clean copy is obtained outside the run.
5. Return updated `Decker`.

---

### Black IC

#### `resolveLethalBlackIc(decker: Decker, ic: LethalBlackIC, securityCode: SecurityCode, diceRoller: DiceRoller): IcDamageResult`

PRD: ICC-11.

1. Determine Damage Level by `securityCode`: Blue/Green → MODERATE; Orange/Red → SERIOUS. Set `power = ic.rating`.
2. `effectivePower = max(0, power - decker.cyberdeck.hardening)` — Hardening reduces Power for body test only.
3. **Icon resistance test**: Roll `decker.persona!!.bod` dice vs. `power` (Armor protects normally, applied before this roll via `DefenderParticipant.armorCurrentRating`).
4. **Physical body resistance test**: Roll `decker.body` dice vs. `effectivePower` (Hacking Pool may NOT be added; Karma Pool may be used — resolved externally). **The Armor utility does NOT reduce Power for this body test** (ICC-11); Hardening already accounts for any deck-hardware mitigation.
5. Stage both results independently with `stage()`.
6. Apply icon damage to Condition Monitor; apply physical damage to Physical Condition Monitor.
7. If icon crashes before decker dies: set IC effective rating to `ic.rating + 2` for all subsequent tests (caller holds this state).
8. If decker dies (Physical CM full): set `dumpShockTriggered = true`; caller resolves final MPCP attack at `ic.rating * 2` via `resolveBlasterMpcpTest`. **If this MPCP attack reduces `cyberdeck.mcpRating` to 0**, the caller must additionally delete all data files downloaded by the decker during the run from both `cyberdeck.storedUtilities`/download handles and any connected offline storage. The MPCP rating is explicitly set to 0 (not clamped to a floor above 0).
9. Apply Black IC pin if first hit (ICC-10).
10. Return `IcDamageResult`. `simsenseOverload = null` (Black IC, CC-28).

#### `resolveNonLethalBlackIc(decker: Decker, ic: NonLethalBlackIC, securityCode: SecurityCode, diceRoller: DiceRoller): IcDamageResult`

PRD: ICC-12. Identical to `resolveLethalBlackIc` except physical body damage is replaced with Mental damage (Willpower resistance tests); unconsciousness triggers auto-disconnect. Mental damage overflow into Physical CM follows standard SR3 rules (handled by `ConditionMonitor`).

**Final MPCP attack on unconsciousness (rules p. 230):** When the decker is rendered unconscious (Mental CM full), the non-lethal Black IC still makes one final MPCP attack before the auto-disconnect completes. The caller must invoke `resolveBlasterMpcpTest(decker, ic, diceRoller)` at `ic.rating` (standard rating, not doubled) immediately before clearing the connection. If this attack reduces `cyberdeck.mcpRating` to 0, all data downloaded during the run is deleted, identical to the lethal Black IC rule (ICC-11).

---

### Black Hammer and Killjoy

#### `resolveBlackHammer(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult`

PRD: ICC-13. Identical to `resolveLethalBlackIc` **except** no final MPCP attack on decker death.

**Precondition:** Caller must verify `!targetDecker.cyberdeck.immuneToDumpShock` before calling; cyberterminal users and hitchers are immune (CT-04, ACC-03).

#### `resolveKilljoy(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult`

PRD: ICC-14. Identical to `resolveNonLethalBlackIc` with the same no-MPCP-attack exception as Black Hammer.

---

### Track Utility

#### `resolveTrackLock(attack: AttackResult.Hit, targetDecker: Decker, trackRating: Int, diceRoller: DiceRoller): TrackState?`

PRD: CC-30.

1. Roll `targetDecker.persona!!.evasion` dice vs. `trackRating` → `evadeSuccesses`.
2. If `evadeSuccesses >= attack.attackerSuccesses` → return `null` (no lock).
3. `net = attack.attackerSuccesses - evadeSuccesses`
4. `cycleTurns = ceil(10.0 / net).toInt()`
5. Return `TrackState(trackRating, cycleTurns)`.

The caller sets `decker.trackState = result`. While `trackState != null`, Graceful Logoff TN is raised by `trackState.trackingIcRating`.

---

### Slow Utility

#### `resolveSlow(ic: IC, slowRating: Int, securityCode: SecurityCode, icInitiative: CombatInitiative, diceRoller: DiceRoller): SlowResult`

PRD: ICC-15.

**Precondition:** `ic.behavior == IcBehavior.PROACTIVE` (reactive IC is immune; return `SlowResult(0, false)` immediately if reactive).

1. `sv = securityValue(securityCode)`
2. Roll `sv` dice vs. `slowRating` → `icSuccesses`.
3. Roll `slowRating` dice vs. `sv` → `slowSuccesses`.
4. `net = slowSuccesses - icSuccesses`
5. If `net <= 0` → return `SlowResult(0, false)`.
6. `actionsLost = net / 2`
7. `icInert = (icInitiative.initiativePasses - actionsLost) <= 0`
8. Return `SlowResult(actionsLost, icInert)`.

If `icInert`, the IC does not add to the security tally. If not suppressed before the next Combat Turn, the caller re-rolls its initiative and it resumes.

---

## Verification

| Scenario | Expected Result |
|---|---|
| Decker Reaction 6, RI 2, no comms | `CombatInitiative(score = 6 + 3D6, initiativePasses = 3)` (CC-05) |
| Decker RI 2 with meatworld comms | `numDice = max(1, 1+2−1) = 2`; score reduced by ~3.5 average (CC-06) |
| Meatworld comms (non-exempt channel) | Decker inserted into physical-action slot of pass; not Matrix-action slot |
| Meatworld comms via hitcher electrodes | No action-timing displacement; only −1D6 initiative penalty applies |
| Decker declares Delayed Action for physical event | Action resolves in physical-action slot; after any IC acting in same pass |
| Physical Reaction-boosting ware on decker | Does not affect `rollDeckerInitiative`; only Persona Reaction used |
| IC Rating 5 in Orange host | `CombatInitiative(score = 3D6+5, initiativePasses = 3)` (CC-07) |
| IC triggered after pass 1 completed | Caller subtracts 10 from score; IC acts on next pass (CC-08) |
| Reactive IC callback queued mid-turn | Caller waits until all decker actions complete before resolving (CC-02) |
| Maneuver: mover 4 successes, opponent 2 | `ManeuverResult.Success(2)` (CC-17) |
| Maneuver: mover 2, opponent 2 | `ManeuverResult.Failure` — equal successes = fail (CC-17) |
| Parry Attack 3 net successes | `CombatModifiers(parryAttackBonus = 3)` set on target; next incoming attack TN +3 (CC-19) |
| Position Attack 2 net successes | Attacker sets TN −2 OR Power +2 on next attack (CC-20) |
| Attack: Intruding icon in Blue host | TN = 6 (CC-24) |
| Attack: Legitimate icon in Red host | TN = 6 (CC-24) |
| Armor-4 vs. Power 7 | `effectivePower = 3`; defender rolls vs. 3 (CC-28) |
| 4 net attacker successes | Damage stages up by 2 levels (CC-29) |
| 4 net defender successes | Damage stages down by 2 levels (CC-29) |
| IC crashes; decker declares suppression | `suppressIc()` called; tally NOT raised; DF −1 (CC-21, CC-22) |
| IC crashes; no suppression declared | Caller adds `ic.rating` to tally immediately (CC-21) |
| Decker unsuppresses held IC | `unsuppressIc()` called; tally raised by held IC rating; DF +1 (CC-22) |
| `suppressIc` called after decker left system | Precondition violated; tally increment applied instead; `suppressIc` not called |
| Evade Detection: 3 net successes | Hidden for 3 Combat Turns; countdown held by game engine |
| Evade Detection: tally gains 2 points while hidden | Countdown reduced by 2 (from 3 to 1 turn remaining) |
| Lethal Black IC kills decker; MPCP → 0 | All downloaded data deleted from deck and offline storage |
| Lethal Black IC kills decker; MPCP survives | No data deletion; only decker death effects apply |
| Non-lethal Black IC renders decker unconscious | Final MPCP attack at `ic.rating` before disconnect; data deleted if MPCP → 0 |
| Moderate damage from White IC, Willpower fails | `stressBoxesApplied = 1` (CC-31) |
| Deadly damage from Gray IC | `simsenseOverload = null`; `dumpShockTriggered = true`; no Willpower test (CC-31) |
| Black IC damage | `simsenseOverload = null`; pin state set after first hit (CC-31, ICC-10) |
| Dump shock in Orange host (SV 5, Security Rating = Orange) | `DumpShock(level = SERIOUS, power = 5)`; body resistance test applied (CC-32) |
| Black IC first successful hit | `decker.isPinnedByBlackIc == true` (ICC-10) |
| Pin Willpower test succeeds | `JackOutPinResult(succeeded = true, finalIcAttackTriggered = true)` (ICC-10) |
| Pin Willpower test fails | `JackOutPinResult(succeeded = false, finalIcAttackTriggered = false)` (ICC-10) |
| Crippler (Bod) 4 net IC successes | Bod reduced by 2, floor 1; Armor/Hardening not applied (ICC-01) |
| Ripper reduces attribute to 0 | Caller triggers `resolveRipperMpcpTest`; 2 successes → MPCP −1 (ICC-07) |
| Sparky post-crash, 4 successes, Rating 6 | MPCP −2; then 6M physical damage staged up 2× before body resist (ICC-08) |
| Tar Baby wins opposed test | Both programs removed from active memory; tally unchanged (ICC-05) |
| Tar Pit wins + MPCP test 2 successes | Utility corrupted in all memory; decker cannot reload during run (ICC-09) |
| Tar Pit wins + MPCP test 0 successes | Same as Tar Baby result; decker may reload from storage (ICC-09) |
| Track: 5 attacker successes, 2 evade successes | `TrackState(cycleTurns = ceil(10/3) = 4)` (CC-33) |
| Track: evader matches attacker successes | `null` returned — no lock (CC-33) |
| Slow on Reactive IC | Precondition check fails; `SlowResult(0, false)` (ICC-15) |
| Slow: 4 net successes, IC has 2 passes | `SlowResult(actionsLost = 2, icInert = true)` (ICC-15) |
| Black Hammer vs. Cyberterminal user | Caller checks `immuneToDumpShock = true`; does not call `resolveBlackHammer` (ICC-13) |
| Black IC physical body test, Armor-5 loaded | Armor does NOT reduce Power for body test; only Hardening applies (ICC-11) |
