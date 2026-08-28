---
# Performance Review — server

## Summary

The server layer is small and readable, but it has several performance problems that compound on every game turn. The two most serious are: every incoming WebSocket message is JSON-parsed twice (once to read the type discriminator, once to fully decode the payload), and `runBlocking` is used throughout `WebSocketDeckerController.action()`, which blocks Ktor/Netty worker threads while waiting for player input. On top of those, the per-session state broadcast re-serialises the full game state once per connected client (only the `role` string differs), and a `CompletableFuture.get()` thread-block is nested inside `runBlocking`, meaning one OS thread is permanently occupied for the entire duration of a player's turn. The DTO and dispatch layers also contain minor but recurring allocation hotspots.

## Findings

### [CRITICAL] Double JSON parse on every incoming WebSocket message
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31-34
**Issue:** Every text frame is parsed twice. Line 31 calls `Json.parseToJsonElement(json)` to peek at the `type` field, then lines 33–34 call `Json.decodeFromString<JoinMessage>(json)` / `Json.decodeFromString<ActionCommand>(json)` on the same raw string, repeating the full parse. Under any non-trivial message rate this doubles the JSON parsing cost and creates an extra `JsonElement` object tree per message that is immediately discarded.
**Recommendation:** Parse to `JsonObject` once, then decode from the already-parsed element using `Json.decodeFromJsonElement<T>(jsonObject)`. Alternatively, use a sealed/polymorphic `Message` type with `classDiscriminator = "type"` so a single `Json.decodeFromString<Message>(json)` dispatches correctly and the type-peek disappears entirely.

---

### [CRITICAL] `runBlocking` used repeatedly inside a Ktor coroutine handler
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:53-117
**Issue:** `action()` is invoked from the game loop (which itself is wrapped in `runBlocking` at lines 53, 73, 78, 82, 86, 97, 109, 116). Each `runBlocking` call seizes the calling thread for the duration of the inner suspend functions. Because Ktor's Netty dispatcher uses a bounded thread pool, blocking those threads during a player's turn (which can last up to `actionTimeoutSeconds = 120 s`) starves all other WebSocket I/O — including messages from other deckers or keepalives — for the entire turn.
**Recommendation:** Make `action()` a `suspend` function and propagate the coroutine context up through the game loop. Replace every `runBlocking { registry.foo() }` with a direct `registry.foo()` call. The `CompletableFuture` bridge (see next finding) must also be converted before this is fully resolved.

---

### [HIGH] `CompletableFuture.get()` blocks a thread for the entire player-input window
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:70-76
**Issue:** A `CompletableFuture<ActionCommand>` is created each turn and then `.get(actionTimeoutSeconds, ...)` is called, which parks the calling thread (inside `runBlocking`) for up to 120 seconds while waiting for the player to respond. This means one thread per active turn is permanently consumed doing nothing but blocking. With multiple concurrent games this exhausts thread-pool capacity.
**Recommendation:** Replace `CompletableFuture` with `kotlinx.coroutines.CompletableDeferred<ActionCommand>`. Suspend with `withTimeout(actionTimeoutSeconds * 1000L) { deferred.await() }`. This suspends without parking a thread, letting Ktor reuse it for other work. `SessionRegistry.pendingAction` must also be changed to `CompletableDeferred`.

---

### [HIGH] Full `StateMessage` serialised once per connected session in `broadcastWithRoles`
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:92-105
**Issue:** `broadcastWithRoles` iterates all sessions and calls `MatrixJson.encodeToString(base.copy(role = role))` for each one. Since only the `role` field differs, the entire `StateMessage` — including the full decker DTO, the visible-objects list, and the available-actions list — is serialised N times (once per connected client). For a host with many observers this is O(N) serialisation work per turn, each producing a large allocation.
**Recommendation:** Pre-serialise the three possible role variants (`"observer"`, `"registered_decker"`, `"active_controller"`) once each before the loop, then send the pre-built string to each session based on its role. Alternatively, serialise the `base` message once, obtain the JSON string, and use `replace("\"role\":\"observer\"", "\"role\":\"$role\"")` — that is safe here because `role` appears exactly once and contains no special characters.

---

### [MEDIUM] `sessions.toList()` allocates a snapshot list on every `broadcast` call
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:86
**Issue:** `broadcast` takes the lock and calls `sessions.toList()` to create a defensive copy before iterating. This happens multiple times per turn (result message, promote/demote control messages). For small session counts the allocation is trivial, but it is unnecessary when an unmodifiable view or a `CopyOnWriteArrayList` would eliminate the copy.
**Recommendation:** Change `sessions` to a `java.util.concurrent.CopyOnWriteArrayList` (reads are lock-free and copy-free; writes copy the internal array). Remove the `synchronized` block in `broadcast` and iterate directly. Writes still need synchronisation where atomicity across both maps is required, but the broadcast fast-path becomes allocation-free.

---

### [MEDIUM] Static `ANALYZE_HOST` item list rebuilt on every dispatch
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:163-165
**Issue:** Every time `ANALYZE_HOST` is dispatched, the code evaluates:
```kotlin
listOf(HostInfoItem.SecurityRating) + SubsystemType.entries.map { HostInfoItem.Subsystem(it) }
```
`SubsystemType.entries` is a fixed enum and `HostInfoItem.Subsystem` wraps are stateless. The resulting list is identical on every call yet is re-allocated and re-populated each time.
**Recommendation:** Hoist the list to a `companion object` constant:
```kotlin
companion object {
    private val ANALYZE_HOST_ITEMS: List<HostInfoItem> =
        listOf(HostInfoItem.SecurityRating) + SubsystemType.entries.map { HostInfoItem.Subsystem(it) }
}
```

---

### [LOW] JVM reflection via `it::class.simpleName` in DTO hot path
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:63
**Issue:** `target?.let { it::class.simpleName }` uses Kotlin reflection (which delegates to `Class.getSimpleName()`) on every `AvailableAction.Operation` converted to DTO. This runs on every visible action, for every session, on every turn broadcast. The same file already provides a `targetName()` extension function with an explicit `when` branch for each `MatrixObject` subtype; `targetKind` should follow the same pattern.
**Recommendation:** Add a `fun MatrixObject.kindName(): String` extension with a `when` expression mirroring `targetName()`, and replace `it::class.simpleName` with `target?.kindName()`. This eliminates the reflection call and is consistent with the rest of the DTO mapping.

---

### [LOW] `QueryPrecision.valueOf(it)` uses reflective enum lookup
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245
**Issue:** `QueryPrecision.valueOf(it)` performs a reflective name-based enum lookup. It also throws `IllegalArgumentException` on an unrecognised string with no guard; a bad client input would propagate as an unhandled exception through `locateWithState`.
**Recommendation:** Replace with `QueryPrecision.entries.find { e -> e.name == it } ?: QueryPrecision.NORMAL` (or a dedicated parsing helper). This avoids the reflective lookup and degrades gracefully on bad input.

---

## Clean Areas

- `MatrixJson` (`Messages.kt` line 6) is a top-level `val` created once and shared everywhere, so `Json` instance construction cost is paid only once at class-load time.
- `SessionRegistry` correctly separates `synchronized` blocks from `suspend` calls — it never calls a suspend function inside a monitor, avoiding coroutine/monitor deadlock.
- `SessionRegistry.deregister` correctly notifies `pendingAction` via `completeExceptionally` on disconnect, preventing the `CompletableFuture.get()` from hanging until timeout in the disconnection case.
- The `WebSocketDeckerController.interrogationStates` map is scoped to the controller instance and never shared across threads, so it needs no synchronisation.
- DTO data classes are immutable value types; `base.copy(role = role)` produces correct defensive copies and does not alias mutable state.
- `dispatch` and `dispatchHostOperation` are pure control-flow switch statements with no loops or unnecessary allocations beyond what the domain operations themselves require.
---
