---
# Error Handling Review — ui

## Summary

The UI's error handling is adequate for the happy path but has several gaps that manifest as silent failures: the most consequential is an overly broad catch block in the WebSocket message handler that swallows all runtime errors without any logging, making debugging impossible in production. Related to this, two user-visible action paths (sending an action and auto-joining after reconnect) silently no-op when the socket is not ready, giving the user no feedback. The WebSocket error event discards its payload entirely. A handful of rendering paths degrade silently when server data is unexpected or missing rather than surfacing a meaningful indicator. None of these cause a server crash; all impact the user's ability to understand what went wrong.

## Findings

### HIGH — Catch block in `onmessage` is too broad and completely silent
**File:** frontend/src/hooks/useWebSocket.ts:105
**Issue:** The `try/catch` wrapping the entire `onmessage` handler catches not only `JSON.parse` failures (its stated intent) but also any runtime error thrown inside the `switch` — including a bad dispatch call, a null dereference on `msg`, or a type mismatch. The catch body is `// ignore malformed frames` with no `console.error` or logging of any kind. In production there is no way to know that a message was silently dropped or that an unexpected server message shape caused an error.
**Recommendation:** Narrow the try/catch to cover only the `JSON.parse` call. Handle parse errors separately and let dispatch errors propagate (or at minimum log them):
```ts
ws.onmessage = (ev: MessageEvent) => {
  let msg: ServerMessage
  try {
    msg = JSON.parse(ev.data as string) as ServerMessage
  } catch (e) {
    console.warn('[ws] malformed frame:', e)
    return
  }
  switch (msg.type) { ... }
}
```

### HIGH — `sendAction` silently no-ops when the socket is not OPEN
**File:** frontend/src/hooks/useWebSocket.ts:138
**Issue:** When the user clicks an action card while the WebSocket is reconnecting or closed, `sendAction` returns immediately with no effect and no feedback. The action is simply lost. The user sees the action card as clickable (the `disabled` prop in `ActionsPanel` only gates on `isActiveTurn`, not on socket readiness), clicks it, and nothing happens.
**Recommendation:** Either disable action cards when `!ws.connected`, or dispatch an `ERROR` event so the NarrativePanel displays a message such as "Connection lost — action not sent":
```ts
const sendAction = useCallback((actionIndex: number, params?: ActionParams) => {
  if (wsRef.current?.readyState !== WebSocket.OPEN) {
    dispatch({ type: 'ERROR', msg: { type: 'error', message: 'disconnected' } })
    return
  }
  ...
}, [])
```
And add `'disconnected': 'Not connected — action was not sent'` to `ERROR_LABELS`.

### MEDIUM — `ws.onerror` discards the error event entirely
**File:** frontend/src/hooks/useWebSocket.ts:118
**Issue:** `ws.onerror = () => ws.close()` ignores the `Event` argument. WebSocket error events carry a meaningful `message` in some environments and always indicate why the connection failed. Discarding it silently makes it impossible to distinguish a network error from a TLS failure from a server rejection, even in development.
**Recommendation:**
```ts
ws.onerror = (ev) => {
  console.error('[ws] error:', ev)
  ws.close()
}
```

### MEDIUM — `pendingNameRef` is never cleared on error or permanent failure
**File:** frontend/src/hooks/useWebSocket.ts:69
**Issue:** When `join(name)` is called, `pendingNameRef.current` is set and never cleared. If the server responds with an `error` (e.g., `name_already_taken`), the ref still holds the rejected name. On the next reconnect, when the server sends a `control` message with `role === 'observer'`, the hook automatically re-sends the same rejected join name (line 90-93), triggering another server error immediately. The user sees the join screen, types a different name, but the stale pending name fires first.
**Recommendation:** Clear `pendingNameRef.current` when an `ERROR` message is dispatched, so a failed join does not replay automatically:
```ts
case 'error':
  pendingNameRef.current = null
  dispatch({ type: 'ERROR', msg })
  break
```

### MEDIUM — Location details silently absent when location object not in `visibleObjects`
**File:** frontend/src/components/LocationPanel.tsx:75
**Issue:** If `decker.location` is set to a named node but that node is not present in `visibleObjects` (e.g., it arrived in a stale state snapshot or the server omitted it), `locationObj` is `null` and the panel renders only the location name with no fields at all. There is no indicator that the detail data is missing — the panel just looks empty below the location name.
**Recommendation:** Add a fallback row when `locationObj` is null and `decker.location !== 'not jacked in'`:
```tsx
{!locationObj && decker.location !== 'not jacked in' && (
  <div className="no-data">[ LOCATION DATA UNAVAILABLE ]</div>
)}
```

### MEDIUM — `LocationFields` default branch returns null silently
**File:** frontend/src/components/LocationPanel.tsx:66
**Issue:** The `switch` over `obj.kind` handles four known node kinds and falls through to `return null` for anything else. If the server introduces a new object kind that maps to a location (e.g., a new grid tier), the location panel renders no fields without any indication that something is unrecognised.
**Recommendation:** Log a warning and render a placeholder in the default case:
```tsx
default:
  console.warn('[LocationPanel] unknown location kind:', (obj as MatrixObjectDto).kind)
  return <Field label="KIND" value={(obj as MatrixObjectDto).kind} />
```

### LOW — `actionLabel` has no default case; returns `undefined` for unknown action kinds
**File:** frontend/src/components/ActionsPanel.tsx:11
**Issue:** TypeScript's exhaustive check covers the current union, but at runtime the server could send an action `kind` not present in the discriminated union (e.g., a newly added action type). The switch falls through with no return, so `actionLabel` returns `undefined`. This renders as an empty `<span className="action-kind">` — a blank action label — with no indication to the user that something is wrong.
**Recommendation:** Add a default case:
```ts
default:
  return (action as { kind: string }).kind
```

### LOW — `ERROR_LABELS` map is duplicated across two files
**File:** frontend/src/App.tsx:10 and frontend/src/components/NarrativePanel.tsx:3
**Issue:** The same `ERROR_LABELS` record is defined identically in both `App.tsx` (for the join screen) and `NarrativePanel.tsx` (for the event log). When the server adds a new error code, both files must be updated in sync. Missing one means the join screen shows a friendly message while the narrative log shows the raw code, or vice versa.
**Recommendation:** Extract to a shared module, e.g. `src/utils/errorLabels.ts`, and import it in both files.

### LOW — `findIndex` result not guarded in `EntitiesPanel` focus handler
**File:** frontend/src/components/EntitiesPanel.tsx:93
**Issue:** The click handler calls `entities.findIndex(e => e.index === obj.index)` and passes the result directly to `setFocusIdx`. Since `obj` always comes from the same filtered `entities` array in the same render, `findIndex` will always succeed in practice. However, if the entity list changes between render and click (e.g., a state update races with the click), `findIndex` could return `-1`. With `focusIdx === -1`, `clamped` also becomes `-1` (since `Math.min(-1, positive)` is `-1`), and `entities[-1]` is `undefined`, causing a crash when `EntityCard` attempts to access `obj.kind`.
**Recommendation:** Guard the setter:
```ts
onClick={() => {
  const idx = entities.findIndex((e) => e.index === obj.index)
  if (idx !== -1) setFocusIdx(idx)
}}
```

## Clean Areas
- Reconnect logic with exponential back-off (3 s → 30 s cap) is solid and prevents hammering a downed server.
- The `reducer` in `useWebSocket` has fully exhaustive `switch` arms over `WsAction` — TypeScript will catch any missed case at compile time.
- `JoinScreen` correctly clears the error on submit and shows a translated message for every known server error code.
- `ActionsPanel` correctly stops click propagation on inline controls so adjusting a precision toggle does not simultaneously fire the action.
- `NarrativePanel` handles both `result` and `error` event kinds explicitly with no fallthrough.
- `DamageMonitor` guards `Math.max(0, 10 - u.rating)` against negative repeat counts, preventing a silent rendering error for out-of-range ratings.
- The WebSocket URL construction correctly selects `wss:` vs `ws:` based on page protocol.
---
