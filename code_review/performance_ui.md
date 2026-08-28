---
# Performance Review — ui

## Summary

The Matrix UI is a small, turn-based React/TypeScript app that receives infrequent WebSocket messages and renders at most a few dozen game objects at a time. There are no algorithmic time-bomb issues — no nested loops over unbounded data, no blocking I/O, no expensive computation on the hot path. The dominant performance concerns are React-specific: unnecessary object allocations on every render cycle, missing memoization on derived lists, and one correctness-adjacent key-assignment bug that also causes avoidable reconciliation work. The CSS layer introduces a `border-color` animation that prevents compositor-only promotion. None of these will cause perceptible lag at the current data scale, but they set bad precedents and will hurt if the game ever becomes real-time or the entity/action counts grow.

## Findings

### MEDIUM — Index-based keys cause full list re-reconciliation when the event window slides

**File:** frontend/src/components/NarrativePanel.tsx:32, 46
**Issue:** Events are keyed by their position in the array (`key={i}`). The reducer caps the log at 20 entries using `state.events.slice(-19)` — this drops entries from the *front* of the array. When that happens every existing item shifts its index by 1, so React sees 19 "changed" nodes instead of 1 new node. Each event item is unmounted and remounted with the shifted key, causing a full DOM churn for the entire log on every action. A typical game session fires one action every few seconds, making this a consistent unnecessary tax on every turn resolution.
**Recommendation:** Attach a stable, monotonically increasing sequence number to each event in the reducer (e.g. `seq: state.nextSeq + 1`) and use that as the key. Alternatively derive a key from `ev.kind + ev.msg.details` if uniqueness is guaranteed. Either approach lets React identify that only the tail item is new and reuse the existing 19 DOM nodes.

### MEDIUM — `visibleObjects` filtered twice per render in EntitiesPanel with no memoization

**File:** frontend/src/components/EntitiesPanel.tsx:73, 87
**Issue:** On every render, the component runs `visibleObjects.filter(isEntity)` to produce `entities`, then immediately runs a second `.filter((_, i) => i !== clamped)` to produce the non-focused subset for rendering. Both allocate new arrays. Neither result is memoized. Any parent re-render (e.g., the NarrativePanel `events` array changing) triggers both passes even when `visibleObjects` is reference-identical. The `ENTITY_KINDS.includes(obj.kind)` inside `isEntity` also walks a 4-element array for every object.
**Recommendation:** Wrap both derivations in `useMemo`:
```ts
const entities = useMemo(() => visibleObjects.filter(isEntity), [visibleObjects])
const others   = useMemo(() => entities.filter((_, i) => i !== clamped), [entities, clamped])
```
Replace the `ENTITY_KINDS.includes` membership test with a `Set` for O(1) lookup: `const ENTITY_KIND_SET = new Set<string>(['HostSubsystem', 'IcProgram', 'File', 'Device'])`.

### MEDIUM — New `onClick` closure allocated per entity card on every render

**File:** frontend/src/components/EntitiesPanel.tsx:92
**Issue:** The compact card list is rendered with an inline arrow function:
```ts
onClick={() => setFocusIdx(entities.findIndex((e) => e.index === obj.index))}
```
This creates a brand-new function object for every entity on every render. Because `entities` is also recreated each render (see finding above), the closure captures a fresh `entities` reference every time, invalidating any React bailout that would otherwise skip re-rendering compact cards. Additionally, the `findIndex` inside the handler searches the entities array by `index` field — while this only runs on click, it is unnecessary: the entity's position in the filtered array is already known at render time as `i`.
**Recommendation:** Pass the numeric position directly:
```ts
// In the .map:
.map((obj, i) => (
  <EntityCard key={obj.index} obj={obj} focused={false}
    onClick={() => setFocusIdx(i)} />
))
```
This still creates a closure per item, but captures only a primitive. If further optimization is needed, lift to a single stable `handleCardClick` that reads a `data-idx` attribute from the DOM event target.

### LOW — `pulse-border` animation mutates `border-color`, blocking compositor promotion

**File:** frontend/src/App.css:101-103
**Issue:** The active-turn animation on `.narrative-panel.active-turn` transitions both `box-shadow` and `border-color`:
```css
@keyframes pulse-border {
  from { box-shadow: ...; border-color: var(--green-dim); }
  to   { box-shadow: ...; border-color: var(--green); }
}
```
`box-shadow` changes can be handled on the compositor thread, but `border-color` changes require a repaint every frame. Because the animation runs continuously during the player's turn (every ~0.8 s cycle, alternating), the browser re-paints the narrative panel at 60 fps for the duration of the turn. On lower-end hardware this raises power consumption and can cause jank in neighbouring panels.
**Recommendation:** Achieve the same visual effect using only `box-shadow` and `opacity` (both compositor-only) or replace `border-color` changes with an absolutely-positioned `::after` pseudo-element whose `opacity` or `transform` animates instead.

### LOW — `getState` / `patchState` / `handleClick` recreated on every render in ActionsPanel

**File:** frontend/src/components/ActionsPanel.tsx:56-68
**Issue:** Three functions (`getState`, `patchState`, `handleClick`) are defined as plain `function` declarations inside the component body. They are recreated on every render. `patchState` is passed as an `onClick` prop to multiple inline elements, and `handleClick` is called from each action card's `onClick`. This bypasses React's referential-equality bailout for all child elements that receive these as props.
**Recommendation:** Wrap `patchState` and `handleClick` with `useCallback`. `getState` reads `cardStates` from the render closure; it can be an inline helper or moved inside the `useCallback` bodies where it is used. Note: `patchState` currently calls `getState(idx)` outside the `setCardStates` updater function, which means it reads `cardStates` from a potentially-stale closure if multiple updates are batched. Moving `getState` logic inside the functional updater (`setCardStates(prev => ({ ...prev, [idx]: { ...(prev[idx] ?? defaultCardState()), ...patch } }))`) fixes both the stale-closure risk and avoids the outer-closure capture.

### LOW — `Array.from` and `String.prototype.repeat` called unconditionally on every render

**File:** frontend/src/components/DeckerPanel.tsx:20, 65-66
**Issue:** `DamageMonitor` calls `Array.from({ length: maxBoxes }, ...)` on every render to build the box array. `DeckerPanel` calls `'●'.repeat(...)` and `'○'.repeat(...)` for every active utility on every render. Both allocate new objects regardless of whether the underlying values changed. For a decker with 10 health boxes and 5 utilities, that is 10 array elements plus 10 strings per render cycle.
**Recommendation:** Wrap `DamageMonitor`'s render in `React.memo` and the `Array.from` call in `useMemo(... [maxBoxes])`. Similarly, memoize the rating-bar string per utility. Alternatively, wrap the entire `DeckerPanel` in `React.memo` — since `decker` is only replaced when a `STATE` message arrives, the component would skip all re-renders triggered by unrelated state (e.g. new events).

### LOW — `visibleObjects.find()` linear scan without memoization in LocationPanel

**File:** frontend/src/components/LocationPanel.tsx:75-81
**Issue:** Every render calls `visibleObjects.find(...)` to locate the current location object among visible nodes. Without `useMemo`, this scan repeats on any parent re-render even when `visibleObjects` and `decker.location` are unchanged.
**Recommendation:**
```ts
const locationObj = useMemo(() =>
  decker.location === 'not jacked in'
    ? null
    : visibleObjects.find(o =>
        (o.kind === 'GridNode' || o.kind === 'LocalGrid' || ...) && o.name === name
      ) ?? null,
  [decker.location, visibleObjects, name]
)
```

### INFO — `ERROR_LABELS` map is duplicated across two modules

**File:** frontend/src/App.tsx:10-15 and frontend/src/components/NarrativePanel.tsx:3-8
**Issue:** The same `ERROR_LABELS` record is defined independently in both files. This is two allocations of an identical object at module load, and creates a maintenance hazard (maps can diverge silently).
**Recommendation:** Extract to `frontend/src/utils/errorLabels.ts` and import from both consumers.

### INFO — No explicit `React.memo` on pure display components

**File:** frontend/src/components/DeckerPanel.tsx:33, LocationPanel.tsx:71, EntitiesPanel.tsx:72
**Issue:** When `NarrativePanel`'s `events` prop updates (a new dice result arrives), React re-renders `App`, which re-renders all five panel components even though `gameState`, `role`, and `visibleObjects` are unchanged. `DeckerPanel`, `LocationPanel`, and `EntitiesPanel` are pure display components that produce the same output whenever their props are identical.
**Recommendation:** Wrap each with `React.memo`. This is particularly valuable for `DeckerPanel` and `EntitiesPanel` because they perform non-trivial sub-renders (damage boxes, entity card list). `NarrativePanel` itself is also a candidate since it only needs to update when `events` or `isActiveTurn` changes.

### INFO — Vite build config has no explicit target or chunk strategy

**File:** frontend/vite.config.ts:1-18
**Issue:** No `build.target` is set (defaults to `modules`, which is broad). No `build.rollupOptions.output.manualChunks` is configured, so React and React-DOM land in the same bundle as application code. For a production deployment this means the app bundle cannot be cached independently from vendor libraries.
**Recommendation:** Add:
```ts
build: {
  target: 'es2020',
  rollupOptions: {
    output: {
      manualChunks: { vendor: ['react', 'react-dom'] },
    },
  },
}
```

## Clean Areas

- **useWebSocket.ts reducer design**: The `useReducer` + `useRef` pattern correctly separates WebSocket lifecycle management from React rendering. `join` and `sendAction` are properly stabilised with `useCallback([], [])`, preventing cascading re-renders of consumers.
- **Event log bounded at 20 entries**: `state.events.slice(-19)` in the reducer ensures the log never grows unbounded, which is the right place to enforce this cap.
- **Reconnect with exponential back-off**: The doubling delay (3 s → 30 s max) is implemented cleanly with refs so it does not interfere with React state.
- **No third-party state or animation libraries**: The app imports only React and React-DOM. This keeps the bundle tiny (~140 kB unminified) and removes entire categories of performance risk.
- **WebSocket message dispatch is O(1)**: The `switch (msg.type)` in `onmessage` dispatches immediately with no scanning, sorting, or transformation before the reducer sees the data.
- **CSS variables for theming**: All colours are CSS custom properties. There are no JS-side colour computations or inline style objects that would cause style recalculations.
- **`ActionsPanel` card state keyed by server-assigned `action.index`**: State is not keyed by array position, so local control state (precision toggle, scanner rating) survives server re-orderings of the action list.
---
