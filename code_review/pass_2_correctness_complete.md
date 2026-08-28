# Correctness Review — complete (cross-cutting)

## Summary

The three-component stack is largely well-typed at the wire boundary: the `@JsonClassDiscriminator("kind")` sealed-class strategy, the `@SerialName` annotations on role/error enums, and the hand-maintained comment in `MatrixObjectDto.kt` calling out the five raw `.name`-serialised enums all hold up under inspection. However, two result-translation bugs in `WebSocketDeckerController` send structurally valid but semantically wrong data to the client: `AnalyzeSecurityResult` always reports `success: true` regardless of the dice outcome, and logon/logoff conversions always discard dice roll counts. A third cross-cutting issue is that the TypeScript `SystemOperation` union type contains four values (`GRACEFUL_LOGOFF`, `LOGON_TO_RTG`, `LOGON_TO_LTG`, `LOGON_TO_HOST`) that the server never emits inside an `Operation` DTO variant — widening the contract beyond what the wire ever carries. Remaining findings are lower-severity: a permanent reconnect banner, a silent grid-operation fallback, reflective `simpleName` use, and an asymmetric JSON codec pair.

## Findings

### [HIGH] `AnalyzeSecurityResult.toDispatch()` hardcodes `success = true`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:283`
**Issue:** The converter always constructs `DispatchResult(decker, true, ...)` regardless of `outcome.deckerWins`. Every other result adapter that wraps a contested roll uses the outcome boolean — `AnalyzeHostResult.toDispatch()` (line 280) and `EditFileResult.toDispatch()` (line 290) both use `outcome.deckerWins`. As a result the `ResultMessage` sent to the UI always carries `success: true` for `ANALYZE_SECURITY` even when the host wins the roll, giving the player incorrect feedback about the operation outcome.
**Recommendation:** Change the return to `DispatchResult(decker, outcome.deckerWins, outcome.deckerSuccesses, outcome.hostSuccesses, details)`, consistent with the other roll-based converters.

---

### [MEDIUM] TypeScript `SystemOperation` type is wider than the wire contract
**File:** `frontend/src/types/messages.ts:75`
**Issue:** The `SystemOperation` union includes `'GRACEFUL_LOGOFF'`, `'LOGON_TO_RTG'`, `'LOGON_TO_LTG'`, and `'LOGON_TO_HOST'`. The server never serialises these as the `operation` field of a `kind: 'Operation'` DTO — it uses the dedicated `AvailableActionDto.GracefulLogoff`, `LogonToRtg`, `LogonToLtg`, and `LogonToHost` variants instead. Any UI code that branches on `operation` (e.g. to show parameter inputs or action labels) could include dead branches for these four values, and any future exhaustiveness check or switch over `SystemOperation` would be falsely wide. If the game logic ever inadvertently generates `AvailableAction.Operation(operation = LOGON_TO_RTG)`, the server's `dispatchGridOperation` would also silently return `success: false` with a confusing "requires host context" message.
**Recommendation:** Remove `'GRACEFUL_LOGOFF' | 'LOGON_TO_RTG' | 'LOGON_TO_LTG' | 'LOGON_TO_HOST'` from the `SystemOperation` type. Add a comment parallel to the one in `MatrixObjectDto.kt` explaining which Kotlin enum each TS type mirrors.

---

### [MEDIUM] Logon and logoff dice data silently discarded in `ResultMessage`
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:257`
**Issue:** Both `LogonResult.toDispatch()` (lines 257–260) and `LogoffResult.GracefulSuccess.toDispatch()` (line 263) hardcode `deckerSuccesses = 0, hostSuccesses = 0`. Shadowrun 2e logon attempts (especially to secured hosts) involve contested rolls; discarding the counts means the `NarrativePanel` always displays "0 decker vs 0 host" for every logon and graceful logoff, stripping the player of roll feedback that informs tactical decisions about whether to stay or jack out.
**Recommendation:** If `LogonResult.Success` and `LogoffResult.GracefulSuccess` carry an outcome or success-count field, propagate them to `DispatchResult`. If the underlying game objects do not yet carry counts for these operations, consider adding them so the full dice result reaches the UI.

---

### [MEDIUM] `dispatchGridOperation` silently fails all non-RELOCATE operations
**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:151`
**Issue:** When the decker is not inside a host, `dispatch()` routes any `AvailableAction.Operation` to `dispatchGridOperation`, which handles only `RELOCATE_ICON` and falls through with `"${action.operation} requires host context"` for everything else. This is a silent design constraint: if `Decker.availableActions()` ever exposes a non-RELOCATE `Operation` while the decker is on the grid (e.g., due to a bug or future feature), the action is accepted by the client, submitted to the server, and silently returns `success: false` with no indication of a programming error versus a legitimate failure.
**Recommendation:** Add an assertion or log at WARN level inside the `else` branch of `dispatchGridOperation` so unexpected operation names surface during development. Alternatively, filter at the `availableActions()` / DTO layer so grid-context `Operation` entries can only contain `RELOCATE_ICON`.

---

### [LOW] Reconnect banner is never cleared within a session
**File:** `frontend/src/hooks/useWebSocket.ts:43` / `frontend/src/App.tsx:103`
**Issue:** The `CONTROL` reducer sets `reconnected: true` when `msg.reconnect === true` but never resets it to `false` within the same session — it only clears on `DISCONNECTED`. The `App.tsx` reconnect banner (`"SESSION RESTORED — reconnected to active game"`) therefore remains visible for the entire remainder of the session once a reconnect has occurred.
**Recommendation:** Add a `CLEAR_RECONNECT` action dispatched after a short timeout (e.g., 5 seconds), or reset `reconnected` to `false` in the `STATE` reducer on the first full state message received after reconnect.

---

### [LOW] `targetKind` uses `simpleName` which can return null
**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:66`
**Issue:** `targetKind = target?.let { it::class.simpleName }` relies on Kotlin reflection. `KClass.simpleName` returns `null` for anonymous and local classes. All current `MatrixObject` variants are named sealed subclasses, so this is safe today, but it is a fragile pattern: any anonymous lambda or object expression used as a `MatrixObject` in the future would silently produce `null` where the UI expects a descriptive string.
**Recommendation:** Replace with an explicit `when (target) { ... }` mapping to string literals, parallel to the `targetName()` extension on line 70, eliminating the reflection dependency and making the set of possible `targetKind` values part of the compile-time contract.

---

### [INFO] Asymmetric JSON codec: `MatrixJson` for outbound, plain `Json` for inbound
**File:** `src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt:37` / `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:7`
**Issue:** Outgoing messages are encoded with `MatrixJson { encodeDefaults = true }` while incoming `JoinMessage` and `ActionCommand` frames are decoded with the default `Json {}` codec. The asymmetry is currently harmless — `encodeDefaults` only affects encoding, and default decoding correctly handles nullable fields with Kotlin defaults. However, if future configuration diverges (e.g., adding `namingStrategy`, `explicitNulls = false`, or `coerceInputValues`) to `MatrixJson`, the inbound decoder will not share those settings, creating subtle inconsistencies.
**Recommendation:** Create a single module-level `val MatrixJson = Json { encodeDefaults = true }` and use it for both `encodeToString` and `decodeFromString` calls in `MatrixServer.kt`.

## No Issues Found In

- **Role serialisation contract** — `SessionRole` `@SerialName` values (`"observer"`, `"registered_decker"`, `"active_controller"`) match the TypeScript `Role` union exactly.
- **ErrorCode serialisation** — all seven `@SerialName` values on `ErrorCode` match the TypeScript `ErrorCode` union, and `App.tsx`'s `ERROR_LABELS` record covers all seven with no gaps.
- **Sealed-class discriminator** — `@JsonClassDiscriminator("kind")` on both `MatrixObjectDto` and `AvailableActionDto` matches the `kind` field used in every TypeScript union branch.
- **Enum wire values for `AlertStatus`, `SecurityCode`, `TopologyType`, `SubsystemType`, `IcBehavior`** — all five Kotlin enums match their TypeScript counterparts exactly; the `MatrixObjectDto.kt` comment correctly lists them as `.name`-serialised.
- **`ActionType` enum** — `FREE | SIMPLE | COMPLEX` matches the Kotlin enum in both name and serialisation.
- **`ActionParams` nullability** — optional `params` field omitted by the client is correctly handled as `null` by the server's default-value deserialization.
- **`broadcastWithRoles` per-session role stamping** — the role is correctly overridden per session inside the synchronized block before serialization, so no session ever receives another session's role in a `StateMessage`.
- **TOCTOU fix in `setPendingAction` / `promoteForTurn` ordering** — `pendingAction` is set before `promoteForTurn` sends the `ControlMessage`, preventing the race where a fast client could submit an action before the deferred was registered.
- **`DeckerStateDto` field set** — all ten fields match between the Kotlin DTO and the TypeScript interface.
