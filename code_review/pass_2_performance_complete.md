# Performance Review — complete (cross-cutting)

## Summary

The stack is correct and safe, but every player action triggers a full-state broadcast that is serialised once per connected session while holding the registry lock. The `StateMessage` payload is comprehensive (decker stats, full visible-object list, full action list) and is sent to every session including observers who have no use for half of it. The frontend receives these snapshots and replaces the entire `gameState` reference each time, causing every panel to re-render unconditionally. Because the game is turn-based and sessions are small (typically two to four clients), none of these issues are critical in today's load, but the lock-under-serialisation pattern is a latency landmine and the full-state approach will not scale gracefully if multi-decker or spectator-heavy runs become common.

---

## Findings

### [HIGH] JSON serialisation runs inside the registry lock

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:112`

**Issue:** `broadcastWithRoles` builds every session's frame — including calling `MatrixJson.encodeToString(base.copy(role = role))` — inside the `synchronized(lock)` block. JSON serialisation of a full `StateMessage` (decker stats + visible-objects list + available-actions list) is CPU-bound and can take an unbounded amount of time relative to other lock operations. While it holds the lock, `register`, `deregister`, `receiveAction`, `promoteForTurn`, and `demoteAfterTurn` are all blocked. On a slow JVM warm-up or a large payload this delays incoming action processing.

**Recommendation:** Read only the role-mapping data inside the lock (a snapshot of `sessions` → `role`), exit the lock, then serialise outside it.

```kotlin
suspend fun broadcastWithRoles(base: StateMessage) {
    // read role assignments under lock — fast
    val roleMap: List<Pair<DefaultWebSocketServerSession, SessionRole>> = synchronized(lock) {
        sessions.map { s ->
            val role = when {
                s == activeController        -> SessionRole.ACTIVE_CONTROLLER
                sessionDecker.containsKey(s) -> SessionRole.REGISTERED_DECKER
                else                         -> SessionRole.OBSERVER
            }
            s to role
        }
    }
    // serialise and send outside the lock
    for ((session, role) in roleMap) {
        val text = MatrixJson.encodeToString(base.copy(role = role))
        runCatching { session.send(Frame.Text(text)) }
    }
}
```

---

### [MEDIUM] Full StateMessage re-serialised once per session, differing only in `role`

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:119`

**Issue:** `base.copy(role = role)` creates a new `StateMessage` object for every connected session, and `encodeToString` produces a full JSON payload each time. With N sessions the same `decker`, `visibleObjects`, and `availableActions` trees are serialised N times. Since `role` is a tiny enum, the only way N sessions receive different text is via that one field.

**Recommendation:** Serialise the role-agnostic body once, then inject only the role value. The simplest approach is to serialise to a `JsonObject`, patch the `role` key, and re-encode:

```kotlin
val bodyJson = MatrixJson.encodeToJsonElement(StateMessage.serializer(), base)
    .jsonObject.toMutableMap()
// For each session:
val patched = JsonObject(bodyJson + ("role" to Json.encodeToJsonElement(role)))
val text = patched.toString()
```

Alternatively, maintain one pre-serialised "observer" string and two variants, since there are only three possible roles. This eliminates N-1 redundant full serialisations.

---

### [MEDIUM] Full-state snapshot broadcast on every turn — no delta

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:65–71`

**Issue:** `broadcastWithRoles(stateBase)` sends the complete `DeckerStateDto`, `visibleObjects`, and `availableActions` to every session at the start of every turn. There is no mechanism to send only what changed since the last broadcast. For a host with many IC programs and available operations, `visibleObjects` and `availableActions` can be long lists that are identical between consecutive turns.

**Recommendation:** In the near term, consider splitting `StateMessage` into a lightweight "turn-prompt" message that carries only role, turn indicator, and a sequence number, reserving full state for the initial join and for events that actually change the object lists (logon, logoff, operation outcomes that spawn IC). For a tabletop simulation with a handful of sessions, a simpler win is to add ETags or a `stateVersion` counter and let the client skip re-rendering unchanged sections.

---

### [MEDIUM] Observers receive `availableActions` they cannot use

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:66–70` and `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:113–120`

**Issue:** Every broadcast sends the full `availableActions` list to all sessions regardless of role. Observers and `REGISTERED_DECKER` sessions waiting their turn cannot act; for them, receiving the list is pure waste. For a host with many operations, `availableActions` can be the largest part of the payload (each `AvailableActionDto` carries operation name, target object, and metadata).

**Recommendation:** When building the per-session frame in `broadcastWithRoles`, pass an empty list for `availableActions` to non-controller sessions:

```kotlin
val msg = if (role == SessionRole.ACTIVE_CONTROLLER)
    base.copy(role = role)
else
    base.copy(role = role, availableActions = emptyList())
```

---

### [LOW] O(n²) initiative resolution in `Game.runCombatTurn`

**File:** `src/main/kotlin/com/shadowrun/matrix/game/Game.kt:20–27`

**Issue:** Each iteration of the combat loop calls `states.any { ... }` (O(n)), `states.filter { ... }.maxByOrNull { ... }` (O(n)), and `states.indexOf(state)` (O(n)) — three linear scans per iteration, with the loop running once per initiative pass per combatant. This is O(n²) overall. Typical Shadowrun combats have 5–15 participants, so the absolute cost is negligible now, but the pattern is fragile as n grows.

**Recommendation:** Replace with an indexed mutable structure or a priority queue sorted by descending initiative. Decrement in place and re-sort only when needed. Since `states` is a small `MutableList`, even a simple `sortedWith` at the start plus sequential processing would flatten it to O(n log n).

---

### [LOW] All React panels re-render on every STATE message

**File:** `frontend/src/App.tsx:101–116`

**Issue:** `gameState` is replaced wholesale on every `STATE` action in the reducer (`gameState: action.msg`). The five child panels — `LocationPanel`, `DeckerPanel`, `NarrativePanel`, `EntitiesPanel`, `ActionsPanel` — receive props derived directly from this new reference. Without `React.memo`, every panel re-renders on every state update. Because state updates only arrive when it is a turn, the actual render frequency is low (roughly once per turn), so this is not painful today.

**Recommendation:** Wrap each panel component in `React.memo`. For `NarrativePanel`, the `events` array is already capped at 20 items via `slice(-19)`, which is good. Consider extracting individual selectors (e.g. `gameState.decker`, `gameState.visibleObjects`) as stable references only when the sub-tree actually changes, using `useMemo` in `App`:

```tsx
const decker = useMemo(() => gameState.decker, [gameState.decker])
```

---

### [LOW] Events array rebuilt on every RESULT/ERROR message

**File:** `frontend/src/hooks/useWebSocket.ts:50–57`

**Issue:** The reducer uses `[...state.events.slice(-19), newEvent]` on every `RESULT` and `ERROR` action. This allocates a new array and discards the old one on every message. With a 20-item cap the allocation is small, but the pattern is an O(n) copy on every incoming event.

**Recommendation:** Use a fixed-capacity ring buffer or an `Immutable.js` deque if event frequency ever increases. For current load this is informational — the cost is trivial.

---

### [INFO] `encodeDefaults = true` serialises redundant type discriminator on every message

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:7`

**Issue:** `val MatrixJson = Json { encodeDefaults = true }` causes the `type` field (e.g. `"type": "state"`) to appear in every outgoing frame. This is intentional for client-side dispatch, but `encodeDefaults` also causes any field with a default value elsewhere in the DTOs (such as `reconnect: Boolean = false` in `ControlMessage` and `params: ActionParams? = null` in `ActionCommand`) to be serialised unconditionally. The overhead is negligible but worth knowing if the message schema grows.

---

### [INFO] No WebSocket frame compression

**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt` (Ktor WebSocket configuration, not shown)

**Issue:** There is no evidence in the reviewed files that `permessage-deflate` WebSocket compression is enabled. `StateMessage` payloads contain repetitive JSON keys and string values (operation names, subsystem types) that compress well, potentially 60–70% reduction. Over a slow connection (e.g. player running a VPN to the GM's server), uncompressed frames are noticeable.

**Recommendation:** Enable compression in the Ktor `install(WebSockets)` block:

```kotlin
install(WebSockets) {
    extensions {
        install(WebSocketDeflateExtension) {
            compressionLevel = Deflater.DEFAULT_COMPRESSION
        }
    }
}
```

---

## No Issues Found In

- **`SessionRegistry.broadcast`** — correctly takes a snapshot of sessions under the lock and then sends outside the lock; no lock-under-IO problem here.
- **`SessionRegistry.receiveAction`** — atomically captures both `activeController` and `pendingAction` under a single lock acquisition; no TOCTOU.
- **`GameContext.applyDeckerOperationResult`** — tally comparison is cheap and correctly bounded; `updateHost` + `checkTriggers` run only when tally increases.
- **`DeckerStateDto.toDto()`** — lightweight field projection; no deep copies or recursive traversals.
- **`useWebSocket` reconnect back-off** — exponential back-off with a 30 s ceiling prevents reconnect storms.
- **`useWebSocket` `sendAction` / `join`** — both wrapped in `useCallback` with stable dependency arrays, preventing unnecessary re-creations.
- **`ActionParams` 4 096-byte content guard** — `dispatchHostOperation` rejects oversized `newContent` before any heap allocation for the byte array.
