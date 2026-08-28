---
# Error Handling Review — Complete System (Cross-Cutting)

## Summary

The system has a well-structured three-layer pipeline (game_logic → server → ui), and the happy-path contract between layers is solid: DTOs are faithfully mirrored in TypeScript, the `ErrorMessage` type is wired end-to-end, and the reconnect loop in the UI is competently implemented. The serious problems all live at the error paths, not the success paths. Two structural gaps dominate: (1) `runCatching` in `MatrixServer` silently discards every exception that occurs while processing an incoming frame, so the client never learns that its message failed; (2) exceptions that escape `WebSocketDeckerController.action()` — from unsafe casts, a raw `valueOf`, or an unhandled `ExecutionException` cause — propagate uncaught up through `Game.runOutOfCombatTurn/runCombatTurn` and crash the game loop with no notification to any connected client. Together these two gaps mean that a large class of runtime errors produces: no UI feedback, no server log, and a silently broken session.

---

## Findings

### [CRITICAL] `runCatching` result is never inspected — all inbound errors are silently swallowed

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29-37`
**Issue:** The entire frame-dispatch block is wrapped in `runCatching { ... }` but the returned `Result<Unit>` is discarded. Any exception thrown inside — JSON parse failure, unknown `msgType`, a throw from `receiveJoin` or `receiveAction`, a serialization mismatch — is silently eaten. The client that sent the malformed or unrecognised message receives no feedback; the server emits no log entry. This also masks programming errors (wrong message shape, missing required fields) during development.
**Recommendation:** Replace `runCatching { ... }` with an explicit try/catch that, on failure, sends an `ErrorMessage` back to `this` (the session) and logs the exception. For JSON parse errors the details can be generic ("malformed message"); for unrecognised `msgType` send `ErrorMessage("unknown_message_type")`.

**Resolution (Phase 1.3):**
`MatrixServer.kt` now sends an `ErrorMessage` on failure in the `runCatching` handler instead of silently swallowing it. An `else` branch was also added to handle unknown `msgType` values, returning `ErrorMessage("unknown_message_type")` to the session.

---

### [CRITICAL] Exceptions from `dispatch()` escape uncaught to the game loop, crashing it silently

**Parts Affected:** game_logic / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:105` and `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:14-25`
**Issue:** `dispatchHostOperation` contains more than a dozen unchecked casts of the form `(action.target as MatrixObject.IcProgram).ic` (lines 168, 170, 173, 178, 183, 186, 197, 200, 225). A `ClassCastException` from any of these, as well as an `IllegalArgumentException` from `QueryPrecision.valueOf(it)` (line 245), propagate out of `dispatch()` into `action()`, which has no surrounding catch. `Game.runOutOfCombatTurn` and `runCombatTurn` also have no try/catch around `decker.action()`. The exception therefore terminates the entire game turn loop. No `ResultMessage` or `ErrorMessage` is sent to any session; all connected clients are left waiting forever for a response that will never arrive.
**Recommendation:** Wrap the `val result = dispatch(chosen, cmd, diceRoller)` call in a try/catch inside `action()`. On exception: broadcast a `ResultMessage(success = false, details = "Internal error — turn aborted")`, call `registry.demoteAfterTurn(decker.name)`, log the full stack trace, and return `ActionResult.DeckerAction` to allow the game loop to continue. Separately, the unsafe casts should be guarded: if `action.target` is the wrong type, return a `DispatchResult` with an explanatory `details` string rather than throwing.

**Resolution (Phase 1.4):**
`WebSocketDeckerController.kt` now wraps the dispatch-and-apply block in a try/catch. On exception it broadcasts an error `ResultMessage` and unconditionally calls `demoteAfterTurn`, so the game loop can always continue and the decker is never left permanently promoted.

---

### [HIGH] `ExecutionException` with unknown cause is re-thrown from `action()` — game loop crashes, client stuck

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:83-89`
**Issue:** The `catch (e: ExecutionException)` block only handles `DeckerDisconnectedException`. Any other cause — e.g., an exception thrown inside the `CompletableFuture` by `future.complete(cmd)` if the future were completed exceptionally by something other than disconnect — falls through to `throw e`. This re-throw escapes `action()` with the same consequences as Finding 2: the game loop crashes and all clients hang. The situation is made worse because `pendingAction` is not cleared in this path (the `finally` block does clear it at line 92–93, but that executes after the re-throw only because `finally` still runs — however `registry.demoteAfterTurn` is never called, leaving the decker permanently promoted).
**Recommendation:** After the `DeckerDisconnectedException` check, add a catch-all `else` branch that broadcasts an error `ResultMessage`, demotes the decker, and logs the unexpected cause, rather than re-throwing.

**Resolution (Phase 1.7):**
`WebSocketDeckerController.kt` now has a catch-all branch in the `ExecutionException` handler that broadcasts an error result, calls `demoteAfterTurn`, and logs the unexpected cause instead of re-throwing, preventing the game loop from crashing on unknown future failures.

---

### [HIGH] Server restart mid-game: client auto-rejoins into an empty registry but receives no signal of lost game state

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/frontend/src/hooks/useWebSocket.ts:110-118`, `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:24-27`
**Issue:** `useWebSocket` implements exponential-backoff reconnect. On reconnect the server sends `control { role: "observer" }` and, because `pendingNameRef.current` still holds the decker name from the previous session, the client immediately sends a `join` message. The server re-registers the client as a fresh decker with no game context. Meanwhile, the stale `gameState` in the React reducer is never cleared on `DISCONNECTED` (the reducer resets only `connected` and `role`). So between reconnect and the first new `state` message, the UI shows the last pre-disconnect game state as if it were current. If no `state` message arrives (e.g., the game is between turns), the player can act on outdated information.
**Recommendation:** Add a `gameState: null` reset to the `DISCONNECTED` reducer case. The server should also send a `ResultMessage` or an `ErrorMessage` with a code like `"session_restored"` immediately after registering a decker that matches a name, so the UI can show a reconnection notice.

---

### [HIGH] `QueryPrecision.valueOf()` on raw client input — uncaught `IllegalArgumentException` escapes to game loop

**Parts Affected:** server / game_logic
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245`
**Issue:** `params?.precision?.let { QueryPrecision.valueOf(it) }` calls the JVM `Enum.valueOf` on a string that came directly from the client over the WebSocket. If the client sends any `precision` value other than a valid `QueryPrecision` name, `valueOf` throws `IllegalArgumentException`. This is not caught anywhere in the call chain and escapes to the game loop (same crash path as Finding 2).
**Recommendation:** Replace with `params?.precision?.let { runCatching { QueryPrecision.valueOf(it) }.getOrNull() } ?: QueryPrecision.NORMAL` and send an `ErrorMessage("invalid_precision")` back to the session if the value is unrecognised, then return early without advancing the game turn.

**Resolution (Phase 1.1):**
`WebSocketDeckerController.kt` `locateWithState` now uses `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL`. Invalid precision strings are silently coerced to `NORMAL` rather than throwing, preventing `IllegalArgumentException` from escaping to the game loop.

---

### [HIGH] Unknown `msgType` silently dropped — client never learns its message was ignored

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31-35`
**Issue:** The `when (msgType)` block has no `else` branch. If `msgType` is null (key absent from JSON) or any value other than `"join"` or `"action"`, the frame is silently discarded. This is inside `runCatching` (Finding 1), so even if an exception occurred it would also be dropped. A client sending a mistyped message type gets no feedback.
**Recommendation:** Add an `else ->` branch that sends `ErrorMessage("unknown_message_type: $msgType")` back to the session. This is especially helpful during development when new message types are being added.

**Resolution (Phase 1.3):**
An `else` branch was added to the `when (msgType)` block in `MatrixServer.kt`, returning `ErrorMessage("unknown_message_type")` for any unrecognised message type. This was implemented as part of the same Phase 1.3 fix that added the `.onFailure` handler.

---

### [MEDIUM] Stale `gameState` displayed after disconnect — no UI indication that data is outdated

**Parts Affected:** ui / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/frontend/src/hooks/useWebSocket.ts:36-37`, `src/main/kotlin/com/shadowrun/matrix/frontend/src/App.tsx:90-94`
**Issue:** On `DISCONNECTED`, the reducer leaves `gameState` unchanged. `App.tsx` renders `<DeckerPanel>`, `<LocationPanel>`, etc., using this stale data without any visual indicator that the connection is lost. A player may believe their decker is in a state that no longer exists on the server.
**Recommendation:** Either clear `gameState` to `null` on `DISCONNECTED` (which will render the "SYNCHRONISING" banner after reconnect), or add a `connected` overlay/banner to the game grid that dims the panels while disconnected.

**Resolution (Phase 2.4):**
`useWebSocket.ts` `DISCONNECTED` reducer case now sets `gameState: null`, so the UI correctly shows the synchronising state on reconnect rather than displaying stale pre-disconnect game data.

---

### [MEDIUM] `GameContext.updateDecker` is a silent no-op when decker not found — state divergence undetected

**Parts Affected:** game_logic / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:27-30`, `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:107`
**Issue:** `updateDecker(old, new)` does nothing if `old` is not in `deckers`. `applyDeckerOperationResult` calls this, so if the `WebSocketDeckerController`'s `decker` field has drifted from the canonical entry in `context.deckers` — which can happen if the controller's `decker` is updated after an operation but the context reference is different — the context's decker list silently retains the stale copy. Security tally changes and IC triggers derived from the new decker's host state are then computed against wrong baseline values.
**Recommendation:** Log a warning (and in debug mode, throw) when `idx < 0` in `updateDecker`. Add an assertion in `applyDeckerOperationResult` that `old` is present in the list before proceeding.

**Resolution (Phase 2.2):**
`GameContext.kt` now logs a warning when `updateDecker` cannot find the decker in the list (index < 0), making state divergence immediately visible in logs rather than silently producing stale game state.

---

### [MEDIUM] `ERROR_LABELS` map is an incomplete, fragile contract between server codes and UI strings

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/frontend/src/App.tsx:10-15`, `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:33,37,110,114`
**Issue:** The server sends two distinct styles of error strings: code-style keys (`"not_your_turn"`, `"no_action_pending"`, `"already_registered"`, `"name_already_taken"`) and free-text sentences (`"Action timed out"`, `"Decker disconnected — turn forfeit"`, `"Invalid action index 99"`, `"No controller registered for decker X — turn skipped"`). The `JoinScreen` in `App.tsx` maps only the four code-style keys; all other strings fall through to raw display via `?? last.msg.message`. The free-text strings from `WebSocketDeckerController` are shown only in `NarrativePanel` (via `ResultMessage.details`), not in `ErrorMessage`. This inconsistency makes it impossible to internationalise or style errors uniformly, and any new error code added server-side silently degrades to raw display.
**Recommendation:** Standardise server error strings to always use code-style keys (e.g., `"action_timed_out"`, `"decker_disconnected"`, `"invalid_action_index"`). Expand `ERROR_LABELS` to cover all codes. Move `ERROR_LABELS` to a shared constants file used by both `JoinScreen` and any future error display component. Apply the same lookup in `NarrativePanel` for `ErrorMessage` events.

---

### [LOW] TypeScript `ResultMessage` marks `deckerSuccesses`/`hostSuccesses` as optional; Kotlin always sends them

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/frontend/src/types/messages.ts:88-89`, `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:40-43`
**Issue:** `ResultMessage` in TypeScript declares `deckerSuccesses?: number` and `hostSuccesses?: number`, but the Kotlin DTO has non-optional `Int` fields and `MatrixJson` is configured with `encodeDefaults = true`, so both fields are always present in the wire format. The optional annotations in TypeScript are therefore misleading and force consumers to handle `undefined` that will never actually occur. If a future change makes the fields genuinely optional on the Kotlin side, the TypeScript code will silently accept `undefined` without any type error surfacing.
**Recommendation:** Change the TypeScript declarations to `deckerSuccesses: number` and `hostSuccesses: number` to match the actual wire contract.

---

## Clean Seams

- The `ErrorMessage` type (`type: "error"`, `message: string`) is defined identically on both sides and is correctly routed through the reducer to the `events` list, then rendered in both `JoinScreen` and (implicitly) `NarrativePanel`.
- `useWebSocket` implements exponential backoff reconnect (3 s base, 30 s cap) and correctly resends the `join` message on reconnect using `pendingNameRef`, so a brief network hiccup is transparent to the player.
- `DeckerDisconnectedException` is cleanly modelled as a typed exception propagated through `CompletableFuture.completeExceptionally`, and the handling in `action()` correctly broadcasts a `ResultMessage` before returning, rather than leaving the game loop stuck.
- The `broadcast` and `broadcastWithRoles` methods wrap each individual send in `runCatching`, so a single dead session cannot prevent other sessions from receiving the message.
- The `ActionCommand.actionIndex` → `availableActions.getOrNull(cmd.actionIndex)` null-check (line 95-102 of `WebSocketDeckerController.kt`) correctly bounds-checks client input and returns a `ResultMessage` with details rather than crashing.
- The `AvailableActionDto` sealed class on the Kotlin side maps cleanly to the TypeScript discriminated union on `kind`, giving the UI type-safe access to action-specific fields.
---
