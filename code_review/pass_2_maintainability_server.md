# Maintainability Review — server

## Summary

The server component is well-structured overall: the WebSocket message loop is clean, the registry's locking discipline is clear, and the DTO layer is self-contained. The main maintainability debt lives in `WebSocketDeckerController.kt`, which has a single very long `when` expression (`dispatchHostOperation`, ~80 lines, 20+ branches), at least one confirmed dead private function, and a repeated "failure result" construction that appears six or more times inline. A suppressed compiler warning masks a related design smell. The registry carries three manually-synchronised parallel maps that are error-prone to evolve. Several magic numbers are scattered across the component without named constants.

---

## Findings

### [HIGH] Dead code: `LocateDeckerResult.toDispatch()` is never called
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:293`
**Issue:** The private extension function `LocateDeckerResult.toDispatch()` is defined but has no call site in the file. `LOCATE_DECKER` in `dispatchHostOperation` returns a stub `DispatchResult` directly (line 222) rather than invoking this converter, and no other dispatch path produces a `LocateDeckerResult`.
**Recommendation:** Remove the function. If `LOCATE_DECKER` is ever implemented properly, introduce the call site at that time.

---

### [HIGH] Unchecked casts throughout `dispatchHostOperation`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:172–226`
**Issue:** Twelve or more `action.target as MatrixObject.X` casts are performed without a null check or type guard. If `action.target` is `null` or the wrong subtype (e.g. due to a future refactor of `AvailableAction`), the function throws `ClassCastException` or `NullPointerException` at runtime rather than returning a structured error.
Examples:
```kotlin
val ic = (action.target as MatrixObject.IcProgram).ic          // line 172
val node = (action.target as MatrixObject.HostSubsystem).node  // line 181
val file = (action.target as MatrixObject.File).file           // line 189
```
**Recommendation:** Use `as?` with an explicit null-check and return a `DispatchResult` error payload, or add a precondition guard at the top of each branch:
```kotlin
val ic = (action.target as? MatrixObject.IcProgram)?.ic
    ?: return DispatchResult(decker, false, 0, 0, "Expected IcProgram target")
```

---

### [MEDIUM] `@Suppress("UNUSED_PARAMETER")` masks a design smell on `cmd`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:149`
**Issue:** `dispatchGridOperation` accepts `cmd: ActionCommand` purely for signature symmetry with `dispatchHostOperation`, but never uses it. The suppression annotation silences the compiler warning rather than fixing the root cause. The task description also mentioned suppressed warnings on `host` and `diceRoller`; as of this read those are not present, only `cmd` remains.
**Recommendation:** Remove `cmd` from `dispatchGridOperation`'s signature and update the two call sites in `dispatch()` to omit it. If symmetric signatures are desired for a future abstraction, introduce a common function type or interface rather than silencing the warning.

---

### [MEDIUM] DRY violation: failure `ResultMessage` constructed inline six+ times
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:58–119`
**Issue:** The pattern `ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "...")` appears verbatim on lines 58–62, 76, 80, 88, 95, and 116–119. Each repetition must be updated in lockstep if the message structure changes.
**Recommendation:** Extract a private helper:
```kotlin
private fun failResult(details: String) =
    ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = details)
```
and replace all six inline constructions with `MatrixJson.encodeToString(failResult("..."))`.

---

### [MEDIUM] `dispatchHostOperation` — high cyclomatic complexity
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:158–237`
**Issue:** The function spans ~80 lines and contains a single `when` expression with 20+ branches, several of which contain multi-line sub-expressions. Cyclomatic complexity is at least 22. This makes the function difficult to navigate, test independently, or extend without risk of touching unrelated branches.
**Recommendation:** Group related operations into smaller private helpers (e.g. `dispatchAnalyzeOperation`, `dispatchLocateOperation`, `dispatchFileOperation`) and delegate from the `when` expression. Each helper would be independently testable and under 15 lines.

---

### [MEDIUM] `SWAP_MEMORY` and `LOCATE_DECKER` filtered from actions *and* handled as stubs in dispatch
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:48–50, 222, 231`
**Issue:** Lines 48–50 filter these two operations out of the available-actions list sent to the client, so they can never be chosen. Yet `dispatchHostOperation` still has explicit stub cases for both. The filtering makes the stub cases unreachable, which is contradictory — either the filtering is the right boundary (in which case the stubs are dead), or the stubs are the right boundary (in which case the filter is premature).
**Recommendation:** Decide on a single enforcement point. If WebSocket support is simply not implemented for these operations, keep the stubs in `dispatch` (as the authoritative guard) and remove the pre-filter, or add a comment explicitly explaining why both layers exist.

---

### [MEDIUM] `synchronized(Any())` blocks coroutine threads
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:17, 27, 31, 41, 65, 85, 95, 105, 112, 129`
**Issue:** `SessionRegistry` uses a plain Java `synchronized` block on a `val lock = Any()`. In a Ktor/coroutine environment, `synchronized` pins the underlying OS thread for the duration, potentially blocking other coroutines on the same dispatcher. The suspend functions that call into `synchronized` blocks (`register`, `receiveJoin`, `deregister`, etc.) are subject to this.
**Recommendation:** Replace `val lock = Any()` and all `synchronized(lock) { }` blocks with `kotlinx.coroutines.sync.Mutex` and `mutex.withLock { }`. This keeps the coroutine scheduler free and is idiomatic for Ktor.

---

### [MEDIUM] Three manually-synchronised parallel maps in `SessionRegistry`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:18–20`
**Issue:** `sessions`, `deckerSessions`, and `sessionDecker` are three separate collections that must stay in sync on every register/deregister. Any future maintenance that touches one map without the others will silently introduce inconsistency. The existing code is correct but fragile.
**Recommendation:** Introduce a small `DeckerSession` data class that bundles `(name: String, session: DefaultWebSocketServerSession)` and hold a single `LinkedHashMap<DefaultWebSocketServerSession, DeckerSession>` (or similar), deriving the reverse-lookup inline. This collapses three structures to one.

---

### [LOW] Magic number: max decker name length
**File:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:37`
**Issue:** `if (name.length > 32)` embeds the policy limit as a bare literal.
**Recommendation:** `private const val MAX_DECKER_NAME_LENGTH = 32`

---

### [LOW] Magic number: error-message truncation length
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:46`
**Issue:** `e.message?.take(120)` truncates at 120 characters with no named constant or comment explaining the choice.
**Recommendation:** `private const val MAX_ERROR_DETAIL_LENGTH = 120`

---

### [LOW] Magic number: file content size cap
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:201`
**Issue:** `content.length > 4096` is a policy limit embedded as a bare literal.
**Recommendation:** `private const val MAX_FILE_CONTENT_BYTES = 4096`

---

### [LOW] Magic zeros in `relocateIcon` calls
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:153, 230`
**Issue:** Both `relocateIcon(opponentSensor = 0, trackerMcpRating = 0, diceRoller)` calls pass zero for two parameters without explanation. It is unclear whether zero is "not applicable on the grid", a deliberate game rule, or a placeholder.
**Recommendation:** Add a comment or introduce named constants (`NO_OPPONENT_SENSOR`, `NO_TRACKER`) to document intent.

---

### [LOW] Double JSON parse in `MatrixServer.kt`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:35–38`
**Issue:** Each incoming message is parsed twice: once to extract the `type` field (line 35) and again for the full decode (lines 37–38). On high-frequency connections this adds unnecessary overhead and doubles parse-error surface.
**Recommendation:** Parse once into a `JsonElement`, then use `Json.decodeFromJsonElement<JoinMessage>(element)` (or a sealed-class discriminated union) to avoid the second parse.

---

### [LOW] Stringly-typed `precision` field in `ActionParams`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:53`
**Issue:** `val precision: String?` in `ActionParams` represents a `QueryPrecision` enum value transmitted as a raw string. The conversion uses `runCatching { QueryPrecision.valueOf(it) }.getOrNull()`, silently falling back to `NORMAL` on any typo.
**Recommendation:** Annotate with `@Serializable` and use the `QueryPrecision` enum directly, or at minimum document the valid values. The silent fallback to `NORMAL` on an invalid precision string could mask client bugs.

---

### [LOW] Indentation inconsistency in `MatrixServer.kt`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:32–50`
**Issue:** The `try` block on line 33 is at the same indentation level as the enclosing `if (frame is Frame.Text)` on line 32, rather than one level deeper. The closing braces on lines 50–51 are correct but the opening structure is misleading.
**Recommendation:** Indent the `try { ... }` body one extra level to reflect that it is inside the `if` body.

---

### [INFO] `actionType: String` is redundant with the `kind` JSON discriminator
**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:15, 19–51`
**Issue:** Every `AvailableActionDto` subclass declares `val actionType: String` (set from the domain enum's `.name`) and is also tagged with a `@SerialName`/`@JsonClassDiscriminator("kind")`. The `kind` field already uniquely identifies the action type; `actionType` carries the same information in a different format. Clients must handle both fields.
**Recommendation:** Evaluate whether `actionType` is consumed by the frontend for a distinct purpose. If not, remove it from the DTO and derive the label from `kind` on the client side.

---

### [INFO] `"not jacked in"` magic string label
**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:26`
**Issue:** The location label `"not jacked in"` is a bare string literal. If frontend code checks this string to determine UI state it becomes a fragile coupling.
**Recommendation:** Consider using a nullable `location: String?` (null = not jacked in) or a typed location DTO so clients can branch on structure, not string content.

---

## No Issues Found In

- `DeckerDisconnectedException.kt` — minimal and appropriate.
- `MatrixObjectDto.kt` — the doc comment correctly calls out the enum-as-raw-string contract with the frontend, enum serialization is consistent throughout, and mapper functions are straightforward.
- `Messages.kt` — `ErrorCode` and `SessionRole` use `@SerialName` consistently; the message type hierarchy is clear.
- `SessionRegistry.broadcast` / `broadcastWithRoles` — snapshot-then-send pattern correctly avoids holding the lock during I/O.
- `WebSocketDeckerController.action` TOCTOU fix — the comment on lines 51–52 correctly explains why `setPendingAction` is called before `promoteForTurn`, and the implementation matches the comment.
- `DispatchResult` private data class — appropriate scoping, fields are clear.
- `LogonResult.toDispatch`, `LogoffResult.toDispatch`, `AnalyzeHostResult.toDispatch`, `AnalyzeSecurityResult.toDispatch`, `EditFileResult.toDispatch` — concise and correct converters with no duplication between them.
