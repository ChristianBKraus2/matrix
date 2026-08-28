---
# Concurrency Review — ui

## Summary

The UI is a React 18 single-page app with no Web Workers or SharedArrayBuffer, so classical thread-safety hazards do not apply. The relevant concurrency surface is instead: React's asynchronous rendering and automatic batching (which can expose stale-closure bugs), the WebSocket lifecycle (which involves overlapping async callbacks and mutable refs), and check-then-act patterns on `wsRef.current` that are safe in most circumstances but break down when the socket is in the CONNECTING state. Two genuine correctness defects were found — one that can produce duplicate live WebSocket connections and one that silently discards user input when React batches state updates — plus several lower-severity hygiene issues.

## Findings

### HIGH: `connect()` guard does not exclude CONNECTING state — duplicate sockets possible

**File:** frontend/src/hooks/useWebSocket.ts:74  
**Issue:** The early-return guard is `wsRef.current?.readyState === WebSocket.OPEN`. `WebSocket.OPEN` is `1`. `WebSocket.CONNECTING` is `0`. If `connect()` is called a second time while the first socket is still handshaking (readyState `0`) — which happens under React 18 StrictMode's double-invoke of effects in development, or if the reconnect timer fires very quickly after a failed connection attempt and a new caller also invokes `connect()` — the guard is `false` and a second `WebSocket` object is created. `wsRef.current` is immediately overwritten with the new socket (line 77), but the first socket's event handlers remain live (they close over the local `ws` variable). When the abandoned socket eventually opens and then closes, its `onopen` dispatches a spurious `CONNECTED` action, its `onclose` dispatches `DISCONNECTED`, and — critically — overwrites `reconnectTimer.current` with a new timer handle, leaking the previous one. Under StrictMode this sequence is reliably reproducible: cleanup closes ws1 → ws1.onclose fires asynchronously → by then the second effect run has already created ws2 via the same guard → ws1.onclose races with ws2's normal lifecycle.

**Recommendation:** Extend the guard to cover `CONNECTING` as well:
```ts
const rs = wsRef.current?.readyState
if (rs === WebSocket.OPEN || rs === WebSocket.CONNECTING) return
```
This ensures at most one socket is ever in-flight at a time.

---

### MEDIUM: `patchState` reads stale closure state inside a functional updater

**File:** frontend/src/components/ActionsPanel.tsx:61  
**Issue:** `patchState` is:
```ts
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
}
```
`getState(idx)` reads the `cardStates` variable captured from the enclosing render's closure, not from `prev`. In React 18's automatic batching, multiple calls to `patchState` for the same `idx` within a single event handler (or across microtasks in a concurrent render) all see the same stale `cardStates[idx]`. The second call therefore spreads the original pre-patch values under the first patch's changes, silently discarding the first update. A concrete example: the EDIT_FILE textarea fires a rapid change event while a precision toggle is also being set; both `patchState` calls are batched, the first is lost.

**Recommendation:** Read from `prev` inside the updater instead of from the closure:
```ts
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({
    ...prev,
    [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch },
  }))
}
```

---

### MEDIUM: Reconnect timer handle overwritten without clearing — orphaned timers possible

**File:** frontend/src/hooks/useWebSocket.ts:112  
**Issue:** Inside `ws.onclose`, a new timer is unconditionally assigned to `reconnectTimer.current`:
```ts
reconnectTimer.current = setTimeout(() => { ... connect() }, reconnectDelay.current)
```
If `reconnectTimer.current` already holds a live timer (e.g., from a prior socket closing before its timer fired, which can happen if the new socket immediately fails), the old handle is orphaned — it cannot be cancelled because the ref no longer points to it. The orphaned timer fires later and calls `connect()`, potentially creating a socket when one is already open or connecting. The unmount cleanup only cancels whatever is in `reconnectTimer.current` at that moment; orphaned handles escape it.

**Recommendation:** Clear any existing timer before scheduling a new one:
```ts
ws.onclose = () => {
  dispatch({ type: 'DISCONNECTED' })
  if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
  reconnectTimer.current = setTimeout(() => {
    reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30000)
    connect()
  }, reconnectDelay.current)
}
```

---

### LOW: `focusIdx` silently migrates to a different entity when `visibleObjects` changes

**File:** frontend/src/components/EntitiesPanel.tsx:74–75  
**Issue:** `focusIdx` is local component state that is never reset when the `visibleObjects` prop is replaced by a new server state message. The `clamped` guard prevents an out-of-bounds crash, but when the entity list shrinks and then grows again, `focusIdx` may point to a completely different entity than the one the user was examining. Because entities are identified by `index` (a server-assigned integer) rather than by position in the array, the apparent focus can jump to an unrelated entity without any user gesture. This is a state-management race between component-local UI state and server-driven prop updates.

**Recommendation:** Use a `useEffect` to reset the focus when the entities list identity changes:
```ts
const entityIds = entities.map(e => e.index).join(',')
useEffect(() => { setFocusIdx(0) }, [entityIds])
```
Alternatively, store the focused entity's `index` rather than its array position, so the focus follows the entity through list mutations.

---

### LOW: `join()` reads `wsRef.current` twice across a check-then-act

**File:** frontend/src/hooks/useWebSocket.ts:131–133  
**Issue:**
```ts
if (wsRef.current?.readyState === WebSocket.OPEN) {
  wsRef.current.send(JSON.stringify(msg))
}
```
`wsRef.current` is dereferenced twice. In single-threaded JavaScript this is safe, but the pattern is fragile: if the code were ever refactored to introduce an `await` between the check and the send (e.g., for message serialization), the ref could be replaced in between. This is the same check-then-act shape that causes the HIGH finding above.

**Recommendation:** Cache the ref locally before the check to make the intent unambiguous and safe under any future async refactoring:
```ts
const ws = wsRef.current
if (ws?.readyState === WebSocket.OPEN) {
  ws.send(JSON.stringify(msg))
}
```
Apply the same pattern in `sendAction` (line 138) and in `connect` (line 74).

---

### INFO: `ERROR_LABELS` map is duplicated across two modules

**File:** frontend/src/App.tsx:10–15 and frontend/src/components/NarrativePanel.tsx:3–8  
**Issue:** The same `ERROR_LABELS` constant is copy-pasted into both files. If a new error code is added on the server side, only one copy tends to be updated, causing the other to show raw server strings instead of human-readable labels. This is not a concurrency defect but is a shared-mutable-data analogue: two consumers of the same conceptual truth with no single source.

**Recommendation:** Extract to `frontend/src/types/messages.ts` (or a new `errorLabels.ts` utility) and import from both consumers.

---

### INFO: JoinScreen error is never cleared when a non-error event arrives

**File:** frontend/src/App.tsx:29–34  
**Issue:** The `useEffect` fires on every change to the `events` array and sets `error` only when the last event is an `error` kind. It never calls `setError('')` when the last event is a `result`. So if the user sees an error (e.g., "name already taken"), edits their name, receives a `result` event for some unrelated reason, and the event array reference changes, the error banner stays on screen indefinitely until `handleSubmit` is clicked. In a concurrent render where events arrive rapidly, the effect could also mis-attribute an error from a prior attempt to the current join attempt if events are processed in burst.

**Recommendation:** Clear the error whenever an event that is not of kind `error` arrives, or, preferably, model the join error as a distinct server-side response rather than sharing the generic `events` log:
```ts
useEffect(() => {
  const last = events[events.length - 1]
  if (last?.kind === 'error') {
    setError(ERROR_LABELS[last.msg.message] ?? last.msg.message)
  } else if (last) {
    setError('')
  }
}, [events])
```

## Clean Areas

- The reducer in `useWebSocket.ts` is a pure function with no side effects; all state transitions go through a single dispatch path, which eliminates the class of bugs caused by multiple independent `useState` setters updating related fields non-atomically.
- `useCallback([], [])` with an empty dependency array on `connect`, `join`, and `sendAction` correctly prevents those functions from being recreated on every render, avoiding effect re-subscription loops.
- Incoming message parsing is wrapped in a try/catch that silently drops malformed frames rather than crashing the WebSocket pipeline.
- The exponential backoff logic (`Math.min(delay * 2, 30000)`) is correct and prevents thundering-herd reconnection storms.
- All server-to-client message dispatching uses discriminated union switching (`msg.type`) with no implicit field access, making unexpected message shapes fail loudly at the switch default rather than silently corrupting state.
- Component prop types are all immutable data (plain DTOs from the server); no component shares mutable objects or callback references that could cause cross-component state corruption.
- `ActionsPanel` correctly uses `e.stopPropagation()` on inline controls to prevent a control-click from simultaneously firing the parent action click handler — avoiding the double-dispatch hazard.
---
