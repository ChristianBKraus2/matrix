# Concurrency Review — complete (cross-cutting)

## Summary

Across all three parts the system is largely sound: `GameContext` and the domain objects are owned exclusively by the game-loop coroutine during a turn, the server layer uses two non-nested mutexes correctly, and the UI is single-threaded with a well-structured reducer. Two cross-cutting defects emerge only when all three parts are read together. First, `WebSocketDeckerController` builds the `StateMessage` from its own stale `decker` field rather than from the current `GameContext` snapshot, so IC damage applied between turns is invisible to the player who acts next — a correctness gap that spans game logic, server, and UI rendering. Second, the two-lock `broadcastWithRoles` race in the server (already flagged as a per-part MEDIUM) has a concrete 120-second cross-layer consequence: the UI renders action availability purely from the `role` field in `StateMessage`, so a mistakenly assigned role silently hides the action panel and the active player cannot submit any action, leaving all connected clients stalled until the server-side 120-second timeout fires. A further cross-cutting concern is that the contract "the game loop is the sole writer of `GameContext`" is implicit and not documented at the API boundary where the server layer receives `context` as a parameter, making the invariant invisible to future contributors.

## Findings

### [MEDIUM] Stale controller decker shown to client when IC actions ran since last decker turn
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:43-68
**Issue:** At the start of `action()`, lines 43-46 build `visibleObjects` and `availableActions` from `this.decker`, and line 64 wraps that into the `StateMessage` sent to all clients. `this.decker` is last written at the end of the *previous* call to `action()` (lines 100 and 104). If one or more IC turns ran after that previous decker turn — updating the decker in `GameContext` via `context.updateDecker()` — the controller's field is stale: it reflects pre-IC health, pre-IC location, and pre-IC suppression state. The `StateMessage` therefore shows the player their character with wrong damage totals, wrong condition-monitor boxes, and potentially wrong available actions. If an IC crashed the decker (setting `currentLocation = null` in context while `this.decker.currentLocation` is still non-null), the stale view would present a full action menu for a decker who has already been jacked out. The cross-cutting path is: game_logic IC `action()` updates `GameContext._deckers` → server `WebSocketDeckerController.decker` is not re-read from context at turn start → UI receives and renders incorrect `DeckerStateDto`.
**Recommendation:** Re-sync `this.decker` from `GameContext` at the very first line of `action()`, before building the state snapshot:
```kotlin
override suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
    // Re-read from context so IC damage applied since last turn is visible.
    decker = context.deckers.firstOrNull { it.name == decker.name } ?: decker
    val visibleObjects = decker.visibleObjects()
    ...
}
```
This mirrors the re-sync already performed at line 104 after the player's own action and requires no structural change.

---

### [MEDIUM] broadcastWithRoles role-assignment race silently hides action panel and causes 120-second stall for all clients
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:130-131
**Issue:** `broadcastWithRoles` reads the active controller from `TurnCoordinator` (first lock) and the session list from `SessionRegistry` (second lock) in two separate acquisitions. In the gap between them the active controller can change, causing the role field in `StateMessage` to be set incorrectly — e.g., `REGISTERED_DECKER` sent to the session that was just promoted. The cross-cutting consequence is concrete: the React UI in `useWebSocket.ts` stores the received `role` in the reducer (line 46-47) and `App.tsx` gates rendering of the action panel on `role === 'active_controller'`. A session that receives `REGISTERED_DECKER` instead of `ACTIVE_CONTROLLER` will not render the action panel; the human player has no way to submit a turn. The server's `TurnCoordinator` continues to wait for `deferred.await()` up to the full `actionTimeoutSeconds` (120 s by default). During that window every connected client sees a stuck game with no feedback, because the server does not send another `StateMessage` until after the timeout fires and the turn is forfeited. The per-part server review already flagged the race; this finding documents the user-visible cross-cutting severity.
**Recommendation:** Same as the server-review recommendation: perform the controller lookup inside the `SessionRegistry.mutex` block by either caching the active controller reference inside `SessionRegistry` under its own mutex, or adding a non-locking `currentControllerUnsafe()` accessor on `TurnCoordinator` for use when `SessionRegistry.mutex` is already held.

---

### [LOW] CancellationException through broad catch triggers UI broadcast during scope shutdown
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:76
**Issue:** The per-part server review flagged `catch (e: Exception)` as swallowing `CancellationException`. The cross-cutting angle: when the catch fires on cancellation, lines 77-79 call `registry.broadcast(...)` and `registry.demoteAfterTurn(...)`. `broadcast` iterates all active sessions and calls `session.send(...)` on a cancelled scope. Each `send` throws `CancellationException` again, which is silently absorbed by the `runCatching` wrapper inside `broadcast`. The net effect is that every connected UI client receives no message at all — no `ResultMessage`, no updated `StateMessage` — when a turn aborts due to server shutdown or scope cancellation. The UI stays in whatever state it was showing (action panel open, previous result visible) with no indication that the server has gone away. The eventual WebSocket close event does trigger `DISCONNECTED` in the reducer, but the missing result leaves the events log incomplete and can cause the client to attempt a reconnect carrying a stale `reconnectToken` and `pendingNameRef` from the interrupted turn.
**Recommendation:** Add `catch (e: CancellationException) { throw e }` before the generic `Exception` handler in both try/catch blocks inside `action()` (lines 72 and 112).

---

### [INFO] "Game loop owns GameContext" contract is implicit at the server/game-logic boundary
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:42, src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:21
**Issue:** `WebSocketDeckerController.action(context: GameContext, ...)` receives a `GameContext` reference and mutates it (line 101: `context.applyDeckerOperationResult(...)`). The only thing preventing a concurrent `SessionRegistry` or `TurnCoordinator` operation from also mutating `context` is an implicit convention that only the game-loop coroutine ever calls `action()`. This convention is correct today but is documented nowhere — not in `GameContext`, not in `WebSocketDeckerController`, not in the `ActiveIcon` interface. Any future server-layer change that passes `context` to a coroutine launched outside the game loop (e.g., for health-check reads, state-sync pushes, or an admin endpoint) would silently introduce unsynchronised concurrent writes against the raw `ArrayList` backing `GameContext._deckers`. The per-part game-logic review flagged `GameContext` itself; this finding notes that the undocumented constraint is load-bearing across the server/game-logic boundary.
**Recommendation:** Add a KDoc comment to `GameContext` and to `ActiveIcon.action()` stating: "All calls must originate from the single game-loop coroutine. `GameContext` has no internal synchronisation." Optionally add a `@GuardedBy("game-loop coroutine")` annotation or a `check` in `applyDeckerOperationResult` that asserts the calling thread name if running in test mode.

---

### [INFO] actionIndex wire protocol relies on an undocumented ordering contract across all three layers
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:54, frontend/src/hooks/useWebSocket.ts:157
**Issue:** The server serialises `availableActions` as an ordered list with each entry carrying an explicit `index` field (0..N-1, assigned by `mapIndexed` in `AvailableActionDto.toDto()`). The client sends back `actionIndex: number` in `ActionCommand`. The server resolves the chosen action via `availableActions.getOrNull(cmd.actionIndex)` (line 90 of `WebSocketDeckerController.kt`), where `availableActions` is the *local snapshot* captured at the start of `action()` — not a fresh call to `decker.availableActions()`. This means the round-trip is safe only when: (a) the client sends the `index` field from the received `AvailableActionDto` rather than a UI-side display position; and (b) the server does not re-generate `availableActions` between sending the `StateMessage` and receiving the `ActionCommand`. Condition (b) is guaranteed by the current sequential design; condition (a) is a client-side requirement that is nowhere stated in the shared type definitions or protocol documentation. If a future UI change re-sorts or re-filters the displayed action list and accidentally sends the display-order position instead of `action.index`, the player would silently execute a different action than selected.
**Recommendation:** Add a comment in `messages.ts` beside `ActionCommand.actionIndex`: "Must be the `index` field from the received `AvailableActionDto`, not a display-order position." Optionally rename the field to `actionDtoIndex` to make the intent explicit.

## No Issues Found In

- The game-loop sequencing guarantee: `Game.runCombatTurn()` calls `action()` on each entry in the initiative list serially, so `GameContext` is never accessed concurrently in the current implementation.
- DTO serialisation boundary (`Messages.kt`, `AvailableActionDto.kt`, `messages.ts`): all DTOs are immutable; serialisation and deserialisation produce new objects with no shared mutable state.
- `sendAction` readyState guard in `useWebSocket.ts`: the single-threaded JS event loop makes the guard between readyState check and `send()` race-free.
- `TurnCoordinator` internal consistency: all fields are accessed under its own mutex; no cross-boundary lock inversion with `SessionRegistry`.
- Reconnect token handshake: the token is written to `reconnectTokenRef` in `onmessage` and read in `join()`, both on the JS main thread, so there is no concurrency hazard.
- `DiceRoller`: uses `Random.Default` which is thread-local on the JVM; safe at any concurrency level.
- All domain value objects (`Decker`, `Host`, `Persona`, `Cyberdeck`, etc.): purely immutable `data class` instances; no cross-cutting concurrency risk regardless of how they are shared.
