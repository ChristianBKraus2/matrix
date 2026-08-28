# Concurrency Review — complete (cross-cutting)

## Summary

The server uses a single JVM intrinsic lock (`SessionRegistry.lock`) to serialize all mutations of shared WebSocket session state, and a `CompletableDeferred<ActionCommand>` to bridge the blocking game-loop thread and the coroutine-based WebSocket handlers. The game loop runs on a plain thread, blocking inside `runBlocking` during each player turn; incoming client messages complete the deferred from Ktor's coroutine thread pool without acquiring the game-loop thread. `GameContext` and its mutable collections are intentionally kept single-threaded (game-loop only). The overall design is sound and several races that existed in earlier forms (TOCTOU on `pendingAction`/`activeController`, disconnect during deferred completion) are explicitly handled with correct patterns. Two real concurrency defects remain: `session.send()` inside `broadcast()` and `broadcastWithRoles()` has no timeout, meaning a slow or stuck WebSocket client can suspend indefinitely and deadlock the game-loop's `runBlocking` call; and JSON serialization is done while holding the shared lock in `broadcastWithRoles()`, adding unnecessary contention.

---

## Findings

### [HIGH] Unbounded `session.send()` inside `runBlocking` can deadlock the game loop

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107` (broadcast) and `:124` (broadcastWithRoles)

**Issue:** Both `broadcast()` and `broadcastWithRoles()` call `session.send(Frame.Text(text))` in a plain `for` loop wrapped only in `runCatching`. `runCatching` catches exceptions but does nothing about indefinite suspension. Ktor's `DefaultWebSocketServerSession.send()` is a `suspend` function backed by a bounded channel; if a client's outgoing buffer is full (e.g., the client has crashed or is not reading), the call suspends indefinitely. These functions are called from within the `runBlocking { ... }` block of `WebSocketDeckerController.action()`. `runBlocking` blocks its calling thread — the game-loop thread — until every coroutine it owns completes. A single stuck client therefore freezes the entire game loop, blocking all other players' turns for the duration of the session or until the OS closes the TCP connection (which can take minutes under default keepalive settings).

**Recommendation:** Wrap each `session.send()` call with a short timeout:

```kotlin
runCatching { withTimeoutOrNull(5_000L) { session.send(Frame.Text(text)) } }
```

Alternatively, access the underlying `outgoing` channel's `trySend` for a completely non-blocking fire-and-forget, accepting that slow clients simply miss the frame. Either approach eliminates the deadlock vector.

---

### [MEDIUM] JSON serialization held inside `synchronized(lock)` in `broadcastWithRoles`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:112-120`

**Issue:** `broadcastWithRoles` serializes the `StateMessage` for every connected session while holding the intrinsic lock. CPU-bound work (JSON serialization) inside a monitor increases lock-hold time and contention. Any other thread that needs the lock (e.g., a concurrently arriving `receiveAction`, `deregister`, or `register` call) is blocked for the full duration of serialization across all sessions. At the current scale (handful of clients, small messages) this is unlikely to matter in practice, but it is unnecessary coupling.

**Recommendation:** Collect only the lightweight `(session, role)` pairs inside the lock, then serialize outside it:

```kotlin
val pairs: List<Pair<DefaultWebSocketServerSession, SessionRole>> = synchronized(lock) {
    sessions.map { s ->
        val role = when {
            s == activeController        -> SessionRole.ACTIVE_CONTROLLER
            sessionDecker.containsKey(s) -> SessionRole.REGISTERED_DECKER
            else                         -> SessionRole.OBSERVER
        }
        s to role
    }
}
for ((session, role) in pairs) {
    val text = MatrixJson.encodeToString(base.copy(role = role))
    runCatching { session.send(Frame.Text(text)) }
}
```

---

### [MEDIUM] `GameContext` mutable collections have no static concurrency enforcement

**File:** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:16-17`

**Issue:** `deckers: MutableList<Decker>` and `activeIc: MutableList<IC>` are annotated only with a KDoc comment ("Game-loop thread only — no concurrent access"). Nothing in the type system prevents a WebSocket handler or other coroutine from accidentally mutating them. The invariant holds today because `WebSocketDeckerController.action()` only touches `GameContext` after `deferred.await()` returns (i.e., while the game-loop thread still owns `runBlocking`), but this is a fragile implicit contract.

**Recommendation:** At minimum, rename the fields with a naming convention that signals thread-affinity (e.g., `_deckers` with a guarded accessor), or make the lists private with access only through methods that include a thread-ownership assertion:

```kotlin
private val _deckers: MutableList<Decker> = deckers.toMutableList()
// Only call from game-loop thread
val deckers: List<Decker> get() {
    check(Thread.currentThread() == gameLoopThread) { "GameContext accessed off game-loop thread" }
    return _deckers
}
```

Even a debug-mode assertion would catch accidental cross-thread access during development.

---

### [LOW] Error-path `session.send()` in `MatrixServer.kt` also lacks a timeout

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:45-48`

**Issue:** The `catch (e: Exception)` handler inside the WebSocket frame loop calls `this.send(Frame.Text(...))` wrapped in `runCatching`. Like the game-loop broadcast paths, this send has no timeout. If the erroring client's outgoing buffer is full, the coroutine handling that client's frames suspends indefinitely. In this case the game loop is not directly affected (the handler runs on its own coroutine), but the Ktor coroutine thread is held, and crucially the `for (frame in incoming)` loop never resumes — the session stays registered in `SessionRegistry` until the OS eventually times out the TCP connection. The next frame from that client is never processed.

**Recommendation:** Apply the same `withTimeoutOrNull(5_000L)` guard to the error-reply send here.

---

### [LOW] No shutdown or interrupt path in the game loop

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:13-26`

**Issue:** `runOutOfCombatTurn()` and `runCombatTurn()` iterate over deckers/IC and call `action()` sequentially. Each `action()` call blocks for up to `actionTimeoutSeconds` (default 120 s) per actor. There is no cooperative cancellation signal, thread-interrupt check, or overall-turn deadline. If the game loop gets stuck (e.g., a timeout fires but subsequent `broadcast()` hangs on a slow client — see HIGH finding above), there is no external mechanism to recover.

**Recommendation:** Wrap the entire turn in an outer timeout (e.g., a `Thread.interrupt()` mechanism or a structured-concurrency scope with a deadline), and/or address the root cause with per-send timeouts as described above.

---

### [LOW] Frontend `sendAction` has no guard against duplicate submission

**File:** `frontend/src/hooks/useWebSocket.ts:142-150`

**Issue:** `sendAction` sends an `ActionCommand` immediately on every call. If a player double-clicks a button, two identical `action` messages are sent in rapid succession. The server handles the second correctly — the first call completes the `CompletableDeferred`, and the second finds it already completed, returning `NO_ACTION_PENDING`. The player receives an error event. This is functionally safe but creates a confusing UX.

**Recommendation:** Track a "waiting for result" flag in `WsState` and disable the action UI (or guard `sendAction`) after the first action is sent, re-enabling it when the next `state` or `result` message arrives.

---

## No Issues Found In

- **`setPendingAction` before `promoteForTurn` ordering** — The TOCTOU fix (setting `pendingAction` before broadcasting the `ACTIVE_CONTROLLER` role) is correctly implemented and commented. A client that receives the control message and responds immediately will always find the deferred ready.
- **`deregister` completing the deferred outside the lock** — Correctly avoids lock inversion: the lock is released before `completeExceptionally` is called, so no callbacks triggered by completion can deadlock by trying to re-acquire the lock.
- **`receiveAction` atomicity** — Both `activeController` and `pendingAction` are checked in one `synchronized` block, eliminating the TOCTOU window between those two checks.
- **`finally { registry.setPendingAction(null) }`** — Runs on all code paths including early returns from the `DeckerDisconnectedException` catch; clearing `null` over `null` (already cleared by `deregister`) is idempotent and safe.
- **Double-complete on `CompletableDeferred`** — If two `receiveAction` calls race past the `isCompleted` check (possible in theory because `complete()` is called outside the lock), the second `complete()` call is a no-op. `CompletableDeferred` is designed for exactly this.
- **`broadcast()` snapshot pattern** — Takes a list snapshot inside the lock and iterates outside it, correctly avoiding I/O while holding the lock.
- **`sessions`, `deckerSessions`, `sessionDecker`, `disconnectedDeckerNames` mutation** — All mutations go through `synchronized(lock)` consistently across `register`, `receiveJoin`, `deregister`, `promoteForTurn`, `demoteAfterTurn`, and `receiveAction`.
- **Frontend `useWebSocket` ref patterns** — JavaScript is single-threaded; `wsRef` and `pendingNameRef` are `useRef` values accessed only in event handlers and callbacks on the same JS event loop. No race conditions are possible.
- **Frontend reconnect backoff** — Exponential backoff with a 30 s cap is implemented correctly; the reconnect timer is properly cancelled on unmount.
- **`Host`, `Decker`, and other domain objects are immutable `data class`es** — All state transitions return new copies, eliminating shared-mutable-object hazards across the entire domain layer.
