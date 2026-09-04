# Architecture Review — game_logic

## Summary

The game_logic component is anchored by a technically clean package structure (`game`, `network`, `operations`, `combat`, `decker`, `ic`, `programs`, `config`) and a well-chosen immutable-value-object approach throughout the domain model. However, the centrepiece `Decker` class has grown into a textbook God Object at 1285 lines, collapsing entity state, 20+ operation methods, navigation logic, active-memory management, combat-tick advancement, and game-loop participation into a single `data class`. This primary violation cascades into a bidirectional dependency between the `decker` and `game` packages, a tally-state duplication between `Decker.currentLocation` and `GameContext.host`, and a misuse of the `ActiveIcon` interface for a type whose `action()` is a permanent no-op stub. Secondary findings include `GameContext` blurring the line between state container and orchestration service, behavioural IC objects embedded inside the immutable `Host` model, and two config loaders independently duplicating the same parsing helpers.

---

## Findings

### [CRITICAL] `Decker` is a God Object

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:51`

**Issue:** `Decker` is a `data class` that simultaneously acts as:
- A domain value object (name, attributes, condition monitors, persona, location)
- A navigation controller (jackInToLtg, jackInToHost, logonToRtg/Ltg/Pltg/Host, gracefulLogoff, jackOut)
- An operation executor (analyzeHost, analyzeIc, analyzeIcon, analyzeSecurity, analyzeSubsystem, decryptAccess, decryptFile, decryptSlave, locateFile, locateSlave, locateAccessNode, locateDecker, locateIc, downloadData, editFile, uploadData, controlSlave, editSlave, monitorSlave, nullOperation, makeComcall, tapComcall, relocateIcon, invokeMediac, resolvePointerChain, bufferMessage, resolveScrambleDestructTest)
- An active-memory manager (loadUtility, unloadUtility, swapUtility, advanceCombatTurn)
- A game-loop participant (implements `ActiveIcon`)
- A visibility and action enumerator (visibleObjects, availableActions)

The result is ~40 import lines and a 1285-line file. Any change to one concern risks breaking others. The class cannot be tested in isolation for any single responsibility. The `data class` designation is misleading — the type carries extensive behaviour, making `copy()` semantics surprising in the presence of derived state.

**Recommendation:** Extract at minimum three collaborators:
1. `DeckerNavigator` (or extension functions) — all logon/logoff/jackIn methods. These accept a `Decker` value and return a `LogonResult`/`LogoffResult`, keeping state transformations pure.
2. `DeckerOperations` (or an `OperationService`) — all Matrix operation methods. They already delegate to `SystemTestResolver`; the outer shell belongs outside the entity.
3. `DeckerMemoryManager` — loadUtility, unloadUtility, swapUtility, advanceCombatTurn.
Keep `Decker` as a plain value object: attributes, condition monitors, persona, location, utility slots, and computed properties (hackingPool, detectionFactor, effectiveDetectionFactor).

---

### [HIGH] Bidirectional package dependency between `decker` and `game`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:46–47` and `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:7`

**Issue:** `Decker` imports `ActionResult`, `ActiveIcon`, and `GameContext` from the `game` package (lines 46–47 of Decker.kt). `GameContext` imports `Decker` from the `decker` package. This creates a circular package dependency. The intended layering — `game` orchestrates `decker`, not the other way around — is broken. Any module-boundary enforcement (e.g., splitting into Gradle subprojects or enforcing with ArchUnit) would immediately fail on this cycle.

**Recommendation:** Remove `Decker`'s implementation of `ActiveIcon`. The game loop already treats deckers specially: `Game.runOutOfCombatTurn()` calls `decker.action()` which returns a fixed `ActionResult.DeckerAction` stub — it does nothing. Replace this with an explicit branch in `Game` that skips deckers or calls a separate `DeckerTurnHandler`. `IC` can remain an `ActiveIcon` because its `action()` actually executes logic. The `decker` package should have no imports from `game`.

---

### [HIGH] Behavioural `IC` objects embedded in immutable `Host` data model

**File:** `src/main/kotlin/com/shadowrun/matrix/network/Host.kt:28` and `src/main/kotlin/com/shadowrun/matrix/network/SecuritySheaf.kt:7`

**Issue:** `Host.icPrograms: List<IC>` and `TriggerStep.activatedIc: List<IC>` embed objects that implement `ActiveIcon` inside an immutable configuration data class. `IC` is a behavioural, game-loop-participating type (it imports `GameContext`, calls `context.updateDecker`). This conflates two distinct concerns: the *configuration* of which IC programs exist on a host, and the *live game instances* of those IC programs that act each turn. When `GameContext.checkTriggers` calls `activeIc.addAll(step.activatedIc)`, it promotes config objects directly into the live game list — the same `IC` instance is both a config descriptor and an active combatant.

**Recommendation:** Separate IC configuration from IC runtime state. `Host` should hold `List<IcDefinition>` (a plain data type: type, rating, guardedNode). A factory — either in `GameContext` or a dedicated `IcFactory` — converts `IcDefinition` to a live `IC` instance when a trigger fires. This makes `Host` a pure data object and keeps `IC` (with its `game`-package imports) out of the network model.

---

### [MEDIUM] `GameContext` blends state container with orchestration logic

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:49–71`

**Issue:** `GameContext` is supposed to be the live game state bag, but `applyDeckerOperationResult()` (lines 61–71) performs multi-step orchestration: it reads the old tally from `host`, reads the new tally from the incoming decker snapshot, calls `updateDecker`, conditionally calls `updateHost`, and then calls `checkTriggers`. This is application-service-level logic sitting in what should be a passive state container. `checkTriggers` (lines 49–59) similarly combines tally comparison, IC spawning, and alert transitions. Both methods are hard to test without constructing a full `GameContext`.

**Recommendation:** Move `applyDeckerOperationResult` and `checkTriggers` to a `GameOrchestrator` or `TurnProcessor` service that holds a reference to `GameContext` as mutable state. `GameContext` should expose only primitive mutation operations (`updateDecker`, `updateHost`, `addIc`, `removeIc`) with no policy decisions inside.

---

### [MEDIUM] Tally state duplicated between `Decker.currentLocation` and `GameContext.host`

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:63` and `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1208`

**Issue:** The security tally for the current host is tracked in two places simultaneously: embedded inside `Decker.currentLocation` (as a field on the `Host` copy stored in `MatrixLocation.OnHost`) and separately as `GameContext.host`. The comment at `GameContext.kt:63` acknowledges this: "Use the live context host as the tally baseline, not the potentially stale decker snapshot." This is a data coherence smell — two sources of truth that can diverge, requiring defensive reconciliation code in `applyDeckerOperationResult`.

**Recommendation:** Remove tally from `Decker.currentLocation`. `MatrixLocation.OnHost` should hold a reference identifier (host name or an immutable `HostId`) rather than a mutable snapshot copy of the host. The canonical tally lives only in `GameContext.host`. `Decker.withUpdatedTally()` (which currently copies a modified host into the decker's location) should be eliminated; callers that need the tally after an operation should read it from `GameContext`.

---

### [MEDIUM] `immuneToDumpShock` overloaded as cyberterminal proxy in `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt:116–118`

**Issue:** `effectiveRating()` applies the cyberterminal program-rating penalty (CT-03: −1 to all utility ratings) by checking `deck.immuneToDumpShock`. These are two distinct game rules with different scopes: immunity to dump shock applies to hitcher observers (`HitcherObserver`, ACC-03) as well as cyberterminals, but the −1 program rating penalty applies only to cyberterminals. A hitcher whose deck is configured with `immuneToDumpShock = true` would incorrectly receive the −1 utility rating reduction, even though they're not using a cyberterminal.

**Recommendation:** Add an explicit `isCyberterminal: Boolean` flag to `Cyberdeck` (set by the `Cyberterminal()` factory). Use that flag in `effectiveRating()`. The `immuneToDumpShock` flag remains for its own purpose. This makes the two rules independently expressible.

---

### [MEDIUM] `Decker.controlSlave` bypasses `SystemTestResolver`, inconsistent with all other operations

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:901–916`

**Issue:** Every other operation method (analyzeHost, decryptAccess, downloadData, editFile, etc.) delegates dice rolling and outcome construction to `SystemTestResolver.resolve()`. `controlSlave` manually calls `diceRoller.roll()` twice and constructs a `SystemTestOutcome` inline. This bypasses the resolver's logging, the cyberterminal rating-reduction rule, and any future cross-cutting behaviour added to the resolver. The variation appears unintentional given a comment that acknowledges an `effectiveSkill` override — there's no reason that couldn't be passed through a resolver variant.

**Recommendation:** Add a `resolveWithSkillOverride(decker, operation, accessRating, hostSecurityValue, dicePool, diceRoller)` overload to `SystemTestResolver`, or thread the `effectiveSkill` parameter through an extended `resolve()` signature. Use that from `controlSlave`.

---

### [MEDIUM] Persona construction logic embedded in `performLogon` rather than a factory

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1234–1246`

**Issue:** `performLogon()` contains 12 lines of persona-attribute extraction from `cyberdeck.personaPrograms` using four `firstOrNull` lookups with default-zero fallbacks. This construction logic is not cohesive with the logon flow itself; it belongs in a `Persona.from(cyberdeck, reaction)` factory or a companion factory on `Persona`. Currently it cannot be reused or independently tested, and the null-defaults are silently applied with no warning if a persona program is missing.

**Recommendation:** Add `Persona.from(cyberdeck: Cyberdeck, deckerReaction: Int): Persona` as a factory function. Move the attribute extraction there. Consider logging a warning or throwing if a required persona program (e.g., Masking) is absent.

---

### [MEDIUM] Duplicate `parseSecurityRating` and `parseSubsystemRatings` in `GridLoader` and `HostLoader`

**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:135–153` and `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:182–200`

**Issue:** Both loaders contain identical private implementations of `parseSecurityRating(String): SecurityRating` and `parseSubsystemRatings(Any?): SubsystemRatings`. Any change to the parsing format must be applied in both places. `GridLoader` already delegates host construction to `HostLoader.buildFromMap`, so the two loaders are coupled — but the shared parsing helpers are not extracted.

**Recommendation:** Move the two parsing helpers to a `ConfigParsers` internal object (or top-level functions) in the `config` package. Both loaders import from there.

---

### [MEDIUM] `CombatResolver` holds IC-type-specific resolution logic that leaks out of the IC hierarchy

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:154–275`

**Issue:** `CombatResolver` has individual resolver methods named after concrete IC subtypes: `resolveCrippler`, `resolveRipper`, `resolveBlaster`, `resolveBlasterMpcpTest`, `resolveSparky`, `resolveSparkyMpcpTest`, `resolveTarBaby`, `resolveTarPit`, `resolveTarPitMpcpTest`, etc. Meanwhile, each IC subclass's `action()` calls back into these same methods. The IC-specific behavior is split across two files: the dispatch (which IC calls which resolver) is in `IC.kt`, and the mechanics are in `CombatResolver.kt`. Adding a new IC type requires changes in both files. `CombatResolver` also imports all IC subtypes by name (`import com.shadowrun.matrix.ic.Blaster`, `Crippler`, `GrayIC`, etc.), creating a wide fan-in dependency.

**Recommendation:** Keep `CombatResolver` for generic mechanics (initiative, attack resolution, damage staging, suppression). Move IC-type-specific logic into the IC classes themselves or into strategy objects co-located with the IC types. `CombatResolver` should not need to import concrete IC subclasses.

---

### [LOW] `DeckerExtensions.asDefenderParticipant` uses force-unwrap and unchecked cast

**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:7–12`

**Issue:** The extension function calls `persona!!` (NPE if not jacked in) and `currentLocation as MatrixLocation.OnHost` (ClassCastException if on a grid node). Both are unchecked assumptions with no diagnostic message. This function is called from every IC `action()` method in `IC.kt`, so a decker that is somehow present in the game but not on a host would crash the combat loop.

**Recommendation:** Replace with `requireNotNull(persona) { "asDefenderParticipant called on decker ${name} without a persona" }` and `require(currentLocation is MatrixLocation.OnHost) { ... }` or return an `Option`/nullable and handle it at call sites.

---

### [LOW] `Decker.action()` is a stub — misuse of the `ActiveIcon` interface

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:70`

**Issue:** `ActiveIcon.action()` is the game-loop contract: return an `ActionResult` describing what the icon did. `Decker.action()` unconditionally returns `ActionResult.DeckerAction`, a sentinel with no content. `Game.runOutOfCombatTurn()` calls it in a loop and discards the result. The interface is implemented purely to allow `Decker` and `IC` to share a collection type in `ActiveIconState`, but the contract is meaningless for deckers.

**Recommendation:** See the bidirectional dependency finding above. Once `Decker` no longer implements `ActiveIcon`, `ActiveIconState` can become a sealed type with `DeckerState` and `IcState` variants, making the distinction explicit rather than relying on a do-nothing default.

---

### [LOW] `HostLoader.buildIc` requires manual update for every new IC type

**File:** `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:154–179`

**Issue:** `buildIc()` is a hardcoded `when` expression mapping lowercase strings to constructor calls. Every new IC type requires a matching branch. The method also imports all IC subtypes individually. For a fixed game ruleset this is low risk, but it tightly couples the loader to the IC type hierarchy.

**Recommendation:** Register IC factories in a companion-object map on a shared `IcRegistry` (e.g., `"killer" to { rating, node -> Killer(rating, node) }`). `HostLoader` then does a single map lookup. Adding a new IC type only requires registering it, not modifying the loader.

---

## No Issues Found In

- **`Game.kt`** — Clean, minimal orchestrator. `runCombatTurn()` and the initiative-list builder are focused and easy to follow.
- **`ActionResult`**, **`ActiveIcon`**, **`ActiveIconState`** — Well-defined, appropriately small.
- **`AlertTransitions.kt`** — Pure function, clear PRD references, no cross-cutting concerns.
- **`common/Enums.kt`**, **`common/SharedTypes.kt`** — Lean, no behaviour leakage, appropriate home for cross-cutting domain types.
- **`Cyberdeck.kt`** — Clear value object with well-enforced invariants in `init`. The `Cyberterminal()` factory function is a clean builder pattern.
- **`Program.kt`**, **`PersonaProgram.kt`** — Appropriately thin hierarchy.
- **`Matrix.kt`**, **`Node.kt`**, **`SAN.kt`**, **`DataFile.kt`**, **`RemoteDevice.kt`** — All are pure data types with no cross-cutting imports.
- **`SecuritySheaf.kt`**, **`TriggerStep`** — Data-only, except for the IC embedding issue covered above.
- **`GridInitializer.kt`** — Appropriately thin facade over `GridLoader`.
- **`DeckCatalogLoader.kt`** — Focused, single responsibility.
- **`PointerChain.kt`**, **`DownloadHandle.kt`** — Clean value types.
- **`Accessory.kt`** — Correctly minimal.
