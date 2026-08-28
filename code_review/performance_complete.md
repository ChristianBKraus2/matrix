---
# Performance Review — Complete System (Cross-Cutting)

## Summary

The dominant cross-cutting issue is that every turn triggers a complete, unconditional state snapshot that traverses all three layers: the game loop constructs full domain-to-DTO mappings, the server serializes those DTOs N times (once per connected session), and the UI replaces its entire state reference causing all five panels to re-render. No layer communicates what actually changed, so each layer does maximum work on every action regardless of how small the real delta was. A secondary systemic problem is the CompletableFuture bridge that pins a JVM thread for up to 120 seconds per turn while waiting for player input, and the double JSON parse on every inbound message at the server entry point.

---

## Findings

### [HIGH] Full state broadcast on every turn re-serialized N times

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:92-105` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:64-73`  
**Issue:** `broadcastWithRoles` iterates every connected session and calls `MatrixJson.encodeToString(base.copy(role = role))` for each one. The only difference between messages is the single `role` string field, but the entire `StateMessage` — including the full `decker`, `visibleObjects`, and `availableActions` trees — is serialized once per session. With N connected observers, the same kilobyte-scale payload is encoded N times per turn, allocating N independent JSON strings and N intermediate DTO copies (via `copy()`).  
**Recommendation:** Pre-serialize the role-invariant body once. Then either (a) send a base state message without a role field and follow it with the existing lightweight `ControlMessage` which already carries the role, allowing the client to merge them; or (b) build the JSON string once, then do a single targeted string replacement of the role value before sending to each session. Option (a) is cleaner and aligns with the existing control-message contract already in place.

---

### [HIGH] Full state snapshot sent even when nothing visible changed

**Parts Affected:** game_logic / server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:50-73` and `frontend/src/hooks/useWebSocket.ts:43-45`  
**Issue:** `WebSocketDeckerController.action()` unconditionally calls `decker.visibleObjects()` and `decker.availableActions()`, maps them to DTOs, and broadcasts the full snapshot every turn. There is no comparison against the previous state. On the client, the `STATE` reducer case always replaces `gameState` with a new object reference (`{ ...state, gameState: action.msg }`), which means all five panels — `LocationPanel`, `DeckerPanel`, `NarrativePanel`, `EntitiesPanel`, `ActionsPanel` — receive new prop references every turn even if the underlying data is byte-for-byte identical to the prior turn. None of the panel components use `React.memo` (observable from `App.tsx:101-109` where they are rendered unconditionally from `gameState`).  
**Recommendation:** Server-side: track the last-sent DTO snapshot per session and skip the broadcast if nothing changed, or introduce a versioned sequence number so the client can detect a no-op state. Client-side: wrap each panel in `React.memo` with a shallow equality check so React bails out of reconciliation when its slice of state is unchanged. For deeper savings, split `gameState` into independent atoms (decker, objects, actions) so a change to decker stats does not trigger a re-render of `EntitiesPanel`.

---

### [HIGH] `availableActions` sent to all sessions but only useful to `active_controller`

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:16-21` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:65-69`  
**Issue:** `StateMessage` embeds `availableActions: List<AvailableActionDto>` and is broadcast to every connected session including observers. Observers have no ability to send an `action` command — the server enforces `not_your_turn` — so the action list is wasted payload for every non-controller session. On a host with many visible subsystems, IC programs, files, and devices, the `availableActions` list can be large because `AvailableAction.Operation` is emitted once per target per operation type.  
**Recommendation:** Send `availableActions` only in the message addressed to the `active_controller`. All other sessions can receive the state message without the actions list (omit the field or send an empty array). This requires the single targeted send to the controller to be separated from the broadcast, which also removes the need for the per-session `copy(role = role)` described in the first finding.

---

### [MEDIUM] Double JSON parse on every inbound WebSocket message

**Parts Affected:** server  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:31-34`  
**Issue:** Every inbound text frame is parsed twice. Line 31 calls `Json.parseToJsonElement(json)` to extract the `type` discriminator field, producing a full in-memory JSON tree. Lines 33-34 then call `Json.decodeFromString<JoinMessage>(json)` or `Json.decodeFromString<ActionCommand>(json)`, parsing the same string again from scratch. For the expected low message rate of a turn-based game this is a minor CPU cost, but it is an unnecessary allocation of an intermediate `JsonElement` tree on every message.  
**Recommendation:** Use a `@Serializable sealed class` with a `type` field as the discriminator and decode once with `Json.decodeFromString<ClientMessage>(json)`. Kotlinx serialization's polymorphic JSON decoding handles the dispatch internally in a single parse pass.

---

### [MEDIUM] Redundant `kind` field doubles type discriminator in every DTO

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:10,16` and `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:11,17`  
**Issue:** Every `MatrixObjectDto` subclass and every `AvailableActionDto` subclass carries both a `@SerialName` annotation (used by kotlinx.serialization as the polymorphic `type` key) and an explicit `kind: String` instance field defaulted to the same literal. The serialized JSON therefore contains the discriminator value twice: once from the sealed-class `classDiscriminator` and once from the `kind` field. Every object in `visibleObjects` and every entry in `availableActions` carries this duplicate. The TypeScript types in `messages.ts` also model `kind` as the discriminator used in switch/discriminated unions, meaning the client relies on `kind` instead of the kotlinx `type` key — but both are always present in the wire payload.  
**Recommendation:** Pick one mechanism. Either remove the explicit `kind` field and configure kotlinx to use `"kind"` as the class discriminator (`Json { classDiscriminator = "kind" }`), or keep the `kind` field and remove `@SerialName` annotations (replacing them with the default class name matching). Eliminating the duplicate halves the type-annotation overhead for every object in the two largest arrays in the state message.

---

### [MEDIUM] Game-loop thread blocked waiting for player input via `CompletableFuture`

**Parts Affected:** game_logic / server  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:70,75-93`  
**Issue:** `WebSocketDeckerController.action()` is called from the synchronous `Game.runCombatTurn()` / `runOutOfCombatTurn()` loop. Inside it blocks the calling thread with `future.get(actionTimeoutSeconds, TimeUnit.SECONDS)` for up to 120 seconds. This means one JVM thread is held idle for the entire think time of each player turn. The design also uses `runBlocking { }` to call `suspend` functions (broadcast, promote, demote) from the blocking game-loop context, which submits coroutine work to the Netty event loop and then blocks the calling thread waiting for it — an impedance mismatch between the two concurrency models.  
**Recommendation:** For the current single-game scope this is functional, but if multiple games or deckers are ever run concurrently this design does not scale. The cleaner architecture is to make the game loop itself a coroutine (suspend function), replace `CompletableFuture` with a `Channel<ActionCommand>`, and use `withTimeout { channel.receive() }` to await player input without pinning a thread.

---

### [LOW] `synchronized` lock held inside `suspend` functions on the coroutine dispatcher

**Parts Affected:** server  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:24,29,51,65,75,85,92,107`  
**Issue:** Every `suspend` function in `SessionRegistry` acquires a JVM `synchronized(lock)` block. Although the locked sections are short and only read/write the in-memory collections, holding a monitor on a coroutine dispatcher thread blocks that thread from executing other coroutines for the duration of the lock. If the Netty/coroutine pool is small (as is typical for Ktor's default configuration), a brief period of lock contention under multiple simultaneous connections can stall the entire event loop.  
**Recommendation:** Replace `synchronized` with a `Mutex` from `kotlinx.coroutines.sync` and use `mutex.withLock { }` inside the suspend functions. This suspends (yields) rather than blocks the thread while waiting for the lock, keeping the dispatcher free for other coroutines.

---

### [LOW] `visibleObjects` includes structural topology data that never changes mid-session

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:107-134` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:50-51`  
**Issue:** `decker.visibleObjects()` is called and fully mapped to DTOs on every turn. Many of the visible objects — `GridNode`, `LocalGrid`, `PrivateGrid`, `HostSubsystem` — contain structural fields (`region`, `parentRtgName`, `owner`, `topologyType`, `description`) that cannot change during a session. Only mutable fields like `alertStatus`, `securityTally`, and `offline` are dynamic. The full structural data is re-serialized and re-transmitted every turn regardless.  
**Recommendation:** Send the full object list only on the first state message after a decker logs on to a new location. On subsequent turns, send only the mutable fields that can change (alertStatus, securityTally, offline flag) as a compact update. If a full diff protocol is too complex to add now, at minimum cache the serialized `visibleObjects` JSON and only recompute it when the decker's location changes.

---

### [LOW] Events array allocates a new array reference on every result or error

**Parts Affected:** ui  
**File(s):** `frontend/src/hooks/useWebSocket.ts:47-50`  
**Issue:** The `RESULT` and `ERROR` reducer cases construct a new array with `[...state.events.slice(-19), { kind: 'result', msg }]` on every event. This creates two intermediate arrays (`slice` then spread) and a new event wrapper object per event. While the per-event cost is negligible, `NarrativePanel` receives a new `events` array reference every time any event fires, causing it to re-render even if the panel would display the same content.  
**Recommendation:** Use a fixed-length circular buffer (a plain array with a write-index) held in a `useRef` rather than the reducer, or at minimum wrap `NarrativePanel` in `React.memo` with a custom comparator that checks `events.length` and the last event identity rather than full array reference equality.

---

### [INFO] `ResultMessage` is broadcast separately after state, requiring two round-trips per turn

**Parts Affected:** server / ui  
**File(s):** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:109-117`  
**Issue:** After dispatching an action the server sends a `ResultMessage` (success, dice totals, details string) and then calls `demoteAfterTurn`, which sends a `ControlMessage`. The updated game state is not sent again after the action — it will only arrive at the start of the decker's next turn. This means the UI shows stale state (pre-action decker stats) immediately after the action completes, and the player must wait until their next turn to see the outcome reflected in the `DeckerPanel`. This is a UX gap driven by the architecture of only sending state at turn start.  
**Recommendation:** Send a lightweight state update (`StateMessage`) after the action resolves, or merge the result details into the next turn's state message with a `lastActionResult` field. This eliminates the multi-turn stale display without adding a separate message type.

---

## Clean Seams

- The `AvailableAction`-to-`AvailableActionDto` mapping is a clean projection: only names and types cross the boundary, not live domain objects. The UI correctly selects an action by index (an opaque handle) rather than reconstructing a domain type from the DTO, avoiding any round-trip domain logic on the client.
- `DeckerStateDto` is minimal and flat — no nested graphs, no redundant collections. It exposes only what the UI needs to display (damage tracks, pool, utilities) without leaking internal cyberdeck structure.
- The WebSocket message protocol is well-typed on both sides: `Messages.kt` and `messages.ts` are structurally aligned, and the TypeScript union type `ServerMessage` mirrors the Kotlin sealed-class dispatch exactly. Contract drift between server and client is unlikely.
- The reconnect logic in `useWebSocket.ts` uses exponential back-off capped at 30 seconds, which prevents thundering-herd reconnection storms after a server restart.
- `SessionRegistry.broadcast` takes a pre-serialized `String` rather than a DTO, allowing callers to serialize once for the uniform-content case (error and result messages). The pattern is already available and is used correctly for `ResultMessage` and `ControlMessage` paths.
