# Performance Review — server

## Summary

The server layer is lean and well-structured for its scale (max 32 connections, turn-based game loop). No algorithmic complexity problems exist in the hot path. Three concrete inefficiencies were found: every incoming WebSocket message is JSON-parsed twice, a `setOf(...)` literal inside `action()` allocates a fresh `HashSet` on every game turn, and `broadcastWithRoles` serializes a full JSON payload once per connected session instead of once per role. The remaining items are minor one-off allocations and a single reflection call.

## Findings

### [MEDIUM] Double JSON parse on every incoming message
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:46
**Issue:** The frame text is parsed twice for every incoming message: once with `Json.parseToJsonElement(json)` to read the `type` discriminator, and again with `Json.decodeFromString<JoinMessage>(json)` or `Json.decodeFromString<ActionCommand>(json)` to fully decode the payload. Deserialization is the single most expensive per-message operation; doubling it for every frame is unnecessary work.
**Recommendation:** Use a single polymorphic decode pass with a `@JsonClassDiscriminator("type")` sealed class covering `JoinMessage` and `ActionCommand`, or decode once into `JsonElement`, branch on the already-parsed element, and decode the sub-object from that element using `Json.decodeFromJsonElement(...)` rather than re-parsing the raw string.

**[DEFERRED]** — Double JSON parse not consolidated; out of scope for this session.

---

### [LOW] New `HashSet` allocated on every game turn
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:46
**Issue:** `setOf(SystemOperation.SWAP_MEMORY, SystemOperation.LOCATE_DECKER)` inside `action()` constructs and populates a new `HashSet` each time `action()` is called — once per decker turn. While small, this allocation is entirely avoidable.
**Recommendation:** Extract the set to a `companion object` constant:
```kotlin
companion object {
    private val FILTERED_OPERATIONS = setOf(
        SystemOperation.SWAP_MEMORY,
        SystemOperation.LOCATE_DECKER
    )
}
```
Then reference `FILTERED_OPERATIONS` in the `filterNot` call.

**[RESOLVED]** — Moot: `SWAP_MEMORY` and `LOCATE_DECKER` removed from `addHostSystemActions`; the `filterNot` call and its `HashSet` no longer exist.

---

### [LOW] Per-session JSON serialization in `broadcastWithRoles`
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:142
**Issue:** `broadcastWithRoles` calls `MatrixJson.encodeToString(base.copy(role = role))` inside the loop over all sessions. With up to 32 connections, this serializes the full `StateMessage` (which includes the complete decker state, visible objects, and available actions) up to 32 times. In practice only three distinct payloads are ever needed: one per `SessionRole` value.
**Recommendation:** Pre-serialize the three role variants before the loop and assign each session to its pre-built string:
```kotlin
val byRole = SessionRole.entries.associateWith { r ->
    MatrixJson.encodeToString(base.copy(role = r))
}
for ((session, role) in snapshot) {
    runCatching { session.send(Frame.Text(byRole.getValue(role))) }
}
```

**[DEFERRED]** — Per-session JSON serialization not pre-computed; out of scope for this session.

---

### [LOW] `HostInfoItem` list rebuilt on every ANALYZE_HOST operation
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:197
**Issue:** `listOf(HostInfoItem.SecurityRating) + SubsystemType.entries.map { HostInfoItem.Subsystem(it) }` constructs and concatenates two lists on every `ANALYZE_HOST` dispatch. The result is deterministic and does not vary per call.
**Recommendation:** Cache the result in a `companion object` constant so it is built once at class-load time.

**[DEFERRED]** — `HostInfoItem` list not cached; out of scope for this session.

---

### [INFO] Reflection call in `AvailableActionDto` DTO mapping
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:66
**Issue:** `target?.let { it::class.simpleName }` uses Kotlin reflection to obtain the class name for every `Operation` action converted to a DTO. Reflection lookups are significantly slower than direct name properties or a `when` branch.
**Recommendation:** Add a `val kindName: String` property to `MatrixObject` (or use the existing `targetName()` pattern already present in this file) and eliminate the `::class.simpleName` call.

**[DEFERRED]** — Reflection call not replaced; out of scope for this session.

---

### [INFO] Sessions list copied to snapshot on every `broadcast`
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:123
**Issue:** `sessions.toList()` allocates a new `ArrayList` snapshot inside the mutex on every `broadcast` call. With MAX_CONNECTIONS=32 and frequent broadcasts (once per game turn), this is a minor but avoidable allocation per turn.
**Recommendation:** At this scale the impact is negligible. If broadcast frequency grows, consider using an immutable persistent-collection approach (e.g. `PersistentList` from `kotlinx.collections.immutable`) so the snapshot copy becomes a zero-cost reference read.

**[DEFERRED]** — Snapshot allocation on broadcast not optimised; out of scope for this session.

---

## No Issues Found In

- `TurnCoordinator.kt` — mutex-guarded simple state; no loops, no allocations beyond the deferred itself.
- `SessionRegistry` join/deregister/promote/demote paths — O(1) map operations, correctly scoped under a single mutex.
- `DeckerStateDto.kt` / `MatrixObjectDto.kt` — flat one-shot DTO mapping with no repeated work or redundant traversals.
- `Messages.kt` — `MatrixJson` is a module-level singleton; DTO classes are plain data classes with no hidden cost.
- `DeckerDisconnectedException.kt` — trivial.
- `WebSocketDeckerController` dispatch tree — the `when` chains are O(1) and the dispatch logic itself introduces no allocation or complexity beyond what the game operations require.
