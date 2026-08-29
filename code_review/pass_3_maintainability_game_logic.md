# Maintainability Review — game_logic

## Summary

The game logic layer is generally well-structured: sealed class hierarchies are clean, PRD references are consistently cited in KDoc, and the extension-file pattern keeps `Decker.kt` from becoming a god class. The most significant maintainability concerns are concentrated in `CombatResolver.kt`, where several pairs of IC-resolution functions have near-identical bodies that will silently diverge if rules change. Secondary concerns include a handful of misleading names (`resetDeckers`, `maintainMonitoredOperation`, `hostTallyDelta`) and two dead-code items — an unused logger and an unread `securityDeckerCount` field — that suggest incomplete features. Cyclomatic complexity is generally low; no single function is unreadably complex.

---

## Findings

### [HIGH] `resolveBlasterMpcpTest` and `resolveRipperMpcpTest` are byte-for-byte identical

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:195`

**Issue:** Both functions share the same five-line body — compute `tn = hardening + mcpRating`, roll `ic.rating` dice, halve successes, cap at 0. The only difference is the IC parameter type (`Blaster` vs `Ripper`). Any future rule adjustment to one will be missed in the other.

**Recommendation:** Extract a private helper `fun reduceMcpRating(decker: Decker, icRating: Int, diceRoller: DiceRoller): Decker` and delegate from both public functions.

**[DEFERRED]** — `reduceMcpRating` helper not extracted; out of scope for this session.

---

### [HIGH] MPCP-on-kill block duplicated between `resolveLethalBlackIc` and `resolveNonLethalBlackIc`

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:309`

**Issue:** The seven-line block that fires when `dumpShockTriggered` — computing `mpcpTn`, rolling `ic.rating * 2` dice, halving successes, capping at 0 — is copy-pasted identically in `resolveLethalBlackIc` (lines 309–320) and `resolveNonLethalBlackIc` (lines 357–368). A rule change to the "final Blaster attack" must be applied in two places.

**Recommendation:** Extract a private helper `fun applyFinalMpcpBlast(decker: Decker, ic: BlackIC, diceRoller: DiceRoller): Pair<Decker, Int>` and call it from both functions.

**[DEFERRED]** — `applyFinalMpcpBlast` helper not extracted; out of scope for this session.

---

### [HIGH] `resolveTarBaby` and `resolveTarPit` have near-identical bodies

**File:** `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt:176`

**Issue:** Both functions roll opposing dice pools (`ic.rating` vs `utility.currentRating`), check `icSuccesses >= utilitySuccesses`, remove the utility on IC-wins, and do a sensor check on IC-loss. `resolveTarPit` adds one extra MPCP-corruption test, but the shared five-line core is duplicated verbatim. Both return `TarBabyResult`.

**Recommendation:** Extract the shared contest-and-sensor logic into a private helper and add the extra TarPit MPCP step only in `resolveTarPit`.

**[DEFERRED]** — Shared TarBaby/TarPit logic not extracted; out of scope for this session.

---

### [MEDIUM] `resetDeckers` replaces all deckers with a single one — name is misleading

**File:** `src/main/kotlin/com/shadowrun/matrix\game/GameContext.kt:33`

**Issue:** `fun resetDeckers(decker: Decker)` clears the entire decker list and adds a single replacement. The name suggests a multi-decker reset but the signature takes one decker. Callers reading only the call site will not expect the side-effect of removing all other deckers.

**Recommendation:** Rename to `replaceAllDeckersWith(decker: Decker)` or `setActiveDecker(decker: Decker)` to make the destructive intent explicit.

**[DEFERRED]** — `resetDeckers` not renamed; out of scope for this session.

---

### [MEDIUM] `logonToRtg` sets security tally directly instead of accumulating

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:104`

**Issue:** The `buildLocation` lambda in `logonToRtg` does `rtg.copy(securityTally = hostTallyDelta)`, which replaces the RTG's existing tally with the delta. Every other logon function adds to the existing tally (e.g., `logonToLtg` uses `ltg.securityTally + hostTallyDelta`). The parameter name `hostTallyDelta` implies an increment, but `logonToRtg` treats it as an absolute value. If an RTG already carries a non-zero tally — possible in multi-session scenarios — it will be silently discarded.

**Recommendation:** Change line 104 to `MatrixLocation.OnRTG(rtg.copy(securityTally = rtg.securityTally + hostTallyDelta))` to match the pattern of all other logon functions.

**[RESOLVED]** — Fixed in `DeckerNavigationExtensions.kt`: `logonToRtg` now accumulates `rtg.securityTally + hostTallyDelta`.

---

### [MEDIUM] `maintainMonitoredOperation` is a no-op

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:334`

**Issue:** The function returns `handle` unchanged whether `handle.active` is true or false; in the inactive branch it only logs a warning. The PRD reference is SO-13/SO-14, which should require a maintenance dice roll each turn. As written, the method provides no enforcement and callers get back the same handle regardless.

**Recommendation:** Either implement the maintenance roll (success keeps the handle active, failure deactivates it) or remove the function until the rule is ready to implement, to avoid giving callers false confidence.

**[DEFERRED]** — `maintainMonitoredOperation` stub not removed or implemented; out of scope for this session.

---

### [MEDIUM] Unused logger in `Decker` companion object

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:214`

**Issue:** `private val logger = KotlinLogging.logger {}` is declared inside the companion object but no `logger.*` call appears anywhere in `Decker.kt`. All logging for Decker operations is done in the extension files, each of which declares its own file-level logger.

**Recommendation:** Remove the companion object (or just the logger declaration if the companion is kept for other reasons) to eliminate dead code.

**[DEFERRED]** — Unused logger in `Decker` not removed; out of scope for this session.

---

### [MEDIUM] `securityDeckerCount` in `TriggerStep` is never read

**File:** `src/main/kotlin/com/shadowrun/matrix/network/SecuritySheaf.kt:13`

**Issue:** The field is documented "AL-02: number of security decker NPCs to spawn on Active Alert," but `GameContext.checkTriggers()` — the only consumer of `TriggerStep` — only reads `activatedIc` and `alertTransition`. The NPC decker spawning logic is absent, so this field is dead configuration that silently does nothing.

**Recommendation:** Either implement the NPC decker spawning in `checkTriggers`, or mark the field with a TODO comment and suppress the IDE warning, so future maintainers know it is intentionally incomplete.

**[DEFERRED]** — `securityDeckerCount` not documented as a planned future feature; out of scope for this session.

---

### [MEDIUM] Logon-result logging block repeated six times

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:52`

**Issue:** The `.also { result -> when (result) { is LogonResult.Success -> logger.info { … } is LogonResult.Failure -> logger.warn { … } } }` pattern appears on the `performLogon` return value in all six navigation functions (`jackInToLtg`, `jackInToHost`, `logonToRtg`, `logonToLtg`, `logonToPltg`, `logonToHost`). The only variation is the verb in the log message string.

**Recommendation:** Extract a private extension `fun LogonResult.logOutcome(name: String, operation: String, logger: KLogger)` that accepts the decker name and operation label, then call it from each site with a single line.

**[DEFERRED]** — Logon-result logging not extracted to a shared extension; out of scope for this session.

---

### [MEDIUM] `controlSlave` builds `SystemTestOutcome` manually, bypassing the standard resolver

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:303`

**Issue:** Unlike `editSlave` and `monitorSlave` (which call `SystemTestResolver.resolve()`), `controlSlave` constructs `SystemTestOutcome` directly from raw dice results. This bypasses the cyberterminal utility-rating reduction (`effectiveRating()`) applied by the standard resolver, and bypasses the structured logging in the resolver. The three slave operations therefore diverge in behaviour for cyberterminal users.

**Recommendation:** Refactor `SystemTestResolver.resolve()` to accept an optional explicit skill pool override (for `effectiveSkill`), or add a dedicated `resolveControlSlave` overload that handles the Spoof modifier correctly and calls the standard logging path.

**[DEFERRED]** — `controlSlave` resolver bypass not refactored; out of scope for this session.

---

### [LOW] `fakeOutcome` is an informal name used in two places

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:453`

**Issue:** Two functions — `makeComcall` (passcode bypass, line 453) and `relocateIcon` (non-standard contest, line 499) — use the local name `fakeOutcome`. The name signals "this is not real" without explaining why. Readers must inspect both call sites to understand the intent.

**Recommendation:** Rename to `syntheticOutcome` (or `bypassOutcome` / `contestOutcome`) with a one-line comment explaining why a standard `SystemTestOutcome` is constructed manually.

**[DEFERRED]** — `fakeOutcome` not renamed; out of scope for this session.

---

### [LOW] `persona!!` used after `check(persona != null)` throughout extension files

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:53`

**Issue:** Many extension functions call `check(persona != null)` (or `require(persona != null)`) at the top, then dereference `persona!!` multiple times in the same function body (e.g., lines 53–54, 63–64). In Kotlin, extension functions cannot smart-cast a `val` property of the receiver, so the `!!` is technically required, but the resulting code looks unsafe to readers who expect the check to suffice.

**Recommendation:** Adopt the pattern already used in `invokeMediac` and `asDefenderParticipant`: capture `val p = requireNotNull(persona) { "…" }` once, then use `p` throughout.

**[DEFERRED]** — `persona!!` not replaced with `requireNotNull` capture pattern; out of scope for this session.

---

## No Issues Found In

- `src/main/kotlin/com/shadowrun/matrix/game/ActiveIcon.kt` — minimal, correct interface
- `src/main/kotlin/com/shadowrun/matrix/game/ActionResult.kt` — clean sealed hierarchy
- `src/main/kotlin/com/shadowrun/matrix/game/ActiveIconState.kt` — clean data holder
- `src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt` — single, focused extension
- `src/main/kotlin/com/shadowrun/matrix/game/Game.kt` — clean turn-loop orchestration
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt` — well-structured enum with clear metadata
- `src/main/kotlin/com/shadowrun/matrix/operations/MatrixObject.kt` — clean sealed hierarchy
- `src/main/kotlin/com/shadowrun/matrix/operations/AvailableAction.kt` — clean sealed hierarchy
- `src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt` — clean; result types are well-named and well-documented
- `src/main/kotlin/com/shadowrun/matrix/decker/Persona.kt` — clean data class with attribute dispatch
- `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt` — thorough `init` validation; clear error messages
- `src/main/kotlin/com/shadowrun/matrix/network/Node.kt` — appropriately minimal
- `src/main/kotlin/com/shadowrun/matrix/network/Host.kt` — clean data model with sensible defaults
- `src/main/kotlin/com/shadowrun/matrix/common/Enums.kt` — clean, no duplication
- `src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt` — clean value types; `ConditionMonitor` helpers are well-factored
- `src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt` — single responsibility, correct exploding-die logic
