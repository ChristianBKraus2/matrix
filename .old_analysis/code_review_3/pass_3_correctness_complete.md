# Correctness Review — complete (cross-cutting)

## Summary

Across all three layers the individual reviews surfaced seven issues in game_logic, four in the server, and seven in the UI. Three of those findings are symptoms of deeper cross-layer structural gaps that are only visible when the layers are read together. The most consequential is a three-layer protocol omission: the locate-by-name mechanic (locateFile, locateSlave, locateAccessNode) has no `query` field anywhere in the `ActionParams` DTO, so even fixing the game_logic empty-query bug in isolation leaves the feature permanently broken — the server cannot relay a search term and the UI cannot supply one. A second structural gap is that `Decker.availableActions()` populates grid contexts (RTG, LTG, PLTG) with operations — NULL_OPERATION, ANALYZE_SECURITY, LOCATE_IC, ANALYZE_IC — that the server's `dispatchGridOperation` handler does not implement; a decker on any grid node sees these actions offered but receives a "requires host context" error for every one of them except RELOCATE_ICON. A third cross-layer interaction is the compound effect of the UI's stale reconnect-token ref and the server's inverted null-token guard, which together can allow a client to claim an unrelated decker identity after a reconnect. One additional cross-cutting structural issue is the absence of a stable location index in the server DTO, which means the UI's name-based location lookup (identified in the UI review) cannot be fixed at the frontend alone.

## Findings

---

### [HIGH] Locate-by-name query field is absent from the protocol — mechanic broken across all three layers

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:51` and `frontend/src/types/messages.ts:9`

**Issue:** The game_logic review identifies that `locateFile`, `locateSlave`, and `locateAccessNode` default to an empty query string, matching every entry in the host. The recommended fix is to add a `query: String` parameter to those functions. However, that fix cannot take effect because the protocol has no field to carry the query. `ActionParams` in `Messages.kt` declares `newContent`, `inactivitySeconds`, `precision`, `hasValidPasscode`, and `scannerDeviceRating` — no `query`. The TypeScript `ActionParams` in `messages.ts` mirrors the same five fields exactly. In `WebSocketDeckerController.locateWithState` (line 310) only `precision` is extracted; there is no code path that could ever read a query string from the client command. The result is that all three locate operations will match the first entry in the list regardless of what any future fix does at the game_logic level, because the search term can never reach it.

**Recommendation:** Add `val query: String? = null` to `ActionParams` in `Messages.kt` and `query?: string` to `ActionParams` in `messages.ts`. In `locateWithState`, extract `val query = params?.query ?: ""` and pass it through the lambda: `{ prec -> decker.locateFile(host, query, prec, diceRoller) }`. Update the UI's ActionsPanel to render a text input for the query field when the action is LOCATE_FILE, LOCATE_SLAVE, or LOCATE_ACCESS_NODE.

---

### [HIGH] Grid operations offered by game layer are unimplemented in server dispatch — player sees actions that always fail

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:150` and `src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:144`

**Issue:** `Decker.addGridSystemActions()` (called for every RTG, LTG, and PLTG location) adds NULL_OPERATION, ANALYZE_SECURITY, LOCATE_IC, and ANALYZE_IC to the available-actions list. These are serialised to the client as valid `AvailableActionDto` entries with non-null `index` values. When the player selects any of them, `WebSocketDeckerController.dispatch` routes them to `dispatchGridOperation` (because `host == null`). `dispatchGridOperation` handles only `RELOCATE_ICON`; every other operation falls through to: `DispatchResult(decker, false, 0, 0, "${action.operation} requires host context")`. The decker cannot perform a null-operation (pass a turn), analyze their current grid node's security, or look for IC while on any grid node. The UI faithfully shows all offered actions and the player can click them, but every click results in a failure result with a confusing internal error message.

**Recommendation:** Extend `dispatchGridOperation` to handle the operations that `addGridSystemActions` exposes. At minimum: route `NULL_OPERATION` to `decker.nullOperation`; route `ANALYZE_SECURITY` to `decker.analyzeSecurity`; route `LOCATE_IC` to `decker.locateIc`; route `ANALYZE_IC` to `decker.analyzeIc`. Alternatively, remove the unsupported operations from `addGridSystemActions` until server-side handling is added — but this must be done in both places together to keep the contract coherent.

---

### [MEDIUM] Stale UI reconnect token + inverted server null-token guard interact to allow cross-session identity claim

**File:** `frontend/src/hooks/useWebSocket.ts:37` (token not cleared) and `src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:59` (guard inverted)

**Issue:** The UI review found that `reconnectTokenRef.current` is never cleared in the `DISCONNECTED` reducer or the `ws.onclose` handler. The server review found that the reconnect guard reads `if (stored != null && msg.reconnectToken != stored)`, which unconditionally allows reconnection when `stored` is null. Combining both: a player who holds decker A's reconnect token, loses their connection, and then calls `join()` with a new decker name B will attach A's stale token to the join for B. If B's token slot is absent from `reconnectTokens` (e.g. B is a freshly registered name, or the server performed a game reset), `stored` is null, the guard evaluates to false, and the reconnect is accepted — the client is treated as a legitimate reconnect for B with no token required. Separately, either bug is low-impact; together they create a path to claiming any name that has no stored token.

**Recommendation:** Apply both fixes together. In `useWebSocket.ts`, add `reconnectTokenRef.current = null` inside `ws.onclose` before the reconnect timer is set. In `SessionRegistry.kt`, change the guard to `if (stored == null || msg.reconnectToken != stored)` so a missing stored token is the strictest case (reject, not accept).

---

### [MEDIUM] `DeckerStateDto.location` is a display string — UI location-index fix requires server DTO change

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt` and `frontend/src/types/messages.ts:41`

**Issue:** The UI review identified that `LocationPanel` resolves the current location node by matching the display name extracted from `decker.location` against `visibleObjects`, and recommends carrying a stable `locationIndex` instead. However, `DeckerStateDto` in the TypeScript types has `location: string` (line 43 of `messages.ts`), matching the Kotlin server DTO which also serialises location as a human-readable string. There is no `locationIndex` or equivalent stable reference anywhere in the DTO. This means the LocationPanel recommendation cannot be implemented at the frontend alone — the Kotlin `DeckerStateDto.toDto()` mapping must be updated to include a numeric or otherwise stable location identifier, and the TypeScript `DeckerStateDto` must be updated to match.

**Recommendation:** Add `locationIndex: Int?` to the Kotlin `DeckerStateDto` (set from the host/grid's stable index when jacked in, null otherwise) and add `locationIndex?: number` to the TypeScript `DeckerStateDto`. In `LocationPanel`, change the lookup to `visibleObjects.find(o => o.index === decker.locationIndex)` instead of name-matching.

---

### [INFO] `ActionParams.precision` is an untyped `String?` on both sides — silent NORMAL fallback on mismatch

**File:** `src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:55` and `frontend/src/types/messages.ts:11`

**Issue:** The TypeScript `ActionParams.precision` is typed as a union of five literal strings matching the Kotlin `QueryPrecision` enum names. The Kotlin `ActionParams.precision` is `String?`. The server parses it with `runCatching { QueryPrecision.valueOf(it) }.getOrNull() ?: QueryPrecision.NORMAL`, silently falling back to NORMAL for any unrecognised value. If the `QueryPrecision` enum gains a new variant whose name is misspelled or capitalised differently in the TypeScript union, both sides will compile without error and the user will silently get NORMAL precision instead of their selected value. The TypeScript type provides no compile-time guarantee because the value crosses a serialisation boundary.

**Recommendation:** Add a comment in `ActionParams` (both files) cross-referencing `QueryPrecision` and noting that the TypeScript union must be kept in sync manually — matching the existing comment in `messages.ts` for `AlertStatus`, `SecurityCode`, etc. Consider adding a server-side integration test that sends each precision value and asserts it is parsed correctly, to catch future regressions.

---

## No Issues Found In

- Index alignment between `AvailableActionDto.toDto(mapIndexed)` and `availableActions.getOrNull(cmd.actionIndex)` in the controller — the index produced by the server and consumed by the server are derived from the same in-memory list slice, so they cannot drift.
- Message type discriminants — all seven `ServerMessage` variants are correctly round-tripped: Kotlin `@SerialName` values match TypeScript string literals for `type` and `role` fields.
- `ErrorCode` serialisation — all seven `ErrorCode` enum values use `@SerialName` with lowercase snake_case matching the TypeScript `ErrorCode` union exactly.
- `SessionRole` / TypeScript `Role` alignment — three values, all consistent across both sides.
- `MatrixObjectDto` variant shapes — all eight Kotlin `toDto()` mappings produce fields that match the TypeScript union members exactly; the `kind` discriminator uses `class.simpleName` which matches the `@SerialName` values.
- `AvailableActionDto` navigation actions (LogonToRtg, LogonToLtg, LogonToPltg, LogonToHost, GracefulLogoff, JackOut) — these are dispatched directly by type at the server without any cross-layer state dependency, and their DTO shapes match the TypeScript union.
- `ActionCommand` / `ActionParams` null-safety — all nullable `ActionParams` fields are accessed with `?: default` at the call sites in the controller, so a missing params object never causes a NullPointerException.
