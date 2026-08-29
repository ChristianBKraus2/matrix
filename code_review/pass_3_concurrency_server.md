# Concurrency Review — server

## Summary

The server layer uses a clean two-mutex design: `SessionRegistry` owns a `Mutex` for session maps and `TurnCoordinator` owns a separate `Mutex` for turn state. The two locks are never held simultaneously, which eliminates classical deadlock. The DTO layer is stateless and concurrency-free. The main problems are: a narrow but impactful race window in `promoteForTurn` that can leave a turn stuck for up to 120 seconds when a decker disconnects at the wrong moment; a two-phase read in `broadcastWithRoles` that can assign roles inconsistently; and a broad `catch (Exception)` that will suppress coroutine cancellation in the turn-dispatch path.

## Findings

### [HIGH] Disconnect-before-setActive race leaves active turn hung for 120 seconds

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:106-107
**Issue:** `promoteForTurn` releases `SessionRegistry.mutex` after looking up the session, then calls `turns.setActive(session)` in a separate lock acquisition on `TurnCoordinator`. If the decker disconnects in that gap, `deregister` runs first: it removes the session from the registry maps and calls `turns.cancelIfActive(session)`, but `activeController` is still `null` at that moment, so `cancelIfActive` returns `null` and the pending `CompletableDeferred` is never cancelled. `promoteForTurn` then calls `turns.setActive(session)` and installs the now-dead session as the active controller. The deferred set by `setPendingAction` will never be completed; `withTimeoutOrNull` in `WebSocketDeckerController.action()` drains the full 120-second timeout before the game turn proceeds.
**Recommendation:** After `turns.setActive(session)`, re-check under `SessionRegistry.mutex` that the session is still registered. If the re-check fails, call `turns.setActive(null)` and return `false`, so the caller's `setPendingAction(null)` cleanup path fires immediately:
```kotlin
turns.setActive(session)
val stillRegistered = mutex.withLock { deckerSessions[deckerName] == session }
if (!stillRegistered) {
    turns.setActive(null)
    return false
}
```
This reduces the window to near-zero. For a fully atomic solution, extract a combined `tryPromote(deckerName)` operation that performs the map lookup and `setActive` inside a single `SessionRegistry.mutex` acquisition, with `TurnCoordinator` state updated through a callback rather than a separate coroutine call.

**[DEFERRED]** — Post-`setActive` re-check not implemented; the specific race window remains; out of scope for this session.

---

### [MEDIUM] broadcastWithRoles reads active controller and session list in two separate lock acquisitions

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:130-131
**Issue:** `broadcastWithRoles` calls `turns.currentController()` (acquires and releases `TurnCoordinator.mutex`) and then `mutex.withLock { sessions.map { ... } }` (acquires `SessionRegistry.mutex`). Between these two acquisitions the active controller can change — a decker could be demoted or a new one promoted. The resulting snapshot therefore assigns `ACTIVE_CONTROLLER` to a session that is already demoted, or assigns `REGISTERED_DECKER` to the newly promoted session, producing an inconsistent state message.
**Recommendation:** Move the controller lookup into the `SessionRegistry.mutex` block. Because `TurnCoordinator.currentController()` is a suspend function, the simplest safe approach is to add a non-locking accessor (`fun currentControllerUnsafe(): DefaultWebSocketServerSession?`) on `TurnCoordinator` for use when the `SessionRegistry.mutex` is already held, or to cache the active controller reference directly in `SessionRegistry` under its own mutex so that both pieces of state are read atomically.

**[RESOLVED]** — Fixed in `SessionRegistry.kt`: `currentControllerUnsafe()` accessor added to `TurnCoordinator`; called inside the `SessionRegistry.mutex` block.

---

### [LOW] `catch (Exception)` in `action()` swallows coroutine CancellationException

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:76
**Issue:** The outer `try/catch` in `action()` has a `catch (e: Exception)` arm. `kotlinx.coroutines.CancellationException` is a subclass of `Exception`. If the enclosing coroutine scope is cancelled while `deferred.await()` is suspended, the `CancellationException` is caught, `broadcast` and `demoteAfterTurn` are called (which will themselves throw `CancellationException` again on a cancelled scope), and the cancellation propagates only indirectly. Code that runs between the catch and the next suspend point executes when it should not.
**Recommendation:** Add `catch (e: CancellationException) { throw e }` before the generic `Exception` handler, or narrow the catch to specific expected exception types (`IOException`, application-specific exceptions).

**[RESOLVED]** — Fixed in `WebSocketDeckerController.kt`: `CancellationException` is now re-thrown before the general `catch (e: Exception)` handler.

---

### [LOW] `WebSocketDeckerController.decker` is a plain `var` read and written across suspension points

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:39
**Issue:** `decker` is declared `var decker: Decker = initialDecker` with a private setter. It is written at lines 100 and 104 after dispatch completes and read at the top of `action()` before the suspension point. While today only the single game-loop coroutine calls `action()`, the field has no visibility annotation. Any future code that reads `decker` from a different coroutine or thread (for logging, stats, or a second action path) would have no guaranteed-visible value.
**Recommendation:** Annotate `@Volatile var decker`, or wrap in an `AtomicReference<Decker>` if compare-and-swap semantics are ever needed. This is a low-cost defensive measure that also documents the intent.

**[DEFERRED]** — `decker` field not annotated `@Volatile`; out of scope for this session.

---

## No Issues Found In

- `TurnCoordinator` — all mutable fields (`activeController`, `pendingAction`) are accessed exclusively under `mutex.withLock`; `cancelIfActive` atomically extracts and clears both fields in a single lock acquisition; no lock ordering problem with `SessionRegistry` because the two mutexes are never nested.
- `SessionRegistry.register` — connection count check and `sessions.add` are atomic under `mutex`; `ControlMessage` send happens outside the lock, which is correct since the session reference is stable.
- `SessionRegistry.receiveJoin` — all map mutations (`deckerSessions`, `sessionDecker`, `disconnectedDeckerNames`, `reconnectTokens`) occur inside a single `mutex.withLock` block; token comparison and registration are atomic.
- `SessionRegistry.deregister` — correctly releases `SessionRegistry.mutex` before calling `TurnCoordinator`, avoiding lock inversion; the returned deferred is cancelled outside the lock, which is safe.
- `SessionRegistry.broadcast` — takes a list snapshot under the mutex and iterates outside; each `send` is wrapped in `runCatching`, preventing one failed session from blocking others.
- `MatrixServer` (WebSocket frame loop) — each WebSocket connection processes frames sequentially in a single coroutine; no shared mutable state is accessed directly.
- DTO classes (`Messages.kt`, `DeckerStateDto.kt`, `AvailableActionDto.kt`, `MatrixObjectDto.kt`) — pure immutable data and serialization; no concurrency concerns.
- `DeckerDisconnectedException` — trivial marker class; no state.
