---
# Critical Findings — Matrix of Shadowrun Code Review

## Overview

The review covers 8 categories (correctness, security, concurrency, error handling, performance, architecture, maintainability, testing) across 4 scopes (game_logic, server, ui, complete), producing roughly 150 individual findings. The dominant risk areas are: (1) three IC types whose primary combat effects are silently discarded, meaning the core security-escalation and damage system is partly non-functional; (2) the server game loop can be permanently crashed or stuck by a single malformed client message; and (3) the server/game-loop boundary has uncoordinated synchronization primitives that create a window where a legitimate player action is silently rejected. The UI has zero automated tests.

---

## Critical & High Severity Findings

---

### BROKEN GAME MECHANICS

---

### 1. `Probe.action()` never applies security tally — alert escalation is completely broken — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:86  
**Impact:** Probe IC is the primary mechanism for raising the host's security tally, which triggers passive and active alert transitions and activates additional IC. The result from `CombatResolver.resolveProbe` (tally successes) is stored in a local variable referenced only in a log message and never written to the host. Alert escalation is therefore structurally inoperative. No test catches this because IC action tests only assert on the return type/message string.  
**Fix:** After resolving probe, call `context.updateHost(host.copy(securityTally = host.securityTally + tallyPoints))` then `context.checkTriggers(oldTally, newTally)`.

---

### 2. `Blaster.action()` resolves an attack but never applies persona CM damage — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:117  
**Impact:** On a hit, only the MPCP reduction test is performed. The persona condition-monitor damage — the primary offensive output of Blaster — is silently discarded. The decker's BOD roll is used to defend against an attack whose result is thrown away.  
**Fix:** After `resolveBlasterMpcpTest`, also call `CombatResolver.applyIcDamage(target, result, this, diceRoller)` and compose both updates into a single `context.updateDecker` call.

---

### 3. `Sparky.action()` discards both icon CM damage and physical body damage — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:147  
**Impact:** Same pattern as Blaster. The `sparkySuccesses` integer needed to call `resolveSparkyBodyDamage` is explicitly discarded with `_`. When Sparky hits a decker, only MPCP reduction happens; neither persona nor physical damage is applied. The two-phase resolution contract is not expressed in the type system.  
**Fix:** Capture both return values; call `applyIcDamage` then `resolveSparkyBodyDamage(decker, ic, sparkySuccesses, diceRoller)`. Better: collapse into a single `resolveSparkFull` helper in `CombatResolver`.

---

### 4. `jackOut()` Black IC pin guard trusts caller-supplied boolean — pin trivially bypassable — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:328  
**Impact:** Default parameter is `false`, so any call to `decker.jackOut()` without the named argument bypasses the Black IC pin check regardless of internal state. The server dispatch layer adds a second check, but that guard can be circumvented by any non-WebSocket controller or future refactor.  
**Fix:** Remove the parameter entirely. Replace with `check(!isPinnedByBlackIc) { "Cannot jack out while pinned by Black IC" }`.

---

### 5. `analyzeIcon` double-applies the Analyze utility to the target number — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:672  
**Impact:** The effective TN becomes `control - sensor - 2 × analyze_rating` instead of the correct `control - sensor - analyze_rating`. All other analyze operations pass the raw subsystem rating and let `SystemTestResolver` handle utility reduction once.  
**Fix:** Remove the manual `- analyze.currentRating` from the `tn` computation in `analyzeIcon` and pass the raw `host.subsystemRatings.control` (minus sensor if that is a valid rule bonus) as `accessRating`.

---

### 6. `resolvePointerChain` 1D6 formula produces wrong distribution — chain length 1 is impossible — Correctness / Game Logic

**Source review:** code_review/correctness_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1017  
**Impact:** `diceRoller.roll(1, 2).let { it.dice.first() % 6 + 1 }` applied to an exploding d6 means face-values that are multiples of 6 are structurally unreachable (6 explodes to 7+). Therefore `value % 6 == 0` (chain length 1) never occurs. Distribution is non-uniform.  
**Fix:** Add `DiceRoller.rollFlatD6(): Int = random.nextInt(1, 7)` (no explosion) and use that here.

---

### 7. Hard cast in `asDefenderParticipant` crashes all IC combat actions — Error Handling / Game Logic

**Source review:** code_review/error_handling_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt:11  
**Impact:** `(currentLocation as MatrixLocation.OnHost)` and `persona!!` are unchecked. Called from `Killer.action`, `Blaster.action`, `Sparky.action` for every decker target. If a decker is in any other location state, `ClassCastException` propagates through the IC action and terminates the entire combat turn for all participants.  
**Fix:** Replace with safe casts: `requireNotNull(currentLocation as? MatrixLocation.OnHost) { "..." }` and `requireNotNull(persona) { "..." }`.

---

### 8. Zero persona attributes at logon cause deferred crash in combat — Error Handling / Game Logic

**Source review:** code_review/error_handling_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:1222  
**Impact:** If persona programs are missing from the cyberdeck, attributes default to 0. Logon succeeds. The crash (`IllegalArgumentException: numberOfDice must be positive`) only appears later during the first combat roll that uses that attribute. No warning is logged at logon time.  
**Fix:** Add a warning log for each zero attribute at persona creation. Add a `require` that all four persona programs are present with rating ≥ 1, or enforce in `Cyberdeck.init`.

---

### SERVER STABILITY — CRASHES & SILENT FAILURES

---

### 9. `runCatching` in the WebSocket frame handler swallows all exceptions silently — Correctness / Server

**Source review:** code_review/correctness_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29  
**Impact:** JSON parse errors, type mismatches, null dereferences, and any runtime exception inside `receiveJoin`/`receiveAction` are silently discarded. The offending client receives no `ErrorMessage`. Protocol bugs and server-side errors are completely invisible in production.  
**Fix:** Replace bare `runCatching { }` with `.onFailure { e -> logger.error(e); session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage("bad_request: ${e.message?.take(120)}")))) }`.

---

### 10. Unchecked casts in `dispatchHostOperation` crash the game loop — Correctness / Server

**Source review:** code_review/correctness_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:168,172,177,182,187,192,199,224  
**Impact:** `action.target as MatrixObject.IcProgram` (and 7 similar casts) throw `ClassCastException` if the domain produces a mismatched target. The exception escapes `action()` with no `ResultMessage` broadcast, no `demoteAfterTurn` call, and `activeController` permanently set — the game session hangs until the decker disconnects.  
**Fix:** Replace each hard cast with a safe `as?` guard: `(action.target as? MatrixObject.IcProgram)?.ic ?: return DispatchResult(decker, false, 0, 0, "Expected IcProgram target")`. Also add a top-level `try/catch` in `action()` that calls `demoteAfterTurn` before re-throwing.

---

### 11. `QueryPrecision.valueOf()` on raw client input crashes the game-loop thread — Security / Server

**Source review:** code_review/security_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245  
**Impact:** `valueOf` throws `IllegalArgumentException` for any precision string that is not a valid enum name. This exception is thrown inside `action()` after the `runCatching` guard in `MatrixServer.kt` has already returned — it executes on the game-loop thread, propagates uncaught, and can permanently crash or stall the current game turn. Also: the TypeScript type declares `precision: 'NORMAL' | 'HIGH'` but `'HIGH'` is not a valid `QueryPrecision` constant (the enum has `VERY_VAGUE`, `VAGUE`, `NORMAL`, `SPECIFIC`, `VERY_SPECIFIC`), so the UI can trigger this crash with a type-correct value.  
**Fix:** `QueryPrecision.entries.firstOrNull { it.name == it } ?: QueryPrecision.NORMAL`. Fix the TypeScript union to `'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'`.

---

### 12. Non-disconnect `ExecutionException` re-thrown without demoting controller — Error Handling / Server

**Source review:** code_review/error_handling_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:83-90  
**Impact:** Only `DeckerDisconnectedException` is handled. Any other cause falls through to `throw e`. `demoteAfterTurn` is never called, leaving the decker permanently as `activeController`. All subsequent actions from other sessions are rejected as `not_your_turn`.  
**Fix:** Wrap lines 104–119 in a `try/finally` that unconditionally calls `registry.demoteAfterTurn(decker.name)`. Log unexpected exceptions before re-throwing.

---

### CONCURRENCY & RACE CONDITIONS

---

### 13. `promoteForTurn` and `pendingAction` assignment are not atomic — player gets "no_action_pending" — Concurrency / Complete

**Source review:** code_review/concurrency_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53-71 and SessionRegistry.kt:107-117  
**Impact:** `promoteForTurn` sends the `active_controller` control message to the client before `registry.pendingAction = future` is set on the next line. A fast client (including over loopback) can receive the promotion, send its action, and have `receiveAction` find `pendingAction == null` — returning "no_action_pending" to a player who was just told it is their turn.  
**Fix:** Assign `registry.pendingAction = future` before calling `promoteForTurn`, or fold both into a single synchronized block: `promoteForTurnWithFuture(deckerName, future)`.

---

### 14. `receiveAction` authorization and future completion under two separate locks — TOCTOU — Concurrency / Complete

**Source review:** code_review/concurrency_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:108-117  
**Impact:** The read of `pendingAction` (volatile, no lock), the `activeController` check (under `lock`), and `future.complete(cmd)` (no lock) are three separate operations. Two concurrent WebSocket coroutines for the same session can both pass authorization; one wins `complete()` silently with no error sent to the loser.  
**Fix:** Protect the full read-authorize-act sequence inside a single `synchronized(lock)` block, or use `AtomicReference` with compare-and-set.

---

### 15. Unhandled exception in `dispatch` leaves `activeController` permanently set — Concurrency / Complete

**Source review:** code_review/concurrency_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:91-119  
**Impact:** The `finally` block only nulls `pendingAction`. If `dispatch` or `context.applyDeckerOperationResult` throws, `demoteAfterTurn` is never called. `activeController` remains pointing at the former session for the remainder of the game until overwritten by the next `promoteForTurn`.  
**Fix:** Wrap the dispatch-and-apply block in its own `try/finally` that unconditionally calls `registry.demoteAfterTurn(decker.name)`.

---

### 16. `runBlocking` + `CompletableFuture.get()` blocks Ktor thread pool for up to 120 seconds — Concurrency / Server

**Source review:** code_review/concurrency_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53-117  
**Impact:** Every `runBlocking` call seizes a Ktor/Netty thread for the duration of all inner suspensions plus the full player-input timeout. If the game loop is ever dispatched onto a coroutine dispatcher, this deadlocks. Even on a plain thread, all WebSocket I/O from other deckers is starved during the turn.  
**Fix:** Make `action()` a `suspend` function. Replace `CompletableFuture` with `CompletableDeferred` and `future.get(...)` with `withTimeout { deferred.await() }`.

---

### 17. `GameContext` exposes `MutableList` fields publicly with no synchronization — Concurrency / Game Logic

**Source review:** code_review/concurrency_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:15-16  
**Impact:** `deckers` and `activeIc` are directly mutable by any caller. Any concurrent IC action or server-layer code that accesses these lists without coordination produces unsynchronized data races.  
**Fix:** Replace public `MutableList` fields with private backing fields. Expose read-only `List` views. Route all mutations through context methods protected by a `Mutex` or `@Synchronized`.

---

### 18. Lost-update on `securityTally` in `applyDeckerOperationResult` — Concurrency / Game Logic

**Source review:** code_review/concurrency_game_logic.md  
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:55-63  
**Impact:** Both old and new tally values are read from stale decker-embedded host snapshots rather than the live `context.host`. Under concurrent execution two operations can both read tally=0, compute tally=2 and tally=3, and the second write overwrites the first. Final tally is 3 instead of 5; trigger-step boundaries are silently skipped.  
**Fix:** Store tally deltas rather than absolute values. Apply the delta to the live `context.host.securityTally` inside a lock: `updateHost(host.copy(securityTally = host.securityTally + delta))`.

---

### SECURITY & INPUT VALIDATION

---

### 19. `hasValidPasscode` is client-controlled — MAKE_COMCALL bypasses System Test — Security / Server

**Source review:** code_review/security_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:222 and Messages.kt:35  
**Impact:** The client sends `"hasValidPasscode": true` and the server skips the entire System Test, returning a synthetic success outcome. Any player can forge passcode possession via the browser console. There is no server-side verification against actual held credentials.  
**Fix:** Remove `hasValidPasscode` from `ActionParams` and `ActionCommand` entirely. Determine passcode possession server-side from `decker` state set by a prior verified game event.

---

### 20. No authentication on the WebSocket endpoint — Security / Server

**Source review:** code_review/security_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:24  
**Impact:** Any TCP client that can reach port 8080 can register any unclaimed decker name and send actions on that decker's turns. After a disconnect the name is immediately re-claimable by any observer. There is no shared secret, token, or challenge-response.  
**Fix:** Add a pre-shared key (configured via environment variable) that clients must supply in the `join` message or as a WebSocket query parameter. Validate and close immediately on mismatch.

---

### 21. Unbounded `newContent` in `EDIT_FILE` causes memory exhaustion — Security / Server

**Source review:** code_review/security_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:196 and Messages.kt:32  
**Impact:** A client bypassing the browser UI can send a multi-megabyte JSON string in `newContent`. The server deserializes it fully into a `ByteArray`. Because `runCatching` swallows any resulting OOM error, there is no circuit-breaker.  
**Fix:** Add a size cap in `receiveAction` before the command reaches game logic: reject any `newContent` longer than a game-defined maximum (e.g. 65 536 bytes). Also configure a `maxFrameSize` on the Ktor `WebSockets` plugin.

---

### ARCHITECTURE — LAYER INVERSION

---

### 22. Server layer implements a domain interface and acts as a game participant — Architecture / Complete

**Source review:** code_review/architecture_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:42 and Game.kt:15  
**Impact:** `WebSocketDeckerController` implements `ActiveIcon`, which the game loop calls directly. The domain model therefore depends on a transport object. The `action()` method that satisfies the interface simultaneously does turn promotion, state serialization, blocking I/O wait, action dispatch, and context mutation. Testing any game-turn logic requires instantiating server infrastructure. This is the root cause of findings 10, 13–16, and others.  
**Fix:** Introduce a pure-domain `DeckerController` interface (`chooseTurn(context, availableActions): AvailableAction`). `WebSocketDeckerController` implements this thin port. All game-state mutations stay in the domain; the server layer does transport only.

---

### 23. Multi-turn interrogation state lives in the server layer — Architecture / Complete

**Source review:** code_review/architecture_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47  
**Impact:** `interrogationStates` is game state (accumulated successes across turns for locate operations) stored in the WebSocket controller. If a decker disconnects mid-interrogation, state is lost silently. `GameContext` has no way to cancel interrogation on dump-out. Any save/restore of game state would miss this map.  
**Fix:** Move `InterrogationState` into the game engine layer (e.g. `TurnCoordinator`), not into `Decker`/`Persona`. **PRD cross-check:** prd_game.md explicitly states "The game engine (e.g. TurnCoordinator) holds `interrogationStates: Map<SystemOperation, InterrogationState>` per active decker" — moving it to `Decker` would contradict the PRD. The correct fix is to ensure the game engine (not the server WebSocket layer) owns this map and clears it on logoff/jackout/dump.

---

### CROSS-CUTTING STATE DRIFT

---

### 24. `WebSocketDeckerController.decker` goes stale after alert-transition-triggering operations — Correctness / Complete

**Source review:** code_review/correctness_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:104-107 and GameContext.kt:55-64  
**Impact:** After `applyDeckerOperationResult` triggers an alert transition, `updateHost` replaces every decker entry in `context.deckers` with a new version pointing to the updated host. The controller's `decker` field is not re-read from the context. On every subsequent turn, `decker.visibleObjects()` and `decker.availableActions()` operate on a stale embedded host — wrong alert status, wrong subsystem ratings.  
**Fix:** After `context.applyDeckerOperationResult(oldDecker, decker)`, re-read the authoritative copy: `decker = context.deckers.firstOrNull { it.name == decker.name } ?: decker`.

---

### 25. `AnalyzeSecurityResult.toDispatch()` hardcodes `success = true` — Correctness / Server

**Source review:** code_review/correctness_server.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:293  
**Impact:** All connected clients always see `"success": true` for `ANALYZE_SECURITY` operations, even when the host out-rolled the decker. The actual dice outcome is invisible.  
**Fix:** Use `DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, details)`.

---

### 26. `SWAP_MEMORY` and `LOCATE_DECKER` advertised to clients but always fail silently — Correctness / Complete

**Source review:** code_review/correctness_complete.md  
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:220-221, 229-230  
**Impact:** Both operations appear in the `availableActions` list sent to the UI. A player who selects either one wastes a turn and receives only a failure result. There is no up-front indication that the action cannot be submitted.  
**Fix:** Either filter `SWAP_MEMORY` and `LOCATE_DECKER` from `availableActions` before broadcasting, or add an `unsupported: Boolean` field to `AvailableActionDto.Operation` so the UI can disable them.

---

### TESTING GAPS

---

### 27. The entire React/TypeScript frontend has zero automated tests — Testing / UI

**Source review:** code_review/testing_ui.md  
**File:** frontend/package.json  
**Impact:** No test framework is installed. No test files exist. The `useWebSocket` reducer, reconnect logic, `buildParams`, role-gated UI branches, event-buffer capping, and every component are completely unverified. Correctness bugs (stale `cardStates`, ghost reconnect loop, duplicate socket creation) were found by reading only.  
**Fix:** Add vitest + @testing-library/react + jsdom. Add `"test": "vitest run"` to `package.json`. At minimum unit-test the `reducer` (pure function, trivially testable) and the `buildParams`/`actionLabel` pure functions.

---

### 28. IC action tests verify message strings, not game state — silent bugs pass undetected — Testing / Game Logic

**Source review:** code_review/testing_game_logic.md  
**File:** src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt:382, 396, 410  
**Impact:** Tests for Blaster, Ripper, and Sparky only assert `assertIs<ActionResult.IcAttack>` and a message string. A complete no-op implementation passes all three. This is exactly why findings 2 and 3 above (Blaster/Sparky silently discarding damage) went undetected.  
**Fix:** After each IC action with an all-success roller, assert the specific affected field changed: `cyberdeck.mcpRating < original.mcpRating` (Blaster), `persona.conditionMonitor.damage > 0` (Blaster icon damage), `physicalConditionMonitor.damage > 0` (Sparky).

---

## Notable Medium Findings Worth Prioritizing

**M1. IC ratings (rating, behavior, guardedNodeType) leaked to all observers before ANALYZE — Security / Complete**  
`MatrixObjectDto` for `IcProgram` sends full statistics to every session on every broadcast. In Shadowrun rules these are hidden until an ANALYZE operation succeeds. The `ANALYZE` operation is informationally vacuous as a result.  
**File:** code_review/security_complete.md — `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:62-63`  
**Fix:** Add an `analyzed` flag to the IC domain object. In `toDto()`, replace `rating`, `behavior`, `guardedNodeType` with null/sentinel values for unanalyzed IC.

**M2. `analyzeSecurity` returns stale `alertStatus` from parameter host, not live context — Correctness / Game Logic**  
If an alert transition occurred between when the caller obtained their `host` reference and when this method runs, the returned `alertStatus` is stale.  
**File:** code_review/correctness_game_logic.md — `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:689`  
**Fix:** Derive return values from `updatedDecker.currentLocation` (which carries the live tally/alert state) rather than from the parameter.

**M3. `GameContext.applyDeckerOperationResult` tally comparison uses embedded host, not live context — Correctness / Complete**  
The old tally baseline comes from the decker's embedded host snapshot (`old.currentLocation.host.securityTally`). If another IC action already advanced `context.host.securityTally` before this call, the delta is miscalculated and trigger checks can be silently skipped.  
**File:** code_review/correctness_complete.md — `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:56-64`  
**Fix:** Read the baseline from `context.host.securityTally` (the live ground truth), not from the decker's embedded snapshot.

**M4. `maintainMonitoredOperation` is a no-op — the operation-abort mechanic is entirely unimplemented — Correctness / Game Logic**  
The PRD (SO-13, SO-14) requires that failing to supply a Free Action each initiative pass aborts the monitored operation. The current implementation returns the handle unchanged unconditionally. The abort mechanic is inoperative.  
**File:** code_review/correctness_game_logic.md — `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:936`  
**Fix:** The game engine's initiative loop must track per-turn maintenance; deactivate unmaintained handles at end of each pass.

**M5. Stale `gameState` displayed after disconnect — no visual indication data is outdated — Error Handling / Complete**  
On `DISCONNECTED`, the reducer clears `role` (showing `JoinScreen`) but leaves `gameState` populated. After reconnect, if the server sends `ControlMessage` before a new `StateMessage`, the component briefly renders with pre-disconnect game data as though it is current.  
**File:** code_review/error_handling_complete.md — `frontend/src/hooks/useWebSocket.ts:36-37`  
**Fix:** Add `gameState: null` to the `DISCONNECTED` reducer case.

---

## Quick Wins (Low Effort / High Impact)

- **`runCatching` → add `.onFailure` handler** — Three lines (`logger.error(e)` + `session.send(ErrorMessage(...))`) make every server-side error visible. Eliminates finding 9 and dramatically improves debuggability of findings 10, 11, 12.

- **`QueryPrecision.valueOf` → safe lookup** — One-liner: `QueryPrecision.entries.firstOrNull { it.name == precision } ?: QueryPrecision.NORMAL`. Eliminates crash-the-game-loop vector (finding 11).

- **Fix TypeScript `precision` union type** — Change `'NORMAL' | 'HIGH'` to `'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC'` in `frontend/src/types/messages.ts:11`. Prevents the UI from ever generating a `'HIGH'` value that crashes the server.

- **`AnalyzeSecurityResult.toDispatch()` — change hardcoded `true` to `outcome.deckerWins`** — One-character fix that makes `ANALYZE_SECURITY` report the actual roll result to all clients (finding 25).

- **Remove `hasValidPasscode` from `ActionParams`/`ActionCommand`** — Delete the field from the DTO and the TypeScript type. Derive it server-side. Eliminates security finding 19 entirely.

- **`jackOut()` — remove the boolean parameter, use internal `isPinnedByBlackIc`** — Replace `fun jackOut(pinnedByBlackIc: Boolean = false)` with a parameterless version that reads `check(!isPinnedByBlackIc)`. Eliminates finding 4.

- **`Probe.action()` — apply the tally result** — ~5 lines: `val newTally = host.securityTally + tallyPoints; context.updateHost(host.copy(securityTally = newTally)); context.checkTriggers(host.securityTally, newTally)`. Restores the core security escalation mechanic (finding 1).

- **`connect()` guard — include `CONNECTING` state** — Add `|| state === WebSocket.CONNECTING` to the early-return guard in `useWebSocket.ts:74`. Prevents duplicate socket creation during React StrictMode double-mount.

- **Null `onclose`/`onerror` before calling `ws.close()` in cleanup** — Add `ws.onclose = null; ws.onerror = null` before `ws.close()` in the `useEffect` cleanup. Stops the ghost reconnect loop.

- **`ResultMessage.deckerSuccesses`/`hostSuccesses` — remove `?` optionality in TypeScript** — Two-character fix per field in `messages.ts`. Aligns the TypeScript contract with the server's guarantee that both fields are always present.

- **`ERROR_LABELS` — extract to shared module** — Move the constant from `App.tsx` and `NarrativePanel.tsx` into `frontend/src/utils/errorLabels.ts` and add imports. Eliminates a DRY violation with zero functional risk.

- **Add `require(face != 6)` to dice roller test helpers** — One `require` in each test helper prevents an infinite-loop footgun for future test writers. Documented in project memory but not enforced in code.
---
