# Error Handling Review — game_logic

## Summary

The game_logic component is structurally solid — immutable data classes, sealed result types, and explicit `require`/`check` guards in many places show deliberate design. However, several force-unwrap (`!!`) calls in hot combat paths are unguarded, two IC types silently skip their intended effects (Sparky never deals body damage; Probe tally points are never applied), and a handful of config-loader casts will throw uncontextualised exceptions on bad YAML. The `GameContext.updateDecker()` silent-return path means state can diverge with nothing more than a `System.err` print, and callers have no way to detect it. The `jackOut()` pin-check design accepts the pin state as a caller-supplied parameter rather than reading it from the decker, which allows the guard to be bypassed silently.

---

## Findings

### [CRITICAL] `asDefenderParticipant()` double unsafe dereference
**File:** `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:8–11`
**Issue:** Both `persona!!` and the unchecked `as MatrixLocation.OnHost` cast are called unconditionally. Every IC attack in `IC.kt` routes through this extension before the IC resolver runs. If a decker's persona has been cleared (post-logoff state lingering in `context.deckers`) or the decker is on a grid node rather than a host, the call throws `NullPointerException` or `ClassCastException` respectively, crashing the game loop with no recovery path.
**Recommendation:** Add explicit precondition guards: `checkNotNull(persona)` and `check(currentLocation is MatrixLocation.OnHost)`, or return a `Result`/nullable and let callers handle the failure gracefully.

---

### [CRITICAL] Unguarded `persona!!` throughout `CombatResolver`
**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:39,89,157,185,207,260,291,337,379,395,411`
**Issue:** Every combat resolver function that touches the decker's persona accesses it via `!!` without a prior null check. `rollDeckerInitiative` (line 39) is called directly from `Game.buildInitiativeList()` for every decker in `context.deckers`; if any decker lacks a persona, the whole turn crashes. The same pattern repeats in `applyIcDamage`, `resolveCrippler`, `resolveRipper`, `resolveTarBaby`, `resolveTarPit`, `resolveLethalBlackIc`, `resolveNonLethalBlackIc`, `resolveBlackHammer`, `resolveKilljoy`, and `resolveTrackLock`.
**Recommendation:** Either enforce at the `GameContext` level that `deckers`/`activeIc` never contains a decker without a persona once combat begins, or add `require(decker.persona != null)` at the top of each affected function and propagate the error as a named exception or sealed result.

---

### [HIGH] Sparky never applies physical body damage
**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:146–157`
**Issue:** `Sparky.action()` calls `resolveSparkyMpcpTest` and commits the MPCP reduction, but the second return value (`successes`) is discarded with `_` and `resolveSparkyBodyDamage` (defined in `CombatResolver.kt:241`) is never called. The decker takes MPCP damage from a Sparky hit but zero physical body damage — silently wrong game state, no exception thrown.
**Recommendation:** Capture the successes value and call `resolveSparkyBodyDamage` on the hit path, then pass the resulting decker to `context.updateDecker`.

---

### [HIGH] Probe tally points computed but never applied
**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:86–88`
**Issue:** `CombatResolver.resolveProbe` returns the number of tally points the Probe generated, but `Probe.action()` only embeds that number in a log string and returns. The security tally on the host is never incremented. This is a silent logic failure — no exception, but the game state is wrong every time Probe fires.
**Recommendation:** Use `context.checkTriggers` / `context.updateHost` (or an analogous path through `context.applyDeckerOperationResult`) to commit the tally increment before returning.

---

### [HIGH] Division by zero when `ioSpeedMpPerTurn` is zero
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:360` and `:841`
**Issue:** Both `loadUtility` and `downloadData` divide `utility.mpSize` / `file.sizeMp` by `cyberdeck.ioSpeedMpPerTurn` with no guard. `Cyberdeck` has no `init` validation that `ioSpeedMpPerTurn > 0`, so a misconfigured deck (or one loaded from a YAML file with a missing `io_speed`) produces an `ArithmeticException` at the exact moment the player attempts to load or download — not at construction time.
**Recommendation:** Add `require(ioSpeedMpPerTurn > 0)` to `Cyberdeck.init`, and/or guard both division sites explicitly.

---

### [HIGH] `GameContext.updateDecker()` silent failure leaves state diverged
**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:29–36`
**Issue:** When the decker is not found in the context list, the method logs to `System.err` and silently returns. Every IC action and every operation that calls `context.updateDecker` after modifying decker state may silently drop its update. No caller checks the return value (the method returns `Unit`), and there is no exception to propagate the error upward.
**Recommendation:** Either throw an `IllegalStateException` (consistent with other precondition violations in this codebase) or change the return type to a `Boolean` / sealed result and require callers to handle the failure case.

---

### [HIGH] `jackOut()` pin check reads caller-supplied parameter, not decker state
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:329–337`
**Issue:** `jackOut(pinnedByBlackIc: Boolean = false)` accepts the pin flag from the caller rather than reading `this.isPinnedByBlackIc`. Any call site that omits the parameter (the default is `false`) silently bypasses the Black IC pin guard, allowing a pinned decker to jack out without triggering the final attack sequence. The correct pin state is already on the decker.
**Recommendation:** Replace the parameter with `check(!isPinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }` and remove the parameter entirely.

---

### [MEDIUM] `applyDeckerOperationResult()` silently discards tally for grid-level operations
**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:61–71`
**Issue:** `newTally` is extracted from `new.currentLocation as? MatrixLocation.OnHost`. If the decker is on an RTG, LTG, or PLTG, the cast returns `null` and `newTally` falls back to `oldTally`. The condition `newTally > oldTally` is then always false, so `updateHost` and `checkTriggers` are never called, and any host-successes from a grid-level operation are discarded without warning.
**Recommendation:** Extend the tally-extraction logic to cover all `MatrixLocation` subtypes, mirroring the `withUpdatedTally` logic already present in `Decker.kt:1207–1213`.

---

### [MEDIUM] Interrogation operations use empty-string default query — matches every target
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:750,783,814`
**Issue:** All three interrogation methods (`locateFile`, `locateSlave`, `locateAccessNode`) fall back to `InterrogationState(operation, "")` when no prior state exists. An empty string passes `name.contains("", ignoreCase = true)` for every file or device on the host, so the first call with accumulated successes ≥ threshold always returns the first item in the list, regardless of what the decker was supposedly searching for.
**Recommendation:** Require a non-blank query parameter in each method, or add a `require(state.query.isNotBlank())` guard before the locate logic executes.

---

### [MEDIUM] `resolvePointerChain()` force-unwraps `file.pointerToHost`
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1032`
**Issue:** After confirming `file.isPointer`, the code does `file.pointerToHost!!`. If a `DataFile` is flagged as a pointer but the `pointerToHost` field was never populated (e.g., loaded from YAML without a `pointer_to_host` key), this crashes with `NullPointerException`.
**Recommendation:** Replace with `requireNotNull(file.pointerToHost) { "DataFile '${file.name}' is flagged as a pointer but has no pointerToHost" }` for a context-bearing error.

---

### [MEDIUM] Config loaders: unchecked YAML casts lose context on failure
**File:** `src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogLoader.kt:12`  
**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:22,31,42,145`  
**File:** `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:43,55,155`
**Issue:** YAML maps are cast directly with `as List<...>` / `as Map<...>` / `as Int` / `as String` throughout all three loaders. A missing required key returns `null`, which then throws `NullPointerException` at the cast site with no indication of which file, which field, or which entry caused the problem. `DeckCatalogLoader.load()` line 12 is the most exposed: `data["decks"] as List<Map<String, Any>>` will NPE if the top-level `decks` key is absent.
**Recommendation:** Replace hard casts with `?: error("Missing field '...' in ...")` or wrap individual parse functions in try/catch that rethrow with the entry name and source file path as context.

---

### [MEDIUM] `GridLoader` silently drops unresolved RTG references
**File:** `src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt:31`
**Issue:** `ids.mapNotNull { rtgById[it] }` silently discards any RTG ID listed in `connected_rtgs` that does not appear in the same YAML file. A misconfigured topology (e.g., a cross-file reference or a typo) produces a network with missing connections and no error or warning.
**Recommendation:** Use `map { rtgById[it] ?: error("connected_rtg '$it' not found in grid") }` to surface the misconfiguration at load time.

---

### [LOW] `AlertTransitions.applyAlertTransition()` — `NO_ALERT` is a silent no-op
**File:** `src/main/kotlin/com/shadowrun/matrix/network/AlertTransitions.kt:29`
**Issue:** The `NO_ALERT` branch returns the host unchanged with only a comment warning. If a caller accidentally passes `NO_ALERT` (e.g., from a YAML `alert_transition: no_alert`), the call silently succeeds and the intended transition never happens.
**Recommendation:** Add a `require(newAlertStatus != AlertStatus.NO_ALERT) { "applyAlertTransition cannot transition to NO_ALERT" }` or at minimum a `logger.warn`.

---

### [LOW] Pointer chain silently loops when `connectedHosts` is empty
**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1035`
**Issue:** `current.connectedHosts.firstOrNull() ?: current` falls back to `current` itself, so if a host has no connected hosts, the chain repeats the same host node for the remainder of the chain length. The final file lookup on `links.last()` may return an unrelated file. No warning is emitted.
**Recommendation:** Log a warning when the fallback fires, or `require` that pointer files have a valid chain before calling `resolvePointerChain`.

---

### [LOW] `HostLoader.buildNodes()` silently drops duplicate subsystem nodes
**File:** `src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt:55`
**Issue:** `nodes.groupBy { it.subsystemType }.mapValues { (_, v) -> v.first() }` silently discards all but the first node when a YAML file lists multiple nodes of the same subsystem type. The dropped nodes are never used for IC guarded-node matching without any indication.
**Recommendation:** Add `require(nodes.size == nodes.map { it.subsystemType }.toSet().size) { "Duplicate subsystem node types found" }` before the groupBy.

---

## No Issues Found In

- **`DiceRoller.kt`** — `require(numberOfDice > 0)` and `require(targetNumber >= 2)` are consistently enforced; all call sites that build TNs use `maxOf(2, ...)` before passing to `roll()`.
- **`Cyberdeck.kt` init block** — Validates persona program ratings, total rating budget, utility ratings, and memory capacity at construction time; these are comprehensive and will surface misconfiguration early.
- **`Host.kt` init block** — Requires one node per subsystem type; the `require` fires at construction, not lazily.
- **`Game.runCombatTurn()`** — The `maxByOrNull { ... } ?: break` guard correctly handles an empty filtered list without crashing.
- **`GameContext.checkTriggers()`** — Pre-computes `newlyTriggered` before mutating `activeIc`, so there is no concurrent-modification risk on that loop.
- **`Decker.requireJackpoint()` / `requireJackedIn()` / `requireNotJackedIn()`** — Consistent use of named helper guards with clear error messages throughout logon/logoff paths.
- **`CombatResolver.suppressIc()` / `unsuppressIc()`** — Both have explicit precondition checks or return-early patterns.
- **`SystemTestResolver.resolve()` / `resolveInterrogation()`** — No `!!` usage; all fields accessed through well-typed parameters.
- **`AlertTransitions` upgrade guard** — `if (transition.ordinal > host.alertStatus.ordinal)` correctly prevents alert-level downgrade.
