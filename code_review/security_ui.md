---
# Security Review — ui

## Summary

The Matrix UI is a clean, minimal React/TypeScript SPA with no hardcoded secrets and no use of `dangerouslySetInnerHTML`, so React's built-in JSX escaping prevents all obvious XSS vectors. The main security concerns are concentrated in the `ActionParams` contract: two client-supplied fields — `hasValidPasscode` and `scannerDeviceRating` — represent gameplay facts that the client should not be allowed to self-report, as a motivated player can trivially forge them via the browser console. A third field, `newContent`, has no size cap on the client side and can carry an arbitrarily large payload to the server. Beyond the data contract, there is no authentication layer on the WebSocket connection: any visitor who can reach `/decker/ws` may claim any decker name. The development proxy rewrites the WebSocket `Origin` header, which would neutralise any origin-based CSRF guard on the backend during dev testing.

## Findings

### HIGH — Client self-reports `hasValidPasscode`, bypassing game mechanic
**File:** frontend/src/types/messages.ts:9  
**Issue:** `ActionParams.hasValidPasscode` is a boolean the client sends to the server as part of an `MAKE_COMCALL` action. The client literally declares whether it possesses a valid passcode. A player can open the browser console and call `ws.sendAction(idx, { hasValidPasscode: true })` regardless of whether they legitimately obtained one in-game. The flag serves as a privilege gate that the attacker controls.  
**Recommendation:** Remove `hasValidPasscode` from `ActionParams`. The server should track passcode possession in its own authoritative game state and evaluate legality of the MAKE_COMCALL action there, sending the action as unavailable if the passcode has not been acquired. If the server genuinely needs the client to confirm some UI choice, rename it to something that reflects pure preference, and ensure the server never uses it as a security gate.

### HIGH — `scannerDeviceRating` has no upper bound; server must not trust the value
**File:** frontend/src/components/ActionsPanel.tsx:141  
**Issue:** The scanner-rating stepper decrements with `Math.max(0, …-1)` but increments without any cap. A player can click the `+` button indefinitely (or call `sendAction` directly) and send an arbitrarily large integer — e.g. `scannerDeviceRating: 999999` — which could distort dice-pool calculations, cause integer overflow on the server, or grant an unintended advantage in the TAP_COMCALL operation.  
**Recommendation:** Add a maximum in the stepper UI (`Math.min(cs.scannerDeviceRating + 1, MAX_RATING)` where `MAX_RATING` matches the game rules, e.g. 12). More importantly, the server must clamp or reject out-of-range values rather than using the client-supplied number directly.

### MEDIUM — `newContent` textarea has no length limit; server DoS vector
**File:** frontend/src/components/ActionsPanel.tsx:152  
**Issue:** The `<textarea>` for the EDIT_FILE operation has no `maxLength` attribute. A player can paste megabytes of text into it and submit. The resulting `newContent` string is serialised into the WebSocket frame and sent to the server, which must then process and potentially persist it. Without a length cap, this is a straightforward client-side contribution to a server resource-exhaustion attack.  
**Recommendation:** Add `maxLength={4096}` (or a value consistent with game rules) to the textarea. Mirror the same limit server-side as the authoritative enforcement.

### MEDIUM — Decker name has no character-set validation
**File:** frontend/src/App.tsx:39  
**Issue:** The join form enforces `maxLength={32}` via the HTML attribute and rejects blank input, but applies no character-set filter before calling `onJoin(name.trim())`. A name containing control characters, null bytes, or characters with special meaning in the server's storage or logging layer (e.g. newline, tab, `"`, `\`) can be sent to the server. While React prevents these from causing XSS in the UI itself, they may trigger parsing or injection issues server-side or in log files.  
**Recommendation:** Add a client-side regex guard before calling `onJoin`, e.g.:
```ts
if (!/^[\w \-]{1,32}$/.test(name.trim())) {
  setError('Name may only contain letters, numbers, spaces, and hyphens.')
  return
}
```
Enforce an equivalent pattern on the server.

### MEDIUM — Development proxy rewrites WebSocket `Origin`, defeating backend CSRF checks
**File:** frontend/vite.config.ts:14  
**Issue:** `rewriteWsOrigin: true` causes Vite's dev proxy to replace the WebSocket `Origin` header with the target origin (`ws://localhost:8080`) before forwarding the upgrade request. If the Kotlin backend validates `Origin` to defend against cross-site WebSocket hijacking (CSWSH), this setting silently disables that check during all development and integration testing, creating a false sense of security and making it harder to discover a misconfigured CORS/origin policy before production.  
**Recommendation:** Remove `rewriteWsOrigin: true` (or set it to `false`). Configure the backend's allowed-origins list to explicitly include the Vite dev-server origin (`http://localhost:5173`) rather than suppressing the check. This keeps the security layer exercised during development.

### LOW — No authentication on the WebSocket connection
**File:** frontend/src/hooks/useWebSocket.ts:91  
**Issue:** The only identity claim a connecting client makes is the `deckerName` string in the `JoinMessage`. There is no session cookie, bearer token, or other credential. Any unauthenticated visitor who can reach `/decker/ws` (or who intercepts network traffic) can join the game under any name not yet taken.  
**Recommendation:** If this is a multi-player game intended for authenticated users, attach an auth token to the WebSocket upgrade request (e.g. `new WebSocket(url + '?token=' + authToken)` or rely on an HttpOnly session cookie that the browser sends automatically on the upgrade). The server should reject connections that lack a valid token. If the project is currently single-player / LAN-only, document this as a known design decision.

### LOW — Raw server error strings rendered verbatim when code is unrecognised
**File:** frontend/src/components/NarrativePanel.tsx:48  
**Issue:** `ERROR_LABELS[ev.msg.message] ?? ev.msg.message` falls back to the raw server-provided string when the error code is not in the local lookup table. React text nodes are HTML-escaped, so XSS is not possible, but the server can surface arbitrary diagnostic text (e.g. stack traces, internal state) directly in the player's UI if error handling on the backend is not careful.  
**Recommendation:** Change the fallback to a generic user-facing string such as `'An unexpected error occurred.'` so that internal server error details are never exposed to the end-user.

### INFO — `actionIndex` forgery relies entirely on server-side validation
**File:** frontend/src/hooks/useWebSocket.ts:138  
**Issue:** `sendAction(actionIndex, params)` sends the index back to the server. Because `actionIndex` is assigned by the server in `AvailableActionDto`, the client cannot invent valid indices for actions that were never offered — provided the server validates that the submitted index corresponds to a currently available action for that player's turn. No client-side defence exists or is needed; this note is a reminder that the server validation is load-bearing.  
**Recommendation:** Confirm the server rejects `actionIndex` values that are not in the current player's `availableActions` list and discards `params` fields that are not appropriate for the chosen action kind.

### INFO — No Content Security Policy headers visible in the codebase
**File:** frontend/vite.config.ts  
**Issue:** No `Content-Security-Policy` header is configured in the Vite dev server or in the build output. Without a CSP, any XSS that does sneak through (e.g. via a future use of `dangerouslySetInnerHTML`, a third-party dependency, or server-injected script tags) has no additional browser-level containment.  
**Recommendation:** Add a strict CSP when deploying (via reverse-proxy response headers or a `<meta>` tag): `default-src 'self'; connect-src 'self' wss:; style-src 'self' 'unsafe-inline'; script-src 'self'`. The `'unsafe-inline'` on styles can be tightened further once the CRT stylesheet is confirmed not to rely on inline styles beyond those already in JSX `style` props.

## Clean Areas

- No `dangerouslySetInnerHTML` anywhere in the codebase; all server-supplied strings are rendered as React text nodes and are therefore HTML-escaped automatically.
- No hardcoded credentials, tokens, API keys, or secrets found in any source file.
- WebSocket URL is derived from `window.location.protocol` and `window.location.host`, ensuring the app connects to its own origin in production and never to a hardcoded address.
- The `actionIndex` sent by the client originates from server-assigned indices in `AvailableActionDto`, not from free-form client input.
- The decker-name input enforces `maxLength={32}` at the HTML level and strips surrounding whitespace before sending.
- The events ring-buffer is capped at 20 entries (`state.events.slice(-19)`), preventing unbounded memory growth from a flood of server messages.
- CSS class name injection is not feasible: React serialises all `className` strings as DOM attribute values, not as stylesheet rules.
- No third-party runtime dependencies beyond React and React-DOM; the attack surface from the dependency tree is minimal.
---
