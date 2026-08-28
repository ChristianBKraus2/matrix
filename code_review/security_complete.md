---
# Security Review — Complete System (Cross-Cutting)

## Summary

The most important structural decision in this system is correct: the server re-derives `availableActions` from authoritative game state on every turn and validates the client's `actionIndex` against that server-side list (WebSocketDeckerController.kt:51, 95). The client cannot invent actions that do not exist. However, the contract breaks down at the `ActionParams` level: several numeric and boolean parameters inside the action command are consumed by the server without any validation, directly influencing dice-roll outcomes and operation results. Combined with an absence of session authentication (any client can claim any decker name), an unauthenticated client can cheat on multiple game operations, crash the game loop with a crafted enum string, and exhaust server memory via unbounded file content. The DTO layer also leaks IC ratings and IC behavior flags to every observer before any ANALYZE operation has been performed, violating the game's information-asymmetry rules.

---

## Findings

### HIGH — Client-supplied `hasValidPasscode` and `scannerDeviceRating` bypass server authority

**Parts Affected:** server / game_logic  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:35-36`
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:222,231`

**Issue:** `ActionParams.hasValidPasscode` and `ActionParams.scannerDeviceRating` are taken verbatim from the client message and passed into game logic as if they were authoritative facts about the game world. `hasValidPasscode` controls whether `MAKE_COMCALL` treats the decker as having a valid passcode; `scannerDeviceRating` is the opponent device rating fed into `TAP_COMCALL`. A cheating client can send `hasValidPasscode: true` regardless of whether they actually hold a passcode, and can send `scannerDeviceRating: 0` to maximise their TAP_COMCALL advantage. Both values must be derived server-side from the game state (`decker`/`host` objects), not accepted from the client.

**Recommendation:** Remove `hasValidPasscode` and `scannerDeviceRating` from `ActionParams` entirely. In `dispatchHostOperation`, derive the passcode from `decker`'s carried items and the scanner rating from the host's device list. The client has no business supplying values that the server already knows.

---

### HIGH — Invalid `precision` enum string crashes the game-loop thread

**Parts Affected:** server / game_logic  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245`
- `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29` (runCatching scope ends before the exception occurs)

**Issue:** `locateWithState` calls `QueryPrecision.valueOf(params.precision)` with the raw client string. If the client sends `"precision": "EXPLOIT"` (or any non-enum value), `valueOf` throws `IllegalArgumentException`. This exception is thrown inside `dispatch()`, which is called from `action()` after `future.get()` returns — meaning it executes on the **game-loop thread**, completely outside the `runCatching` wrapper in the WebSocket frame handler. The unchecked exception propagates up through `Game.runOutOfCombatTurn()` / `runCombatTurn()`, crashing the current game turn. The game may become permanently stuck.

**Recommendation:** Validate `precision` before the `valueOf` call:
```kotlin
val precision = params?.precision
    ?.let { runCatching { QueryPrecision.valueOf(it) }.getOrNull() }
    ?: QueryPrecision.NORMAL
```
If the value is unrecognised, treat it as `NORMAL` (or return an error to the client before `future.complete` is called, by validating in `receiveAction`).

---

### HIGH — No session authentication; any client can squat any decker name

**Parts Affected:** server / ui  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:29-48`
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:9-12`
- `frontend/src/hooks/useWebSocket.ts:129-135`

**Issue:** A `join` message with `deckerName` is the only credential required. Any connecting client can claim the name of a decker whose player has not yet connected (or who disconnected). After `deregister` removes a disconnected session (line 56), the name is immediately available for anyone to re-register without any proof of identity. There is no shared secret, no token, no challenge-response. A hostile observer already connected to the same game session can time a reconnect to hijack the active decker identity.

**Recommendation:** For a minimal fix, generate a per-session secret on the server side when a decker first joins and communicate it back to the client; require it in any subsequent `join` after a reconnect. For a more complete fix, require an operator-issued session token passed as a WebSocket query parameter so identity is established before the WebSocket handshake completes.

---

### HIGH — Unbounded `newContent` in `EDIT_FILE` causes memory exhaustion

**Parts Affected:** server / game_logic  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:32`
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:196`

**Issue:** `ActionParams.newContent` is an arbitrary `String` that is converted to a `ByteArray` and passed into `decker.editFile(...)`. The UI enforces `maxLength={32}` on the decker-name field, but there is no corresponding enforcement on file content. A client bypassing the browser UI (e.g., a raw WebSocket script) can send a megabyte-scale string as `newContent`, which is deserialised into memory by the server on every edit attempt. Because the `runCatching` wrapper in the frame handler (MatrixServer.kt:29) silently swallows any resulting OOM error, there is no circuit-breaker.

**Recommendation:** Add a size cap in `receiveAction` (or in the DTO deserialiser) before the command reaches game logic — e.g., reject any `newContent` longer than a game-defined maximum file size (typically a few kilobytes in SR rules).

---

### MEDIUM — IC ratings and behavior flags leaked in DTO before ANALYZE

**Parts Affected:** server / game_logic / ui  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:62-63` (target `MatrixObject.IcProgram` exposes `ic`)
- `frontend/src/types/messages.ts:61`

**Issue:** `MatrixObjectDto` for `IcProgram` includes `rating`, `behavior` (`PROACTIVE` / `REACTIVE`), and `guardedNodeType`. In Shadowrun rules these attributes are supposed to be hidden until the decker successfully runs ANALYZE IC or ANALYZE ICON. The DTO is built from `decker.visibleObjects()` without filtering for what has actually been revealed through ANALYZE results. Every observer — including one seated at the table who controls a different character — receives the full IC statistics on every state broadcast, making the ANALYZE operation informationally vacuous.

**Recommendation:** Add an "analyzed" flag or a `revealedAttributes` set to the IC domain object. In the `toDto()` mapping, replace `rating`, `behavior`, and `guardedNodeType` with `null` / sentinel values for IC that has not yet been successfully analyzed by this decker.

---

### MEDIUM — `deckerName` length validated only in the browser

**Parts Affected:** server / ui  
**File(s):**
- `frontend/src/App.tsx:59` (`maxLength={32}`)
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:29-48` (no length check)

**Issue:** The UI input field carries `maxLength={32}` but the server imposes no equivalent limit on `JoinMessage.deckerName`. A client bypassing the browser can register a name of arbitrary length. Long names are stored in both `deckerSessions` and `sessionDecker` maps, included in all `ControlMessage` broadcasts, and become part of log strings in `ResultMessage.details`. A 1 MB decker name would be broadcasted to every connected session on every control message.

**Recommendation:** Enforce the length limit in `receiveJoin` on the server: reject names exceeding 32 characters with an `error` message before storing anything.

---

### MEDIUM — Full game state (actions, decker stats) broadcast to all observers

**Parts Affected:** server / ui  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:92-105`
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:64-68`

**Issue:** `broadcastWithRoles` sends the identical `StateMessage` — including `decker` health/stats, `visibleObjects`, and `availableActions` — to every connected session, varying only the `role` field. Observer sessions therefore receive the complete decker stat block and the full list of legal actions. In a situation with multiple humans at the same server (GM + players), an observer can trivially read the decker's exact HP, utilities, and turn options from the raw WebSocket stream. This is a design-level information-asymmetry issue.

**Recommendation:** Define separate view projections per role. Observers should receive a stripped-down projection (location summary, result narrative) rather than the full game state. The `active_controller` view is the only one that needs the full `availableActions` list.

---

### LOW — TOCTOU between `activeController` check and `future.complete`

**Parts Affected:** server  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107-118`

**Issue:** `receiveAction` checks `session == activeController` inside one `synchronized(lock)` block, then checks `future == null || future.isDone` outside any synchronized section, then calls `future.complete(cmd)`. Between the two checks a concurrent call could set `pendingAction` to null, or a timeout in `action()` could set `registry.pendingAction = null` on the game-loop thread while `future.complete` is about to be called. `CompletableFuture.complete` is thread-safe and will return false rather than double-complete, but the surrounding null check is not atomic with the `complete` call.

**Recommendation:** Capture `pendingAction` inside the same `synchronized(lock)` block as the `activeController` check, then call `future.complete(cmd)` on the captured reference outside the lock:
```kotlin
val future = synchronized(lock) {
    if (session != activeController) return@synchronized null
    pendingAction?.takeIf { !it.isDone }
} ?: run { /* send error */ return }
future.complete(cmd)
```

---

### LOW — No WebSocket origin validation (CSRF via WebSocket)

**Parts Affected:** server  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:24`

**Issue:** Ktor's `WebSockets` installation here does not configure an `allowedHosts` or origin check. Any web page served from any origin can open a WebSocket to `/decker/ws` and send `join` + `action` messages. Because the game does not use cookies for session tracking, classical CSRF does not apply, but a script on a hostile page could still silently join the game as a registered decker and consume turns if it can guess an unclaimed name.

**Recommendation:** In the Ktor WebSockets install or an intercepting plugin, validate the `Origin` header against the expected host. In development this can be a configurable allow-list; in production it should match the server's own hostname.

---

### INFO — `runCatching` in frame handler silently drops all errors

**Parts Affected:** server  
**File(s):**
- `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29-35`

**Issue:** Every deserialization error, unknown message type, or runtime exception thrown synchronously inside the frame handler is swallowed without sending an error response to the client or logging the failure. A client sending a structurally valid JSON with a subtly wrong field type will receive no feedback, making debugging and detecting malformed clients very difficult.

**Recommendation:** Add at minimum a `onFailure` branch in the `runCatching` that logs the exception (with the raw frame for context) and sends an `ErrorMessage` back to the offending session. This does not change the security posture but dramatically improves visibility into malformed or adversarial input.

---

## Clean Seams

- **Action-index trust boundary is correct.** The server recomputes `availableActions` from authoritative `Decker` state on every turn (WebSocketDeckerController.kt:51) and looks up the chosen action by index against that server-side list (line 95). The client cannot synthesise a new action type or reference an action outside the current legal set.
- **`activeController` enforcement is solid.** `receiveAction` rejects any session that is not the current `activeController`, preventing observers or non-turn deckers from injecting actions mid-turn.
- **Turn lifecycle is server-driven.** `promoteForTurn` / `demoteAfterTurn` are called exclusively by the game loop, not triggered by client messages, so a client cannot self-promote to controller.
- **`JackOut` pinned-IC check is server-side.** The Black IC pin check (`decker.isPinnedByBlackIc`) in `dispatch` is evaluated against server state, not a client flag.
- **DTO type hierarchy matches the domain sealed class.** `AvailableActionDto` mirrors `AvailableAction` structurally, and `toDto()` is a pure server-side mapping — the client never sends an `AvailableActionDto` back to the server.
- **Disconnect handling is covered.** `deregister` correctly clears `activeController` and completes the pending future exceptionally, preventing a stuck game turn when a decker disconnects mid-action.
---
