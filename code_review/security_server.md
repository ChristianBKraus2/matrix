---
# Security Review — server

## Summary

The server is a single-process Ktor WebSocket server with no authentication layer. Because this appears to be a local LAN game tool the attack surface is smaller than a public service, but several issues still matter: a client-supplied Boolean that short-circuits game mechanics, an unbounded string that can exhaust heap memory, an unvalidated enum parse that can crash the game thread, and the full cyberdeck stat block being broadcast to every observer on every turn. There are no hardcoded credentials or secrets, no path-traversal risk in static file serving, and the action-index validation is correctly server-side.

## Findings

### [HIGH] Client self-reports passcode validity for MAKE_COMCALL

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:222  
**Issue:** `p?.hasValidPasscode ?: false` passes a client-supplied Boolean directly to the game logic. The client declares "I have a valid passcode" and the server believes it. There is no server-side check that the decker actually holds the passcode. A malicious client sends `"hasValidPasscode": true` and bypasses the passcode requirement entirely.  
**Recommendation:** Remove `hasValidPasscode` from `ActionParams` and `ActionCommand`. Determine passcode possession on the server from the decker's state (`decker.hasPasscodeFor(host)` or equivalent) before invoking `makeComcall`.

---

### [HIGH] Unvalidated `precision` string crashes the game thread

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245  
**Issue:** `QueryPrecision.valueOf(it)` is called with the raw string from `cmd.params.precision`. If a client sends any value that is not a valid `QueryPrecision` enum name (including an empty string or a typo), `valueOf` throws `IllegalArgumentException`. This exception is thrown inside `WebSocketDeckerController.action()`, which runs on the game loop thread — outside the `runCatching` wrapper in `MatrixServer.kt`. The uncaught exception propagates to the game scheduler, crashing or stalling the entire session.  
**Recommendation:** Replace `QueryPrecision.valueOf(it)` with a safe lookup and fall back to a default: `QueryPrecision.entries.firstOrNull { e -> e.name == it } ?: QueryPrecision.NORMAL`. Alternatively, make `ActionParams.precision` a serializable enum so kotlinx.serialization rejects unknown values before dispatch.

---

### [HIGH] No authentication on the WebSocket endpoint

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:24  
**Issue:** Any TCP client that can reach port 8080 can connect to `/decker/ws`, register any unclaimed decker name, and send actions on that decker's turns. There is no shared secret, token, or session cookie required. On a LAN this allows any device on the network to hijack an unclaimed decker slot or flood the registry with connections.  
**Recommendation:** Add a pre-shared key (configured via environment variable, not hardcoded) that clients must supply in a `join` message or as a query parameter on the WebSocket upgrade request. Validate it in `receiveJoin` and close the connection immediately on mismatch.

---

### [MEDIUM] `newContent` (EDIT_FILE) has no length limit

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:196  
**Issue:** `p?.newContent?.toByteArray()` converts a client-supplied string to bytes with no size check. A client can send a multi-megabyte or multi-gigabyte JSON string in `newContent`, causing heap exhaustion on the server. The Ktor WebSocket layer itself does not impose a frame-size limit unless explicitly configured.  
**Recommendation:** Add a `MAX_CONTENT_BYTES` constant (e.g. 65536) and reject the action before conversion: `if ((p?.newContent?.length ?: 0) > MAX_CONTENT_BYTES) { /* send error, return */ }`. Also configure a maximum incoming frame size on the `WebSockets` plugin installation in `MatrixServer.kt` (`maxFrameSize`).

---

### [MEDIUM] Unbounded client integers (`inactivitySeconds`, `scannerDeviceRating`)

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:33-36  
**Issue:** `inactivitySeconds` and `scannerDeviceRating` are passed directly to game methods with no range validation. A client sending `inactivitySeconds = Int.MAX_VALUE` or a negative value could produce nonsensical game state or integer overflow in downstream arithmetic. `scannerDeviceRating` at extremely large values could produce similarly broken dice pool calculations.  
**Recommendation:** Clamp both values to sane game ranges (e.g. `inactivitySeconds` in `0..3600`, `scannerDeviceRating` in `0..12`) and return an `ErrorMessage` for out-of-range inputs.

---

### [MEDIUM] Full cyberdeck stats broadcast to all observers on every turn

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:64-68  
**Issue:** `StateMessage` includes `decker.toDto()` which contains `hackingPool`, `mcpRating`, and the full list of active utilities with their ratings. This entire payload is sent to every session — including unauthenticated observers — via `broadcastWithRoles`. Opponent players watching as observers learn the exact hardware and software loadout of the active decker, which is information the game rules explicitly gate behind the Analyze Icon operation.  
**Recommendation:** Create a `DeckerStateDtoPublic` that exposes only the name and location. Send the full `DeckerStateDto` only to the session whose decker it is (`role == "active_controller"` or `role == "registered_decker"` for that decker). All other sessions receive the redacted form.

---

### [MEDIUM] No connection limit — trivial connection-flood DoS

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:16  
**Issue:** Every incoming WebSocket connection is added to `sessions` with no cap. An attacker or a buggy client can open thousands of connections, exhausting file descriptors and memory. The `broadcast` method then iterates the entire set on every game event.  
**Recommendation:** Enforce a maximum connection count in `register`. If `sessions.size >= MAX_CONNECTIONS` (e.g. 20), send an error frame and close the new session before adding it.

---

### [MEDIUM] `deckerName` has no length or character validation

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:30  
**Issue:** `msg.deckerName` is accepted as-is and stored as a HashMap key and embedded in `ControlMessage` JSON that is broadcast to all clients. An empty string `""`, a name thousands of characters long, or a string containing JSON-special characters (e.g. `"` characters if ever interpolated unsafely) are all accepted. The empty-string case in particular registers silently, taking the `""` slot.  
**Recommendation:** Validate before inserting: reject empty names, enforce a maximum length (e.g. 32 characters), and restrict to a safe character set (alphanumerics, spaces, hyphens).

---

### [LOW] `runCatching` silently discards all parse and dispatch errors

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29  
**Issue:** The `runCatching` block swallows every exception from JSON parsing and `receiveJoin`/`receiveAction` without logging or notifying the client. A malformed message from a broken client disappears with no trace. This also makes it impossible to detect a client that is sending garbage to probe the server.  
**Recommendation:** Add logging inside the `onFailure` handler at minimum: `runCatching { … }.onFailure { e -> logger.warn("Message processing failed", e) }`. For parse errors, also send an `ErrorMessage` back to the sender so legitimate clients can detect their own bugs.

---

### [LOW] `pendingAction` is a public mutable field

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:22  
**Issue:** `pendingAction` is declared `var` with no accessor restriction, allowing any code with a `SessionRegistry` reference to replace or null out the future mid-turn. Currently only `WebSocketDeckerController` writes it, but the exposure is broader than necessary and could introduce subtle race conditions if a second controller type is added.  
**Recommendation:** Make `pendingAction` `internal` or `private` and expose a pair of methods (`setPendingAction(future)` / `clearPendingAction()`) with appropriate synchronisation, so the invariant is enforced by the type rather than by convention.

---

### [LOW] No TLS — WebSocket traffic is plaintext

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:46-49  
**Issue:** The server starts a plain `ws://` listener. All game state (decker stats, available actions, file names, device addresses) and the decker name are transmitted in cleartext. On a shared network this is trivially sniffable.  
**Recommendation:** For LAN use a self-signed certificate configured via Ktor's SSL block, or tunnel behind a reverse proxy with TLS. At minimum document the plaintext assumption so operators are aware.

---

### [INFO] No WebSocket origin validation

**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:24  
**Issue:** The WebSocket upgrade request is accepted from any `Origin`. For a browser-based client this means a malicious web page on the same LAN can open a WebSocket connection to the server using the victim's browser, though this is largely mitigated by the lack of cookie-based credentials.  
**Recommendation:** Install Ktor's `Origin` check (`install(WebSockets) { … }` combined with a custom interceptor) and reject upgrades whose `Origin` header does not match the expected host, or at least log unexpected origins.

---

## Clean Areas

- Action index validation is correctly server-side: `availableActions.getOrNull(cmd.actionIndex)` looks up against the server-built list, so the client cannot reference an action it was not offered.
- Static file serving via `staticResources` uses Ktor's built-in handler which prevents path traversal by construction.
- The `receiveAction` guard `session != synchronized(lock) { activeController }` correctly rejects actions from non-active sessions and sends a clear `not_your_turn` error rather than silently accepting them.
- Decker disconnection during a pending turn is handled gracefully: the `DeckerDisconnectedException` path completes the future exceptionally and broadcasts a forfeit message rather than hanging the game indefinitely (the timeout provides an additional safety net).
- No hardcoded credentials, API keys, or secrets were found anywhere in the reviewed files.
- `visibleObjects` originates from `decker.visibleObjects()` — the server filters the object list at the domain layer before serialisation, so clients receive only what they should be able to perceive.
---
