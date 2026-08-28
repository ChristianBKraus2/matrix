# Security Review — complete (cross-cutting)

## Summary

The Matrix of Shadowrun server is a single-process Ktor application exposing one
unauthenticated WebSocket endpoint that drives all game interaction. The trust model
is session-object identity (Ktor `DefaultWebSocketServerSession` pointer equality)
rather than any cryptographic credential, which is workable for a local LAN game
but creates a hard blocker: when a decker disconnects, their game identity can be
silently claimed by any other connected client who sends the same decker name.
Beyond that critical impersonation gap the main exposures are unbounded resource
consumption (sessions, the disconnected-name set), unvalidated integer parameters
from the client that flow directly into game logic, and exception detail leakage in
`BAD_REQUEST` error frames. Server-side action dispatch is architecturally sound —
the action list is generated on the server and clients send only an index — so the
game-state manipulation surface is narrow and well-bounded. No authentication,
authorisation, TLS, origin checking, or rate limiting exists anywhere in the stack.

---

## Findings

### [CRITICAL] Disconnected decker identity can be hijacked by any observer

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:41-58`

**Issue:** When a decker disconnects, `deregister()` removes their name from
`deckerSessions` and adds it to `disconnectedDeckerNames`. The next `receiveJoin`
for that name sees `deckerSessions.containsKey(name) == false`, registers the new
session successfully, sets `isReconnect = true`, and sends the impostor a
`REGISTERED_DECKER` control frame with the stolen name. No secret, token, or
challenge proves the reconnecting client is the original player. Any observer
watching the broadcast (all result/state frames are sent to every connection) knows
decker names and can race to claim one the moment the real player disconnects.

**Recommendation:** Add a per-session reconnect secret generated at initial
registration and returned in the first `ControlMessage`. Require the client to echo
it in the `JoinMessage` to claim a disconnected identity. Alternatively, restrict
reconnect acceptance to the same remote address (weaker but cheap).

---

### [HIGH] No authentication or authorisation on the WebSocket endpoint

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:28`

**Issue:** `webSocket("/decker/ws")` accepts every TCP connection without credentials,
shared secrets, or network-layer access control. Any host that can reach port 8080
can register as a decker, receive full game state, and submit actions during their
turn. For a game intended to run on a local table this may be acceptable, but no
defence-in-depth (firewall rules, bind address restriction, pre-shared token) is
present or documented.

**Recommendation:** At minimum bind the server to `127.0.0.1` for local-only use
(`embeddedServer(Netty, host = "127.0.0.1", port = port)`). For networked play, add
a pre-shared token checked in a Ktor plugin before the WebSocket upgrade, or put the
server behind a reverse proxy that enforces access control.

---

### [HIGH] Internal exception messages leaked to clients

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:45-46`

**Issue:** The catch block inside the message loop sends `e.message?.take(120)` as
the `details` field of a `BAD_REQUEST` error frame:

```kotlin
ErrorMessage(message = ErrorCode.BAD_REQUEST, details = e.message?.take(120))
```

Kotlin serialisation exceptions routinely include class names, field names, and
unexpected-token details. A client fuzzing the WebSocket can extract internal type
names and enumerate which fields are required or malformed, giving useful information
for deeper attacks.

**Recommendation:** Replace with a fixed string such as `"Malformed message"`. Log
the full exception server-side at DEBUG level for diagnostics.

---

### [HIGH] No limit on concurrent WebSocket sessions (resource exhaustion)

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:18`

**Issue:** `sessions: LinkedHashSet<DefaultWebSocketServerSession>` grows without
bound. Every connecting client is added in `register()` and broadcast targets include
all of them. An attacker can open thousands of idle WebSocket connections, filling
memory and causing `broadcastWithRoles` to block on a long list of sends.

**Recommendation:** Reject connections beyond a configurable cap (e.g. 20) in
`register()`, sending a close frame with a policy-violation code before adding to
the set.

---

### [HIGH] `disconnectedDeckerNames` is never pruned (unbounded memory leak)

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:25`

**Issue:** `disconnectedDeckerNames: HashSet<String>` accumulates every name that
has ever connected and disconnected. It is only trimmed when the same name reconnects
(`disconnectedDeckerNames.remove(name)`). In a long-running session with many
one-time players, or under a trivial script that registers and disconnects with
distinct names in a loop, the set grows without bound.

**Recommendation:** Wrap entries with a timestamp and evict names older than a
configurable TTL (e.g. 10 minutes), or cap the set at a small fixed size (e.g. 50
names) and drop the oldest on overflow.

---

### [MEDIUM] Unvalidated integer parameters flow from client into game logic

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:229,233`

**Issue:** Two `ActionParams` fields are forwarded to game functions without range
checks:

- `p?.inactivitySeconds ?: 0` → `decker.nullOperation(host, inactivitySeconds, diceRoller)`
- `p?.scannerDeviceRating ?: 0` → `decker.tapComcall(host, scannerDeviceRating, diceRoller)`

A client sending `inactivitySeconds = Int.MAX_VALUE` (2 147 483 647) or
`scannerDeviceRating = Int.MAX_VALUE` could trigger integer overflow or unreasonably
long computation inside those functions depending on how the values are used
downstream. The `ActionParams` DTO (`Messages.kt:52,55`) also declares both as
plain `Int?` with no annotation-level constraint.

**Recommendation:** Clamp both values to a game-valid range before forwarding
(e.g. `inactivitySeconds` to `0..3600`, `scannerDeviceRating` to `0..12`). Add
the clamps at the `dispatchHostOperation` call sites.

---

### [MEDIUM] No character-set restriction on decker name (log injection)

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:37`

**Issue:** `receiveJoin` only rejects names longer than 32 characters. Names may
contain newlines, ANSI escape sequences, Unicode control characters, or HTML
fragments. Because decker names appear verbatim in server-side log/error strings
(e.g. `"No controller registered for decker ${decker.name}"`,
`WebSocketDeckerController.kt:59`) and in broadcast `ResultMessage.details` strings
visible to all clients, a crafted name can pollute server logs or inject content
into the narrative display of every connected browser.

**Recommendation:** Validate that `deckerName` matches a restricted pattern such
as `^[A-Za-z0-9 _\-]{1,32}$` before accepting the join. Reject with
`NAME_TOO_LONG` (or a new `INVALID_NAME` error code) if it fails.

---

### [MEDIUM] WebSocket Origin header not validated

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:28`

**Issue:** Ktor's `webSocket` handler does not validate the HTTP `Origin` header
during the WebSocket upgrade handshake. Any web page loaded in a browser (on any
origin) can open a WebSocket to `ws://localhost:8080/decker/ws` and fully
participate in the game. This enables drive-by cross-origin attacks if a player
visits a malicious site while the game server is running.

**Recommendation:** Install Ktor's `WebSockets` plugin alongside a custom
`createWebSocketExtensions` check, or add a pre-routing plugin that rejects
upgrades whose `Origin` header does not match a whitelist (e.g. `localhost:8080`,
`127.0.0.1:8080`).

---

### [MEDIUM] No rate limiting on incoming WebSocket messages

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31-50`

**Issue:** The `for (frame in incoming)` loop processes frames as fast as the client
sends them. A malicious or buggy client can flood the server with `join` or `action`
messages, causing lock contention in `SessionRegistry` and starving legitimate
clients. There is no per-connection message-rate cap, no maximum frame size
configuration, and no backpressure.

**Recommendation:** Configure `WebSockets { maxFrameSize = 64 * 1024 }` to cap
individual frame size. Add a per-session token-bucket or sliding-window counter in
the frame loop; send a `BAD_REQUEST` error and close the connection if the rate
is exceeded.

---

### [LOW] 120-second action timeout blocks the game-loop coroutine

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:39,74`

**Issue:** `withTimeoutOrNull(actionTimeoutSeconds * 1000L)` defaults to 120 seconds.
During that window `runBlocking` holds the calling thread. If the server runs the
game loop on a single thread (the common case with `runBlocking`), no other decker
can take a turn. A client that connects, gets promoted to `ACTIVE_CONTROLLER`, and
then simply stops sending can suspend the entire game for two minutes. While this is
ultimately a DoS of a game session rather than a data breach, it can be triggered
by any registered decker.

**Recommendation:** Reduce the default timeout to a value matching the game's
intended pace (e.g. 30 seconds). Expose it as a configurable parameter so GMs can
adjust per session.

---

### [LOW] Client-side role enforcement is cosmetic only

**File:** `frontend/src/App.tsx:81`, `frontend/src/components/ActionsPanel` (inferred)

**Issue:** `isActiveTurn={role === 'active_controller'}` gates the actions UI but
a client can bypass this by sending a raw `action` WebSocket frame at any time. The
server's `receiveAction` correctly rejects non-controllers with `NOT_YOUR_TURN`,
so there is no game-state impact — but the UI check provides false assurance.

**Recommendation:** No code change needed; the server-side enforcement is correct.
Add a comment near the UI gate making the layered trust explicit so future
contributors do not over-rely on the client check.

---

### [LOW] No HTTP security headers on static resource serving

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:27`

**Issue:** `staticResources("/", "static")` serves the React bundle with no
`Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, or
`Referrer-Policy` headers. This matters most if the server is ever exposed beyond
localhost.

**Recommendation:** Install Ktor's `Headers` plugin to append security headers to
all responses:

```kotlin
install(DefaultHeaders) {
    header("X-Content-Type-Options", "nosniff")
    header("X-Frame-Options", "DENY")
    header("Content-Security-Policy", "default-src 'self'; connect-src 'self' ws: wss:")
}
```

---

### [INFO] No server-side TLS enforcement

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:59-62`

**Issue:** The server binds plain HTTP/WS on the configured port. TLS is not
configured in the embedded server. Whether a reverse proxy provides TLS is outside
the codebase. The frontend WebSocket hook correctly selects `wss:` when loaded over
HTTPS, but if no proxy exists all traffic is unencrypted.

**Recommendation:** Document the expected deployment topology (proxy vs. direct).
For LAN play this is low risk; for internet-facing use, add a TLS configuration
block or mandate a proxy.

---

## No Issues Found In

- **Action dispatch trust model** — The server generates `availableActions` and
  sends them to the client; the client returns only an integer index. The server
  re-derives the chosen action from its own list, never trusting client-provided
  action data. `getOrNull` bounds-checks the index before use.
  (`WebSocketDeckerController.kt:93-98`)

- **Turn-ownership enforcement** — `receiveAction` atomically checks
  `session == activeController` and `pendingAction` non-null before completing the
  deferred. The TOCTOU race between promote and receive is explicitly addressed by
  setting `pendingAction` before `promoteForTurn`. (`SessionRegistry.kt:127-140`,
  `WebSocketDeckerController.kt:53-55`)

- **`precision` string deserialisation** — `QueryPrecision.valueOf(it)` is wrapped
  in `runCatching`, so unknown enum strings silently default to `NORMAL` with no
  crash or injection. (`WebSocketDeckerController.kt:243`)

- **`editFile` content size cap** — A 4 096-byte hard limit is checked before
  forwarding `newContent` to the game layer. (`WebSocketDeckerController.kt:201-203`)

- **Disconnected-decker pending-action cancellation** — `deregister` atomically
  reads and nulls `pendingAction`, then calls `completeExceptionally` outside the
  lock, preventing a stuck future if the active controller disconnects.
  (`SessionRegistry.kt:61-81`)

- **WebSocket URL construction** — The frontend derives the WebSocket protocol from
  `window.location.protocol`, so it automatically uses `wss:` when loaded over
  HTTPS. (`useWebSocket.ts:79`)
