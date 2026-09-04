# Maintainability Review — server

## Summary

The server layer is structurally sound: `TurnCoordinator` and `SessionRegistry` are well-separated, the DTO package maps cleanly to domain types, and the overall module boundary is clear. However, `WebSocketDeckerController` accumulates several maintainability debts: repeated failure-result boilerplate, a duplicated `RELOCATE_ICON` dispatch block, and a dead converter that can never be reached. Two classes are also coupled through raw `String` error keys instead of a shared typed enum, which makes renaming or extending error cases error-prone. Minor issues include a passthrough delegation wrapper that adds no value, a fully-qualified type reference that should be an import, and a redundant field in the DTO sealed hierarchy.

---

## Findings

### [HIGH] `RELOCATE_ICON` dispatch logic duplicated across two methods
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:149 and :297
**Issue:** The body of the `RELOCATE_ICON` branch is copy-pasted verbatim in both `dispatchGridOperation` (lines 149–156) and `dispatchMiscOp` (lines 297–303). Both blocks read `decker.trackState`, pass `trackingIcRating` to both parameters of `relocateIcon`, and call `.toDispatch()`. A future change (e.g. adding a third parameter or changing the fall-back value) must be made in two places.
**Recommendation:** Extract a private `dispatchRelocateIcon(diceRoller)` helper and call it from both sites.

**[RESOLVED]** — Fixed in `WebSocketDeckerController.kt`: `dispatchRelocateIcon` private helper extracted and called from both dispatch sites.

---

### [HIGH] Cross-class string coupling for error keys between `TurnCoordinator` and `SessionRegistry`
**File:** src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt:42–44 and SessionRegistry.kt:150–153
**Issue:** `TurnCoordinator.claimAction` returns raw `String` literals `"NOT_YOUR_TURN"` and `"NO_ACTION_PENDING"`. `SessionRegistry.receiveAction` then maps those same string literals back to `ErrorCode` enum values with a `when` expression. The two files are tightly coupled through untyped string constants; misspelling or renaming one side silently produces the `else -> ErrorCode.BAD_REQUEST` fallback with no compile-time warning.
**Recommendation:** Define a small sealed class or enum (e.g. `ClaimError`) in the `server` package with `NOT_YOUR_TURN` and `NO_ACTION_PENDING` cases, and change `claimAction` to return `Pair<CompletableDeferred<ActionCommand>?, ClaimError?>`.

**[DEFERRED]** — `ClaimError` sealed class not introduced; out of scope for this session.

---

### [MEDIUM] Failure `ResultMessage` construction repeated six or more times without a helper
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:55, 73, 77, 85, 92, 113, 116
**Issue:** Every early-exit path in the `action` method constructs a `ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = "...")` inline. The three zero-value fields are noise that obscures the intent and creates multiple mutation points if the message shape changes.
**Recommendation:** Add a private helper such as `fun failMessage(details: String) = ResultMessage(success = false, deckerSuccesses = 0, hostSuccesses = 0, details = details)` and replace all six call sites.

**[RESOLVED]** — Fixed in `WebSocketDeckerController.kt`: `broadcastFail` private helper extracted and used to replace all inline `ResultMessage(success=false,...)` broadcasts in `conductTurn`.

---

### [MEDIUM] `LocateDeckerResult.toDispatch()` is unreachable dead code
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:364
**Issue:** `LocateDeckerResult.toDispatch()` is defined as a private extension, but `LOCATE_DECKER` is explicitly short-circuited in `dispatchLocateOp` (line 233) with a hard-coded error string and is also filtered out of `availableActions` in `action` (line 45–46). No code path can reach the converter.
**Recommendation:** Delete `LocateDeckerResult.toDispatch()`. The corresponding comment in `dispatchLocateOp` sufficiently documents why the operation is unsupported.

**[DEFERRED]** — `LocateDeckerResult.toDispatch()` dead code not removed; out of scope for this session.

---

### [MEDIUM] `actionType` field in `AvailableActionDto` duplicates the JSON discriminator
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:15
**Issue:** Every subclass of `AvailableActionDto` carries an `actionType: String` field set to `actionType.name` — the same information already conveyed structurally by the `@JsonClassDiscriminator("kind")` / `@SerialName` annotation. The client receives both `kind` and `actionType` with effectively the same value (e.g. `"kind": "LogonToRtg"` and `"actionType": "LogonToRtg"`). This doubles the maintenance surface: changes to action types must be reflected in both the `@SerialName` annotation and the `actionType` value.
**Recommendation:** If the frontend only needs one discriminator field, remove `actionType` from the sealed class and its subclasses and rely solely on `kind`. If both fields are intentionally present for the frontend, document why.

**[DEFERRED]** — `actionType` field carries the action cost category (FREE/SIMPLE/COMPLEX), not the discriminator; the finding was a misread. Field retained as-is.

---

### [LOW] `Triple` used for opaque multi-value return inside `receiveJoin`
**File:** src/main/kotlin/com/shadowrun/matrix\server\SessionRegistry.kt:50
**Issue:** The mutex block returns `Triple(error, isReconnect, token)` where the three components have different types and meanings. Destructuring to `(error, isReconnect, token)` is workable but fragile — positional ordering is the only contract, and adding a fourth field requires touching every destructuring site.
**Recommendation:** Replace with a private local data class (e.g. `data class JoinOutcome(val error: ErrorCode?, val isReconnect: Boolean, val token: String?)`) for self-documenting, order-independent access.

**[RESOLVED]** — Fixed in `SessionRegistry.kt`: `Triple<ErrorCode?, Boolean, String?>` replaced with named `JoinOutcome` data class.

---

### [LOW] `setPendingAction` in `SessionRegistry` is a pure passthrough wrapper
**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:29
**Issue:** `SessionRegistry.setPendingAction` does nothing except delegate to `turns.setPendingAction`. All call sites in `WebSocketDeckerController` could call `registry.turns.setPendingAction(...)` directly, or `turns` could be made internal and exposed more narrowly.
**Recommendation:** Either remove the wrapper and expose `turns` directly, or keep the wrapper and add a brief comment explaining why the indirection exists (e.g. to allow `SessionRegistry` to intercept future side-effects).

**[DEFERRED]** — `setPendingAction` passthrough not removed or documented; out of scope for this session.

---

### [LOW] Fully-qualified type reference for `ActionParams` instead of an import
**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:311
**Issue:** The `locateWithState` method's parameter type is written as the fully-qualified `com.shadowrun.matrix.server.dto.ActionParams?` rather than the short name. `ActionParams` is not in the file's import list even though all sibling types from the same package (`ActionCommand`, `MatrixJson`, etc.) are imported.
**Recommendation:** Add `import com.shadowrun.matrix.server.dto.ActionParams` to the import block and use the short name in the signature.

**[DEFERRED]** — Fully-qualified `ActionParams` reference not shortened; out of scope for this session.

---

### [LOW] Misaligned indentation in `MatrixServer.kt` obscures control flow
**File:** src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:43–63
**Issue:** The `if (frame is Frame.Text)` block (lines 43–63) is indented at the same level as the surrounding `for` loop body and the inner `try` is further indented, but the closing `}` for the `if` at line 62 is placed at the `for`-body indent level, making the nesting structure hard to read at a glance.
**Recommendation:** Reformat so the `if` block body is consistently one indent level deeper than the `for` body, or refactor the `if` into `if (frame !is Frame.Text) continue` to flatten the nesting.

**[DEFERRED]** — `MatrixServer.kt` indentation not reformatted; out of scope for this session.

---

### [INFO] Enum-to-frontend contract documented only by a comment
**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt:10–13
**Issue:** The doc comment lists five Kotlin enum types that are serialised by `.name` (not `@SerialName`) and warns that the corresponding TypeScript union types must be kept in sync manually. There is no automated check.
**Recommendation:** Consider adding a test that serialises one instance of each enum value and asserts the expected JSON string, giving the CI pipeline a compile-time-adjacent safety net for the contract.

**[DEFERRED]** — No contract test added for enum serialisation; out of scope for this session.

---

## No Issues Found In

- `TurnCoordinator.kt` — small, focused, all state guarded by a single mutex; method names are precise.
- `DeckerDisconnectedException.kt` — single-purpose, correctly placed in the server package.
- `DeckerStateDto.kt` — clean mapping; `UtilityDto` is appropriately minimal; `MatrixLocation.label()` is concise and exhaustive.
- `MatrixObjectDto.kt` — `toDto` mapping and `targetName()` extension are well-structured; the sealed hierarchy mirrors the domain model cleanly.
- `Messages.kt` — `ErrorCode`, `SessionRole`, and the message data classes are consistently named and serialised; `MatrixJson` instance is properly shared.
- `dispatchHostOperation` — the large `when` arm is warranted by the domain operation count; sub-dispatch delegation keeps each arm shallow.
