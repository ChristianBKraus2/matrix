---
# Concurrency Review — server

## Summary

The server uses a hybrid concurrency model: a Ktor coroutine-based WebSocket layer sits alongside a blocking game-loop thread that bridges into the coroutine world via `runBlocking`. The `SessionRegistry` correctly releases its `synchronized(lock)` monitor before every suspension point, so there are no coroutine-deadlocks from held monitors. However, the two key shared fields — `activeController` (guarded by `lock`) and `pendingAction` (guarded only by `@Volatile`) — live in separate synchronization domains, producing a set of check-then-act gaps between authorization verification and future completion. Additionally, every `runBlocking` call in `WebSocketDeckerController.action()` is a latent deadlock: if the game loop is ever dispatched onto a coroutine thread that shares a bounded pool with Ktor's own coroutines, the blocking wait on `future.get()` will starve the dispatcher before any of the queued sends can complete.

## Findings

### CRITICAL — `runBlocking` in `action()` risks deadlock if game loop runs on a coroutine dispatcher
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53
**Issue:** `action()` is a plain (non-suspend) blocking method that calls `runBlocking` at least six separate times (lines 53–56, 73, 78–81, 85–87, 97–100, 109–117). Each `runBlocking` call blocks the calling thread until all internal suspend calls (`registry.promoteForTurn`, `registry.broadcastWithRoles`, `session.send`, etc.) complete. If `action()` is ever dispatched via `launch` or `async` onto `Dispatchers.Default` or `Dispatchers.IO`, the blocked thread is held for the entire player-action timeout (`actionTimeoutSeconds`, default 120 s). If Ktor's Netty worker coroutines need a thread from that same pool to deliver the WebSocket frames that would unblock `future.get()`, the whole pipeline deadlocks. Ktor's send pipeline and incoming frame dispatch both ultimately funnel through coroutines, so the risk is real whenever the game loop is not guaranteed to run on a dedicated, non-coroutine thread.
**Recommendation:** Declare `action()` as a `suspend` function and remove all `runBlocking` wrappers. Await `future.get(...)` via a non-blocking bridge such as `withContext(Dispatchers.IO) { future.get(timeout, unit) }` or replace `CompletableFuture` entirely with a `kotlinx.coroutines.CompletableDeferred<ActionCommand>`. Ensure through documentation or thread-factory configuration that if `action()` must remain blocking, it is only ever called from a dedicated game-loop thread that is not part of any coroutine dispatcher pool.

### HIGH — `activeController` and `pendingAction` live in separate synchronization domains, producing authorization/action-state races
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107
**Issue:** `receiveAction` reads `pendingAction` via a plain volatile read (line 108), then checks `activeController` inside a separate `synchronized(lock)` block (line 109), then re-examines the previously captured future for `isDone` (line 113), and finally calls `future.complete(cmd)` (line 117). These three operations are not atomic with respect to each other. Concretely: (a) if `pendingAction` is set *after* `activeController` is promoted in a racing turn transition, a legitimate controller can read a null future, pass the active-controller check, and be incorrectly rejected with "no_action_pending". (b) Two concurrent WebSocket coroutines for the same session (possible under Ktor's default multi-threaded dispatcher) could both pass the authorization check before either calls `complete()`, resulting in a double-completion attempt — `CompletableFuture.complete()` is safe but only the first wins, and the return value of the second is silently discarded (see also the MEDIUM finding below).
**Recommendation:** Protect `pendingAction` with the same `lock` used for `activeController`. Perform the full read-authorize-act sequence inside a single `synchronized(lock)` block, or replace the compound check with an `AtomicReference<CompletableFuture<ActionCommand>?>` and a compare-and-set to claim the future atomically. At minimum, promote and assign the future atomically in `promoteForTurn` so that a session that passes the `activeController` check is guaranteed to see a non-null, non-done future.

**Resolution (Phase 4.1):**
`SessionRegistry.kt` now captures `pendingAction` inside the same `synchronized(lock)` block as the `activeController` check, so the full authorize-and-act sequence is atomic and no race can yield a spurious `no_action_pending` to a legitimate controller.

### HIGH — `deregister` checks `activeController` inside lock then reads `pendingAction` outside lock (TOCTOU)
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:51
**Issue:** `deregister` sets `wasController = (activeController == session)` and clears `activeController` inside the lock (lines 53–59), then reads `pendingAction` and calls `completeExceptionally` outside the lock (lines 60–62). The window between the lock release and the volatile read is small but real: if the game loop assigns a new `pendingAction` future and calls `promoteForTurn` for a different decker in that window, the stale `wasController == true` flag causes `deregister` to complete the *new* decker's future exceptionally, aborting a turn that belongs to a different, still-connected player.
**Recommendation:** Read and complete `pendingAction` atomically inside the same `synchronized(lock)` block where `activeController` is cleared. Since `completeExceptionally` on a `CompletableFuture` is non-blocking, holding the lock across it is safe (no suspension, no I/O).

**Resolution (Phase 4.2):**
`SessionRegistry.kt` now reads and nulls `pendingAction` inside the `synchronized(lock)` block that clears `activeController`, then completes the future exceptionally outside the lock, closing the window that could have aborted a different decker's turn.

### MEDIUM — return value of `future.complete(cmd)` is silently discarded in `receiveAction`
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:117
**Issue:** `future.complete(cmd)` returns `false` when the future was already completed (e.g., by a concurrent timeout or disconnect that fired between the `isDone` check on line 113 and this call). The return value is not checked, and no error message is sent back to the client. The player's WebSocket client receives no acknowledgement that its action was dropped, leaving it in an undefined waiting state.
**Recommendation:** Check the return value: `if (!future.complete(cmd)) { session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = "action_no_longer_pending")))) }`. This closes the TOCTOU gap in the check (line 113) → act (line 117) sequence and provides client feedback.

### MEDIUM — `pendingAction` written by game-loop thread with no fence relative to `lock`-guarded state
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:71
**Issue:** In `action()`, `registry.pendingAction = future` (line 71) is a plain volatile write that is *not* coordinated with the `synchronized(lock)` that guards `activeController`. `promoteForTurn` is called *before* `pendingAction` is set (line 73 sets the future; `promoteForTurn` is called on line 53). This means a fast client that responds to the `active_controller` control message before the game loop reaches line 71 will enter `receiveAction`, pass the `activeController` check, and read a null `pendingAction`, getting "no_action_pending" spuriously. The sequence should be: set future → set activeController → notify client.
**Recommendation:** Reorder the operation sequence in `action()` so that `registry.pendingAction = future` is assigned before `promoteForTurn` is called. Alternatively, encapsulate the two-step assignment inside a single synchronized block in `SessionRegistry` (a `promoteForTurnWithFuture(deckerName, future)` method that sets both `activeController` and `pendingAction` atomically under `lock` before sending the control frame).

**Resolution (Phase 1.6):**
`WebSocketDeckerController.kt` now assigns `registry.pendingAction` before calling `runBlocking { registry.promoteForTurn(...) }`, ensuring the future is visible to `receiveAction` before the client receives the `active_controller` control message.

### LOW — `decker` field in `WebSocketDeckerController` is a non-volatile plain `var`
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:44
**Issue:** `decker` is reassigned on line 106 after each dispatched operation. If any code path ever reads `decker` from a thread other than the game loop (e.g., a future diagnostic endpoint, a spectator broadcast that serializes live state), the JVM memory model does not guarantee visibility of the latest value without a happens-before edge.
**Recommendation:** Annotate `decker` with `@Volatile`, or access it exclusively through the synchronized section of `SessionRegistry`. Document the invariant that only the game-loop thread may call `action()` and therefore only one thread may read/write `decker`.

### LOW — `interrogationStates` map has no thread-safety annotation or enforcement
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47
**Issue:** `interrogationStates` is a plain `LinkedHashMap` (via `mutableMapOf()`). It is safe today because `action()` is the only entry point and the game loop is expected to be single-threaded per controller. However, nothing prevents a second caller from invoking `action()` concurrently (e.g., test code, a future AI controller wrapper), which would produce a `ConcurrentModificationException` or silently corrupt interrogation progress.
**Recommendation:** Add a `@GuardedBy("single game-loop thread")` comment, or replace with `ConcurrentHashMap` as a cheap defensive measure. Consider adding a runtime `check` at the top of `action()` that asserts single-thread access (e.g., via a `AtomicBoolean` in-flight guard).

### INFO — `runCatching` in WebSocket frame dispatch silently swallows parse and dispatch errors
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29
**Issue:** The `runCatching { }` wrapper around the entire frame-dispatch block (lines 29–35) discards deserialization exceptions, unknown-type frames, and any exception thrown by `receiveJoin` or `receiveAction`. Under concurrent load, a malformed frame or a registry exception will be invisible, making it impossible to distinguish a client bug from a server-side race condition during debugging.
**Recommendation:** Log failures at least at WARN level: `runCatching { ... }.onFailure { logger.warn("frame dispatch failed", it) }`. For production, consider sending an `ErrorMessage` back to the client for deserialization failures so it can surface the issue.

### INFO — No backpressure on `broadcast` / `broadcastWithRoles` send loops
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:85
**Issue:** The broadcast loops call `session.send()` sequentially for every connected session. A slow or unresponsive client will block the loop (each `send` suspends until the frame is enqueued), delaying state delivery to all subsequent sessions. Under many connected observers this compounds. This is a liveness concern rather than a correctness bug, but it interacts with the `runBlocking` issue above: the game-loop thread is blocked for the sum of all send latencies.
**Recommendation:** Use `coroutineScope { sessions.forEach { launch { runCatching { it.send(...) } } } }` to fan out sends concurrently. This bounds total broadcast time to the slowest single client rather than the sum of all clients.

## Clean Areas
- `synchronized(lock)` blocks in all `SessionRegistry` suspend functions correctly release the monitor before every suspension point (`session.send`, etc.), avoiding the classic "monitor held across suspension" deadlock.
- `CompletableFuture.complete()` and `completeExceptionally()` are used correctly; the `CompletableFuture` itself is thread-safe, so concurrent calls from timeout, disconnect, and client response are handled without data corruption.
- `broadcast` takes a snapshot of `sessions` inside the lock and iterates outside, correctly avoiding lock contention during I/O.
- `broadcastWithRoles` computes the full role-assignment snapshot inside a single synchronized block, ensuring all sessions see a consistent view of `activeController` for a given broadcast.
- DTO classes (`DeckerStateDto`, `MatrixObjectDto`, `AvailableActionDto`) are immutable `data class` instances; they are safe to pass across thread boundaries without additional synchronization.
- `deregister` correctly covers the case where the disconnecting session is the active controller and signals the pending future before any new turn can start (modulo the lock-scope issue noted above).
---
