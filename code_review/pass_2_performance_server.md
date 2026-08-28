# Performance Review — server

## Summary

The server component is lean and well-structured for a single-game tabletop simulator, but two issues stand out as real risks under load: `runBlocking` inside `WebSocketDeckerController.action()` parks a Ktor thread for the entire duration of a player's turn (up to 120 seconds), and `SessionRegistry.broadcastWithRoles` performs full JSON serialization of every session's state message while holding the registry's JVM monitor lock — blocking all other registry operations during the encode pass. A third issue, double-parsing of every inbound WebSocket frame in `MatrixServer`, is minor in this context but unnecessary. One further waste — computing `visibleObjects` and `availableActions` before confirming that a controller is even connected — is low severity but easy to eliminate.

---

## Findings

### [HIGH] `runBlocking` in `action()` parks a Ktor thread for up to 120 seconds

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45`

**Issue:** `action()` is a non-suspend interface method that bridges into coroutine world via `runBlocking`. `runBlocking` blocks the calling thread for the entire coroutine body — including the `withTimeoutOrNull(actionTimeoutSeconds * 1000L)` wait, which defaults to 120 seconds. If the game loop is driven from a Ktor coroutine dispatcher thread (the most natural wiring given this is a Ktor application), one of the very few threads in that pool is pinned for the full turn, degrading WebSocket accept/send throughput for all connected observers during that window. This is a structural mismatch between the blocking `ActiveIcon` interface contract and the async runtime.

**Recommendation:** The correct fix is to make `ActiveIcon.action()` a `suspend fun`. That allows `WebSocketDeckerController.action()` to use `withTimeoutOrNull` directly without `runBlocking`, freeing the dispatcher thread while waiting for the player's command. If changing the interface is not immediately feasible, run the game loop on a dedicated thread (e.g., `Dispatchers.IO` or a purpose-built single-thread context) rather than a Ktor coroutine thread, so the block cannot starve the server.

---

### [HIGH] JSON serialization inside `synchronized(lock)` in `broadcastWithRoles`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:112–121`

**Issue:** `broadcastWithRoles` builds the list of `(session, serializedText)` pairs entirely inside `synchronized(lock)`. For every connected session it calls `MatrixJson.encodeToString(base.copy(role = role))`, which encodes the full `StateMessage` — decker stats, all visible objects, all available actions — under the JVM monitor. While that lock is held, every other registry operation (`register`, `deregister`, `receiveAction`, `promoteForTurn`, `demoteAfterTurn`, and `broadcast`) is blocked. In a game with several observers this serialization pass could easily take several milliseconds, and it runs on the same thread that must also process incoming action frames.

**Recommendation:** Only capture the minimal state needed inside the lock — a list of `(session, role)` pairs — then release the lock and serialize outside it:

```kotlin
suspend fun broadcastWithRoles(base: StateMessage) {
    val sessionRoles: List<Pair<DefaultWebSocketServerSession, SessionRole>> = synchronized(lock) {
        sessions.map { s ->
            s to when {
                s == activeController        -> SessionRole.ACTIVE_CONTROLLER
                sessionDecker.containsKey(s) -> SessionRole.REGISTERED_DECKER
                else                         -> SessionRole.OBSERVER
            }
        }
    }
    // Serialize outside the lock
    for ((session, role) in sessionRoles) {
        val text = MatrixJson.encodeToString(base.copy(role = role))
        runCatching { session.send(Frame.Text(text)) }
    }
}
```

---

### [MEDIUM] Every inbound frame is parsed twice

**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:35–38`

**Issue:** Every incoming WebSocket message is deserialized twice. First the raw JSON string is parsed into a generic `JsonElement` just to read the `"type"` discriminator field, then the same string is decoded again into the typed message class (`JoinMessage` or `ActionCommand`). This doubles the allocations and CPU work for every frame.

```kotlin
val msgType = Json.parseToJsonElement(json).jsonObject["type"]?.jsonPrimitive?.content
when (msgType) {
    "join"   -> registry.receiveJoin(this, Json.decodeFromString<JoinMessage>(json))
    "action" -> registry.receiveAction(this, Json.decodeFromString<ActionCommand>(json))
```

**Recommendation:** Use a single sealed/polymorphic deserialization pass with a `JsonClassDiscriminator` on a sealed `ClientMessage` hierarchy (`JoinMessage` and `ActionCommand` as subclasses), so the type is resolved and the payload decoded in one pass. Alternatively, keep the current approach but parse once into a `JsonObject`, extract the `type` field, then use `Json.decodeFromJsonElement<JoinMessage>(jsonObject)` instead of re-parsing from the string — this still allocates a `JsonObject` but avoids a second full string parse.

---

### [MEDIUM] `visibleObjects()` and `availableActions()` computed before controller presence is verified

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:46–62`

**Issue:** `decker.visibleObjects()` and `decker.availableActions()` (plus the subsequent `filterNot`) are called at the top of `action()`, before `registry.promoteForTurn(decker.name)` checks whether a WebSocket session for this decker is actually connected. If no controller is registered, both results are discarded immediately and the function returns early. Depending on how expensive `visibleObjects()` and `availableActions()` are in the domain layer (they likely walk the host graph), this is wasted work on every skipped turn.

```kotlin
val visibleObjects = decker.visibleObjects()      // computed here
val availableActions = decker.availableActions()  // computed here
    .filterNot { ... }

registry.setPendingAction(deferred)
val hasController = registry.promoteForTurn(decker.name)
if (!hasController) {
    // both lists are thrown away
    return@runBlocking ActionResult.DeckerAction
}
```

**Recommendation:** Move the `visibleObjects()` / `availableActions()` calls to after the `hasController` guard, so they only run when there is an active controller to receive the resulting `StateMessage`.

---

### [LOW] `broadcast()` allocates a full session-list copy on every call

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:105`

**Issue:** `broadcast` calls `sessions.toList()` inside the lock to get a snapshot, then iterates it outside. This is the correct pattern for avoiding lock contention during I/O, but it does allocate a new list on every broadcast. With a small, stable observer count this is negligible; at scale (many observers receiving result messages after each action) it produces GC churn.

**Recommendation:** For this simulator's expected scale this is not worth changing now. If observer counts grow, consider maintaining a pre-allocated snapshot array or using a `CopyOnWriteArrayList` to eliminate the explicit copy entirely.

---

### [INFO] `setPendingAction` and `promoteForTurn` are separate lock acquisitions

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53–55`

**Issue:** The code correctly sets the pending action before promoting the turn controller (preventing the TOCTOU race where the client sends its action before the server has registered the deferred), but these are two separate `synchronized(lock)` calls. A sufficiently fast disconnect between the two acquisitions could still observe an inconsistent state (active controller set, pending action not yet visible in `receiveAction`). In practice this window is nanoseconds wide and the code's comment shows it was intentionally reasoned about.

**Recommendation:** No change required. Documented for completeness. If the ordering guarantee ever needs strengthening, both assignments could be combined into a single `synchronized` block in a new `setPendingAndPromote` method on `SessionRegistry`.

---

## No Issues Found In

- `DeckerDisconnectedException.kt` — trivial exception class, nothing to optimize.
- `dto/Messages.kt` — DTO definitions and the `MatrixJson` singleton are appropriate; `encodeDefaults = true` is intentional for the wire format.
- `dto/DeckerStateDto.kt` — the `toDto()` mapping is a flat projection; no recursive or repeated work.
- `dto/AvailableActionDto.kt` — `toDto()` is a simple indexed map with no redundant traversal.
- `dto/MatrixObjectDto.kt` — `toDto()` reads only the fields needed for the wire type; no deep copies or extra allocations.
- `SessionRegistry.deregister` — correctly captures `pendingAction` inside the lock before completing it exceptionally outside the lock, avoiding lock-held suspension.
- `SessionRegistry.receiveAction` — correctly reads both `activeController` and `pendingAction` atomically to prevent TOCTOU between the permission check and the `complete()` call.
