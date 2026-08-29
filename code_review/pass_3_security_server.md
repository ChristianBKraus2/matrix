# Security Review — server

## Summary

The server layer has a good structural foundation for turn-based authority: the active controller is tracked by session identity on the server, action indices are validated against a server-computed list, parse errors do not leak stack traces, and the WebSocket frame size is capped at 64 KiB. The main concerns fall into two groups. First, game-affecting parameters (`hasValidPasscode`, `scannerDeviceRating`, `inactivitySeconds`) arrive from the active client and are used without server-side corroboration, allowing a motivated player to manipulate game outcomes by crafting their JSON payload. Second, the decker name receives only a length check before being broadcast to every connected session, creating a stored-injection risk if the frontend ever renders it without escaping. A logic inversion in the reconnect token guard means token absence is treated as "pass" rather than "fail", and the disconnected-name set is never pruned.

## Findings

### [HIGH] Client-supplied `hasValidPasscode` bypasses game authentication check
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:287
**Issue:** `ActionParams.hasValidPasscode` is a Boolean sent by the active client and forwarded directly to `decker.makeComcall(host, diceRoller, p?.hasValidPasscode ?: false)`. The server never verifies whether the decker actually holds a valid passcode for that host; the client simply asserts it. A player sends `"hasValidPasscode": true` in every comcall action to gain an unearned mechanical advantage.
**Recommendation:** Remove `hasValidPasscode` from `ActionParams` entirely. Derive the value from server-held decker/host state (e.g., a flag set by a prior successful decrypt or access operation) so the check cannot be spoofed by the client.

**[RESOLVED]** — `hasValidPasscode` removed from `ActionParams`; `WebSocketDeckerController` now always passes `false` to `makeComcall` with a TODO comment.

### [HIGH] Client-supplied `scannerDeviceRating` determines tap-comcall effectiveness
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:288
**Issue:** `ActionParams.scannerDeviceRating` is an integer from the active client used directly as `decker.tapComcall(host, p?.scannerDeviceRating ?: 0, diceRoller)`. A client can supply an arbitrarily large value (e.g. `999`) to inflate their scanner rating and guarantee a favorable dice outcome.
**Recommendation:** Look up the scanner device rating from the server-authoritative decker/device model rather than accepting it from the client. If no scanner is equipped, default to 0 and reject non-zero client-supplied values.

**[RESOLVED]** — `scannerDeviceRating` removed from `ActionParams`; `WebSocketDeckerController` now always passes `0` to `tapComcall` with a TODO comment.

### [MEDIUM] Reconnect token guard is inverted — token absence silently grants access
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:57-65
**Issue:** The guard is `if (stored != null && msg.reconnectToken != stored)`. When `stored` is `null` (the name is in `disconnectedDeckerNames` but has no corresponding `reconnectTokens` entry), the condition evaluates to `false` regardless of what the client sends, and the reconnect is allowed unconditionally. The current code flow always populates `reconnectTokens` at join time, so `stored == null` is rare today, but a future refactor (or any partial state inconsistency) silently widens the door to identity theft.
**Recommendation:** Invert the guard so that the absence of a stored token is itself a rejection:
```kotlin
if (stored == null || msg.reconnectToken != stored) {
    Triple(ErrorCode.NAME_ALREADY_TAKEN, false, null)
}
```

**[RESOLVED]** — Fixed in `SessionRegistry.kt`: guard now reads `if (stored == null || msg.reconnectToken != stored)`.

### [MEDIUM] Decker name has no character-set validation — stored injection risk
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:45
**Issue:** `receiveJoin` only rejects names longer than 32 characters. HTML special characters (`<`, `>`, `&`, `"`), control characters, null bytes, and Unicode direction-override codepoints (U+202E, etc.) are accepted and stored. The name is subsequently broadcast to every connected session in `ControlMessage.deckerName` and `StateMessage.decker.name`. If the frontend renders these fields as raw HTML rather than escaped text content, an attacker can inject persistent script that executes in every observer's browser for the duration of the session.
**Recommendation:** After the length check, validate the name against an explicit allowlist (e.g., `[A-Za-z0-9 _\-]{1,32}`) and return a `NAME_INVALID` error code for rejections. Reject any name containing `<`, `>`, `&`, `"`, control characters, or zero-width codepoints.

**[RESOLVED]** — Fixed in `SessionRegistry.kt`: name validated against `[A-Za-z0-9 _\-]{1,32}` allowlist regex.

### [LOW] Client-supplied `inactivitySeconds` forwarded to game logic without bounds check
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:296
**Issue:** `p?.inactivitySeconds ?: 0` is passed without validation to `decker.nullOperation(host, inactivitySeconds, diceRoller)`. A client can supply a negative value or `Int.MAX_VALUE`. Depending on how `nullOperation` uses this number (timer arithmetic, comparisons, scaling), this could cause integer overflow, unintended negative durations, or logic bypasses.
**Recommendation:** Clamp the value to a sensible non-negative range before forwarding, for example `inactivitySeconds.coerceIn(0, 3600)`.

**[RESOLVED]** — Fixed in `WebSocketDeckerController.kt`: `inactivitySeconds` now clamped via `.coerceIn(0, 3600)` before forwarding.

### [LOW] `disconnectedDeckerNames` is never pruned — unbounded memory and name-squatting
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:24, 98-99
**Issue:** Names are added to `disconnectedDeckerNames` on every disconnect and removed only on a successful reconnect with the original token. After a long session with many unique names, or if the legitimate player never returns, the set grows indefinitely. More concretely, it permanently blocks the slot: no new player can register a name that any prior player ever used, enabling a soft denial-of-service by a player who registers and immediately disconnects with a strategically chosen name.
**Recommendation:** Attach a timestamp to each disconnected-name entry (e.g., store `Map<String, Pair<String, Instant>>`) and evict entries older than a configurable window (e.g., 5 minutes) either lazily during `receiveJoin` or via a periodic cleanup coroutine.

**[DEFERRED]** — `disconnectedDeckerNames` pruning not implemented; out of scope for this session.

### [LOW] `EDIT_FILE` content size checked in characters, not bytes
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:251-254
**Issue:** The guard is `content.length > 4096`, but `content.toByteArray()` is called immediately after (using the default UTF-8 encoding). A string of 4096 four-byte Unicode codepoints passes the character-count check but produces a 16 384-byte array — four times the intended maximum.
**Recommendation:** Check the byte length instead: `content.toByteArray(Charsets.UTF_8).size > 4096`.

**[RESOLVED]** — Fixed in `WebSocketDeckerController.kt`: guard now checks `content.toByteArray(Charsets.UTF_8).size > 4096`.

### [INFO] No per-connection message rate limiting
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:42-63
**Issue:** `MAX_CONNECTIONS` caps total concurrent connections, but there is no throttle on the number of frames a single connection can submit per second. A client can flood the server with valid or malformed frames, driving CPU and memory use even within the connection limit.
**Recommendation:** Implement a simple per-session message counter with a sliding window; close connections that exceed a threshold (e.g., 60 frames per minute).

**[DEFERRED]** — Per-connection rate limiting not implemented; out of scope for this session.

### [INFO] WebSocket endpoint has no authentication gate
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:36
**Issue:** `/decker/ws` accepts any TCP connection that reaches the port. There is no API key, session cookie, shared secret, or IP allowlist. For a LAN-only game this is likely acceptable, but if the server is ever exposed beyond a trusted network any internet user can connect, register a decker name, and observe or participate in a running game.
**Recommendation:** Document the intended deployment boundary (LAN-only) explicitly. If internet exposure is ever planned, add a pre-shared secret checked during the initial WebSocket handshake (e.g., a `Sec-WebSocket-Protocol` header token or a first-frame challenge-response).

**[DEFERRED]** — No authentication gate added; LAN-only deployment boundary not yet documented.

### [INFO] Raw `msgType` from client reflected verbatim in error response
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:51
**Issue:** `ErrorMessage(message = ErrorCode.UNKNOWN_MESSAGE_TYPE, details = msgType)` echoes the client-supplied type string back to that same sender. Because the frame size is capped at 64 KiB the blast radius is limited, but reflecting unsanitised client input is a pattern to avoid.
**Recommendation:** Either omit `details` for unknown-type errors or truncate/sanitise the reflected value (e.g., `msgType?.take(32)`) before sending.

**[DEFERRED]** — Raw `msgType` reflection not sanitised; out of scope for this session.

## No Issues Found In

- **`TurnCoordinator` turn-ownership enforcement:** all state mutation is mutex-guarded; `claimAction` atomically validates session identity, active status, and pending-future completeness — no TOCTOU window.
- **`actionIndex` validation:** the server builds the available-actions list server-side, sends it to the client, then validates the returned index with `getOrNull` — the client cannot reference an action the server did not offer.
- **Reconnect token generation:** tokens are server-generated `UUID.randomUUID()` strings, never derived from client input.
- **Error response hygiene:** parse exceptions return a generic `BAD_REQUEST` with `details = null`; no stack traces or internal class names are leaked to clients.
- **`MAX_FRAME_SIZE` cap (64 KiB):** large-frame denial-of-service is bounded at the Ktor layer (`MatrixServer.kt:26-32`).
- **`DeckerStateDto` and `MatrixObjectDto` serialisation:** only computed, safe fields are projected into DTOs; raw domain objects with internal mutable state are not sent to clients.
- **`precision` string-to-enum conversion:** `runCatching { QueryPrecision.valueOf(it) }.getOrNull()` safely rejects unknown values and defaults to `NORMAL` rather than throwing.
