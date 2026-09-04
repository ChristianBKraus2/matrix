# Error Handling Review — ui

## Summary

The WebSocket reconnection loop is solid: automatic exponential back-off, pending-join replay on re-connect, and server-defined error codes surfaced to the user. However, three systemic gaps undermine resilience: there is no React Error Boundary anywhere in the component tree, so a single render-time crash from unexpected server data blanks the entire screen silently; the `onmessage` catch block swallows every exception (not just JSON parse failures), hiding runtime dispatch errors; and `sendAction` drops messages without any user feedback when the socket is not open. Several lower-severity issues compound these: `ws.onerror` discards the error event entirely, `ERROR_LABELS` is duplicated between `App.tsx` and `NarrativePanel.tsx` with the NarrativePanel copy being a strict subset, and the type cast `JSON.parse(...) as ServerMessage` is never validated at runtime leaving unknown server message types silently ignored.

---

## Findings

### [HIGH] No React Error Boundary anywhere in the component tree

**File:** `frontend/src/App.tsx:101-116`  
**Issue:** Neither the top-level `App` component nor any of the five panel components is wrapped in an Error Boundary. If any panel throws during render — for example because a `StateMessage` arrives with an unexpected shape and accesses a field on `undefined` — React will unmount the entire tree, leaving the user with a completely blank screen and no recovery path short of a hard reload.  
**Recommendation:** Add a minimal `ErrorBoundary` class component (or use `react-error-boundary`) and wrap the whole `<div className="game-grid">` block, or wrap individual panels independently so one bad panel does not kill the others. The fallback UI should match the retro aesthetic (e.g., `[ SYSTEM CRASH — RELOAD TO RECONNECT ]`).

---

### [MEDIUM] `onmessage` catch block silently swallows all exceptions, including runtime dispatch errors

**File:** `frontend/src/hooks/useWebSocket.ts:88-111`  
**Issue:** The `try/catch` wrapping message handling catches everything: malformed JSON *and* any `TypeError`/`RangeError` thrown inside the `switch` cases or inside `dispatch`. If the server sends a structurally valid JSON object whose field types differ from `ServerMessage` (e.g., `role` is `null`, or `availableActions` is absent), the parse succeeds but dispatch may throw. That exception is silently eaten with the comment `// ignore malformed frames`, hiding a real bug that looks like a no-op.  
**Recommendation:** Narrow the catch to only cover `JSON.parse` failures. Re-throw (or at minimum `console.error`) anything that originates from dispatch:

```ts
ws.onmessage = (ev: MessageEvent) => {
  let msg: ServerMessage
  try {
    msg = JSON.parse(ev.data as string) as ServerMessage
  } catch {
    console.warn('[ws] malformed frame discarded', ev.data)
    return
  }
  switch (msg.type) { ... }
}
```

---

### [MEDIUM] Unknown server `message.type` values are silently ignored

**File:** `frontend/src/hooks/useWebSocket.ts:90-108`  
**Issue:** The `switch` on `msg.type` has no `default` case. If the server introduces a new message type (or sends a malformed frame that parses as JSON but has an unrecognised `type`), the message is silently discarded. TypeScript does not guard this at runtime.  
**Recommendation:** Add a `default` case that logs a warning:

```ts
default:
  console.warn('[ws] unrecognised message type:', (msg as { type: string }).type)
```

---

### [MEDIUM] `sendAction` silently drops the action when the socket is not OPEN

**File:** `frontend/src/hooks/useWebSocket.ts:142-150`  
**Issue:** If `wsRef.current?.readyState !== WebSocket.OPEN`, the function returns immediately with no feedback. The player clicks an action card and nothing happens — no error in the narrative, no banner, no visual indication. This is particularly confusing when the socket is mid-reconnect.  
**Recommendation:** Dispatch an `ERROR` action (or a new `SEND_FAILED` action) so the UI can surface feedback. Alternatively, disable the `ActionsPanel` entirely when `!ws.connected` — the `isActiveTurn` guard already disables individual buttons, but a disconnected-but-still-showing-active-turn scenario is possible during the reconnect window.

---

### [MEDIUM] `ERROR_LABELS` is duplicated across two files with the NarrativePanel copy being incomplete

**File:** `frontend/src/components/NarrativePanel.tsx:3-8` and `frontend/src/App.tsx:10-18`  
**Issue:** `App.tsx` defines 7 entries; `NarrativePanel.tsx` defines only 4. Three `ErrorCode` values — `name_too_long`, `unknown_message_type`, `bad_request` — fall through to the raw code string in the narrative panel (e.g., the player sees `bad_request` instead of `Bad request`). Any future addition to `ErrorCode` must be added in two places.  
**Recommendation:** Extract `ERROR_LABELS` into a shared constant in `frontend/src/types/messages.ts` (or a sibling `errorLabels.ts`) typed as `Record<ErrorCode, string>`, then import it in both `App.tsx` and `NarrativePanel.tsx`. The `Record<ErrorCode, string>` type ensures the compiler enforces exhaustiveness.

---

### [LOW] `ws.onerror` discards the `ErrorEvent` entirely

**File:** `frontend/src/hooks/useWebSocket.ts:122`  
**Issue:** `ws.onerror = () => ws.close()` ignores the `ErrorEvent` argument. The browser provides a message and, in some environments, an error code. Discarding it means WebSocket errors produce zero observability — no console output, no differentiation between a network error and a server-side close.  
**Recommendation:** Log the event before closing:

```ts
ws.onerror = (ev) => {
  console.error('[ws] error', ev)
  ws.close()
}
```

---

### [LOW] `DeckerPanel` — negative `u.rating` would throw `RangeError` from `String.repeat`

**File:** `frontend/src/components/DeckerPanel.tsx:65-66`  
**Issue:** `'●'.repeat(Math.min(u.rating, 10))` clamps the upper bound but not the lower. `String.prototype.repeat` throws a `RangeError` for negative arguments. If the server sends a utility with `rating: -1` (malformed or edge-case data), the entire `DeckerPanel` would crash — and without an error boundary this crashes the whole app.  
**Recommendation:** Clamp both bounds: `Math.max(0, Math.min(u.rating, 10))`.

---

### [LOW] Reconnection state resets role and gameState but provides no feedback during the reconnect window

**File:** `frontend/src/hooks/useWebSocket.ts:37`  
**Issue:** On disconnect, `role` and `gameState` are both set to `null`. `App.tsx` checks `!isRegistered` first and renders `JoinScreen` — which shows "ESTABLISHING CONNECTION..." while `connected` is false, then immediately offers the join form once connected. A player who was mid-game will see the join screen for the duration of the reconnect delay (3–30 s) and might re-enter their name, triggering `already_registered`.  
**Recommendation:** Retain a `wasRegistered` flag in state so the reconnect screen can show "RECONNECTING — PLEASE WAIT" rather than the join form during the back-off window.

---

### [INFO] Event list uses array index as React key

**File:** `frontend/src/components/NarrativePanel.tsx:26`  
**Issue:** `key={i}` uses the array index for event items. Because the events array is a sliding window (`.slice(-19)`), indices shift when old events are dropped, causing React to reuse DOM nodes rather than animate new entries correctly. Not a crash risk but can cause visual glitches on rapid event addition.  
**Recommendation:** Assign a monotonically increasing `id` field when appending events in the reducer, and use that as the key.

---

### [INFO] `LocationFields` returns `null` silently for unknown node kinds

**File:** `frontend/src/components/LocationPanel.tsx:66-68`  
**Issue:** The `default: return null` case in `LocationFields` hides the location details panel entirely when an unrecognised `MatrixObjectDto` kind is the current location. There is no console warning and the user sees only the location name with no detail fields.  
**Recommendation:** Add a `default` branch that logs `console.warn('[LocationPanel] unknown node kind:', obj.kind)` so protocol drift is detectable during development.

---

### [INFO] `reconnected` banner has no dismiss mechanism and no timeout

**File:** `frontend/src/App.tsx:103-105`  
**Issue:** The "SESSION RESTORED" banner persists for the entire session once set to `true` — it is only cleared on the next full disconnect. It cannot be dismissed by the user.  
**Recommendation:** Auto-hide after a few seconds (e.g., `setTimeout` dispatch to a `CLEAR_RECONNECT_BANNER` action), or expose a dismiss handler.

---

## No Issues Found In

- **ActionsPanel.tsx** — The `actionLabel` switch is exhaustive at compile time (TypeScript discriminated union); all `AvailableActionDto` variants are handled. The `buildParams` helper correctly handles all parameterised operations. Click propagation on inline controls is correctly stopped.
- **WebSocket reconnection loop itself** — Exponential back-off with a 30 s ceiling, cleanup on unmount (`clearTimeout` + `ws.close()`), and pending-name replay on re-connect are all correctly implemented.
- **JoinScreen error display** — `App.tsx` correctly maps server `error` events to human-readable labels and clears the error on new submit attempts.
- **messages.ts type definitions** — The discriminated unions for `ServerMessage`, `GameEvent`, `MatrixObjectDto`, and `AvailableActionDto` are well-structured and make exhaustive switches straightforward to write.
