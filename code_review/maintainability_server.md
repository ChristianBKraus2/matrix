---
# Maintainability Review — server

## Summary

The server layer is compact and its responsibilities are broadly well-separated, but several maintainability problems accumulate in the two largest files. `WebSocketDeckerController.kt` carries a very long `action()` method, a 25-case dispatch `when` block, repeated inline failure-broadcast blocks, dead extension functions, and suppressed unused parameters. `SessionRegistry.kt` leaks mutable turn-coordination state outward and repeats the same three role-string literals in five different places. The DTO layer introduces a cosmetic redundancy (`kind` mirroring `@SerialName`) that compounds across every sealed-class variant. None of these problems are catastrophic individually, but together they create friction for anyone extending the operation set or changing the wire protocol.

---

## Findings

### [HIGH] `runCatching` silently discards all WebSocket errors
**File:** src/main/kotlin/…/server/MatrixServer.kt:29
**Issue:** The entire message-handling body is wrapped in `runCatching { … }` with no `onFailure` branch. Serialization errors, unknown message types that fail to decode, and any exception from `registry.receiveJoin` / `registry.receiveAction` are all swallowed silently. The client receives no error reply and the server logs nothing. This makes debugging broken clients or protocol mismatches very hard in practice.
**Recommendation:** Add an `onFailure` handler that at minimum logs the exception and, where the session is still open, sends an `ErrorMessage` to the client. Use a more targeted `try/catch` around the decode steps rather than a blanket `runCatching` around the whole frame-handling block.

---

### [HIGH] Role strings are magic literals scattered across five methods in SessionRegistry
**File:** src/main/kotlin/…/server/SessionRegistry.kt:26, 46, 70, 81, 96–99
**Issue:** The strings `"observer"`, `"registered_decker"`, and `"active_controller"` appear hard-coded in `register`, `receiveJoin`, `promoteForTurn`, `demoteAfterTurn`, and `broadcastWithRoles`. A typo in any one place silently sends a role value the client cannot recognise.
**Recommendation:** Extract a `Role` object (or `enum class Role`) with `OBSERVER`, `REGISTERED_DECKER`, `ACTIVE_CONTROLLER` constants. All five call sites then reference the same source of truth, and the compiler enforces completeness.

---

### [HIGH] Failure-broadcast block duplicated four times in `action()`
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:56–60, 78–81, 85–88, 97–100
**Issue:** The pattern `runBlocking { registry.broadcast(MatrixJson.encodeToString(ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "…"))) }` is copy-pasted four times. Changing the wire format of a failure result requires four edits, and the `deckerSuccesses = 0, hostSuccesses = 0` boilerplate obscures the only part that actually varies (the `details` string).
**Recommendation:** Extract a private helper:
```kotlin
private fun broadcastFailure(details: String) = runBlocking {
    registry.broadcast(MatrixJson.encodeToString(
        ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = details)
    ))
}
```
All four call sites then become a single readable line.

---

### [HIGH] `dispatchHostOperation` is a 75-line, 25-case `when` block
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:154–235
**Issue:** Every `SystemOperation` value is handled inline in one enormous `when`. The method is too long to read in one pass, and the unsafe casts (`action.target as MatrixObject.IcProgram`, etc.) are scattered throughout with no single place to document why they are safe. Adding a new operation requires navigating the full block to find the right insertion point.
**Recommendation:** Group the cases into smaller private methods by semantic theme (e.g., `dispatchAnalyzeOperation`, `dispatchLocateOperation`, `dispatchDecryptOperation`). Each group is short enough to review at a glance. The top-level `when` then delegates to these helpers, acting as a routing table rather than an implementation.

---

### [HIGH] `action()` method handles too many concerns and is too long (~70 lines)
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:49–120
**Issue:** `action()` mixes turn-promotion, state serialisation, future creation and assignment, timeout handling, disconnection handling, index validation, dispatch, and result broadcast. Any one of those concerns changing requires reading the whole method. The method's cyclomatic complexity (five return points, three catch branches, two null checks) makes it hard to reason about the happy path.
**Recommendation:** Extract the "wait for player command" logic into a private `awaitCommand(): ActionCommand?` method that handles the timeout and disconnection paths and returns null on failure. `action()` then reads as: promote → build state → await command → validate → dispatch → broadcast → demote.

---

### [MEDIUM] `pendingAction` is a public mutable field on `SessionRegistry`
**File:** src/main/kotlin/…/server/SessionRegistry.kt:22 and WebSocketDeckerController.kt:71, 93
**Issue:** `pendingAction` is a `@Volatile var` that `WebSocketDeckerController` writes directly (`registry.pendingAction = future`, `registry.pendingAction = null`). The registry owns the field but the controller owns its lifecycle. A second controller could overwrite it, and there is no invariant enforcing that only one writer exists at a time. The public setter also means any code can cancel or replace an in-flight future.
**Recommendation:** Move `pendingAction` lifecycle management inside `SessionRegistry`. Add `fun openTurn(): CompletableFuture<ActionCommand>` and `fun closeTurn()` methods so the registry is the sole writer. `WebSocketDeckerController` calls these methods rather than assigning the field directly.

---

### [MEDIUM] `@Suppress("UNUSED_PARAMETER")` marks genuinely unused parameters
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:145, 241–242
**Issue:** `cmd` in `dispatchGridOperation` and `host`/`diceRoller` in `locateWithState` are suppressed rather than removed or replaced with `_`. Suppression hides the smell; the parameters exist only because the call signature was written uniformly across grid and host paths, but the grid path does not use them.
**Recommendation:** Replace unused parameters with `_` (idiomatic Kotlin), or, if they are truly absent from all current and near-future grid operations, remove them from the grid-only overload entirely.

---

### [MEDIUM] `LocateDeckerResult.toDispatch()` is dead code
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:301–304
**Issue:** The extension function `LocateDeckerResult.toDispatch()` is defined but never called. `LOCATE_DECKER` in `dispatchHostOperation` (line 220) returns a hardcoded `DispatchResult` with a "not supported" message. The extension is therefore unreachable.
**Recommendation:** Delete the `LocateDeckerResult.toDispatch()` extension. If `LOCATE_DECKER` support is planned, add a TODO comment at the dispatch site instead of keeping dead code.

---

### [MEDIUM] `else ->` fallthrough in `dispatchHostOperation` hides unhandled operations
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:233
**Issue:** The `when` block ends with `else -> DispatchResult(decker, false, 0, 0, "Unsupported: ${action.operation}")`. Because `SystemOperation` is an enum, using `else` instead of listing each variant means the compiler will not warn when a new `SystemOperation` value is added without a handler. New operations will silently return failure at runtime.
**Recommendation:** Remove the `else` branch and list each unimplemented operation explicitly (e.g., `SystemOperation.LOCATE_DECKER`, `SystemOperation.SWAP_MEMORY`) with their individual stub messages. The compiler then flags any future enum addition as an exhaustiveness error.

---

### [MEDIUM] `kind` field redundantly duplicates `@SerialName` in every DTO subclass
**File:** src/main/kotlin/…/server/dto/MatrixObjectDto.kt:13–101, AvailableActionDto.kt:14–48
**Issue:** Every subclass declares both `@SerialName("GridNode")` and `override val kind: String = "GridNode"`. The `@SerialName` annotation drives kotlinx.serialization's polymorphic discriminator; `kind` is an additional payload field that carries the same string. They must be kept in sync manually across 8 + 7 = 15 subclass declarations. If they ever diverge the client receives two different discriminator values.
**Recommendation:** If the client needs a runtime-accessible type string in the JSON body, replace the default-value fields with `@Transient` and derive `kind` from the serializer's class descriptor, or accept the `@SerialName` value is the discriminator and rely on it. Alternatively, if `kind` must remain a body field, at minimum annotate it with a compile-time check (a unit test asserting `kind == this::class.simpleName` for all instances) so the duplication is at least validated.

---

### [MEDIUM] Two-pass JSON parsing for every incoming frame
**File:** src/main/kotlin/…/server/MatrixServer.kt:31–34
**Issue:** Each incoming frame is parsed twice: once into a `JsonElement` to peek at the `"type"` field, and once again with the concrete decoder. For high-frequency sessions this doubles deserialisation work and requires the raw string to be held in memory across both passes.
**Recommendation:** Use a single polymorphic sealed class (e.g., `sealed class ClientMessage`) registered with a `Json { classDiscriminator = "type" }` instance. `Json.decodeFromString<ClientMessage>(json)` then does one pass and yields the correct subtype directly, eliminating the manual `when (msgType)` routing and the double-parse.

---

### [LOW] Magic zeros for `deckerSuccesses`/`hostSuccesses` in failure results
**File:** src/main/kotlin/…/server/WebSocketDeckerController.kt:57, 79, 87, 98 and throughout `dispatch`
**Issue:** Failure `DispatchResult` and `ResultMessage` constructions pass literal `0, 0` for the success counts. The intent ("not applicable on failure") is non-obvious to a reader who does not already know the domain.
**Recommendation:** This is partially addressed by the `broadcastFailure` helper suggested above. Separately, consider adding a `ResultMessage.failure(details: String)` companion factory that bakes in `success = false, deckerSuccesses = 0, hostSuccesses = 0` so call sites are not required to repeat these values.

---

### [LOW] `ActionParams` is a flat catch-all bag that will grow unboundedly
**File:** src/main/kotlin/…/server/dto/Messages.kt:31–37
**Issue:** `ActionParams` currently holds five unrelated optional fields (`newContent`, `inactivitySeconds`, `precision`, `hasValidPasscode`, `scannerDeviceRating`), each meaningful only for a specific operation. Every new parameterised operation adds another nullable field here, making it impossible to tell which combination of fields is valid for which operation.
**Recommendation:** Consider replacing `ActionParams` with a sealed `ActionPayload` hierarchy (one subclass per operation that needs extra params), or at minimum document in a comment which fields belong to which operation so the contract is explicit.

---

### [INFO] `actionType: String` in `AvailableActionDto` loses enum type safety
**File:** src/main/kotlin/…/server/dto/AvailableActionDto.kt:12
**Issue:** The abstract `actionType: String` is always populated from a domain enum's `.name`. Keeping it as `String` in the DTO means a future refactoring that renames the enum variant will silently produce a different wire value without a compile error. The change is invisible until a client that parses `actionType` breaks.
**Recommendation:** This is a deliberate DTO boundary choice and is acceptable, but it should be covered by a serialisation round-trip test. If the wire value is considered stable (i.e., decoupled from the Kotlin name), the mapping should be made explicit with a dedicated constant or a `@SerialName` on the enum itself.

---

## Clean Areas

- **MatrixServer.kt** — The overall structure is admirably minimal. Routing, WebSocket handling, and static resource serving are all in one short, readable function. The separation between `matrixModule` (testable with a registry stub) and `startMatrixServer` (production entry point) is clean.
- **DeckerDisconnectedException.kt** — A purpose-built exception type with a clear message. Correctly used to signal structured disconnection through the `CompletableFuture` boundary.
- **DeckerStateDto.kt** — Concise, complete, and the private `MatrixLocation.label()` extension is well-scoped. The mapping function reads as a straightforward one-to-one projection.
- **SessionRegistry.register / deregister** — The symmetric register/deregister pair is consistent and correctly maintains all three data structures (`sessions`, `deckerSessions`, `sessionDecker`) in sync within a single `synchronized` block.
- **SessionRegistry.broadcast / broadcastWithRoles** — The snapshot pattern (`sessions.toList()` inside the lock, then iterate outside it) correctly avoids holding the lock during I/O, which is the right approach for coroutine-compatible code.
- **AvailableActionDto.toDto / MatrixObjectDto.toDto** — The `mapIndexed` extension pattern for index assignment is clean and avoids any index management bugs.
---
