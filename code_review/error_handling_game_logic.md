---
# Error Handling Review — game_logic

## Summary

The game logic layer has solid structural foundations: sealed classes return typed results, most public API entry points use `check`/`require` guards with clear messages, and `SystemTestResolver` has comprehensive logging. However, several high-severity gaps exist. The most dangerous is a hard cast in `DeckerExtensions.kt` that crashes every IC combat action if the decker is not currently on a host. Two IC action implementations (`Sparky`, `Probe`) silently discard their primary game effects — damage and tally respectively — without any log entry or error. Zero persona attributes are silently accepted at logon time and produce a deferred `IllegalArgumentException` deep inside combat. The config loaders perform raw `as` casts without null-guards, producing `ClassCastException` at startup with no indication of which file or field caused the failure.

## Findings

### [CRITICAL] Hard cast in `asDefenderParticipant` crashes all IC combat actions

**File:** src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:11
**Issue:** `(currentLocation as MatrixLocation.OnHost)` is an unchecked hard cast. This function is called by `Killer.action`, `Blaster.action`, and `Sparky.action` for every decker target found. If a decker is on an LTG, RTG, or PLTG (not `OnHost`) when an IC fires, the cast throws `ClassCastException`. The exception propagates uncaught through `IC.action` → `Game.runCombatTurn`, terminating the entire combat turn for all participants. The companion hard-bang `persona!!` on line 8 adds an NPE risk for the same call path if somehow a decker with `INTRUDING` status has a null persona.
**Recommendation:** Use a safe cast with a meaningful fallback:
```kotlin
fun Decker.asDefenderParticipant(): DefenderParticipant {
    val p = requireNotNull(persona) { "asDefenderParticipant: ${name} has no persona" }
    val host = requireNotNull(currentLocation as? MatrixLocation.OnHost) {
        "asDefenderParticipant: ${name} is not on a host (location=$currentLocation)"
    }
    return DefenderParticipant(
        bod = p.bod,
        armorCurrentRating = 0,
        personaStatus = p.status,
        securityCode = host.host.securityRating.code
    )
}
```

### [HIGH] `Sparky.action` silently drops icon damage and physical body damage

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:151-157
**Issue:** `Sparky.action` calls `CombatResolver.resolveSparkyMpcpTest` (MPCP reduction) but never calls `CombatResolver.applyIcDamage` (persona condition monitor damage) or `CombatResolver.resolveSparkyBodyDamage` (biofeedback physical damage). Compare to `Killer.action` which correctly calls `applyIcDamage`. The `sparkySuccesses` value returned by `resolveSparkyMpcpTest` is also discarded (the `_` binding), so `resolveSparkyBodyDamage` can never be called. When Sparky hits a decker, the only effect is MPCP reduction; the decker's persona and body take no damage at all. There is no log warning to indicate that effects were skipped.
**Recommendation:** Follow the same pattern as `Killer.action`:
```kotlin
if (result is AttackResult.Hit) {
    val dmg = CombatResolver.applyIcDamage(target, result, this, diceRoller)
    val (afterMpcp, sparkySuccesses) = CombatResolver.resolveSparkyMpcpTest(dmg.updatedDecker, this, diceRoller)
    val afterBody = CombatResolver.resolveSparkyBodyDamage(afterMpcp, this, sparkySuccesses, diceRoller)
    context.updateDecker(target, afterBody)
    return ActionResult.IcAttack("Sparky hit ${target.name}")
}
```

### [HIGH] `Probe.action` never applies tally to the host

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:83-88
**Issue:** `Probe.action` calls `CombatResolver.resolveProbe` to get the number of tally points and reports them in the `ActionResult.IcAttack` message string, but never writes them back to `GameContext`. Neither `context.updateDecker` (to update the decker's current-host tally copy) nor any host-tally mutation is called. The sole purpose of Probe — adding to the security tally to escalate alerts — is completely non-functional. The reported tally in the action message is fiction.
**Recommendation:** Apply the tally through the same mechanism used in decker operations. Either give Probe access to a tally-increment callback or use `context.checkTriggers` directly after adding successes to the host tally via `GameContext.updateHost`.

### [HIGH] Zero persona attributes silently created at logon; deferred crash in combat

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1222-1232
**Issue:** In `performLogon`, when a persona is created for the first time, each attribute falls back to `0` if the corresponding persona program is missing from `cyberdeck.personaPrograms`. `Cyberdeck.init` does not require all four persona programs to be present. A decker with `bod = 0` will pass logon cleanly but later cause `diceRoller.roll(0, tn)` to throw `IllegalArgumentException: numberOfDice must be positive` during the first combat action that uses that attribute (e.g., `applyIcDamage`, `resolveCrippler`). No warning is logged at logon time when a zero attribute is produced.
**Recommendation:** Log a warning for each missing program at persona creation time, and optionally enforce via a `require` that all four programs are present with rating ≥ 1. At minimum:
```kotlin
if (newPersona.bod == 0) logger.warn { "[$name] Logon: BOD persona program missing — defaulting to 0; combat rolls will fail" }
```

### [HIGH] Multiple `decker.persona!!` calls in `CombatResolver` with no guard

**File:** src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:39,89,157,185,211,379,396,411
**Issue:** `rollDeckerInitiative` (line 39), `applyIcDamage` (line 89), `resolveCrippler` (line 157), `resolveTarBaby` (line 185), `resolveRipper` (line 211), `resolveBlackHammer` (line 379), `resolveKilljoy` (line 396), and `resolveTrackLock` (line 411) all use `decker.persona!!` directly. If any of these functions is called with a decker that has `persona == null` (jacked-out decker still referenced in `activeIc` target chain, or stale reference), the JVM throws `NullPointerException` with no game-context information in the message. The exception propagates out of `Game.runCombatTurn` uncaught.
**Recommendation:** Replace each `decker.persona!!` with `requireNotNull(decker.persona) { "CombatResolver.${functionName}: decker '${decker.name}' has no persona" }`. This gives an actionable error message and the same fail-fast behavior without the cryptic NPE stack trace.

### [MEDIUM] `GameContext.updateDecker` silently no-ops without logging

**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:27-30
**Issue:** When `deckers.indexOf(old)` returns -1 (the reference was not found), the method returns without updating and without any log statement. This can silently discard damage applied to a decker if the reference passed as `old` is stale. While current call sites pass a freshly-retrieved reference, any future change that introduces a local copy could cause the silent loss of health state with no observable indication.
**Recommendation:** Add a warning log when the decker is not found:
```kotlin
if (idx < 0) {
    logger.warn { "updateDecker: decker '${old.name}' not found in context — update dropped" }
    return
}
```

### [MEDIUM] Config loaders silently drop unknown RTG cross-references

**File:** src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:31-32
**Issue:** `ids.mapNotNull { rtgById[it] }` silently drops any RTG ID in `connected_rtgs` that does not match a known RTG. A misspelled or missing RTG identifier in `grid.yaml` produces no warning and no error, leaving the topology silently misconfigured. The decker will never be able to navigate to the supposed connected grid.
**Recommendation:** Replace `mapNotNull` with an explicit check:
```kotlin
val connected = ids.map { id ->
    rtgById[id] ?: error("RTG '${rtg.name}': connected_rtgs references unknown id '$id'")
}
```

### [MEDIUM] Unsafe raw casts in config loaders produce uncontextualised `ClassCastException`

**File:** src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:145, HostLoader.kt:191
**Issue:** Both `parseSubsystemRatings` implementations cast `value as Map<String, Int>` without a null check and without catching `ClassCastException`. If the YAML field is absent (`value` is null) or has an unexpected type (e.g., a plain integer), the JVM throws `ClassCastException` with a stack trace pointing inside the loader but with no mention of which RTG, LTG, or host was being parsed. The error is hard to diagnose in a multi-host config file.
**Recommendation:** Validate type and null before casting:
```kotlin
private fun parseSubsystemRatings(value: Any?, contextName: String = "unknown"): SubsystemRatings {
    require(value is Map<*, *>) { "ratings for '$contextName' must be a map, got: ${value?.let { it::class.simpleName } ?: "null"}" }
    @Suppress("UNCHECKED_CAST")
    val map = value as Map<String, Int>
    ...
}
```
Pass the host/RTG name as `contextName` at each call site.

### [MEDIUM] `DeckerLoader` raw cast on `persona_programs` with no null guard

**File:** src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt:52
**Issue:** `data["persona_programs"] as Map<String, Int>` throws `ClassCastException` if the `persona_programs` key is absent from the YAML or contains a non-map value. There is no null check, no `as?` safe cast, and no helpful error message identifying the decker being loaded.
**Recommendation:**
```kotlin
val ppData = requireNotNull(data["persona_programs"] as? Map<*, *>) {
    "Decker '${data["name"]}': persona_programs is required and must be a map"
} as Map<String, Int>
```

### [LOW] `ioSpeedMpPerTurn == 0` silently produces `Int.MAX_VALUE` upload countdown

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:359
**Issue:** `Math.ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn).toInt()` uses floating-point division. If `ioSpeedMpPerTurn` is 0, the result is `Double.POSITIVE_INFINITY`, and `ceil(Infinity).toInt()` returns `Int.MAX_VALUE` on the JVM. The upload will never complete but no exception is thrown and no warning is logged. `Cyberdeck.init` does not validate `ioSpeedMpPerTurn > 0`.
**Recommendation:** Add `require(ioSpeedMpPerTurn > 0) { "ioSpeedMpPerTurn must be positive" }` to `Cyberdeck.init`.

### [LOW] `Scramble.action` return value indistinguishable from "no target found"

**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:93-95
**Issue:** `Scramble.action` always returns `ActionResult.NoTarget`, which is the same value returned by all other IC types when no unauthorized decker is in range. Any logging or event handling that inspects `ActionResult.NoTarget` cannot distinguish "Scramble deliberately chose not to act" from "no target was present." This will make future debugging or audit-logging misleading.
**Recommendation:** Add a dedicated `ActionResult.IcPassive(val reason: String)` subtype, or document the intent with a comment and return `ActionResult.IcAttack("Scramble: passive — no action taken")` to make it visible in logs.

### [INFO] Redundant `!!` after `check(persona != null)` throughout `Decker.kt`

**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt (multiple: 587, 588, 604, 605, 979, 988, 1053)
**Issue:** A pattern of `check(persona != null) { ... }` followed immediately by `persona!!.something` appears throughout the file. The `!!` cannot fail after a passing `check`, but Kotlin requires it because smart-casts do not apply to mutable properties of data classes. This is not a runtime hazard but it is a maintenance signal: a developer might see `!!` and assume the null case is simply accepted rather than guarded. Using a local `val p = checkNotNull(persona)` at the top of each method eliminates both the `check` call and all subsequent `!!` dereferences.
**Recommendation:** Replace the `check` + repeated `!!` pattern with a single `val persona = checkNotNull(this.persona) { "..." }` local binding at the start of each affected method.

## Clean Areas

- `Game.kt` — combat and out-of-combat turn drivers are simple, well-contained, and free of swallowed exceptions.
- `SystemTestResolver.kt` — every resolution path is logged at INFO level; no silent failures; error propagation is clean.
- `DiceRoller.kt` — explicit `require` guards on both `numberOfDice` and `targetNumber`; fail-fast behavior is correct.
- `GameContext.kt` — `checkTriggers` handles the ordinal comparison for alert transitions correctly; no silent alert regressions.
- `AlertTransitions.kt` — exhaustive `when` coverage over `AlertStatus`; the `NO_ALERT` no-op case is documented.
- `Decker.kt` logon/logoff methods — all use `requireJackedIn` / `requireNotJackedIn` guards with clear messages before any state mutation.
- `CombatModifiers.kt` — `init` block enforces the mutual-exclusion invariant between `positionAttackTnBonus` and `positionAttackPowerBonus`.
- Sealed result types (`ActionResult`, `OperationResult`, `LogonResult`, `LogoffResult`, `AttackResult`, `LocateResult`) — well-designed discriminated unions; no stringly-typed error codes.
- `Persona.kt` — `attribute`/`withAttribute` `when` blocks are exhaustive over `PersonaAttributeType`; no missing branches.
- `IC.kt` — `LethalBlackIC.action` and `NonLethalBlackIC.action` correctly delegate all resolution to `CombatResolver` and update the context.
---
