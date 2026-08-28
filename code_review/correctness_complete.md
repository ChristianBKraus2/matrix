---
# Correctness Review — Complete System (Cross-Cutting)

## Summary

The server-to-UI message contract is largely consistent and well-typed on both sides. The most serious cross-cutting issue is a **state drift bug** at the game_logic/server boundary: after any operation that increases the security tally enough to trigger an alert transition, `WebSocketDeckerController.decker` diverges from `context.deckers[idx]` because `GameContext.updateHost` replaces the decker reference in the context list but the controller's own field is never refreshed. A second structural issue is that `runCatching` in the WebSocket frame handler swallows every exception silently, so protocol errors on either side produce no observable signal. There are also two smaller but concrete contract mismatches: `ResultMessage.deckerSuccesses/hostSuccesses` are non-nullable on the server and optional in the TypeScript type, and `SWAP_MEMORY`/`LOCATE_DECKER` are advertised as executable operations by the domain model but always silently fail in the dispatcher — the UI has no way to know these are unimplemented before the user submits them.

---

## Findings

### [HIGH] `WebSocketDeckerController.decker` goes stale after alert-transition-triggering operations

**Parts Affected:** game_logic / server  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:104-107`  
- `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:55-64`  
- `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:33-41`

**Issue:**  
After dispatch, the controller sets `decker = result.decker` and then calls `context.applyDeckerOperationResult(oldDecker, decker)`. When the security tally increases enough to trigger an alert transition, `applyDeckerOperationResult` calls `checkTriggers`, which calls `updateHost(applyAlertTransition(host, transition))`. That second `updateHost` call does `deckers.replaceAll { ... decker.copy(currentLocation = MatrixLocation.OnHost(new)) }`, replacing the decker entry in `context.deckers` with one whose location references the *new* alert-updated host. But `WebSocketDeckerController.decker` is never updated a second time — it still holds the version whose embedded host has the new tally but the *old* alert status. On every subsequent turn, `decker.visibleObjects()` and `decker.availableActions()` operate on a stale host object. The drift is invisible to the UI but silently corrupts the game state presented on the next turn.

**Recommendation:**  
After `context.applyDeckerOperationResult(oldDecker, decker)`, re-read the authoritative copy back out of the context:  
```kotlin
context.applyDeckerOperationResult(oldDecker, decker)
decker = context.deckers.firstOrNull { it.name == decker.name } ?: decker
```
Alternatively, add a `GameContext.currentDeckerState(name: String): Decker` helper and call it at the top of `action()` rather than relying on the controller's own field across turns.

---

### [HIGH] `runCatching` in the frame handler silently swallows all exceptions

**Parts Affected:** server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:29-35`

**Issue:**  
Every incoming frame is wrapped in `runCatching { ... }` with no `.onFailure` handler. A `JsonDecodingException` (malformed JSON from the UI), a missing required field, a class-cast failure inside `receiveJoin` or `receiveAction`, or any other runtime exception is silently discarded. The client receives no `ErrorMessage`, no disconnect — the connection stays open and the action is simply lost. During development this makes protocol bugs between server and UI effectively invisible. In production a client that sends an action with a type mismatch will believe it submitted the action successfully.

**Recommendation:**  
Replace the bare `runCatching` with explicit error handling:
```kotlin
runCatching { /* ... */ }.onFailure { e ->
    session.send(Frame.Text(MatrixJson.encodeToString(
        ErrorMessage(message = "bad_request: ${e.message?.take(120)}")
    )))
}
```
At minimum, log the exception server-side before discarding it.

---

### [HIGH] `SWAP_MEMORY` and `LOCATE_DECKER` advertised but silently unimplemented

**Parts Affected:** game_logic / server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:220-221, 229-230`  
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt:45, 50`

**Issue:**  
`dispatchHostOperation` returns a failure `DispatchResult` with a human-readable string for `LOCATE_DECKER` ("requires a target Persona") and `SWAP_MEMORY` ("requires utility selection"). If `decker.availableActions()` ever returns these as available, the UI will display them as valid choices. The user can select them, the `ActionCommand` is accepted, the action turn is consumed, and the server returns a `ResultMessage(success=false, ...)`. There is no up-front signal that these operations cannot be submitted via WebSocket. The UI has no way to grey them out or show a tooltip, because the action DTO carries no "unsupported" flag.

**Recommendation:**  
Either (a) filter `SWAP_MEMORY` and `LOCATE_DECKER` out of `availableActions` server-side before building the `StateMessage`, or (b) add an `unsupported: Boolean` field to `AvailableActionDto.Operation` and set it for these operations so the UI can disable them with an explanation.

---

### [MEDIUM] `ResultMessage.deckerSuccesses` / `hostSuccesses` — required on server, optional in TypeScript

**Parts Affected:** server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:41-46`  
- `c:\VSCode\private\matrix\frontend\src\types\messages.ts:84-90`

**Issue:**  
The Kotlin `ResultMessage` declares `val deckerSuccesses: Int` and `val hostSuccesses: Int` as non-nullable primitives; they are always present in every emitted JSON frame. The TypeScript counterpart declares them as `deckerSuccesses?: number` and `hostSuccesses?: number`. Any UI code that reads these fields gets `number | undefined` and must guard accordingly. If any component unconditionally uses `msg.deckerSuccesses` in arithmetic (e.g., a future "net hits" display), it will produce `NaN` under TypeScript's type alone, even though the server always sends them. More critically, the mismatch masks a real contract gap: if the server ever stops sending these fields, the TypeScript type would not catch it at compile time.

**Recommendation:**  
Change the TypeScript interface to match the server's guarantee: `deckerSuccesses: number` and `hostSuccesses: number` (non-optional). If there is a code path where these are intentionally absent, that should be a separate message type.

---

### [MEDIUM] `precision` deserialized with `QueryPrecision.valueOf()` — no error boundary

**Parts Affected:** server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:245`  
- `c:\VSCode\private\matrix\frontend\src\types\messages.ts:10`

**Issue:**  
In `locateWithState`, the `precision` string from `ActionParams` is fed directly to `QueryPrecision.valueOf(it)`. If the value is anything other than a valid `QueryPrecision` enum constant, `valueOf` throws `IllegalArgumentException`. This exception propagates out of `dispatch` and up through `action()`, which is running inside `runBlocking` on the game thread. Because `runCatching` is in the WebSocket handler (server side, not here), this exception is not caught; it will propagate uncaught and crash the game loop. The TypeScript type constrains the UI to `'NORMAL' | 'HIGH'`, which is a compile-time guard only — it does not protect against a developer sending raw WebSocket frames during testing, or a future change that adds a third precision value without updating all three places (enum, TS union, dispatcher).

**Recommendation:**  
Replace `QueryPrecision.valueOf(it)` with a safe lookup:
```kotlin
val precision = params?.precision?.let {
    runCatching { QueryPrecision.valueOf(it) }.getOrDefault(QueryPrecision.NORMAL)
} ?: QueryPrecision.NORMAL
```
Also add a server-side validation step that rejects an `ActionCommand` with an unrecognised precision value early, before it reaches the game-logic dispatch layer.

---

### [MEDIUM] `GameContext.applyDeckerOperationResult` — tally comparison uses embedded host, not context host

**Parts Affected:** game_logic / server  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:56-64`

**Issue:**  
The old tally is read from `old.currentLocation.host.securityTally` and the new tally from `new.currentLocation.host.securityTally`. Both come from the decker's embedded host copy, not from `context.host`. If any other code path (e.g., an IC action on the same turn in a combat round) has already advanced `context.host` to a higher tally before `applyDeckerOperationResult` is called, the delta comparison `newTally > oldTally` may silently skip a `checkTriggers` call that should fire, because the "old" baseline is taken from a stale decker snapshot rather than the live context host.

**Recommendation:**  
Read the baseline tally from `context.host.securityTally` (the live ground truth), not from `old.currentLocation`:
```kotlin
val oldTally = context.host.securityTally
val newTally = (new.currentLocation as? MatrixLocation.OnHost)?.host?.securityTally ?: oldTally
```

---

### [MEDIUM] `AvailableActionDto` sealed class emits redundant `type` discriminator alongside explicit `kind` field

**Parts Affected:** server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:8-49`  
- `c:\VSCode\private\matrix\frontend\src\types\messages.ts:67-74`

**Issue:**  
With the default kotlinx.serialization configuration, a sealed class emits a `"type"` discriminator field whose value is set by `@SerialName`. Each `AvailableActionDto` subclass also carries an explicit `kind` field (e.g., `override val kind: String = "LogonToRtg"`) that duplicates the same value. The JSON wire format therefore contains both `"type": "LogonToRtg"` and `"kind": "LogonToRtg"`. The TypeScript side discriminates on `kind`, which always works because the `kind` default value is always kept in sync with `@SerialName`. However, if a future change updates the `@SerialName` annotation but forgets to update the `kind` default (or vice versa), the TS discriminator will silently read the wrong value and pattern-match to the wrong branch. The same redundancy exists in `MatrixObjectDto`.

**Recommendation:**  
Remove the explicit `kind` field from each subclass and configure kotlinx.serialization to use `kind` as its class discriminator:
```kotlin
@Serializable
@JsonClassDiscriminator("kind")
sealed class AvailableActionDto { ... }
```
Then remove `abstract val kind: String` and all `override val kind: String = "..."` defaults. The TypeScript discriminator already uses `kind` and requires no changes.

---

### [LOW] `join` can be sent twice if the WebSocket is already open when `join()` is called

**Parts Affected:** server / ui  
**File(s):**  
- `c:\VSCode\private\matrix\frontend\src\hooks\useWebSocket.ts:129-135`  
- `c:\VSCode\private\matrix\frontend\src\hooks\useWebSocket.ts:90-94`

**Issue:**  
`join(name)` sets `pendingNameRef.current = name` and, if the socket is open, immediately sends the `JoinMessage`. Later, `onmessage` also sends a `JoinMessage` whenever the server delivers a `ControlMessage` with `role === 'observer'` and `pendingNameRef.current` is set. If the server sends a second `observer` control message after the initial join (which does not happen today but is not architecturally ruled out), a duplicate `join` would be sent, producing an `already_registered` error from the server. In the current protocol this is harmless, but the two send paths are fragile and could interact unexpectedly if the handshake sequence changes.

**Recommendation:**  
Clear `pendingNameRef.current` as soon as the join is sent in either path, or centralise the join dispatch to the `onmessage` path only (do not send immediately in `join()`, just set the ref).

---

### [LOW] `DISCONNECTED` reducer preserves stale `gameState`

**Parts Affected:** server / ui  
**File(s):**  
- `c:\VSCode\private\matrix\frontend\src\hooks\useWebSocket.ts:36-37`

**Issue:**  
On disconnect, the reducer clears `role` (which correctly causes the `JoinScreen` to render) but leaves `gameState` populated with the last received state snapshot. On reconnect, if the server sends a `ControlMessage` before a new `StateMessage` (e.g., the game has moved on), the `isRegistered` check will flip to `true` and the component will briefly render with the old stale `gameState` until the first new `state` message arrives.

**Recommendation:**  
Clear `gameState` on disconnect:
```typescript
case 'DISCONNECTED':
  return { ...state, connected: false, role: null, gameState: null }
```

---

### [LOW] All enum-valued strings serialised as raw `String` on both sides — no exhaustiveness guarantee

**Parts Affected:** server / ui  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:19,29,46,59,67,75,80`  
- `c:\VSCode\private\matrix\frontend\src\types\messages.ts:50-53,61`

**Issue:**  
Enum fields such as `alertStatus`, `securityCode`, `topologyType`, `behavior`, and `subsystemType` are serialised as `String` on the Kotlin side (`.name`) and typed as string union literals on the TypeScript side (e.g., `AlertStatus = 'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT'`). If a new enum constant is added to the Kotlin domain model (e.g., a new alert level or topology type), the TypeScript union is silently out of date; the UI switch/case for that field falls through to an unhandled branch with no compiler warning. The TypeScript exhaustiveness check only works if the UI actually uses exhaustive switches over these unions.

**Recommendation:**  
Audit all switch/render paths in the UI components that branch on these string unions and ensure they have explicit `default` or exhaustive branches that surface unexpected values (e.g., display the raw string rather than silently rendering nothing). Long-term, consider generating the TS types from the Kotlin enums at build time.

---

### [INFO] `pendingAction` write-then-read is `@Volatile`, not atomic

**Parts Affected:** server  
**File(s):**  
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:22`  
- `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107-117`

**Issue:**  
`pendingAction` is `@Volatile`, guaranteeing memory visibility across threads but not atomicity of compound operations. In `receiveAction`, there is a read-then-check: `val future = pendingAction; if (future == null || future.isDone) { ... }; future.complete(cmd)`. A theoretical race exists between `pendingAction` being set in `action()` and `receiveAction` reading it. In practice this is safe because `CompletableFuture.complete()` is idempotent on a completed future, and the Ktor coroutine scheduler processes frames sequentially per session. No code change is strictly required, but a comment explaining why the non-atomic pattern is safe here would prevent future confusion.

---

## Clean Seams

- **`DeckerStateDto` field mapping is exact.** All nine fields (`name`, `location`, `isPinnedByBlackIc`, `physicalDamage`, `physicalMaxBoxes`, `mentalDamage`, `mentalMaxBoxes`, `hackingPool`, `mcpRating`, `activeUtilities`) are identical in name, type, and nullability between `DeckerStateDto.kt` and the TypeScript `DeckerStateDto` interface.

- **`JoinMessage` and `ActionCommand` wire formats match perfectly.** Field names, types, and optional markers agree on both sides. The `ActionParams` sub-object matches across all five fields.

- **Error string constants are consistently defined.** The four server-side error codes (`not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`) are exactly mirrored in `App.tsx`'s `ERROR_LABELS` map.

- **`AvailableActionDto` action-dispatch round-trip is correct.** The `action.index` field sent by the UI is used directly as an index into `availableActions` in the server. Since the server populates that list with monotone `mapIndexed` indices, the index-based lookup in `WebSocketDeckerController.kt:95` is always valid for any in-range value.

- **`MatrixObjectDto` field mapping is complete.** All eight `kind` variants are defined in both `MatrixObjectDto.kt` and the TypeScript `MatrixObjectDto` union with matching field names and types. The `guardedNodeType: String?` / `string | null` nullability is consistent.

- **Role state machine is coherent.** The three roles (`observer`, `registered_decker`, `active_controller`) are used consistently in `SessionRegistry`, `WebSocketDeckerController`, `ControlMessage`, `StateMessage`, and the UI's `Role` type. Promotion and demotion paths in `SessionRegistry.promoteForTurn` / `demoteAfterTurn` are symmetric.

- **Reconnect / re-join flow handles the happy path correctly.** `pendingNameRef` survives disconnect and triggers a fresh `JoinMessage` on reconnect when the server sends the initial `observer` control message, allowing seamless re-entry after a dropped connection.
---
