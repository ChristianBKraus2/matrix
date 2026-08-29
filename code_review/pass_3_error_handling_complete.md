# Error Handling Review — complete (cross-cutting)

## Summary

Each layer has its own error-handling discipline, but the seams between them create compounding failure modes that are invisible when reviewing any single layer in isolation. The most severe cross-cutting problem is a complete diagnostic blackout: game_logic throws rich, descriptive exceptions (`require`/`requireNotNull`/`check`), the server's dispatch catch block swallows them without logging and sends only a generic string, and the UI has no structured path to surface that string as anything other than a narrative footnote. A second systemic failure is a silent 120-second game freeze: the server marks a controller active before confirming the client received that notification; if the socket dies at that moment, the client's `sendAction` silently drops the response and all players wait in silence until the turn times out. A third structural fragility is an implicit runtime contract between the game_logic and server layers: game_logic guarantees specific `MatrixObject` subtype/operation pairings in `availableActions()`, and the server uses hard casts on those pairings with no fallback other than the unlogged catch block. All three problems require reading across all three layers to see; none is visible within a single part.

---

## Findings

### [HIGH] Game_logic descriptive exceptions are fully consumed at the server boundary, reaching the UI as an opaque string

**File:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:112-118`
**Issue:** Game_logic operations throughout `DeckerOperationsExtensions.kt`, `DeckerNavigationExtensions.kt`, and `CombatResolver.kt` use `require`, `requireNotNull`, and `check` with detailed diagnostic messages (e.g., `"resolvePointerChain: file '${file.name}' has isPointer=true but pointerToHost is null"`). When any such exception propagates through `dispatch()` into the outer catch at line 112, no logger is present in the file, no message is recorded, and the client receives only `"Internal error — turn aborted"`. The UI's `NarrativePanel` has no special rendering path for this string; it appears as a bare result detail. The diagnostic value produced by the game_logic layer is entirely destroyed at the server boundary before it can reach the user.
**Recommendation:** Add `private val logger = KotlinLogging.logger {}` to `WebSocketDeckerController` and call `logger.error(e) { "dispatch failed for decker ${decker.name}, action ${chosen}" }` inside the catch block. Additionally, consider whether a subset of non-sensitive game-logic error messages (e.g., "not jacked in") should be forwarded to the client as `ErrorMessage(BAD_REQUEST, details = e.message)` rather than the generic "Internal error" string, so the UI can display them through the existing `NarrativePanel` error path.

---

### [HIGH] `promoteForTurn` send failure combined with `sendAction` silent drop causes a silent 120-second turn freeze visible to all players

**Files:** `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:107-108` and `frontend/src/hooks/useWebSocket.ts:130,156`
**Issue:** This failure scenario requires all three layers to reproduce:
1. `SessionRegistry.promoteForTurn()` calls `turns.setActive(session)` on line 107 before `session.send(ACTIVE_CONTROLLER)` on line 108 (identified in the server review). If the send throws, the server believes a controller is waiting but the client never received its role upgrade.
2. The client's `ws.onerror = () => ws.close()` (line 130 of `useWebSocket.ts`) discards all error diagnostic information and triggers reconnect.
3. After reconnect, the client's `sendAction` at line 156 silently returns when the socket is not `OPEN`, meaning the action the server is waiting for is never sent.
4. The server holds the promoted state, no log entry is emitted (no logger in `WebSocketDeckerController`), and the game appears frozen to all connected players until `actionTimeoutSeconds` (default 120 s) expires.

No single-layer review surfaces this scenario because each piece looks locally reasonable: the send ordering is a narrow race, the silent `sendAction` drop seems harmless for non-active-turn clients, and the onerror close is standard practice. Together they combine into a silent, user-visible hang.
**Recommendation:** Apply the server-review fix (send first, then `setActive` only on success). Additionally, have `ws.onerror` log the `ErrorEvent` argument and dispatch a synthetic `ERROR` event so the UI shows a visible "connection lost" notice when the user is the active controller. This both prevents the freeze and gives the user feedback to reload.

---

### [HIGH] Server hard-casts on game_logic domain objects create an implicit, unenforceable cross-layer contract; failures route to the unlogged catch

**Files:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:212,243,249,259,270,274,278` and `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:183-194`
**Issue:** `Decker.addHostSystemActions()` constructs `AvailableAction.Operation` instances with specific `MatrixObject` subtypes as targets (e.g., `DOWNLOAD_DATA` always paired with `MatrixObject.File`, slave ops always with `MatrixObject.Device`). The server's dispatch helpers rely on this pairing via hard casts such as `(action.target as MatrixObject.File)` and `(action.target as MatrixObject.Device)` with no safe-cast guard. This creates an undocumented runtime contract between the two layers: any refactoring of `availableActions()` or `addHostSystemActions()` that changes a target subtype will produce `ClassCastException` at runtime, which is caught silently by the outer catch at line 112, producing an unlogged "Internal error — turn aborted" to the UI. The five operations that correctly use safe cast (`as?`) with a guard return — `ANALYZE_IC` and `ANALYZE_ICON` on lines 201-207 — demonstrate the correct pattern is already known but not uniformly applied.
**Recommendation:** Replace all remaining hard casts in `dispatchDataOp`, `dispatchSlaveOp`, and `dispatchAnalyzeOp` with safe casts followed by an explicit guard (returning a descriptive `DispatchResult` on mismatch), matching the pattern already used for `ANALYZE_IC`. This both prevents the opaque crash and documents the expected target type inline.

---

### [MEDIUM] `ErrorCode` enum is fully defined across all three layers but `NarrativePanel` only maps 4 of 7 codes

**Files:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:17-25`, `frontend/src/types/messages.ts:116-124`, and `frontend/src/components/NarrativePanel.tsx:3-8`
**Issue:** The full cross-layer chain for error reporting is: Kotlin `ErrorCode` enum (7 variants with `@SerialName`) → TypeScript `ErrorCode` union type (all 7 values, correct) → `NarrativePanel.ERROR_LABELS` map (only 4 values: `not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`). The three missing codes — `name_too_long`, `unknown_message_type`, `bad_request` — fall through to the raw snake_case server string. The TypeScript type layer is correct, but the presentation layer is inconsistent with it. This is only visible when tracing all three files together.
**Recommendation:** Export a single authoritative `ERROR_LABELS` map from `frontend/src/types/messages.ts` (or a shared `utils/errorLabels.ts`) covering all 7 `ErrorCode` values, and import it in both `App.tsx` and `NarrativePanel.tsx`. This eliminates the duplication noted in the UI review and ensures new error codes added to the Kotlin enum require only one TypeScript update.

---

### [MEDIUM] No `StateMessage` broadcast after a turn resolves; observers see stale game state until the next turn begins

**Files:** `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:68,105-110`
**Issue:** `broadcastWithRoles(stateBase)` on line 68 is the only `StateMessage` broadcast per turn; it fires at the start of the turn with pre-action state. After `dispatch()` runs and `context.applyDeckerOperationResult()` mutates the game state, only a `ResultMessage` is broadcast (lines 105-110). Observer clients therefore receive a result string ("3 decker vs 1 host") but their displayed decker state, condition monitors, active utilities, and visible objects all remain at the pre-action snapshot until the following turn's state broadcast. If the turn was the last in the round (or the game ends), observers never receive the final state. The error-handling angle is that damage, utility depletion, and tally changes that result from game_logic operations are never propagated to the UI in the same round they occur.
**Recommendation:** After broadcasting the `ResultMessage`, re-query the updated decker state and broadcast a follow-up `StateMessage` with the post-action state. Alternatively, embed the updated `DeckerStateDto` directly in `ResultMessage` so observers can apply it without a second message.

---

### [LOW] `LOCATE_DECKER` and `SWAP_MEMORY` are silently suppressed at the server layer with no client-visible explanation

**Files:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:167,174` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:45-46`
**Issue:** `Decker.addHostSystemActions()` emits `LOCATE_DECKER` and `SWAP_MEMORY` as available operations in the domain list. `WebSocketDeckerController.action()` silently filters them out before sending `StateMessage` to the client. The UI therefore never shows these actions, with no tooltip, greyed-out state, or error to explain their absence. A player who knows these operations exist from documentation has no server- or client-side feedback about why they are unavailable over WebSocket.
**Recommendation:** Either remove `LOCATE_DECKER` and `SWAP_MEMORY` from `Decker.addHostSystemActions()` entirely (if they are genuinely unsupported for all server-connected clients), or send them in the state with a `supported: false` field in `AvailableActionDto.Operation` so the UI can render them as disabled with a reason.

---

### [INFO] Enum serialization strategy is inconsistent across the server–client boundary

**Files:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt` and `src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt`
**Issue:** `SessionRole` and `ErrorCode` in `Messages.kt` use `@SerialName` annotations to serialize as lowercase/snake_case strings (`"observer"`, `"not_your_turn"`), and TypeScript mirrors these exactly. `SystemOperation` and `ActionType`, however, are serialized via Kotlin's `.name` property, producing SCREAMING_SNAKE_CASE strings (`"ANALYZE_HOST"`, `"FREE"`). The `AvailableActionDto` variant discriminators use `@SerialName` with PascalCase class names (`"Operation"`, `"LogonToRtg"`). TypeScript types are currently consistent with each actual wire value, but the three different conventions — `@SerialName` with explicit value, `.name` implicitly, and `@SerialName` matching class name — create a maintenance trap: a developer adding a new Kotlin enum variant may not realize which convention controls its wire value.
**Recommendation:** Adopt a single convention for all boundary enums. The safest choice is to add explicit `@SerialName` annotations to every enum value in `SystemOperation` and `ActionType`, making the wire contract explicit and greppable regardless of future Kotlin naming conventions.

---

## No Issues Found In

- **Action index round-trip integrity:** The filtered `availableActions` list used to compute DTO indices (via `mapIndexed`) is the same list consulted when the client's `actionIndex` is received, so index alignment is consistent across the boundary.
- **`ActionParams.precision` boundary:** The server correctly uses `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL` to handle invalid string values sent by the client, preventing crashes on malformed input.
- **`ControlMessage` / `JoinMessage` / `ResultMessage` DTOs:** These message types are identically and completely defined in both `Messages.kt` and `messages.ts`; no fields are missing on either side.
- **`MatrixObjectDto` sealed hierarchy:** The Kotlin `when` in `dto/MatrixObjectDto.kt` is exhaustive, and the TypeScript discriminated union in `messages.ts` covers the same eight `kind` variants; component `LocationPanel.tsx` uses a safe `default: return null` for unrecognised kinds.
- **`visibleObjects` boundary:** `Decker.visibleObjects()` returns `emptyList()` when not jacked in; the UI's `EntitiesPanel` guards against an empty list with a clamped index; no crash path exists for an empty or null visible-objects array.
- **`TurnCoordinator` state machine:** The mutex-protected `claimAction` / `cancelIfActive` cycle is correct and returns typed error keys rather than throwing; these propagate cleanly to `WebSocketDeckerController` without silent swallowing.
