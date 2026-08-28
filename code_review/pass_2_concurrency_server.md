# Concurrency Review — server

## Summary

The server component is built on Ktor WebSockets with kotlinx.coroutines. `SessionRegistry` manages shared mutable state across concurrent WebSocket connections using `synchronized(lock)`, and `WebSocketDeckerController` bridges the synchronous game-engine interface to the async WebSocket layer via `runBlocking`. The locking discipline in `SessionRegistry` is sound — every `synchronized` block releases before any suspension point, preventing the most common coroutine+monitor deadlock — but the _mechanism_ is wrong: `synchronized` inside `suspend` functions violates coroutine idiom and leaves a maintenance trap. The larger structural hazard is `runBlocking` in `WebSocketDeckerController.action()`: it blocks a real OS thread for up to two minutes while waiting for a client action; if that thread ever belongs to Ktor's coroutine dispatcher the entire server can stall or deadlock. One mutable non-volatile field (`decker`) is written without synchronization or `@Volatile` and has cross-turn visibility risk. Everything else — including the `pendingAction` lifecycle, the check-then-act pattern in `receiveAction`, and the ordering of `setPendingAction`/`promoteForTurn` — is correctly reasoned and safe as written.

---

## Findings

### [CRITICAL] `runBlocking` in `action()` blocks a dispatcher thread for up to 2 minutes

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45`

**Issue:** `action()` is a plain (non-`suspend`) interface method that wraps its entire body in `runBlocking { … }`. Inside it suspends for up to `actionTimeoutSeconds` (default 120 s) on `deferred.await()` waiting for the client to send a WebSocket frame. If `action()` is ever invoked from a thread that belongs to Ktor's dispatcher (e.g. `Dispatchers.Default`, or indirectly via a `launch` on the Netty worker pool), `runBlocking` permanently pins that thread. On a typical 2-core CI box `Dispatchers.Default` has two threads; with one pinned, the single remaining thread must handle every incoming WebSocket frame, including the `receiveAction` call that would eventually complete the deferred. Under any back-pressure this degenerates to a deadlock. Even on many-core machines the pinned thread is a resource held for the entire turn duration, contrary to the non-blocking contract of Ktor.

**Recommendation:** Make the game-engine turn loop itself a coroutine (`suspend fun action(…)` or a `Flow`-based design), eliminating `runBlocking` entirely. If the interface cannot be changed, ensure — and document with a comment and an assertion — that `action()` is _always_ called from a dedicated non-dispatcher game-loop thread (e.g. `newSingleThreadContext("game-loop")`). At minimum add a runtime guard:

```kotlin
override fun action(…): ActionResult {
    check(Thread.currentThread().name.startsWith("game-loop")) {
        "action() must not be called from a coroutine dispatcher thread"
    }
    return runBlocking { … }
}
```

---

### [HIGH] `synchronized(lock)` used inside `suspend` functions — should be `Mutex`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:31,41,65,85,95,104,112,129`

**Issue:** Every `suspend` function in `SessionRegistry` uses `synchronized(lock)`. The code is currently safe because no suspension point occurs _inside_ any `synchronized` block — the lock is always released before `session.send()` or any other suspending call. However, this is a maintenance trap:

1. Kotlin's coroutine runtime may resume a coroutine on a different thread after a suspension point. `synchronized` is a JVM monitor: it _must_ be acquired and released on the same thread. If a future refactor adds even a single suspending call inside a `synchronized` block (common when adding a send or a `delay` for debugging), the coroutine can suspend holding the monitor and attempt to resume on a thread that does not own it, causing either a `IllegalMonitorStateException` at unlock time or, if both threads happen to be contending, a deadlock.
2. The Kotlin coroutines documentation explicitly warns against `synchronized` in `suspend` functions for this reason.
3. IDEs emit a lint warning for this pattern (`SYNCHRONIZED_BLOCK_IN_SUSPEND_FUNCTION`) that is currently being suppressed by habit.

**Recommendation:** Replace the `Any()` lock and all `synchronized(lock)` usages with `kotlinx.coroutines.sync.Mutex`. Protect state reads/writes with `mutex.withLock { … }`, which is coroutine-aware, never pins a thread, and is equally exclusive:

```kotlin
private val mutex = Mutex()

suspend fun register(session: DefaultWebSocketServerSession) {
    mutex.withLock { sessions.add(session) }
    session.send(Frame.Text(MatrixJson.encodeToString(ControlMessage(role = SessionRole.OBSERVER))))
}
```

The non-`suspend` `setPendingAction` also uses `synchronized(lock)`; with a `Mutex` it becomes a `suspend fun` or the lock is replaced with `@Volatile` + `AtomicReference`, which is appropriate for a single-writer field.

---

### [MEDIUM] Mutable field `decker` has no visibility guarantee across turns

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:42-43,103,107`

**Issue:** `var decker: Decker` is a plain JVM field. It is written at lines 103 and 107 inside `runBlocking` and read at lines 46–47 in the next call to `action()`. These two calls happen on whichever thread the game loop uses. If the game loop uses a thread pool (e.g. a fixed-thread-pool executor where consecutive turns may run on different threads), the write from turn N is not guaranteed to be visible to the read in turn N+1 without a happens-before edge. The JVM memory model does not guarantee cross-thread visibility for plain field writes unless an explicit synchronization action intervenes.

**Recommendation:** Annotate `decker` with `@Volatile`:

```kotlin
@Volatile
var decker: Decker = initialDecker
    private set
```

This is sufficient because the field is always written by exactly one logical "owner" per turn and never written concurrently.

---

### [MEDIUM] `future!!.complete(cmd)` return value silently ignored — possible double-completion

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:139`

**Issue:** `receiveAction` captures `pendingAction` under the lock (line 131), exits the lock, then calls `future!!.complete(cmd)` (line 139). `CompletableDeferred.complete()` returns `false` if the deferred is already completed. Between the lock release and the `complete()` call, `deregister()` can fire on another coroutine and call `futureToCancel?.completeExceptionally(DeckerDisconnectedException())`. If that wins the race, `future.complete(cmd)` returns `false` and the client's action is silently discarded. The current behaviour (action discarded when disconnect beats the action) is probably correct, but the silent discard makes it harder to diagnose; and the `!!` operator will still throw if `future` is somehow null despite the null check two lines earlier (which is guarded by the lock, so it cannot be null — but the `!!` is still misleading).

**Recommendation:** Check and log the return value, and replace `!!` with a smart cast:

```kotlin
val completed = future.complete(cmd)
if (!completed) {
    // Deferred was already cancelled (e.g. decker disconnected); action discarded.
    session.send(Frame.Text(MatrixJson.encodeToString(ErrorMessage(message = ErrorCode.NOT_YOUR_TURN))))
}
```

---

### [LOW] `broadcastWithRoles` serializes all frames inside the lock

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:112-121`

**Issue:** The `synchronized(lock)` block in `broadcastWithRoles` includes `MatrixJson.encodeToString(base.copy(role = role))` for every session. Serialization is CPU work; holding the lock while serializing N frames blocks all other `SessionRegistry` operations (including `receiveAction`) for the duration. On a small number of sessions this is inconsequential, but it is unnecessary coupling between the lock (which only needs to protect the `sessions` snapshot) and the serialization work.

**Recommendation:** Snapshot the `(session, role)` pairs under the lock, then serialize outside:

```kotlin
val pairs: List<Pair<DefaultWebSocketServerSession, SessionRole>> = mutex.withLock {
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
    runCatching { session.send(Frame.Text(MatrixJson.encodeToString(base.copy(role = role)))) }
}
```

---

### [INFO] `pendingAction` is not `@Volatile` — correct as-is

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:23`

**Issue (verified, not a bug):** `pendingAction` is a plain `var` without `@Volatile`. Every read and write occurs inside `synchronized(lock)` (`setPendingAction` line 27, `deregister` line 75-76, `receiveAction` lines 131-133). The JVM memory model guarantees that a monitor exit (end of `synchronized`) flushes all writes to main memory, and a monitor entry (start of next `synchronized` on the same lock) reads fresh values. Visibility is therefore fully guaranteed by the lock. `@Volatile` is neither needed nor beneficial here.

---

### [INFO] `setPendingAction` / `promoteForTurn` ordering is correct

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53-55`

**Issue (verified, not a bug):** The comment at line 51 explains the deliberate ordering: `setPendingAction(deferred)` is called _before_ `promoteForTurn`. This is correct. `promoteForTurn` sends a WebSocket message that causes the client to become eligible to send an action. If `pendingAction` were set after that message, there would be a window where the client's action arrives and `receiveAction` finds `f == null`, returning `NO_ACTION_PENDING`. By setting the deferred first (while `activeController` is still null), any early action attempt correctly receives `NOT_YOUR_TURN` instead. The ordering is safe and the race is fully closed.

---

### [INFO] `deregister` correctly cancels `pendingAction` atomically

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:64-81`

**Issue (verified, not a bug):** `deregister` reads and nulls `pendingAction` inside the same `synchronized` block that clears `activeController`. The deferred is then cancelled outside the lock via `completeExceptionally`. This is the correct pattern: the state transition is atomic (nothing can observe the intermediate state), and the side effect (waking up the awaiting coroutine) is performed after the lock is released, avoiding any risk of re-entering the lock from the completion callback.

---

## No Issues Found In

- **DTO classes** (`Messages.kt`, `DeckerStateDto.kt`, `AvailableActionDto.kt`, `MatrixObjectDto.kt`) — all are `@Serializable` immutable data classes; inherently thread-safe.
- **`MatrixJson` global instance** (`Messages.kt:7`) — `kotlinx.serialization.json.Json` instances are documented and implemented as thread-safe; sharing a single instance is correct.
- **`MatrixServer.kt` WebSocket handler** — the `for (frame in incoming)` loop processes frames sequentially per connection; no shared mutable state touched outside of `SessionRegistry` calls.
- **`sessions`, `deckerSessions`, `sessionDecker`, `disconnectedDeckerNames`** collections — all accesses are under `synchronized(lock)`; no direct unsynchronized reads exist.
- **`activeController` field** — plain `var`, no `@Volatile`, but every access is under `synchronized(lock)`; memory visibility is guaranteed.
- **`deregister` / `receiveAction` TOCTOU** — `pendingAction` is read and cleared in the same `synchronized` block; no window exists for a stale-pointer double-completion from the lock-protected paths.
