# Architecture Review — ui

## Summary

The UI is a lean React/TypeScript single-page application that communicates with the backend exclusively over WebSocket. Overall the architecture is sound: all network logic is correctly encapsulated in one custom hook (`useWebSocket`), the reducer pattern is used appropriately for the multi-message state machine, and the five display panels are functionally cohesive. The main structural weaknesses are (1) a `LocationPanel` that performs domain logic — location object lookup and string parsing — that belongs in the hook or reducer, (2) a duplicated and incomplete `ERROR_LABELS` map split across `NarrativePanel` and `App`, and (3) a few leaky or unnecessarily wide prop contracts. None of these are showstoppers, but the location-panel issue in particular makes the component harder to test and maintain.

---

## Findings

### [HIGH] Duplicate and incomplete ERROR_LABELS in NarrativePanel

**File:** `frontend/src/components/NarrativePanel.tsx:3`

**Issue:** `NarrativePanel` defines its own `ERROR_LABELS: Record<string, string>` that covers only 4 of the 7 `ErrorCode` values (`name_too_long`, `unknown_message_type`, and `bad_request` are missing). `App.tsx` separately defines the canonical, fully-typed `Record<ErrorCode, string>` map. The two maps are out of sync: `NarrativePanel` shows the raw code string for errors not in its local map, while `App.tsx` renders the correct label. Any future error code addition requires two separate edits with no compiler enforcement of completeness on the panel copy.

**Recommendation:** Export the `ERROR_LABELS` map (or a lookup helper) from a shared file — e.g. `src/utils/errorLabels.ts` or directly from `src/types/messages.ts` — and import it in both `App.tsx` and `NarrativePanel.tsx`. Type the map as `Record<ErrorCode, string>` so the compiler enforces completeness.

---

### [MEDIUM] Business logic (location object lookup) inside a display component

**File:** `frontend/src/components/LocationPanel.tsx:71`

**Issue:** `LocationPanel` receives the full `gameState` shape and internally finds the current location node by filtering `visibleObjects` on `o.name === name`. This is domain logic — deriving "which Matrix node am I standing in right now" from two separate data fields — embedded in a pure display component. It also uses name-equality matching, which would silently return `null` if two nodes share a name.

**Recommendation:** Compute the current location object in the `useWebSocket` reducer (or as a derived value in `App`) and pass it directly to `LocationPanel` as a prop (`currentNode: MatrixObjectDto | null`). The panel's prop type becomes narrow and explicit; the lookup logic is tested at the hook/app level alongside the rest of state management.

---

### [MEDIUM] Fragile string-prefix parsing for location label

**File:** `frontend/src/components/LocationPanel.tsx:8`

**Issue:** `locKey()` splits `decker.location` by scanning for hardcoded prefixes (`'RTG: '`, `'LTG: '`, `'Host: '`, etc.) to separate a display label from a node name. This is implicit parsing of a backend-controlled string with no shared contract. If the backend ever changes the format (capitalisation, separator, new node type), the UI silently displays wrong labels or blank prefixes.

**Recommendation:** Replace the free-form `location: string` field on `DeckerStateDto` with a structured object — e.g. `{ nodeType: TopologyType | 'RTG' | 'LTG' | ...; nodeName: string } | null` — so the type system enforces the contract. If the backend field cannot be changed, move the parsing into `useWebSocket`'s `STATE` reducer case where it can be tested in isolation.

---

### [MEDIUM] LocationPanel prop type is an anonymous inline subset of StateMessage

**File:** `frontend/src/components/LocationPanel.tsx:5`

**Issue:** The `Props` interface declares `gameState: { decker: DeckerStateDto; visibleObjects: MatrixObjectDto[] }` — an anonymous structural type that mirrors a slice of `StateMessage`. This is both wider than needed (the component only uses `decker.location` and a filtered subset of `visibleObjects`) and not linked to any named type, so if `StateMessage` evolves the mismatch is only caught at use-sites, not at the definition.

**Recommendation:** Once the location lookup is moved to the caller (see finding above), the prop type shrinks to exactly what the component renders: `location: string`, `currentNode: MatrixObjectDto | null`. This makes the component's contract self-documenting.

---

### [MEDIUM] Inline styles in LocationPanel duplicate App.css rules

**File:** `frontend/src/components/LocationPanel.tsx:86`

**Issue:** The `panel-body` div carries `style={{ flexDirection: 'row', flexWrap: 'wrap', gap: '14px 24px', alignItems: 'flex-start' }}`. `App.css` line 265 already contains `.location-panel .panel-body` with exactly these properties. The inline style wins over the stylesheet rule, making the CSS rule dead and creating a split-brain maintenance situation (a future CSS change to that rule has no effect).

**Recommendation:** Remove the inline `style` prop and rely solely on the existing CSS rule, which is already scoped correctly to `.location-panel .panel-body`.

---

### [MEDIUM] NarrativePanel has no auto-scroll to latest event

**File:** `frontend/src/components/NarrativePanel.tsx:15`

**Issue:** Events are appended to the end of the `events` array and rendered in order. The panel uses `justify-content: flex-end` in CSS to push content to the bottom, but this only works when the total content is shorter than the panel. Once 5–6 events accumulate the user must manually scroll down; new events arrive off-screen. There is no `useEffect` to scroll the list to its bottom on update.

**Recommendation:** Add a `useRef` on the event list container (or a sentinel element at the bottom) and a `useEffect` that calls `ref.current.scrollTop = ref.current.scrollHeight` (or `scrollIntoView`) whenever `events` changes.

---

### [LOW] Magic number 19 for event ring-buffer cap

**File:** `frontend/src/hooks/useWebSocket.ts:50`

**Issue:** `state.events.slice(-19)` produces a 20-element cap. The number 19 appears twice (once for RESULT, once for ERROR) with no named constant explaining it. It is easy to misread as "keep 19" rather than "keep 20".

**Recommendation:** Extract to a named constant: `const MAX_EVENTS = 20` (or place it near the `initialState`) and write `state.events.slice(-(MAX_EVENTS - 1))`.

---

### [LOW] Registered role check is an inlined expression rather than a named concept

**File:** `frontend/src/App.tsx:81`

**Issue:** `const isRegistered = ws.role === 'registered_decker' || ws.role === 'active_controller'` encodes a domain rule as an ad-hoc boolean. The same predicate may need to be replicated elsewhere as the app grows.

**Recommendation:** Export a small helper from `messages.ts` or a utils file: `export function isRegisteredRole(role: Role | null): boolean`. This gives the concept a name, keeps the role enumeration logic in one place, and is trivially unit-testable.

---

### [LOW] Cryptic component name EF in EntitiesPanel

**File:** `frontend/src/components/EntitiesPanel.tsx:15`

**Issue:** The local helper component is named `EF`. The name is not self-explanatory; a reader unfamiliar with the file must infer its purpose from context.

**Recommendation:** Rename to `EntityField` to match the surrounding naming style (`EntityCard`, `entity-field` CSS class).

---

### [LOW] ActionsPanel CardState is a flat bag across all operation types

**File:** `frontend/src/components/ActionsPanel.tsx:34`

**Issue:** `CardState` stores `precision`, `hasValidPasscode`, `scannerDeviceRating`, and `newContent` for every action card, regardless of which operation the card represents. An `EDIT_FILE` card carries a `precision` field it will never use. More importantly, `buildParams` uses the current `op` string to decide which field to read — if the server ever sends a different `operation` value for an already-expanded card (e.g. after a game state refresh), stale values from a different param type could be silently sent.

**Recommendation:** This is low-risk for the current action set, but consider making `CardState` a discriminated union keyed by the operation type, or at minimum resetting card state when the `actions` prop changes (the current state persists across state refreshes).

---

### [INFO] Operation-to-params mapping duplicates backend domain knowledge

**File:** `frontend/src/components/ActionsPanel.tsx:26`

**Issue:** `needsPrecision`, `needsPasscode`, `needsScanner`, and `needsEdit` hard-code which `SystemOperation` values require which `ActionParams` fields. This knowledge lives in both the Kotlin backend and this file with no shared contract. Adding a new parameterised operation requires a coordinated, manually-discovered change on the frontend.

**Recommendation:** Consider having the backend annotate each `AvailableActionDto` with a `paramKind` discriminator field (e.g. `'precision' | 'passcode' | 'scanner' | 'edit' | null`) so the frontend renders the correct control purely from the DTO, with no operation-name switch logic.

---

### [INFO] Role is sourced from both CONTROL and STATE messages

**File:** `frontend/src/hooks/useWebSocket.ts:39-46`

**Issue:** `role` is set on both the `CONTROL` and `STATE` reducer cases. In normal flow this is consistent, but if a `STATE` arrives slightly before the `CONTROL` that changes role (e.g. during reconnect sequencing), there is a brief window where the displayed role differs from the server's intent. This is currently safe because the server serialises CONTROL before STATE, but it is undocumented.

**Recommendation:** Add a brief comment in the reducer explaining the expected message ordering and that STATE's role field is considered authoritative. Alternatively, remove `role` from `StateMessage` on the Kotlin side and rely solely on `ControlMessage` for role — but this is a backend contract change.

---

### [INFO] No responsive layout

**File:** `frontend/src/App.css:29`

**Issue:** The `.game-grid` is a fixed 3-column CSS Grid with no breakpoints. On screens narrower than roughly 900px the panels will be visually broken.

**Recommendation:** If the tool is genuinely desktop-only (GM tooling at a table), add a comment documenting the minimum supported viewport width. If mobile/tablet is a future target, add at least one `@media` breakpoint that stacks the panels vertically.

---

## No Issues Found In

- **useWebSocket.ts — WebSocket/display separation:** All WebSocket lifecycle management, message parsing, reconnection backoff, and state transitions are fully contained inside the hook. No panel component touches `WebSocket` directly. The hook's public surface (`connected`, `role`, `gameState`, `events`, `reconnected`, `join`, `sendAction`) is clean and minimal.
- **useWebSocket.ts — Reducer correctness:** The `useReducer` + `useRef` split is correctly applied: serialisable UI state goes in the reducer, imperative handles (`wsRef`, `reconnectTimer`, `reconnectDelay`, `pendingNameRef`) go in refs. No mutable ref is accessed from render code.
- **useWebSocket.ts — Reconnection logic:** Exponential backoff (3 s → 30 s cap) with timer cleanup on unmount is implemented correctly.
- **useWebSocket.ts — pendingName join race:** The `pendingNameRef` approach correctly handles the case where `join()` is called before the WebSocket handshake completes.
- **DeckerPanel.tsx:** Pure display component with no state, no side effects, and a tight prop contract. The nested `DamageMonitor` sub-component is appropriately factored out.
- **ActionsPanel.tsx — click propagation:** Inline control widgets correctly stop click propagation so adjusting a param does not fire the action.
- **types/messages.ts:** The discriminated union types (`MatrixObjectDto`, `AvailableActionDto`, `ServerMessage`) are well-structured. The comment block (lines 52–57) documenting the Kotlin enum sync requirement is exemplary.
- **App.tsx — render-path logic:** The three render phases (join screen → sync banner → game grid) are cleanly separated with early returns and no tangled conditionals.
- **App.css — design token system:** The `--green`, `--green-dim`, `--green-faint`, `--red-alert`, `--amber`, `--blue` custom properties are consistently used throughout and centralised in `:root`. No magic colour literals appear in component rules.
