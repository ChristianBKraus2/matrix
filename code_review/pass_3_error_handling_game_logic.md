# Error Handling Review — game_logic

## Summary

The game logic layer is mostly disciplined: `require`/`check`/`requireNotNull` with descriptive messages are used consistently for preconditions, combat resolution is pure, and structured logging covers the majority of operation paths. However, several genuine gaps remain. The turn-loop exception handlers in `Game.kt` silently swallow all exceptions, printing only `e.message` to stderr with no stack trace and no structured logger integration, meaning a bare `NullPointerException` logs the literal string "null". `logonToRtg` in `DeckerNavigationExtensions.kt` overwrites the RTG security tally instead of accumulating it, silently discarding prior tally history unlike every other navigation helper. `Sparky.action()` in `IC.kt` never calls `applyIcDamage`, so the persona condition monitor is never updated when Sparky connects — only MPCP and body damage happen. `TarPit.action()` omits the mandatory `resolveTarPitMpcpTest` step entirely. `loadUtility` performs integer division by `ioSpeedMpPerTurn` without a zero guard, producing a `pendingUploads` entry with `turnsRemaining = Int.MAX_VALUE` that never resolves. A force-unwrap `!!` in `resolvePointerChain` can crash with no diagnostic message if `pointerToHost` is null despite `isPointer == true`.

---

## Findings

### [HIGH] Exception swallowing in turn loops hides broken state and logs useless "null" messages

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:15-17`
**Issue:** `runOutOfCombatTurn` and `runCombatTurn` both catch `Exception` and emit only `System.err.println("... ${e.message}")`. `e.message` is nullable; a plain `NullPointerException` thrown by the JVM logs the literal text `"null"` with no type, no stack trace, and no context beyond the decker name. Game state modified before the exception was thrown is left permanently inconsistent — if an IC partially updated context before crashing, those mutations persist while the action is treated as never having occurred. The combat loop also continues unconditionally: `states[idx] = state.copy(currentInitiative = state.currentInitiative - 10)` on line 27 runs regardless of whether the action succeeded or threw.
**Recommendation:** Replace `System.err.println` with `logger.error(e) { "..." }` using a `KotlinLogging` logger declared in the `Game` companion object. Rethrow `Error` and `CancellationException` unconditionally. Consider whether a failed action should skip the initiative decrement or flag the icon as incapacitated for the round.

---

### [HIGH] logonToRtg replaces security tally instead of accumulating it

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:103-105`
**Issue:** The `buildLocation` lambda in `logonToRtg` builds `MatrixLocation.OnRTG(rtg.copy(securityTally = hostTallyDelta))`, setting the tally to just the delta from this one test rather than adding it to the existing tally. Every other navigation helper accumulates correctly: `jackInToLtg` line 50 does `ltg.securityTally + updatedTally`, `logonToLtg` line 131 does `ltg.securityTally + hostTallyDelta`, `logonToPltg` line 159 does `inheritedTally + hostTallyDelta`, and `logonToHost` line 191 does `host.securityTally + hostTallyDelta`. Any prior RTG tally is silently discarded on every successful RTG logon.
**Recommendation:** Change the lambda to `MatrixLocation.OnRTG(rtg.copy(securityTally = rtg.securityTally + hostTallyDelta))` to match the pattern used by all other navigation functions.

---

### [HIGH] Sparky.action() never applies persona condition monitor damage

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:159-167`
**Issue:** `Sparky.action()` calls `CombatResolver.resolveSparky` to get an `AttackResult.Hit`, then immediately calls `resolveSparkyMpcpTest` and `resolveSparkyBodyDamage` — but never calls `CombatResolver.applyIcDamage`. As a result, the hit is never recorded on the persona's condition monitor. The decker's MPCP and physical body take damage but the icon condition monitor stays unchanged, defeating the primary persona-damage mechanic. Compare with `Killer.action()` and `Blaster.action()`, which both call `applyIcDamage` before the MPCP test.
**Recommendation:** After the `resolveSparky` call, call `CombatResolver.applyIcDamage(target, result, this, diceRoller)` and thread the returned `dmg.updatedDecker` through `resolveSparkyMpcpTest` and `resolveSparkyBodyDamage` before passing the final decker to `context.updateDecker`.

---

### [HIGH] TarPit.action() omits the mandatory MPCP corruption step

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:173-181`
**Issue:** `CombatResolver.resolveTarPitMpcpTest` exists specifically to corrupt the MPCP when TarPit wins, but `TarPit.action()` never calls it. When `result.bothCrashed == true`, the utility is stripped from the decker's active deck but the MPCP rating is never reduced. The mechanic is silently omitted on every successful TarPit activation.
**Recommendation:** After `context.updateDecker`, when `result.bothCrashed == true`, call `CombatResolver.resolveTarPitMpcpTest(result.updatedDecker, this, utility, diceRoller)` and pass its result back to `context.updateDecker`.

---

### [MEDIUM] loadUtility performs division by ioSpeedMpPerTurn with no zero guard

**File:** `src/main/kotlin/com/shadowrun/matrix\decker\DeckerMemoryExtensions.kt:23`
**Issue:** `Math.ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn).toInt()` — if `ioSpeedMpPerTurn` is 0, Kotlin `Double` division yields `Double.POSITIVE_INFINITY`; `ceil(INFINITY).toInt()` evaluates to `Int.MAX_VALUE` (2,147,483,647). The utility is enqueued in `pendingUploads` with `turnsRemaining = 2_147_483_647` and never completes. No exception is thrown, no warning is logged, and the caller receives a `LoadUtilityResult.Success` with a permanently-stalled upload. By contrast, `downloadData` in `DeckerOperationsExtensions.kt:248` explicitly checks `if (ioSpeed <= 0)` before dividing and returns `Failure`.
**Recommendation:** Add the same guard: `if (cyberdeck.ioSpeedMpPerTurn <= 0) { logger.warn { ... }; return LoadUtilityResult.InsufficientMemory(...) }` before the `Math.ceil` call.

---

### [MEDIUM] resolvePointerChain force-unwraps pointerToHost with no diagnostic message

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:401`
**Issue:** `var current = file.pointerToHost!!` — the `require(file.isPointer)` on line 398 guards against calling this on a non-pointer file, but does not assert that `pointerToHost` is non-null. If a `DataFile` is constructed with `isPointer = true` and `pointerToHost = null` (the field is nullable and the data class allows it), the `!!` operator throws `NullPointerException` with no message, no field name, and no file name — harder to diagnose than the `requireNotNull` pattern used elsewhere in the codebase.
**Recommendation:** Replace `file.pointerToHost!!` with `requireNotNull(file.pointerToHost) { "resolvePointerChain: file '${file.name}' has isPointer=true but pointerToHost is null" }`.

---

### [MEDIUM] TarBaby and TarPit never remove themselves from activeIc when both parties crash

**File:** `src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:107-110,178-180`
**Issue:** `TarBabyResult.bothCrashed == true` means the IC itself is destroyed along with the target utility. `TarBaby.action()` and `TarPit.action()` call `context.updateDecker(target, result.updatedDecker)` to strip the utility but never call `context.removeIc(this)`. The destroyed IC remains in `context.activeIc`, continues to appear in `buildInitiativeList()`, and fires again every turn indefinitely.
**Recommendation:** After `context.updateDecker`, add `if (result.bothCrashed) context.removeIc(this)` in both `TarBaby.action()` and `TarPit.action()`.

---

### [MEDIUM] buildInitiativeList is called outside the per-action try-catch in runCombatTurn

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:21`
**Issue:** `buildInitiativeList()` on line 21 is called before the `while` loop and before the try-catch that protects individual icon actions (lines 25-27). `buildInitiativeList` calls `CombatResolver.rollDeckerInitiative`, which contains `requireNotNull(decker.persona) { ... }`. If any decker has a null persona when `runCombatTurn` is invoked, an `IllegalArgumentException` propagates uncaught out of `runCombatTurn` — the entire turn is aborted and the error is not caught by the per-action handler.
**Recommendation:** Add a pre-flight check before `buildInitiativeList()` that asserts all deckers have a non-null persona, or wrap the call itself in a `try-catch` that logs the offender and returns early with a clear error message.

---

### [LOW] System.err.println bypasses the structured logger

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:16,26`
**Issue:** All other files in the codebase use `KotlinLogging.logger {}`. `System.err.println` bypasses log-level filters, formatters, appenders, and correlation IDs. There is no `logger` declared in `Game` at all.
**Recommendation:** Add `private val logger = KotlinLogging.logger {}` to the `Game` companion object and replace both `System.err.println` calls with `logger.error(e) { "..." }`.

---

### [LOW] addToSecurityTally has no guard against negative points

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:85`
**Issue:** `addToSecurityTally(points: Int)` accepts negative values. A call with `points < 0` quietly decrements the tally; the `checkTriggers(old, new)` call that follows uses an empty range `(old+1)..new` and silently does nothing. No warning is emitted, so accidental negative tally mutations are invisible.
**Recommendation:** Add `require(points >= 0) { "addToSecurityTally: points must be non-negative, got $points" }` or at minimum `if (points <= 0) { logger.warn { ... }; return }` to make the precondition explicit.

---

### [LOW] persona!! after requireJackedIn() which does not assert persona != null

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:431`
**Issue:** `requireJackedIn()` (Decker.kt line 199) only checks `currentLocation != null`. `locateDecker` calls `requireJackedIn()` and then accesses `persona!!.sensor` on line 431. A decker with a non-null location but null persona throws a bare `NullPointerException` here instead of the descriptive message that `requireNotNull` would provide.
**Recommendation:** Replace `persona!!.sensor` with `requireNotNull(persona) { "locateDecker: decker '${name}' has no active persona" }.sensor`, consistent with the pattern used throughout `CombatResolver`.

---

## No Issues Found In

- `GameContext.kt` — `updateDecker` (`check`), `checkTriggers`, `updateHost`, and `applyDeckerOperationResult` all guard invariants and propagate errors to callers; tally accumulation is correct.
- `DeckerOperationsExtensions.kt` (other than `resolvePointerChain`) — `analyze*`, `decrypt*`, `download*`, `upload*`, comcall, and `editFile` operations all correctly propagate `requireJackedIn()`, guard `ioSpeedMpPerTurn`, and log outcomes at appropriate levels.
- `DeckerNavigationExtensions.kt` (other than `logonToRtg`) — All other navigation helpers accumulate tallies correctly and log success/failure paths.
- `DeckerMemoryExtensions.kt` (`unloadUtility`, `swapUtility`, `advanceCombatTurn`) — Utility lifecycle management is correct; depleted utilities are logged and auto-evicted.
- `CombatResolver.kt` — All public functions guard `decker.persona` with `requireNotNull` using descriptive messages; `stage`, damage application, and tally arithmetic are correct.
- `SystemTestResolver.kt` — Resolution, interrogation accumulation, and null-operation bonus are straightforward with no silent failure paths.
- `Cyberdeck.kt` — `init` block enforces all rating and memory constraints eagerly via `require`.
- `Persona.kt` — Simple data carrier; `attribute`/`withAttribute` are exhaustive over the enum.
- `IC.kt` — `Crippler`, `Killer`, `Probe`, `Blaster`, `Ripper`, `LethalBlackIC`, `NonLethalBlackIC` all correctly chain multi-step resolution and update context. `Scramble.action()` returning `NoTarget` is intentional (reactive trigger path is handled by operation resolvers, not the action loop).
- `Host.kt` — `init` block validates that all `SubsystemType` entries have a corresponding node.
- `Grid.kt`, `Node.kt`, `SecuritySheaf.kt` — Data types; no logic surface.
- `DiceRoller.kt` — `require` guards on `numberOfDice > 0` and `targetNumber >= 2` are in place.
- `Enums.kt`, `SharedTypes.kt`, `MatrixObject.kt`, `AvailableAction.kt`, `OperationResult.kt`, `SystemOperation.kt`, `ActionResult.kt`, `ActiveIconState.kt`, `ActiveIcon.kt` — No logic; no error-handling concerns.
