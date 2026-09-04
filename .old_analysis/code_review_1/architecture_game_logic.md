---
# Architecture Review — game_logic

## Summary

The game logic layer has one dominant structural problem that cascades into most other issues: `Decker` is a 1270-line `data class` that is simultaneously a character-attribute carrier, a navigation state machine, a system-operations executor, a memory manager, and a game-loop participant. This God Object forces every other package to depend on it, and its implementation of `ActiveIcon` (a `game`-package interface) creates a bidirectional package dependency between `decker` and `game`. The combat layer (`CombatResolver`) dispatches on concrete IC subtypes rather than using polymorphism, which couples it tightly to the full IC taxonomy. IC programs write directly back into `GameContext` during their `action()` call, creating another bidirectional dependency. Several supporting types (`LocateResult.Located`, `MonitoredOperationHandle`) erase type safety with `Any`. These problems are concentrated in the core domain and will compound as the system grows.

## Findings

---

### [CRITICAL] `Decker` is a God Object with ~1270 lines of mixed concerns

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:51`

**Issue:** `Decker` is declared as a `data class` yet contains 30+ domain-operation methods spanning six distinct responsibilities: character attribute storage, matrix navigation state (`currentLocation`, `jackpoint`, `persona`), combat state (`blackIcPin`, `trackState`, `suppressedIc`), utility/memory management (`loadUtility`, `unloadUtility`, `swapUtility`, `advanceCombatTurn`), the execution of every single system operation (`analyzeHost`, `analyzeIc`, `decryptFile`, `locateFile`, `downloadData`, `controlSlave`, `tapComcall`, `invokeMediac`, `resolvePointerChain`, and ~20 more), and the game-loop contract via `ActiveIcon`. A `data class` signals "value object"; the volume of behavior here violates that contract and makes the class the single most-coupled file in the entire codebase. Every new operation adds lines here rather than in a new, focused class.

**Recommendation:** Extract responsibilities into separate classes. A minimal split: (1) `DeckerAttributes` — the pure character sheet (name, intelligence, body, willpower, reaction, computerSkill); (2) `DeckerSession` — navigation and persona state; (3) `DeckerMemory` — active/pending/stored utility management (loadUtility, unloadUtility, advanceCombatTurn); (4) `SystemOperationService` — all system-test-performing methods, taking a Decker and Host as parameters rather than living on the Decker itself; (5) keep `Decker` as a slim aggregate that delegates and composes these. The `ActiveIcon` implementation can then live in a thin wrapper or be delegated explicitly.

---

### [HIGH] Bidirectional package dependency between `decker` and `game`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:68` and `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:16`

**Issue:** `Decker` (in package `decker`) implements `ActiveIcon`, which is defined in package `game` and carries `GameContext` as a parameter. Meanwhile `GameContext` holds `MutableList<Decker>` and mutates it directly. This is a true circular package dependency: `decker → game → decker`. The existence of `DeckerExtensions.kt` in the `game` package — a bridge file whose only job is to project a `Decker` into a `DefenderParticipant` — is a symptom of this same cycle; it was placed in `game` precisely because it could not cleanly live in `decker` without the cycle becoming even more visible.

**Recommendation:** Introduce an `engine` or `session` package that owns `GameContext`, `ActiveIcon`, and the game loop. Neither `decker` nor `ic` should know about `GameContext`; both should expose pure domain state. The game engine layer reads that state and drives transitions rather than having the entities call back into the context. Concretely: move `ActiveIcon` and `GameContext` to `engine`; have the engine call resolver functions rather than `entity.action(context, dice)`.

---

### [HIGH] `CombatResolver` dispatches on all concrete IC types instead of using polymorphism

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:154`

**Issue:** `CombatResolver` contains named methods for every concrete IC subtype — `resolveCrippler`, `resolveRipper`, `resolveBlaster`, `resolveBlasterMpcpTest`, `resolveRipper`, `resolveRipperMpcpTest`, `resolveSparky`, `resolveSparkyMpcpTest`, `resolveSparkyBodyDamage`, `resolveTarBaby`, `resolveTarPit`, `resolveTarPitMpcpTest`, `resolveLethalBlackIc`, `resolveNonLethalBlackIc`, `resolveBlackHammer`, `resolveKilljoy`. Adding a new IC type requires adding new methods here. Each IC type already has its own class; the resolution logic for an IC should be accessible via that class (through a strategy interface, a secondary `resolve(decker, resolver, dice)` method on the IC, or a visitor), not by a central object that enumerates all subtypes by name.

**Recommendation:** Introduce a `CombatEffect` strategy on `IC` (or a separate resolver interface per IC subtype). `CombatResolver` retains only the generic primitives — `resolveAttack`, `stage`, `icAttackParticipant`, initiative rolling — and each IC class or a dedicated companion object carries the knowledge of its own resolution sequence. This is consistent with the existing polymorphic `action()` dispatch pattern already present on `IC`, which `CombatResolver` then contradicts.

---

### [HIGH] IC programs mutate `GameContext` from inside their `action()` method (bidirectional IC ↔ GameContext dependency)

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:58`

**Issue:** Every concrete IC class (Killer, Crippler, Blaster, Ripper, Sparky, TarBaby, TarPit, LethalBlackIC, NonLethalBlackIC) calls `context.updateDecker(target, result.updatedDecker)` from within its `action()` method. This means `IC` instances depend on `GameContext` at call time and directly mutate the shared game state. `GameContext` in turn holds `MutableList<IC>`. The entity (IC) and the state container (GameContext) are bidirectionally coupled. This makes it impossible to run an IC's resolution logic in isolation without a live context, complicating testing and future rule variants.

**Recommendation:** Make `action()` return a domain event or a `CombatEffect` value object describing what happened (e.g., `DeckerDamaged(decker, result)`, `TallyIncreased(n)`). The game engine layer collects these effects and applies them to `GameContext` after all actions for the initiative pass are resolved. This also eliminates the risk of mid-pass state mutation affecting subsequent IC actions in the same pass.

---

### [HIGH] `Sparky.action()` silently discards the MPCP damage phase

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:152`

**Issue:** `resolveSparkyMpcpTest` returns `Pair<Decker, Int>` where the `Int` is the sparky successes needed to then call `resolveSparkyBodyDamage`. Inside `Sparky.action()` the second element is discarded with `_`: `val (updated, _) = CombatResolver.resolveSparkyMpcpTest(...)`. The physical body damage phase (`resolveSparkyBodyDamage`) is never invoked from the action method. This is an architecture problem because the two-phase resolution contract is not expressed in the type system — `resolveSparkyMpcpTest` returns data that *must* be forwarded to the next call, but nothing enforces that.

**Recommendation:** Collapse the two-phase Sparky resolution into a single `resolveSparkyFull(decker, ic, diceRoller): IcDamageResult` in `CombatResolver` (parallel to `resolveLethalBlackIc` which correctly handles its own two-phase logic internally). `Sparky.action()` then makes one call. The intermediate `sparkySuccesses` value becomes an internal local variable.

---

### [MEDIUM] `LocateResult.Located` and `MonitoredOperationHandle` use `Any` for their target

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt:67` and `src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt:13`

**Issue:** `LocateResult.Located(val target: Any, ...)` can hold a `DataFile`, a `RemoteDevice`, or a plain `String` (the access-node address). `MonitoredOperationHandle(val target: Any)` holds a `Host` or `RemoteDevice`. In both cases callers must cast at the use site with no compile-time guarantee about what type they will receive. This erases the type information that the domain model has carefully defined everywhere else.

**Recommendation:** For `LocateResult`: introduce a sealed `LocatedTarget` with cases `FoundFile(val file: DataFile)`, `FoundDevice(val device: RemoteDevice)`, `FoundAccessNode(val address: String)`. For `MonitoredOperationHandle`: use a sealed `MonitoredTarget` or separate handle subtypes per monitored operation type (e.g., `SlaveHandle`, `ComcallHandle`), matching the pattern already used for `OperationResult`.

---

### [MEDIUM] `GameContext` conflates mutable state bag with domain event logic

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:43`

**Issue:** `GameContext` has two distinct roles: (1) a mutable state container for the current game snapshot (`deckers`, `activeIc`, `host`); and (2) a domain-event handler that applies cascading business rules (`checkTriggers` — which activates IC programs and escalates alert status — and `applyDeckerOperationResult` — which orchestrates tally delta → host update → trigger check). The second role means `GameContext` knows about security escalation policy and trigger thresholds, which belong in a domain service, not in the container that all other classes use to read state.

**Recommendation:** Extract a `SecurityEventProcessor` (or `TriggerEvaluator`) that accepts the current `GameContext` snapshot, a tally delta, and returns an updated snapshot. `GameContext` becomes a pure state holder with simple setters; the processing logic moves to the service layer where it can be tested and varied independently.

---

### [MEDIUM] `Decker.controlSlave` manually replicates dice-rolling instead of using `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:884`

**Issue:** All other system operations delegate to `SystemTestResolver.resolve()` for the decker-vs-host dice contest. `controlSlave` is the one exception: it manually computes the Spoof TN reduction, rolls both dice pools directly, and constructs `SystemTestOutcome` by hand. This is inconsistent and risks diverging from the standard resolution rules if `SystemTestResolver` is updated (e.g., cyberterminal utility-rating penalties, logging format, future house rules).

**Recommendation:** Refactor `controlSlave` to call `SystemTestResolver.resolve(decker, SystemOperation.CONTROL_SLAVE, tn, host.securityRating.value, diceRoller)` consistent with every other operation, allowing the custom skill substitution (`effectiveSkill`) to be passed as a parameter override or applied before the call.

---

### [MEDIUM] Network topology stores bidirectional back-references in `data class` (LTG.parentRtg, PLTG.parentLtg)

**File:** `src/main/kotlin/com/shadowrun/matrix/network/Grid.kt:29`

**Issue:** `LTG` contains `parentRtg: RTG` and `PLTG` contains `parentLtg: LTG`. These are back-references inside `data class` objects that form an immutable tree. Every time any part of the tree changes (e.g., a security tally increment on the RTG), the entire downward tree must be reconstructed and all back-references re-pointed. This is visible in `GridLoader.load()` which requires a two-pass wiring step (lines 29–36) and `buildRtg()` which fixes parent references a second time after child construction (lines 72–74). The same re-wiring issue appears in `GameContext.updateHost()`.

**Recommendation:** Either remove back-references from the data layer and navigate parent→child only (look up parent by traversal from the root `Matrix`), or introduce a separate `TopologyIndex` that maps child IDs to parent IDs, keeping the graph structure outside the data nodes. Either approach eliminates the fragile multi-pass wiring.

---

### [MEDIUM] `Cyberdeck.immuneToDumpShock` semantically overloaded for two distinct rules

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt:33`

**Issue:** `immuneToDumpShock` is true for both cyberterminal users (CT-04) and hitcher-jack observers (ACC-03). However, `SystemTestResolver.effectiveRating()` uses `immuneToDumpShock` as a proxy for the cyberterminal utility-rating penalty (CT-03): `if (deck.immuneToDumpShock) maxOf(0, utility.currentRating - 1)`. Hitchers cannot perform operations at all and would never reach that code, so the bug is currently dormant. But the boolean now carries two semantically different meanings — "dump shock immune" and "has cyberterminal utility penalty" — which is incorrect. A future refactor that gives hitchers any operational ability would silently apply the wrong penalty.

**Recommendation:** Add a distinct `isCyberterminal: Boolean` flag (defaulting to `false`) separate from `immuneToDumpShock`. `SystemTestResolver.effectiveRating` should check `isCyberterminal`, not `immuneToDumpShock`. The `Cyberdeck` init can enforce that `isCyberterminal` implies `immuneToDumpShock` if needed.

---

### [LOW] `parseSecurityRating` and `parseSubsystemRatings` duplicated between `GridLoader` and `HostLoader`

**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:135` and `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:182`

**Issue:** Both `GridLoader` and `HostLoader` contain identical private implementations of `parseSecurityRating(String): SecurityRating` and `parseSubsystemRatings(Any?): SubsystemRatings`. Any change to the YAML format for these fields (e.g., accepting `"blue-4"` instead of `"BLUE-4"`) must be made in two places.

**Recommendation:** Extract both functions into a `YamlParsers` internal object in the `config` package and reference them from both loaders.

---

### [LOW] Persona construction logic is inlined in `Decker.performLogon`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1222`

**Issue:** The rule "persona attributes come from persona programs on the cyberdeck, reaction comes from base Reaction plus ResponseIncrease × 2" is expressed as a multi-line block directly inside the `performLogon` private helper. This means the policy for how a persona is initialized is hidden inside a navigation method rather than expressed as a factory function or constructor on `Persona` itself.

**Recommendation:** Add a `Cyberdeck.buildPersona(baseReaction: Int, status: PersonaStatus): Persona` factory that encodes the persona-from-programs construction. `performLogon` calls `cyberdeck.buildPersona(reaction, PersonaStatus.INTRUDING)`. The construction policy is then co-located with the `Cyberdeck` data it reads.

---

### [LOW] `GameContext.applyDeckerOperationResult` has a silent fallback that can silently corrupt host state

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:60`

**Issue:** Line 60: `val newHost = (new.currentLocation as? MatrixLocation.OnHost)?.host ?: host.copy(securityTally = newTally)`. If `new.currentLocation` is not `OnHost` (e.g., the decker is on an LTG), `newTally` will be 0 (from the `?: 0` on line 57), and the fallback silently calls `host.copy(securityTally = 0)`, resetting the host tally. This path is currently unreachable given the callers, but the fallback is structurally incorrect — a silent zero-reset should be a hard check or assertion.

**Recommendation:** Replace the fallback with `require(new.currentLocation is MatrixLocation.OnHost) { "applyDeckerOperationResult called for decker not on a host" }` or restructure the method to only accept `OnHost` deckers.

---

### [INFO] `DeckerExtensions.kt` signals an underlying coupling problem

**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:1`

**Issue:** This file exists in the `game` package rather than in `decker` or `combat` because placing it in `decker` would add a `decker → combat` dependency and placing it in `combat` would add a `combat → decker` dependency. Its existence in `game` is a workaround for the circular dependency structure, not a natural home. The extension function itself is fine; its awkward placement is the signal.

**Recommendation:** Resolve the underlying dependency cycle (see bidirectional `decker`/`game` finding above) and then move this extension to whichever package is its natural home — likely `combat` as a companion function on `DefenderParticipant`.

---

## Clean Areas

- `DiceRoller` — clean single-responsibility implementation; exploding-dice logic is self-contained, injectable, and well-tested.
- `common/Enums.kt` and `common/SharedTypes.kt` — appropriately thin; enums carry only domain constants with no behavior leaking in.
- `combat/` result types (`AttackResult`, `ManeuverResult`, `TrackState`, `BlackIcPinState`, `CombatModifiers`) — small, focused, correct use of sealed classes.
- `operations/` handle types (`DownloadHandle`, `MonitoredOperationHandle`, `InterrogationState`, `BufferedMessage`, `PointerChain`) — well-scoped value objects modelling in-progress operations cleanly.
- `network/` value types (`Host`, `Grid` subtypes, `DataFile`, `SAN`, `Node`, `MatrixLocation`) — domain concepts are properly encapsulated; `MatrixLocation` sealed hierarchy is a good pattern.
- `SystemOperation` enum — the per-operation metadata (subsystem type, utility, action type, category) is cleanly encoded as enum properties rather than scattered across conditional logic.
- `GridInitializer` — correctly a thin façade over `GridLoader`; classpath concern is isolated.
- `AlertTransitions.kt` — small, focused top-level function; alert escalation rules are easy to find and modify.
---
