# Security Review — complete (cross-cutting)

## Summary

Across all three layers the architecture is broadly sound: the server owns turn authority, action indices are validated against a server-computed list, React auto-escaping eliminates XSS, and reconnect tokens are server-generated. However, several trust-boundary failures are only visible when the layers are read together. Two parameters (`hasValidPasscode`, `scannerDeviceRating`) are accepted from the client and forwarded to game logic without any authoritative server state to validate them against — a structural gap that no single-layer fix can close. The `ActionParams` wire contract is also missing a `query` field for locate operations, meaning the game-logic empty-query loophole identified in the per-part review is not merely exploitable but architecturally guaranteed for every WebSocket session. A further pattern of raw Kotlin enum `.name` strings crossing the wire without runtime validation creates a fragile contract that silently breaks on enum renames and enables CSS class injection in the UI. The `EDIT_FILE` size limit is independently broken at every layer, collectively producing no effective enforcement anywhere in the stack.

## Findings

### [HIGH] No server-authoritative state for `hasValidPasscode` or `scannerDeviceRating` — structural bypass

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:287-288
**Issue:** Each per-part review caught a fragment of this problem. The cross-cutting view reveals that the vulnerability is structural: nowhere in the entire codebase — game logic, server, or client — does server-owned state record (a) which passcodes a decker has legitimately obtained, or (b) the scanner device rating of any equipped hardware. `makeComcall` receives `hasValidPasscode` from `ActionParams` (Messages.kt:55), which the client sends from a YES/NO toggle in `ActionsPanel.tsx`. `tapComcall` receives `scannerDeviceRating` from the same bag, unbounded in the client UI. Without an authoritative store, the server cannot validate either value against reality: any fix that only adds a range check or removes the field from the DTO merely relocates the problem — game logic will need a server-held passcode ledger and a device-inventory lookup before these operations can be trustworthy.
**Recommendation:** Introduce server-side authoritative state — a `Map<String, Set<String>>` keyed on decker name holding legitimately obtained passcode hashes, and a `Cyberdeck`-derived scanner-rating lookup — populated only by successful server-side game outcomes. Remove both fields from `ActionParams`/`messages.ts` entirely. The server derives `hasValidPasscode` from the ledger and `scannerDeviceRating` from the decker's loaded device inventory before calling game logic.

---

### [HIGH] Locate search query absent from wire protocol — empty-query loophole is architecturally guaranteed

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:51-57 / frontend/src/types/messages.ts:9-15
**Issue:** The game-logic review identified that `locateFile`, `locateSlave`, and `locateAccessNode` open a fresh `InterrogationState` with an empty query, and because every string contains the empty string, any decker can locate the first file or device on any host without knowing its name. That finding treated this as an exploitable loophole. Reading across all three layers reveals it is not exploitable — it is the only possible behavior: `ActionParams` (both `Messages.kt` and `messages.ts`) has no `query` or `searchTerm` field. `locateWithState` in `WebSocketDeckerController.kt:310-316` extracts only `precision` from params. There is no path by which the UI or any WebSocket client can supply a non-empty query. Every locate operation issued over WebSocket is permanently locked to empty-query behavior regardless of what the player types. The `precision` field is correctly wired (server parses it via `runCatching { QueryPrecision.valueOf(it) }.getOrNull()`), which makes the absence of `query` even more conspicuous.
**Recommendation:** Add `query: String? = null` to `ActionParams` in `Messages.kt` and `query?: string` to `ActionParams` in `messages.ts`. In `locateWithState`, pass `params?.query?.trim() ?: ""` through to game logic. Add a UI text field for the search term in `ActionsPanel.tsx` for the three locate operations. Implement the `require(query.isNotBlank())` guard in game logic as recommended in the per-part review so that an absent query produces a clear error rather than a silent first-match.

---

### [MEDIUM] Enum strings cross the wire as raw `.name` with no runtime contract enforcement at either end

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt:64-67 / frontend/src/types/messages.ts:53-58
**Issue:** `AvailableActionDto.Operation` serialises `actionType`, `operation`, and `targetKind` via Kotlin's `.name` property — the undecorated identifier string — rather than through `@SerialName`-annotated enums. TypeScript union types (`ActionType`, `SystemOperation`) mirror these strings at compile time, but there is no runtime validation at the parse boundary on either side. The comment in `messages.ts` acknowledges the fragility: "If a Kotlin enum adds, removes, or renames a variant, update the matching type here." In practice, adding or renaming a Kotlin enum value silently changes what arrives over the wire, TypeScript's structural typing accepts the new string as `string`, and the UI injects it directly into CSS class attributes (e.g. `` `action-type ${action.actionType}` `` in `ActionsPanel.tsx:95`). No runtime guard exists between the wire value and the DOM. A future `ActionType.EXTENDED` variant would immediately appear as a new unintended CSS class in every connected browser session.
**Recommendation:** Either use `@SerialName` on Kotlin enums and parse them with `kotlinx.serialization` sealed class discriminators (so the wire contract is stable regardless of identifier renames), or add a runtime allowlist check in the UI reducer before accepting `actionType`/`operation` strings: `const KNOWN_ACTION_TYPES = new Set(['FREE','SIMPLE','COMPLEX'])`. Use `KNOWN_ACTION_TYPES.has(action.actionType) ? action.actionType : 'UNKNOWN'` before any CSS interpolation.

---

### [MEDIUM] `EDIT_FILE` size enforcement is independently broken at every layer — no effective limit exists

**File:** src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt:251-254 / src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:262 / frontend/src/components/ActionsPanel.tsx:154-162
**Issue:** Each per-part review identified one gap in isolation. Together they mean no layer actually enforces the size limit. The UI textarea has no `maxLength`, so the player can type or paste without restriction. The server checks `content.length > 4096` in characters, but then calls `content.toByteArray()` using default UTF-8 encoding: 4096 four-byte codepoints produce a 16 384-byte array, so the guard can be trivially bypassed with non-ASCII input. Game logic's `editFile` accepts `newContent: ByteArray?` with no size check at all. A player who types 4096 CJK characters passes the server guard, produces a ~16 KiB byte array, and stores it in the host's `dataFiles` list without limit.
**Recommendation:** Enforce the limit at the byte level in all three layers consistently. In the server: `content.toByteArray(Charsets.UTF_8).size > 4096`. In game logic: `require(newContent == null || newContent.size <= MAX_FILE_BYTES)`. In the UI: set `maxLength={4096}` on the textarea as a UX aid (noting this is not a security boundary). All three limits must agree on the same constant.

---

### [LOW] `inactivitySeconds` is in the shared wire contract but never populated by the UI — silent contract gap across all three layers

**File:** src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt:53 / frontend/src/types/messages.ts:14 / frontend/src/components/ActionsPanel.tsx (buildParams)
**Issue:** `ActionParams.inactivitySeconds` is defined in both the Kotlin DTO and the TypeScript interface, and the server reads it (`p?.inactivitySeconds ?: 0`) for `NULL_OPERATION`. The game logic presumably uses this value to model tally accumulation during deliberate inactivity. The UI's `buildParams()` in `ActionsPanel.tsx` never sets this field. As a result every null operation submitted via WebSocket passes `0` to game logic, silently disabling whatever mechanic `inactivitySeconds` controls. The contract gap spans all three parts and is invisible from any single-layer view: the DTO layer looks complete, the server read looks complete, and the UI omission looks like "not yet wired."
**Recommendation:** Either wire a numeric input for `inactivitySeconds` in the UI for the `NULL_OPERATION` action type and document its valid range, or remove the field from `ActionParams`/`messages.ts` and hard-code the server to pass a configurable default (e.g., 3 seconds for one turn). Keep the server-side range clamp (`coerceIn(0, 3600)`) recommended in the server review regardless.

---

### [LOW] Decker name character-set gap traverses game-logic logging as well as the UI broadcast path

**File:** src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt:45 / src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:519
**Issue:** The server review identified that decker names are only length-validated, and that HTML special characters and Unicode direction-override codepoints are accepted and broadcast. The cross-cutting view adds a further consequence: `Decker` objects carrying unvalidated names are passed to game logic, and `bufferMessage` (DeckerOperationsExtensions.kt:519) logs the first 40 characters of message content at INFO level. If decker names are embedded in log-visible messages, Unicode direction-override codepoints (U+202E, U+2066–U+2069) in names can cause terminal and log-viewer output to display reversed or misleading text. While the UI is protected by React's text-node escaping, server logs and any log-aggregation tooling are not.
**Recommendation:** Apply the allowlist validation (`[A-Za-z0-9 _\-]{1,32}`) recommended in the server review at join time. This single server-side gate protects game logic, logs, broadcasts, and the UI simultaneously.

---

## No Issues Found In

- **Turn-ownership enforcement across the stack:** the server's `TurnCoordinator` mutex guard and client-side `isActiveTurn` check are correctly layered — the server is authoritative, the UI guard is UX-only and understood as such.
- **`actionIndex` round-trip integrity:** the server builds and filters the available-actions list before both broadcasting it and using it for dispatch lookup; client-supplied indices cannot reference an action the server did not offer.
- **Reconnect token lifecycle:** tokens are server-generated `UUID.randomUUID()` strings, stored server-side, transmitted to the correct session only, kept in a React ref (never localStorage), and cleared on disconnect — no cross-layer leakage.
- **XSS from server-supplied strings in the UI:** all server data rendered via JSX is HTML-escaped automatically; no `dangerouslySetInnerHTML` usage exists anywhere in the frontend.
- **`precision` parameter wire handling:** the server parses the string via `runCatching { QueryPrecision.valueOf(it) }.getOrNull()` and defaults to `NORMAL`; unknown values are silently ignored rather than thrown.
- **WebSocket URL construction:** the URL is derived from `window.location`, not any user-controllable input; `wss:` is correctly selected when the page is served over HTTPS.
- **Frame size cap:** the 64 KiB `MAX_FRAME_SIZE` guard at the Ktor layer bounds large-frame denial-of-service before any application code runs.
