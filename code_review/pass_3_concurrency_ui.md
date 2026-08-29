# Concurrency Review — ui

## Summary

The UI frontend is a single-threaded React application communicating over a single WebSocket, so classical concurrency hazards (shared memory, data races) do not apply. Concurrency risk is instead concentrated in the lifecycle of the WebSocket connection itself: the timing of close/reopen events, the use of `useRef` as invisible side-channel state across async boundaries, and the interaction between React's synchronous rendering model and asynchronous browser events. Two issues were found: one medium-severity defect where the reconnect guard fails to cover the `CONNECTING` state, enabling overlapping sockets and reconnect storms, and one low-severity defect where a post-unmount `dispatch` call is not fully guarded. All pure rendering components are clean.

## Findings

### [MEDIUM] Reconnect guard ignores CONNECTING state — overlapping WebSocket instances
**File:** frontend/src/hooks/useWebSocket.ts:79
**Issue:** `connect()` returns early only when `readyState === WebSocket.OPEN`. If the exponential-backoff timer fires while the previous connection attempt is still in `CONNECTING` state (server unreachable, slow DNS, etc.), a second `WebSocket` is created and assigned to `wsRef.current`, orphaning the first. The orphaned socket still holds live event handlers: its eventual `onclose` fires, dispatches `DISCONNECTED`, and schedules yet another reconnect timer. This can produce stacked reconnect storms (multiple concurrent connection attempts) and duplicate `DISCONNECTED` dispatches that unnecessarily reset `role`, `gameState`, and `events` in the reducer.
**Recommendation:** Extend the guard to cover both open and in-progress states:
```ts
if (
  wsRef.current?.readyState === WebSocket.OPEN ||
  wsRef.current?.readyState === WebSocket.CONNECTING
) return
```

**[DEFERRED]** — `CONNECTING` state not added to the reconnect guard; out of scope for this session.

### [LOW] Post-unmount dispatch not fully guarded in onclose
**File:** frontend/src/hooks/useWebSocket.ts:121
**Issue:** The cleanup function sets `isMountedRef.current = false` and then calls `wsRef.current?.close()`. Because WebSocket `onclose` fires asynchronously (queued as a task), the handler executes after cleanup returns. The handler calls `dispatch({ type: 'DISCONNECTED' })` unconditionally before checking `isMountedRef`. The `isMountedRef` guard only prevents scheduling the reconnect timer, not the dispatch itself. In React 18 the stale dispatch is a silent no-op, but it can produce spurious warnings in React 17 environments and test harnesses that assert no state updates after unmount.
**Recommendation:** Move the `isMountedRef` check to the very first line of `ws.onclose`:
```ts
ws.onclose = () => {
  if (!isMountedRef.current) return
  dispatch({ type: 'DISCONNECTED' })
  reconnectTimer.current = setTimeout(...)
}
```

**[DEFERRED]** — `isMountedRef` check not moved to first line of `onclose`; out of scope for this session.

### [INFO] pendingNameRef auto-rejoin is invisible to React's state model
**File:** frontend/src/hooks/useWebSocket.ts:96-103
**Issue:** `pendingNameRef` is never cleared on disconnect. If `join()` is called while the socket is down, the name is stored in the ref. On reconnect, the first `control` message with `role === 'observer'` triggers an automatic join using that stored name. This cross-request mutable state is invisible to the reducer and to React rendering: there is no derived state or UI feedback indicating that a pending join is queued, and there is no cancellation path (e.g., if the user navigates away or the component re-mounts). In a scenario where the socket reconnects very quickly before the user sees the disconnect, the stale ref from a previous session could send an unexpected join.
**Recommendation:** Either clear `pendingNameRef` inside the `DISCONNECTED` reducer action path (requires lifting it into a ref that is reset on disconnect in `onclose`), or reflect the pending state in the reducer so the UI can show it and the user can cancel it.

**[DEFERRED]** — `pendingNameRef` auto-rejoin state not surfaced to reducer; out of scope for this session.

## No Issues Found In

- `frontend/src/App.tsx` — Role-gated rendering derives from a single `useReducer` snapshot per render cycle; no torn reads between `role` and `gameState`.
- `frontend/src/hooks/useWebSocket.ts` — `sendAction` readyState guard is sufficient within the JS single-threaded event loop; no window exists between the check and the `send()` call where the state could change.
- `frontend/src/components/ActionsPanel.tsx` — `useEffect([actions])` correctly resets card states when the action list changes; per-card state patches use functional updater form (`prev => ...`), avoiding stale closure on `cardStates`.
- `frontend/src/components/EntitiesPanel.tsx` — `focusIdx` is clamped defensively on every render; click handlers close over the entities array from their own render, so there is no stale-closure risk.
- `frontend/src/components/DeckerPanel.tsx` — Pure rendering component; no local async or deferred state.
- `frontend/src/components/NarrativePanel.tsx` — Pure rendering component; events array arrives as a prop already bounded to 20 entries by the reducer.
- `frontend/src/components/LocationPanel.tsx` — Pure rendering component; location object lookup is a synchronous array scan on the current render's props.
- `frontend/src/types/messages.ts` — Type definitions only; no runtime state.
- `frontend/src/App.css` — Stylesheet; not applicable.
