---
# Concurrency Review — Complete System (Cross-Cutting)

## Summary

The system has a single shared-state boundary: the `SessionRegistry` is accessed from both the blocking game-loop thread (via `runBlocking` inside `WebSocketDeckerController.action`) and from Ktor's coroutine-based WebSocket sessions. Two separate, uncoordinated synchronisation mechanisms guard that boundary — `synchronized(lock)` for session maps and `@Volatile` for `pendingAction` — and neither covers the full transaction that spans both. This creates a family of TOCTOU races at the seam between the game engine and the WebSocket server. On top of that, `runBlocking` + `CompletableFuture.get` inside `action()` make the design brittle if the game loop is ever moved into a coroutine context. The UI contract is consistent and the DTO snapshot-before-broadcast approach is architecturally sound; the problems are confined to the server/game-logic seam.

---

## Findings

### [CRITICAL] `promoteForTurn` and `pendingAction` assignment are not atomic — active controller can send an action into a null future

**Parts Affected:** server / game_logic
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53–71` and `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107–117`

**Issue:** `promoteForTurn` sets `activeController` under `lock` (SessionRegistry:66–68) and immediately sends a `control { role: active_controller }` frame to the client (SessionRegistry:69–72). The `pendingAction` future is only assigned *after* the `runBlocking { promoteForTurn(...) }` call returns, on the very next line (`registry.pendingAction = future`, WebSocketDeckerController:71). There is a real window — large enough for a round-trip over localhost — between the promotion message reaching the browser and the future being visible in `pendingAction`. If the UI is fast (or the network is looped back), `receiveAction` can arrive in that window. `pendingAction` is still `null`, so `receiveAction` sends `no_action_pending` back to the player who was just told it is their turn.

**Recommendation:** Assign `pendingAction` *before* calling `promoteForTurn`, or fold both writes into a single `synchronized(lock)` block so the future is visible to `receiveAction` before the client learns it is the active controller. A clean split: create the future, write `pendingAction`, then promote; promotion is the signal to the client that the future is ready.

**Resolution (Phase 1.6):**
`WebSocketDeckerController.kt` now assigns `registry.pendingAction` before calling `runBlocking { registry.promoteForTurn(...) }`, closing the window between the client being told it is active and the future being ready to receive its action.

---

### [CRITICAL] `receiveAction` checks `pendingAction` and `activeController` under two separate locks — TOCTOU race on double-check

**Parts Affected:** server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:108–117`

**Issue:** The method reads `pendingAction` on line 108 (a bare volatile read, outside any lock), then acquires `lock` to check `activeController` on line 109, then checks `future.isDone` on line 113, then calls `future.complete(cmd)` on line 117. None of these steps is part of the same atomic transaction. Two independent races exist:

1. A client with the correct session could send two rapid action frames. Both coroutines read the same non-null, not-done future, both pass the `activeController` check, and both call `future.complete(cmd)`. `CompletableFuture.complete` is internally safe — only the first call succeeds — but the second caller receives no acknowledgement and no error. The game silently drops a user interaction.

2. The game thread's `finally` block writes `registry.pendingAction = null` concurrently with a `receiveAction` that has already read the reference but has not yet called `complete`. This specific race is benign because `complete` on a cancelled future returns `false`, but the session gets no error message for what it legitimately submitted.

**Recommendation:** Protect the entire check-then-complete transaction with `lock` (or replace `CompletableFuture` with a `Channel<ActionCommand>` that is naturally coroutine-safe). Specifically:

```kotlin
suspend fun receiveAction(session: DefaultWebSocketServerSession, cmd: ActionCommand) {
    val accepted = synchronized(lock) {
        if (session != activeController) return@synchronized null to "not_your_turn"
        val f = pendingAction
        if (f == null || f.isDone) return@synchronized null to "no_action_pending"
        f to null          // return future + no error
    }
    val (future, error) = accepted
    if (error != null) { session.send(...error...); return }
    future!!.complete(cmd)
}
```

**Resolution (Phase 4.1):**
`SessionRegistry.kt` now captures `pendingAction` inside the same `synchronized(lock)` block as the `activeController` check, making the read-authorize-complete sequence atomic and eliminating the TOCTOU race.

---

### [HIGH] Unhandled exception in `dispatch` leaves `activeController` permanently set

**Parts Affected:** server / game_logic
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:91–119`

**Issue:** The `finally` block (line 91) only clears `pendingAction`. If `dispatch` (line 105) or `context.applyDeckerOperationResult` (line 107) throws an unexpected runtime exception, the function exits through `finally`, `pendingAction` is nulled, but `demoteAfterTurn` is never called and `activeController` remains pointing at the now-former active session. Until the *next* `promoteForTurn` call overwrites it (which is the next turn), any action that session sends will pass the `session == activeController` guard but hit `no_action_pending` (because `pendingAction` is null). More dangerously, `broadcastWithRoles` during the next turn will still tag this stale session as `active_controller` in the snapshot taken before `promoteForTurn` runs, sending a confusing role message to the wrong client.

**Recommendation:** Wrap the dispatch-and-apply block in a try/finally that unconditionally calls `registry.demoteAfterTurn(decker.name)`:

```kotlin
try {
    val result = dispatch(chosen, cmd, diceRoller)
    decker = result.decker
    context.applyDeckerOperationResult(oldDecker, decker)
    runBlocking { registry.broadcast(...result...); registry.demoteAfterTurn(decker.name) }
} catch (e: Exception) {
    runBlocking { registry.broadcast(...error details...); registry.demoteAfterTurn(decker.name) }
    throw e
}
```

**Resolution (Phase 1.4):**
`WebSocketDeckerController.kt` now wraps the dispatch-and-apply block in a try/catch. On exception, it broadcasts an error `ResultMessage` and unconditionally calls `demoteAfterTurn`, ensuring `activeController` is always cleared and the game loop can continue regardless of what happens inside `dispatch`.

---

### [HIGH] `runBlocking` + `CompletableFuture.get` blocks Ktor's thread pool for up to 120 seconds

**Parts Affected:** server / game_logic
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53,73,76,78–88,109–117`

**Issue:** `WebSocketDeckerController.action` calls `runBlocking { ... }` five separate times and then calls `future.get(actionTimeoutSeconds, TimeUnit.SECONDS)` (default 120 s). `runBlocking` inside a function that is called via a coroutine dispatcher (Ktor's Netty dispatcher is coroutine-based) either steals a thread from the pool for the duration or, if it shares an event loop, deadlocks. Even in the current wiring where `action()` is called from a non-coroutine context (`Game.runCombatTurn` or `runOutOfCombatTurn`), the game thread is blocked for the full action timeout. If the game is ever started inside a `launch { }` block or the Ktor application coroutine scope, the 120-second `get()` will starve the dispatcher.

**Recommendation:** Make `action()` a `suspend` function that uses `withTimeout` + `Channel<ActionCommand>` instead of `CompletableFuture.get`. This lets Ktor handle the game loop as a properly suspended coroutine and removes all `runBlocking` call sites in the controller.

---

### [HIGH] `GameContext.deckers` and `GameContext.activeIc` are unsynchronised `MutableList` shared across the game/server boundary

**Parts Affected:** game_logic / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:13–16` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:50–51`

**Issue:** `deckers` and `activeIc` are plain `MutableList` fields on `GameContext`. `WebSocketDeckerController.action` reads `decker.visibleObjects()` and `decker.availableActions()` (lines 50–51), then later calls `context.applyDeckerOperationResult` (line 107), which calls `updateDecker` (a `replaceAll` on `deckers`) and `checkTriggers` (`activeIc.addAll`). All of this runs on the game thread today, but the `GameContext` reference is owned by `Game`, and nothing prevents a future HTTP diagnostic endpoint or a second concurrent `Game.run*Turn` call from accessing these lists simultaneously. There is no visibility guarantee for the stale `Decker` reference inside `WebSocketDeckerController.decker` if the field were ever read from another thread.

**Recommendation:** Keep all mutation of `GameContext` on a single designated "game thread" or coroutine, and document this invariant with `@GuardedBy` annotations or a `@MainThread`-equivalent marker. Replace `MutableList` with thread-confined collections and add a check (assertion or `ThreadLocal`) that all writes occur on the expected thread.

---

### [MEDIUM] `pendingAction` and `activeController` are guarded by two different mechanisms with no composite atomicity

**Parts Affected:** server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:15–22`

**Issue:** `activeController` is guarded by `synchronized(lock)`. `pendingAction` is declared `@Volatile` with no lock. Code that needs to reason about both (e.g., "is there a pending action AND is this the active controller?") must acquire `lock` for one and rely on volatile semantics for the other. Because a `@Volatile` read is not part of the `synchronized` block, the two values can be observed in an inconsistent combination: `activeController == session` can be true while `pendingAction` is simultaneously being nulled by the game thread's `finally` block, or vice versa. The current code paths happen to be safe in isolation but the invariant is invisible and fragile.

**Recommendation:** Move `pendingAction` inside `lock` discipline (declare it as a regular field, read and write it only inside `synchronized(lock)` blocks) or migrate both to a single `Mutex`-guarded data class. This makes the composite invariant explicit and eliminates the need to reason about happens-before across two separate mechanisms.

**Resolution (Phase 4.3):**
Deferred — full `Mutex` migration replacing all `synchronized` blocks is a larger refactor deferred to a future track. The TOCTOU races were addressed in Phases 4.1 and 4.2 by bringing `pendingAction` reads inside the existing `synchronized(lock)` blocks.

---

### [MEDIUM] `broadcastWithRoles` sends per-session serialisation outside the lock — role can change between snapshot and send

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:92–105`

**Issue:** The role for each session is determined inside `synchronized(lock)` (line 93–100), which correctly snapshots `activeController` at one point in time. However, `MatrixJson.encodeToString(base.copy(role = role))` and `session.send(...)` execute *outside* the lock in a sequential loop (lines 102–104). Because `send` is a suspend call, it can yield between iterations. If `demoteAfterTurn` or `promoteForTurn` runs on the game thread while the loop is mid-flight (possible via `runBlocking` on a different OS thread), an early session in the loop receives a role that is already stale by the time the message is delivered. For a single-game scenario this is a low-probability cosmetic issue, but in a multi-decker combat turn it can cause a session to believe it is `active_controller` when it is not.

**Recommendation:** Serialise all messages inside the `synchronized` block (encoding is cheap) and only call `send` outside with pre-built `Frame.Text` objects. This removes the window between role assignment and serialisation.

---

### [MEDIUM] `SessionRegistry.deregister` clears `activeController` under `lock` but signals the future outside the lock — missed-signal gap

**Parts Affected:** server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:51–63`

**Issue:** `deregister` sets `wasController = true` and `activeController = null` inside `synchronized(lock)` (lines 53–59), then calls `pendingAction?.completeExceptionally(DeckerDisconnectedException())` *outside* the lock (line 61). Between releasing `lock` and completing the future, `receiveAction` on another coroutine could race in, read `pendingAction` (still non-null), check `activeController` (now null, so `session != null` is true), and return `not_your_turn` without completing the future. The future is then completed exceptionally one moment later, but the ordering guarantee disappears: the disconnect handler in `WebSocketDeckerController` fires, but any concurrently-arriving action frame from a second client that arrived in the gap has already been rejected with the wrong error code.

**Recommendation:** Either complete the future inside `synchronized(lock)` (CompletableFuture.completeExceptionally is thread-safe), or read and null `pendingAction` atomically inside the lock and then complete it outside, so nothing else can observe the intermediate state.

**Resolution (Phase 4.2):**
`SessionRegistry.kt` now reads and nulls `pendingAction` inside the `synchronized(lock)` block alongside the `activeController` clear, then completes the future outside the lock, preventing any race between disconnect handling and concurrent action delivery.

---

### [LOW] `WebSocketDeckerController.interrogationStates` is accessed only on the game thread, but has no thread-confinement annotation

**Parts Affected:** game_logic / server
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:47`

**Issue:** `interrogationStates` is a plain `mutableMapOf()`. It is only ever read and written inside `dispatch` / `locateWithState`, both of which execute on the game thread. This is safe today, but there is no annotation or comment expressing this invariant. If a future "cancel current interrogation" message from the client were handled on the WebSocket coroutine, the map would have unsynchronised concurrent access.

**Recommendation:** Add a `// @GuardedBy("game thread only")` comment, or extract the map into a class that asserts single-thread access.

---

### [LOW] TypeScript `ResultMessage` marks `deckerSuccesses` and `hostSuccesses` as optional; Kotlin always sends them as non-null

**Parts Affected:** server / ui
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:41–46` and `frontend/src/types/messages.ts:84–90`

**Issue:** The Kotlin `ResultMessage` declares `deckerSuccesses: Int` and `hostSuccesses: Int` as non-nullable; the server always serialises them. The TypeScript counterpart declares them `deckerSuccesses?: number` (optional). Any UI code that guards on `if (msg.deckerSuccesses !== undefined)` will behave correctly, but any code that assumes absence as meaningful (e.g., defaulting to zero when undefined) could silently misinterpret a zero sent by the server as a missing field. This is a contract drift that will compound as the protocol evolves.

**Recommendation:** Remove the `?` optionality from `deckerSuccesses` and `hostSuccesses` in `messages.ts` to match the server schema exactly.

---

## Clean Seams

- **DTO snapshot is immutable and taken before broadcasting.** `WebSocketDeckerController.action` captures `decker.availableActions()` into a local `availableActions` val before setting `pendingAction`, and the `actionIndex` from the client is validated against that same snapshot. Because `Decker` is a Kotlin `data class` (value semantics), the DTO snapshot cannot be mutated between broadcast and validation.

- **`broadcast` / `broadcastWithRoles` use a session snapshot under lock.** Taking `sessions.toList()` inside `synchronized(lock)` before iterating means session deregistrations that happen concurrently do not cause `ConcurrentModificationException`; the worst outcome is a `send` failure that is swallowed by `runCatching`.

- **CompletableFuture completion is internally thread-safe.** Even where the TOCTOU races exist around checking `isDone`, calling `complete` or `completeExceptionally` concurrently on a `CompletableFuture` will not corrupt state — exactly one call wins, the rest are no-ops. The bugs above are about missing acknowledgements and incorrect error messages, not about crashes or state corruption.

- **The UI uses a pure reducer.** `useWebSocket` processes all server messages through `useReducer`, which is React-serialised and never races with itself. The UI state is always a consistent snapshot of the last received message per type; there is no client-side mutation of game state outside the reducer.

- **Single WebSocket endpoint, single join flow.** `receiveJoin` correctly rejects duplicate registration under `lock`, and the name-uniqueness check and the session-to-name binding are a single atomic operation, preventing two sessions from registering the same decker name.
---
