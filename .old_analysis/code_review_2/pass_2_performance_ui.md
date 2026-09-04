# Performance Review — ui

## Summary

The UI layer is lean and straightforward, which limits the blast radius of any individual issue. However, none of the five panel components are wrapped in `React.memo`, so every WebSocket `state` message triggers a full re-render of the entire component tree — all five panels, unconditionally. For a turn-based tabletop game the message rate is low enough that this rarely matters in practice, but the pattern is still wrong and will scale badly if the server ever streams incremental updates. Three other issues carry real correctness risk alongside their performance impact: a stale-closure bug in `ActionsPanel.patchState` can silently drop rapid control interactions; `LocationPanel` accepts the entire `gameState` object (including `availableActions`) when it only needs two fields, coupling its re-render cadence to data it never reads; and `NarrativePanel` keys event rows by array index, causing React to diff and repaint every row whenever the bounded window slides and drops the oldest entry.

---

## Findings

### [MEDIUM] No React.memo on any panel component
**File:** `frontend/src/App.tsx:106-114`
**Issue:** `LocationPanel`, `DeckerPanel`, `NarrativePanel`, `EntitiesPanel`, and `ActionsPanel` are all rendered as plain JSX without memoization. Every `STATE` message dispatched by the WebSocket reducer produces a new `gameState` reference, which re-renders `App` and unconditionally re-renders all five children — including panels whose props have not changed (e.g., `DeckerPanel` re-renders even when only `availableActions` changed).
**Recommendation:** Wrap each panel in `React.memo`. Because the props are plain objects and arrays from the server DTO, shallow equality is sufficient. No callback-stability work is needed because `sendAction` is already a stable `useCallback` reference.

---

### [MEDIUM] LocationPanel receives full gameState; re-renders on availableActions changes
**File:** `frontend/src/components/LocationPanel.tsx:5`
**Issue:** The component's `Props` interface accepts `{ decker: DeckerStateDto; visibleObjects: MatrixObjectDto[] }` (correctly typed), but at the call site in `App.tsx:106` the whole `gameState` (`StateMessage`) is passed. `StateMessage` also carries `availableActions` and `role`. Any time the server sends a new action list without changing location or decker state, `LocationPanel` still re-renders because `gameState` is a new object reference.
**Recommendation:** Pass only the two fields the component actually uses: `<LocationPanel decker={gameState.decker} visibleObjects={gameState.visibleObjects} />`. This narrows the re-render trigger and makes the coupling explicit. Combined with `React.memo`, the component will skip renders when neither field changes.

---

### [MEDIUM] Stale-closure bug in ActionsPanel.patchState drops rapid updates
**File:** `frontend/src/components/ActionsPanel.tsx:60-62`
**Issue:** `patchState` uses a functional updater (`setCardStates(prev => ...)`) but then calls `getState(idx)` inside it, which reads the closed-over `cardStates` snapshot rather than `prev`. If two updates fire in quick succession (e.g., tapping the `+` stepper button twice before React batches a commit), the second call reads stale state and overwrites the first update.

```ts
// current — stale read of outer cardStates:
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({ ...prev, [idx]: { ...getState(idx), ...patch } }))
}
```
**Recommendation:** Read from `prev` inside the updater:
```ts
function patchState(idx: number, patch: Partial<CardState>) {
  setCardStates(prev => ({
    ...prev,
    [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch },
  }))
}
```

---

### [LOW] EntitiesPanel runs two filter passes on every render without useMemo
**File:** `frontend/src/components/EntitiesPanel.tsx:73,87`
**Issue:** `visibleObjects.filter(isEntity)` (line 73) and the second `.filter((_, i) => i !== clamped)` (line 87) run on every render. For the typical entity counts in a Matrix run (a handful of IC programs, files, and subsystems) this is cheap, but both are pure derivations of props and state that could be computed once.
**Recommendation:** Memoize the entity list with `useMemo`:
```ts
const entities = useMemo(() => visibleObjects.filter(isEntity), [visibleObjects])
```
The second filter is already inside the render path and fine as-is, or can be inlined as a slice/concat if needed.

---

### [LOW] LocationPanel.visibleObjects.find runs on every render without useMemo
**File:** `frontend/src/components/LocationPanel.tsx:75-81`
**Issue:** The linear scan through `visibleObjects` to find the current location node runs unconditionally on every render. In a large host with many visible objects this is O(n) work repeated each render cycle.
**Recommendation:** Wrap in `useMemo` keyed on `visibleObjects` and `decker.location`:
```ts
const locationObj = useMemo(() => {
  if (decker.location === 'not jacked in') return null
  return visibleObjects.find(
    (o) => (o.kind === 'GridNode' || ...) && o.name === name
  ) ?? null
}, [visibleObjects, decker.location])
```

---

### [LOW] NarrativePanel keys event rows by array index
**File:** `frontend/src/components/NarrativePanel.tsx:26`
**Issue:** The events list uses `key={i}` (array index). The bounded window in the reducer (`events.slice(-19)`) removes the oldest entry from the front of the array whenever the 20-entry cap is hit. Because keys are positional, React cannot tell which DOM node maps to which event; it diffs every row in the list and updates them all, rather than just appending the new entry.
**Recommendation:** Assign a stable, monotonically increasing sequence number to each event when it is added in the reducer. Use that id as the React key:
```ts
// in WsState: events: Array<GameEvent & { id: number }>
// in reducer: { kind: 'result', id: state.nextEventId, msg: action.msg }
```
This lets React skip all unchanged rows on append.

---

### [LOW] ActionsPanel cardStates accumulates stale entries across game turns
**File:** `frontend/src/components/ActionsPanel.tsx:54`
**Issue:** `cardStates` is keyed by `action.index` (the server-assigned action index). Action indices change every turn as the available action set changes. Old entries remain in the map indefinitely and are never cleaned up. In a long session the object grows without bound, though the practical impact is small because the number of distinct actions is limited.
**Recommendation:** Reset `cardStates` whenever the `actions` prop changes, using a `useEffect` or by deriving a `resetKey` from the action set and reinitializing state:
```ts
useEffect(() => {
  setCardStates({})
}, [actions])
```

---

### [LOW] Inline onClick factory functions recreated on every render in ActionsPanel
**File:** `frontend/src/components/ActionsPanel.tsx:88,105,136`
**Issue:** Arrow functions like `() => handleClick(action)` and `() => patchState(action.index, { precision: v })` are created fresh on every render inside the `.map()`. Without `React.memo` on `action-card` sub-elements these are harmless, but they prevent any future memoization of individual cards from working.
**Recommendation:** Low priority until card-level memoization is introduced. If cards are eventually memoized, extract handlers using `useCallback` with stable identities or use a data-attribute pattern to avoid per-card closures.

---

### [INFO] ERROR_LABELS duplicated between App.tsx and NarrativePanel.tsx
**File:** `frontend/src/App.tsx:10-18`, `frontend/src/components/NarrativePanel.tsx:3-8`
**Issue:** Two separate copies of the error-label map exist. NarrativePanel's copy is also incomplete (four entries vs. seven in App.tsx), so some error codes fall through to the raw `message` string in NarrativePanel.
**Recommendation:** Extract a single `ERROR_LABELS` map to `frontend/src/types/messages.ts` or a shared `utils/errors.ts` and import it in both places.

---

### [INFO] No WebSocket message batching or throttling
**File:** `frontend/src/hooks/useWebSocket.ts:87-112`
**Issue:** Every incoming WebSocket frame calls `dispatch` synchronously, producing an immediate synchronous re-render. For the current turn-based protocol (one `state` message per turn, one `result` per action) this is perfectly acceptable. The concern would arise only if the server were changed to stream incremental updates at a high rate.
**Recommendation:** No action required for the current protocol. If the server ever shifts to streaming partial updates, batch dispatches with `unstable_batchedUpdates` (React 17) or rely on automatic batching (React 18+) and consider debouncing `STATE` frames.

---

## No Issues Found In

- `useWebSocket` reducer design — immutable updates, events array correctly bounded to 20 entries, no unbounded growth
- `join` and `sendAction` callback stability — both correctly wrapped in `useCallback` with empty dependency arrays
- WebSocket reconnect / exponential-backoff logic — uses refs correctly, no closure or timer-leak issues
- `DeckerPanel` rendering — `Array.from` and `'●'.repeat()` produce tiny allocations for the small damage-track sizes involved; not worth optimizing
- `messages.ts` — pure type declarations, zero runtime cost
- `actionLabel` / `operationOf` / `needsPrecision` / `buildParams` — all top-level pure functions, stable across renders, no memoization needed
