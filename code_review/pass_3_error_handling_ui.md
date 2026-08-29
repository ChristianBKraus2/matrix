# Error Handling Review — ui

## Summary

The UI frontend handles the happy path well: the join screen propagates server error codes to the user, reconnection is automatic, and disabled states prevent most illegal interactions. However, the WebSocket hook has several silent failure modes — a bare `catch {}` that eats all message-processing errors, an `onerror` handler that throws away all diagnostic information, and a `sendAction` that silently drops user actions when the socket is not open. There is also no React error boundary anywhere in the tree, so any unexpected render-time exception (e.g., from an unanticipated server payload shape) produces a blank screen with no message. A secondary inconsistency exists in `NarrativePanel`, which duplicates the error-label map but only covers 4 of the 7 defined error codes.

## Findings

### [MEDIUM] sendAction silently drops user action when socket is not OPEN
**File:** frontend/src/hooks/useWebSocket.ts:156
**Issue:** If the WebSocket is in any state other than `OPEN` (e.g., the connection dropped between the server assigning the user their turn and the user clicking), `sendAction` returns immediately with no feedback. The user clicks an action card, nothing happens, and there is no visible indication that the message was not sent.
**Recommendation:** Either disable all action cards reactively when `!connected` (the `connected` flag is already in state and passed to `ActionsPanel` via `isActiveTurn`, but that only guards the turn role, not the socket state), or dispatch an `ERROR` event with a synthetic message such as `"connection_lost"` so the NarrativePanel shows a visible alert. At minimum, log a warning to the console so the state is not invisible during debugging.

---

### [MEDIUM] Silent catch in onmessage swallows all processing errors
**File:** frontend/src/hooks/useWebSocket.ts:116-118
**Issue:** The entire message-handling block is wrapped in `try/catch` with a comment `// ignore malformed frames`. This is appropriate for a JSON parse failure, but the same `catch` also silently swallows any error thrown by `dispatch` (e.g., a reducer bug or an unexpected payload shape causing a runtime error). There is no logging path at all, making these failures completely invisible.
**Recommendation:** Narrow the guard to the JSON parse step only, and either rethrow or log reducer/dispatch errors:
```ts
let msg: ServerMessage
try {
  msg = JSON.parse(ev.data as string) as ServerMessage
} catch {
  console.warn('[ws] malformed frame', ev.data)
  return
}
// switch(msg.type) outside the try
```

---

### [MEDIUM] No React error boundary — render exceptions blank the entire screen
**File:** frontend/src/App.tsx:79 (App component root)
**Issue:** There is no `<ErrorBoundary>` anywhere in the component tree. If any component throws during render — for example, because the server sends a `MatrixObjectDto` with an unrecognised `kind` and a component tries to access a property on it — React will unmount the entire tree and show a blank screen. The user sees nothing and has no path to recovery.
**Recommendation:** Wrap the game grid (and ideally the join screen) in a simple error boundary that catches render errors, displays a user-visible message ("FATAL ERROR — reload to reconnect"), and optionally logs the error to the console.

---

### [LOW] ws.onerror discards all error diagnostic information
**File:** frontend/src/hooks/useWebSocket.ts:130
**Issue:** `ws.onerror = () => ws.close()` ignores the `ErrorEvent` argument entirely. Browser WebSocket error events carry the event type, timestamps, and in some environments additional details. Discarding this makes it impossible to distinguish a network timeout from a TLS error or a refused connection during debugging.
**Recommendation:** Add a console log: `ws.onerror = (ev) => { console.warn('[ws] error', ev); ws.close() }`. No user-visible change is needed since the `onclose` handler already triggers the reconnect cycle.

---

### [LOW] Unknown server message types silently discarded
**File:** frontend/src/hooks/useWebSocket.ts:92-115
**Issue:** The `switch (msg.type)` block has no `default` case. If the server sends a new message type after a protocol update, or sends a malformed type field, the message is silently dropped with no log entry.
**Recommendation:** Add a `default` case that logs the unknown type:
```ts
default:
  console.warn('[ws] unknown message type', (msg as { type: string }).type)
```

---

### [LOW] NarrativePanel ERROR_LABELS covers only 4 of 7 defined error codes
**File:** frontend/src/components/NarrativePanel.tsx:3-8
**Issue:** `ERROR_LABELS` in `NarrativePanel` defines labels for `not_your_turn`, `no_action_pending`, `already_registered`, and `name_already_taken`, but not for `name_too_long`, `unknown_message_type`, or `bad_request`. These three codes fall through to the raw snake_case string from the server (line 48: `ERROR_LABELS[ev.msg.message] ?? ev.msg.message`). The complete map already exists in `App.tsx`. The duplication also means that future error codes added to `types/messages.ts` must be added in two places.
**Recommendation:** Export `ERROR_LABELS` from a shared location (e.g., `types/messages.ts` or a new `utils/errorLabels.ts`) and import it in both `App.tsx` and `NarrativePanel.tsx`.

## No Issues Found In

- `frontend/src/types/messages.ts` — type definitions only; the `ErrorCode` union and `GameEvent` discriminated union are correctly typed and cover all known server error variants.
- `frontend/src/App.tsx` — `JoinScreen` error display is correct: errors are shown from the event stream, cleared on each new submit attempt, and the `ERROR_LABELS` map here is complete for all 7 codes. The `reconnected` banner and the `SYNCHRONISING WITH HOST…` interstitial both handle intermediate connection states visibly.
- `frontend/src/components/DeckerPanel.tsx` — pure display component; `Math.min`/`Math.max` guards on the program rating pip renderer prevent negative or overflowing renders.
- `frontend/src/components/LocationPanel.tsx` — the `default: return null` in `LocationFields` is safe because the parent already filters `locationObj` to location-type nodes only; a missing match correctly renders without detail fields rather than crashing.
- `frontend/src/components/EntitiesPanel.tsx` — `clamped` index prevents out-of-bounds access when the entity list shrinks between renders.
- `frontend/src/components/ActionsPanel.tsx` — the `disabled` CSS class with `pointer-events: none` prevents click propagation to non-active-turn players; the `e.stopPropagation()` on inline controls correctly prevents accidental action submission when adjusting parameters.
- `frontend/src/App.css` — styling only; no logic or error handling concerns.
