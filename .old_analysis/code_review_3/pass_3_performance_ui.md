# Performance Review — ui

## Summary

The UI frontend is a small, turn-based WebSocket game interface. The overall architecture is sound: a single WebSocket hook drives a reducer, state updates are infrequent (one server push per player action), and component trees are shallow with small data sets (≤20 events, ≤~20 visible entities, ≤~30 actions). No algorithmic hot paths, no large list virtualisation needs, and no blocking work outside the main thread. There are two concrete issues worth fixing — one that causes unnecessary state loss on every server push, and one where a CSS animation forces repaints instead of compositor-only frames — plus a handful of minor allocation patterns that are negligible at current scale but worth noting.

## Findings

### [MEDIUM] ActionsPanel card state reset on every game state push

**File:** frontend/src/components/ActionsPanel.tsx:56-58
**Issue:** `useEffect(() => { setCardStates({}) }, [actions])` compares `actions` by reference. The reducer in `useWebSocket.ts` replaces the entire `gameState` object on every `state` message (`case 'STATE': return { ...state, ..., gameState: action.msg }`), which means `actions` is always a new array reference even when the actual action list is identical. As a result, card state — including textarea content for EDIT_FILE and the precision/passcode/scanner controls — is wiped clean on every server push. If the server sends a state update while the user is composing an edit (e.g., typing file content), their input is silently discarded. Beyond the UX bug this also triggers a redundant `setCardStates` call and re-render on every message.
**Recommendation:** Stabilise the comparison by keying on a serialised form of the action list rather than its reference. The simplest fix is to stringify the action index+kind list and use that as the effect dependency, or to use a `useRef` to track the previous serialised actions and skip the reset when the list is structurally unchanged. Example: derive a stable key `const actionsKey = actions.map(a => a.index).join(',')` and depend on that string instead of the `actions` array.

**[DEFERRED]** — Card-state reset on every push not stabilised; out of scope for this session.

### [LOW] pulse-border animation forces repaints on every frame during active turn

**File:** frontend/src/App.css:100-109
**Issue:** The `pulse-border` keyframe animates `box-shadow` and `border-color`. Neither property is compositable — they trigger layout-adjacent paint work on every animation frame (roughly 60 fps), not just composited GPU layer promotion. During active turn the narrative panel runs this animation continuously.
**Recommendation:** Replace with an `outline` animation (which some browsers promote) or, more reliably, animate `opacity` on a pseudo-element (`::after`) sized to cover the border area. Animating `opacity` and `transform` are the only properties guaranteed to stay on the compositor thread across all browsers. Alternatively, `filter: drop-shadow(...)` on a wrapper is compositor-friendly in most modern engines.

**[DEFERRED]** — `pulse-border` animation not changed to compositor-only properties; out of scope for this session.

### [LOW] EntitiesPanel performs two full passes over the entity list per render

**File:** frontend/src/components/EntitiesPanel.tsx:73,86-94
**Issue:** Every render runs `.filter(isEntity)` to build `entities`, then immediately runs `.filter((_, i) => i !== clamped)` to build the compact list for the non-focused cards. This is two O(n) array allocations where one would do. For typical entity counts (< 20) this is negligible in isolation, but it also lacks `useMemo`, so both passes re-run on every game state push regardless of whether `visibleObjects` changed content.
**Recommendation:** Combine into a single pass, and wrap with `useMemo` keyed on `visibleObjects`:
```ts
const entities = useMemo(() => visibleObjects.filter(isEntity), [visibleObjects])
```
Then replace the second filter with a conditional in the `map`:
```ts
{entities.map((obj, i) => i === clamped ? null : <EntityCard key={obj.index} ... />)}
```

**[DEFERRED]** — `EntitiesPanel` double-pass not combined with `useMemo`; out of scope for this session.

### [LOW] DamageMonitor allocates a new array and two strings on every render

**File:** frontend/src/components/DeckerPanel.tsx:20-30 and 65-66
**Issue:** `Array.from({ length: maxBoxes }, ...)` allocates a fresh array on every render of `DamageMonitor`. Additionally, `'●'.repeat(Math.min(u.rating, 10))` and `'○'.repeat(Math.max(0, 10 - u.rating))` build new strings on every render of each utility row. At current scale (maxBoxes ≈ 10, utilities ≈ 5) this is negligible, but the component re-renders on every server push.
**Recommendation:** Wrap `DamageMonitor` with `React.memo` so it only re-renders when `damage` or `maxBoxes` actually change. Similarly wrap `DeckerPanel` or the utility row rendering. The `repeat` strings can be memoised locally or moved to a lookup table if this ever becomes a concern.

**[DEFERRED]** — `DamageMonitor` and utility-row allocations not memoised; out of scope for this session.

### [INFO] NarrativePanel uses index-based keys on an append-only sliding window

**File:** frontend/src/components/NarrativePanel.tsx:26
**Issue:** Events are keyed by array index `i`. When a new event arrives the reducer pops the oldest entry (`events.slice(-19)`), shifting every index by one. React sees key `0` as a changed node (old item 1 is now at position 0) and re-renders all items instead of only inserting the new tail element.
**Recommendation:** Add a monotonically increasing `id` field to `GameEvent` (generated in the reducer, e.g., `eventSeq: state.eventSeq + 1`) and use that as the key. This lets React correctly identify which item is new and skip re-rendering the unchanged older items.

**[DEFERRED]** — Index-based keys in `NarrativePanel` not replaced with stable IDs; out of scope for this session.

## No Issues Found In

- `frontend/src/hooks/useWebSocket.ts` — Reducer pattern is efficient; events array is bounded to 20 entries; exponential backoff uses refs correctly to avoid stale closures; `connect`, `join`, and `sendAction` are stable `useCallback` references.
- `frontend/src/types/messages.ts` — Pure type declarations; zero runtime cost.
- `frontend/src/App.tsx` — Top-level routing logic is trivial; `ERROR_LABELS` is a module-level constant (not recreated per render); `JoinScreen` effect is correctly scoped to `events` and only fires on event array changes.
- `frontend/src/components/LocationPanel.tsx` — `visibleObjects.find()` on a small bounded array; `locKey` linear scan over 4 prefixes is trivial; no unnecessary allocations.
- `frontend/src/components/NarrativePanel.tsx` — Renders at most 20 items; no computed work beyond simple conditionals.
- `frontend/src/components/ActionsPanel.tsx` (WebSocket send path) — `sendAction` is a stable `useCallback` with a ref guard on socket state; no allocations in the hot send path beyond a single `JSON.stringify` of a small object.
- `frontend/src/App.css` — Static `box-shadow` on panels does not animate; CSS custom properties avoid repeated value computation; `blink` animation uses `step-end` timing which minimises intermediate paint frames.
