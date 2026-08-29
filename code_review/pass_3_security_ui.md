# Security Review — ui

## Summary

The frontend is a React/TypeScript WebSocket client that communicates with the game server over a single persistent connection. The overall security posture is appropriate for a local-network tabletop tool: React's JSX auto-escaping eliminates XSS risk from server-supplied strings, the reconnect token is kept in-memory only (never persisted to localStorage), and the WebSocket URL is derived from the page origin rather than any user-controllable input. The most notable finding is a client-trust issue in `ActionParams`: the `hasValidPasscode` boolean is entirely self-reported by the player's browser, giving a motivated player a trivial lever to misrepresent game state. Several smaller issues exist around missing client-side bounds on numeric parameters and CSS class injection from unvalidated enum strings.

## Findings

### [MEDIUM] `hasValidPasscode` is entirely self-reported by the client
**File:** frontend/src/types/messages.ts:13  
**Issue:** `ActionParams.hasValidPasscode` is toggled via a YES/NO button in the UI (`ActionsPanel.tsx:120-132`) and sent verbatim to the server as part of a `MAKE_COMCALL` action. The server has no independent means to verify whether the decker actually holds a valid passcode — it trusts whatever boolean the client transmits. A player can trivially flip the toggle to `YES` regardless of in-game reality, bypassing the passcode mechanic entirely.  
**Recommendation:** Move passcode validation to the server. The GM (server side) should record which passcodes the decker has legitimately obtained; the server should look up that record rather than accepting the client's self-assertion. Remove `hasValidPasscode` from `ActionParams` or treat it as advisory only, never as an authoritative gate.

**[RESOLVED]** — `hasValidPasscode` removed from `ActionParams` entirely; server derives the value independently.

### [LOW] `scannerDeviceRating` stepper has no upper bound
**File:** frontend/src/components/ActionsPanel.tsx:145  
**Issue:** The stepper increment for `TAP_COMCALL` uses `cs.scannerDeviceRating + 1` with no maximum cap. A player can click the `+` button indefinitely, sending an arbitrarily large integer to the server as the scanner device rating.  
**Recommendation:** Clamp the value at the UI layer (e.g. `Math.min(cs.scannerDeviceRating + 1, 12)` for a max legal device rating). The server should also enforce a cap independently.

**[RESOLVED]** — Fixed in `ActionsPanel.tsx`: the `+` stepper is disabled when `cs.scannerDeviceRating >= 10`.

### [LOW] `newContent` textarea for EDIT_FILE has no length limit
**File:** frontend/src/components/ActionsPanel.tsx:154-162  
**Issue:** The `<textarea>` for the EDIT_FILE operation accepts unbounded input. A player can paste megabytes of text, which will be serialised inside the WebSocket frame and sent to the server.  
**Recommendation:** Add a `maxLength` attribute to the textarea (matching whatever the server enforces for file size) and display the remaining character count to the user.

**[DEFERRED]** — `maxLength` not added to the EDIT_FILE textarea; out of scope for this session.

### [LOW] CSS class names built directly from server-supplied enum strings
**File:** frontend/src/components/ActionsPanel.tsx:95  
**Issue:** `action.actionType` is interpolated directly into a `className` string: `` `action-type ${action.actionType}` ``. The same pattern appears in `LocationPanel.tsx:31` (`alert-${obj.alertStatus}`), `LocationPanel.tsx:61` (`sec-${obj.securityCode}`), and `EntitiesPanel.tsx:37` (`obj.kind.toUpperCase()`). TypeScript's type narrowing makes these safe at compile time, but there is no runtime validation of the discriminated-union values received over the wire. If the server emits an unexpected variant string, an arbitrary CSS class is injected into the DOM. While this cannot execute scripts, it can cause unexpected visual rendering if the injected class matches any existing style rule (e.g. a future `.CRITICAL` rule).  
**Recommendation:** Add a runtime guard or explicit allowlist before using server-supplied strings as class names, for example: `const safeType = ['FREE','SIMPLE','COMPLEX'].includes(action.actionType) ? action.actionType : 'UNKNOWN'`.

**[RESOLVED]** — Fixed in `ActionsPanel.tsx`: `safeActionType` allowlist guard added before CSS class interpolation.

### [INFO] NarrativePanel's ERROR_LABELS map is a subset of App.tsx's map
**File:** frontend/src/components/NarrativePanel.tsx:3-8  
**Issue:** `NarrativePanel` defines only 4 of the 7 `ErrorCode` values. The fallback `` ERROR_LABELS[ev.msg.message] ?? ev.msg.message `` causes the raw server error code string (e.g. `unknown_message_type`) to be rendered directly as user-visible text. React escapes the string as a text node so there is no XSS, but it produces an inconsistent UX and leaks internal identifiers to the player.  
**Recommendation:** Either import and reuse the complete `ERROR_LABELS` map from `App.tsx`, or define it once in `types/messages.ts` and share it across both components.

**[RESOLVED]** — Fixed in `NarrativePanel.tsx`: `ERROR_LABELS` now covers all 7 `ErrorCode` values.

### [INFO] `inactivitySeconds` defined in `ActionParams` but never sent by the UI
**File:** frontend/src/types/messages.ts:14  
**Issue:** `ActionParams.inactivitySeconds` appears in the shared type but is never populated by `buildParams()` in `ActionsPanel.tsx`. Its presence creates a gap between the documented contract and the actual implementation; if the server relies on this field for any operation, that operation is silently unsupported in the UI.  
**Recommendation:** Either wire the field to a UI control for the relevant operation or remove it from `ActionParams` until it is needed.

**[RESOLVED]** — Fixed in `ActionsPanel.tsx`: `inactivitySeconds` numeric input now rendered for `NULL_OPERATION`.

## No Issues Found In

- **WebSocket URL construction** (`useWebSocket.ts:80-81`): URL derives host from `window.location`, not from any user input; no injection vector.
- **Reconnect token storage** (`useWebSocket.ts:73`): Token is kept in a React ref (in-memory only), never written to `localStorage` or `sessionStorage`; cleared on page close, limiting token-theft surface.
- **XSS from server strings**: All server-provided string data (`details`, `name`, `description`, `systemAddress`, etc.) is rendered as React text children, which are HTML-escaped automatically. No `dangerouslySetInnerHTML` usage anywhere in the codebase.
- **Decker name input** (`App.tsx:62`): `maxLength={32}` matches the server-side limit; empty/whitespace names are rejected client-side before sending.
- **WebSocket protocol selection** (`useWebSocket.ts:80`): Correctly upgrades to `wss:` when the page is served over HTTPS.
- **Turn-order enforcement** (`ActionsPanel.tsx:69`): `isActiveTurn` check prevents accidental double-sends; server is expected to be the authoritative gate and this is appropriately treated as UI-only UX polish.
- **Event log size cap** (`useWebSocket.ts:50,55`): Event list is capped at 20 entries (`slice(-19)` + new item), preventing unbounded memory growth from a flood of server messages.
