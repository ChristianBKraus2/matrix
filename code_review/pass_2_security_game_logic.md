# Security Review — game_logic

## Summary

The game_logic component is a purely in-process simulation with no HTTP surface, no authentication model, and no user-supplied runtime serialization — so classic web attack vectors (SQLi, XSS, auth bypass) simply do not apply. The real risks are narrower: the YAML config loaders use an unsafe SnakeYAML constructor that enables arbitrary Java object instantiation from config files; two game-engine paths contain unchecked dereferences and casts that can crash the combat loop when IC attacks a decker in an unexpected state; a Ripper-reduced zero attribute is later passed as a dice count and throws a hard exception; and several caller-trusted boolean/integer parameters have no validation, enabling state manipulation or crashes via buggy or adversarial callers. Tally and query logic contain silent divergence and always-matching query bugs that corrupt game state without any observable error.

---

## Findings

### [CRITICAL] SnakeYAML `load()` allows arbitrary Java object instantiation

**Files:**
- `src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogLoader.kt:11`
- `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:18`
- `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:38`

**Issue:** All three config loaders construct `val yaml = Yaml()` and call `yaml.load<...>(input)`. SnakeYAML's default `Yaml()` constructor uses a fully-permissive constructor that processes `!!`-prefixed type tags in the YAML document, allowing instantiation of arbitrary Java/Kotlin classes — including `Runtime`, `ProcessBuilder`, and gadget chains. If any config file can be influenced by an external party (e.g. loaded from a path controlled by a user, fetched from the network, or committed to a shared repository with tampered content), this is a remote/local code execution vector. The severity is CRITICAL because exploitation requires only editing a YAML file, not modifying compiled code.

**Recommendation:** Replace `Yaml()` with `Yaml(SafeConstructor(LoaderOptions()))` (SnakeYAML ≥ 1.33) in all three loaders. `SafeConstructor` rejects all `!!` type tags and allows only the standard YAML scalar, sequence, and mapping types. No functional change is needed since the loaders already cast everything manually to `Map<String, Any>`.

---

### [HIGH] Unchecked `persona!!` and unchecked cast in `asDefenderParticipant()`

**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:8,11`

**Issue:** The extension function used by every IC `action()` call does:
```kotlin
bod = persona!!.bod,
securityCode = (currentLocation as MatrixLocation.OnHost).host.securityRating.code
```
`findTarget()` in IC.kt only requires `PersonaStatus.INTRUDING` — it does not verify that the target decker's `currentLocation` is `MatrixLocation.OnHost`. If any decker reaches `INTRUDING` status while located on a grid node (e.g., the game engine transitions alert state mid-turn and the decker has not yet moved), the cast throws `ClassCastException` inside `runCombatTurn()`, crashing the entire combat loop and leaving `GameContext` in an inconsistent state. Likewise `persona!!` throws `NullPointerException` if persona is null.

**Recommendation:** Replace the unchecked cast and force-unwrap with explicit guards:
```kotlin
val host = (currentLocation as? MatrixLocation.OnHost)?.host
    ?: error("asDefenderParticipant called on decker not on a host: $name")
val p = persona ?: error("asDefenderParticipant called on decker with no persona: $name")
```
Alternatively, restrict `findTarget()` to only return deckers whose `currentLocation` is `MatrixLocation.OnHost`.

---

### [HIGH] Ripper can reduce persona attribute to zero, crashing subsequent dice rolls

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:212`

**Issue:** `resolveRipper()` uses `max(0, currentAttr - reduction)` — flooring to zero. `resolveCrippler()` at line 163 correctly uses `max(1, ...)`. When a Ripper drives an attribute to 0, any later call that uses that attribute as a dice pool (e.g., `diceRoller.roll(persona.evasion, tn)` in `resolveManeuver`, `resolveTarBaby`, etc.) hits the `require(numberOfDice > 0)` guard in `DiceRoller.roll()` and throws `IllegalArgumentException`, crashing the game-loop thread mid-turn.

**Recommendation:** Change line 212 to `max(1, currentAttr - reduction)`, consistent with Crippler. If the intent is to allow zeroing (different rule interpretation), add defensive clamping in `DiceRoller.roll()` or in every call site that passes a potentially-zero attribute.

---

### [MEDIUM] `suppressIc()` accepts duplicate suppression, stacking the DF penalty unboundedly

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:427`

**Issue:** `suppressIc()` appends an `IcSuppressionState` without checking whether the same IC is already in `decker.suppressedIc`. A caller (or a buggy game-engine path) that invokes this twice with the same IC creates two entries, each decrementing `effectiveDetectionFactor` by 1. There is no upper bound, so repeated calls can drive the effective DF to zero or below, making the decker effectively undetectable.

**Recommendation:** Add a guard at the top of `suppressIc()`:
```kotlin
require(decker.suppressedIc.none { it.ic == ic }) { "IC ${ic.name} is already suppressed" }
```

---

### [MEDIUM] `makeComcall()` `hasValidPasscode` flag is fully caller-trusted

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1093`

**Issue:** When `hasValidPasscode = true`, the method skips the System Test entirely and substitutes a fabricated `SystemTestOutcome(1, 0, true)`. No tally is added, no security test is run, and the handle is returned unconditionally. The flag carries no cryptographic or structural proof — any caller can set it `true`. In a multiplayer or server-mediated scenario this lets a client silently bypass all Matrix security for comcall operations.

**Recommendation:** Move passcode validation to the server/GM layer before calling this method, or assert that the passcode is checked against a stored credential in the decker or host model (e.g., `require(host.validPasscodes.contains(decker.passcode))`). Document clearly in the KDoc that callers are responsible for prior validation.

---

### [MEDIUM] Interrogation query is always empty — `locateFile`/`locateSlave` match any object

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:750,783`

**Issue:** Both `locateFile()` and `locateSlave()` initialise `InterrogationState` with an empty query string `""`:
```kotlin
InterrogationState(SystemOperation.LOCATE_FILE, "")
```
The lookup then calls `name.contains(state.query, ignoreCase = true)`. Because every string contains `""`, on reaching the success threshold the operation always returns the first file or device in `host.dataFiles` / `host.remoteDevices`, regardless of what the decker was searching for. An attacker who reaches the threshold can reliably obtain the first host file or device without specifying a target.

**Recommendation:** The query string must be passed by the caller and stored when the interrogation starts. Add a `query: String` parameter to `locateFile()` and `locateSlave()` and use it when constructing the initial `InterrogationState`. Guard against empty query strings with a `require(query.isNotBlank())` check.

---

### [MEDIUM] `GameContext.updateDecker()` silently diverges state on missing decker

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:32`

**Issue:**
```kotlin
System.err.println("GameContext.updateDecker: decker '${old.name}' not found…")
return
```
When IC applies damage and calls `context.updateDecker(target, dmg.updatedDecker)`, if the index lookup fails the updated decker (with applied damage, pin state, etc.) is simply dropped. The game continues with the stale pre-damage decker in the context list. This is exploitable if a race or ordering bug can cause the lookup to miss: the decker takes no effective damage and game state is permanently corrupted. Even in the single-threaded simulation this creates invisible bugs that are very hard to diagnose.

**Recommendation:** Promote this to a thrown exception rather than a stderr print:
```kotlin
error("GameContext.updateDecker: decker '${old.name}' not found — this is a programming error")
```
A loud crash at the point of corruption is far preferable to silent state divergence.

---

### [LOW] `controlSlave()` `effectiveSkill` parameter has no lower-bound validation

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:896`

**Issue:** `controlSlave()` accepts `effectiveSkill: Int? = null`. If a caller passes 0 or a negative value, `diceRoller.roll(skill, tn)` immediately throws `require(numberOfDice > 0)`. There is no validation before the roll.

**Recommendation:** Add `require((effectiveSkill ?: 1) >= 1) { "effectiveSkill must be positive" }` at the start of `controlSlave()`.

---

### [LOW] `relocateIcon()` caller-supplied sensor and MCP values have no validation

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1148`

**Issue:** `opponentSensor: Int` and `trackerMcpRating: Int` are passed directly to `diceRoller.roll()`. A zero or negative argument crashes with `require(numberOfDice > 0)`.

**Recommendation:** Add `require(opponentSensor >= 0)` and `require(trackerMcpRating >= 0)` guards, and decide whether a 0 tracker MCP means no roll (return success) or 1 die, documenting the intent.

---

### [LOW] `inactivitySeconds` in `nullOperation` / `resolveNullOperation` is unvalidated

**Files:**
- `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:970`
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:65`

**Issue:** A negative `inactivitySeconds` value flows through `NullOperationModifier.totalBonusForDuration()` unchecked. Depending on that function's implementation, negative input could produce a negative Security Value bonus, making the host easier to run a null operation against than it should be.

**Recommendation:** Add `require(inactivitySeconds >= 0) { "inactivitySeconds must be non-negative" }` in `nullOperation()` before delegating to the resolver.

---

### [LOW] Security-sensitive game state emitted at INFO log level

**Files:**
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:42–53`
- `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt` (multiple methods)

**Issue:** INFO-level log messages emit computer skill, detection factor, host security value, tally increments, and decker names. In the current local simulation this is benign, but if logging output is ever forwarded to a shared log aggregator or the engine is extended to a multiplayer server, these messages constitute information disclosure of security-sensitive game parameters to anyone with log access.

**Recommendation:** Demote these log lines to DEBUG level. Reserve INFO for high-level events (jack-in/jack-out, alert escalation), and reserve DEBUG/TRACE for roll internals.

---

## No Issues Found In

- **Injection risks** — No SQL, shell, or OS interaction; no runtime eval. The simulation is purely in-memory.
- **Privilege escalation within game logic** — The `PersonaStatus.LEGITIMATE` / `INTRUDING` distinction is enforced correctly in IC targeting (`unauthorizedDeckerInHost`, `unauthorizedDeckerInNode`). Legitimate deckers are never targeted.
- **Dice roller integrity** — `DiceRoller` uses `kotlin.random.Random` injected at construction, making it testable and deterministic in tests. The exploding-dice loop is bounded by hardware limits on integer accumulation.
- **Network topology enforcement** — `logonToRtg`, `logonToLtg`, `logonToHost` all validate adjacency against the static topology before proceeding; no decker can teleport to a non-adjacent node.
- **Alert escalation** — `checkTriggers` correctly filters `alertTransition.ordinal > host.alertStatus.ordinal`, preventing downgrade. Trigger thresholds use an inclusive range and trigger only once per tally band.
- **Cyberdeck invariants** — `Cyberdeck.init` validates MPCP bounds, persona program totals, active/stored memory capacities, and response increase limits at construction time.
- **Condition monitor overflow** — `ConditionMonitor.applyDamage` uses `coerceAtMost(maxBoxes)`, preventing damage overflow. Dump shock and staged damage use ordinal clamping correctly.
