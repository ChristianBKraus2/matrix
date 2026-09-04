# Server Layer Findings

Files reviewed in full: `MatrixServer.kt`, `SessionRegistry.kt`, `TurnCoordinator.kt`,
`WebSocketDeckerController.kt`, `dto/Messages.kt`, `dto/DeckerStateDto.kt`,
`dto/AvailableActionDto.kt`, `dto/MatrixObjectDto.kt`, `DeckerDisconnectedException.kt`, `Main.kt`.

---

## 🔴 S-1 (HIGH) — Client-supplied `hasValidPasscode` bypasses authentication

**Category:** Security / Correctness
**Where:** [Messages.kt:57](../../src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt#L57),
[WebSocketDeckerController.kt:389](../../src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt#L389),
[DeckerOperationsExtensions.kt:595-607](../../src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt#L595-L607),
[AvailableActionDto.kt:74](../../src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt#L74),
[messages.ts:14](../../frontend/src/types/messages.ts#L14)

The client sends `ActionParams.hasValidPasscode: Boolean?`. The controller plumbs it straight into the
domain call:

```kotlin
decker.makeComcall(host, diceRoller, cmd.params?.hasValidPasscode ?: false)
```

and `makeComcall` **skips the passcode System Test and returns a synthetic success** when the flag is true:

```kotlin
if (hasValidPasscode) {
    // System Test skipped
    return SystemTestOutcome(successes = 1, ..., succeeded = true)
}
```

The server even *advertises* the field: `AvailableActionDto.Operation.paramKind = "hasValidPasscode"`
for `MAKE_COMCALL`, telling the client to supply it. **Any client can set `hasValidPasscode: true` and
bypass the check.** The authentication decision lives on the untrusted side of the boundary.

`Decker.knownPasscodes: Set<String>` already exists as the correct **server-side** source of truth.

**Failure scenario:** A player (or any WebSocket client hitting `/decker/ws`) issues a MAKE_COMCALL
action with `params: { hasValidPasscode: true }` on a host they have no passcode for, and gains
authenticated access with no test rolled.

**Fix:** Resolve passcode validity server-side against `Decker.knownPasscodes` (or the host's
passcode ledger). Remove `hasValidPasscode` from `ActionParams`, the DTO `paramKind`, and `messages.ts`.

---

## 🟠 S-2 (MEDIUM) — Client-supplied `scannerDeviceRating`

**Category:** Security / Correctness
**Where:** [Messages.kt](../../src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt),
[WebSocketDeckerController.kt:396](../../src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt#L396),
[AvailableActionDto.kt:76](../../src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt#L76)

Same trust-boundary class as S-1: the client supplies `scannerDeviceRating` for TAP_COMCALL, a value
that feeds the dice mechanic. A client can send an arbitrarily high rating to skew the outcome. The
rating should be derived server-side from the decker's equipment, not accepted from the wire.

**Fix:** Compute the scanner rating on the server; drop the client field.

---

## 🟠 S-3 (MEDIUM) — WebSocket has no ping/timeout configured

**Category:** Concurrency / Resource management
**Where:** [MatrixServer.kt:31-33](../../src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt#L31-L33)

```kotlin
install(WebSockets) { maxFrameSize = MAX_FRAME_SIZE }
```

No `pingPeriod` and no `timeout`. Half-open / dead TCP connections are not detected. Because a decker's
turn blocks on that session's `CompletableDeferred`, a silently-dropped active controller can stall the
turn until the OS TCP timeout (potentially minutes), and disconnected sessions leak in the registry.

**Fix:** Set `pingPeriod` and `timeout` (e.g. 15s / 30s) so Ktor closes dead sessions; that close then
drives `deregister` → `cancelIfActive` and unblocks the turn.

---

## 🟠 S-4 (MEDIUM) — Raw exception text leaked to clients

**Category:** Security (information disclosure)
**Where:** [MatrixServer.kt:64](../../src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt#L64)

```kotlin
details = e.message?.take(256)
```

Internal exception messages (class names, paths, serialization diagnostics) are sent to the client in
the error frame. Should return a generic `details` and log the real message server-side.

---

## 🟠 S-5 (MEDIUM) — No Origin / authentication check on `/decker/ws`

**Category:** Security
**Where:** [MatrixServer.kt](../../src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt)

The WebSocket endpoint accepts any connection with no Origin validation (cross-site WebSocket hijacking
surface) and no authentication. Combined with S-1 this is an unauthenticated control channel. If the
project intends a trusted-LAN single-session demo this is lower risk — but that assumption should be
explicit, because the code currently enforces nothing.

**Fix:** Validate `Origin` against an allowlist; add at least a shared-secret/token handshake if the
server is ever exposed beyond localhost.

---

## 🟡 S-6 (LOW) — `promoteForTurn` ordering race

**Category:** Concurrency
**Where:** [SessionRegistry.kt:117-124](../../src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt#L117-L124)

The ACTIVE_CONTROLLER frame is sent **before** `turns.setActive(session)`. A very fast client that
acts on the frame before `setActive` runs has its action rejected as NOT_YOUR_TURN. Narrow window,
low impact, but the ordering should be inverted (set active, then notify).

---

## 🟡 S-7 (LOW) — `demoteAfterTurn` not in a `finally`

**Category:** Error handling / Concurrency
**Where:** [WebSocketDeckerController.kt](../../src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt) (demotion replicated per success branch; cancellation paths ~L89-90, L136-137)

`demoteAfterTurn` is duplicated across return branches rather than sitting in a single `finally`.
`CancellationException` paths skip it. The disconnect path is covered (deregister →
`DeckerDisconnectedException` cancels the future), but other cancellations can leave `activeController`
set. Consolidate demotion into `finally` around the turn body.

---

## 🔵 S-8 (INFO) — `Main.kt` production loop

**Where:** [Main.kt:46-55](../../src/main/kotlin/com/shadowrun/matrix/Main.kt#L46-L55)

`while (true) { runBlocking { conductTurn(...) } }` with `Thread.sleep(500)` on exception. A persistently
throwing `conductTurn` busy-loops every 500 ms and spams the log; a new `runBlocking` event loop is
created each turn. Single hardcoded decker + host (demo scaffolding). Acceptable for a demo entry point;
note for when multi-decker support lands.

## 🔵 S-9 (INFO) — Decode/encode Json asymmetry

The server encodes with `MatrixJson` (`encodeDefaults = true`) but decodes inbound frames with a plainer
`Json` configuration. Unknown client fields raise a serialization exception (then caught and surfaced via
S-4). Consider a single shared `Json` with `ignoreUnknownKeys = true` for forward compatibility.
