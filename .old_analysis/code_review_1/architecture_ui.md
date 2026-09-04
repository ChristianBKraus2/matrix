---
# Architecture Review — ui

## Summary

The frontend is a lean React/TypeScript single-page app with a clear top-level decomposition: one hook owns server communication, one App component owns routing between screens, and five panel components own their respective display areas. For a project of this size the overall shape is sound. The main architectural weaknesses are concentrated in two places: `useWebSocket` has absorbed four distinct concerns into one unit (transport, protocol dispatch, session state machine, and outbound message construction), and a handful of domain concepts — error label translation, location-string parsing, and operation-parameter classification — are duplicated or stranded in the wrong layer. These issues are not fatal but they will compound as the feature set grows.

## Findings

### HIGH — useWebSocket bundles transport, protocol, session state machine, and command builder

**File:** frontend/src/hooks/useWebSocket.ts:1
**Issue:** The hook simultaneously owns (1) WebSocket lifecycle and exponential-backoff reconnection, (2) raw-frame dispatch (`switch msg.type`), (3) all client-visible state via a `useReducer`, (4) the implicit join handshake (detecting `observer` role inside `onmessage` to replay a pending name), and (5) outbound message serialisation for both `join` and `action`. A change to the reconnection strategy, the wire protocol, the session flow, or the action-command shape all require edits to the same 150-line file. The hook is also the only unit that "knows" the observer-role handshake exists, but that knowledge is not in the reducer — it lives as a hidden side-effect inside `onmessage`, making the join flow invisible to any reader of `WsState`.
**Recommendation:** Split into at least three units. (a) A `createWsTransport(url)` module that owns connection, reconnection, and raw `send`/`onmessage` callbacks — framework-agnostic, easily unit-tested. (b) A `useGameSession` hook that owns the `useReducer`, the join state machine (including the pending-name handshake, which should be a reducer state value, not a ref), and exposes typed `join` / `sendAction` callables. (c) Keep command serialisation (`buildJoinMessage`, `buildActionCommand`) as pure functions in a `messages` or `protocol` module. This makes each piece independently testable and limits blast radius when any one layer changes.

### HIGH — Implicit join handshake via pendingNameRef is invisible to the state machine

**File:** frontend/src/hooks/useWebSocket.ts:90
**Issue:** When `join(name)` is called before the WebSocket is open, the name is stored in `pendingNameRef`. Later, inside the `onmessage` callback, if the server sends `role: observer`, the hook reads `pendingNameRef` and fires the join message automatically. This side-effect-based handshake is not reflected anywhere in `WsState`, so the reducer cannot express "waiting to join" as a distinct phase, and consumers (e.g. `JoinScreen`) cannot distinguish "connected but not yet joined" from "connected and join message sent". The interaction between `join()`, `pendingNameRef`, and the `onmessage` branch is only discoverable by reading all three together.
**Recommendation:** Add a `phase: 'connecting' | 'pending_join' | 'joining' | 'registered'` field to `WsState`. Drive it from the reducer on `CONNECTED`, `CONTROL` (observer), and `CONTROL` (registered). Remove `pendingNameRef` and instead dispatch a `JOIN_REQUESTED` action from `join()` that stores the name in the reducer; the `onmessage` handler reads `state.pendingName` from a ref that mirrors reducer state, or the effect re-sends on the next `CONNECTED` event. This makes every transition observable and testable.

### MEDIUM — ERROR_LABELS constant is duplicated across App.tsx and NarrativePanel.tsx

**File:** frontend/src/App.tsx:10 and frontend/src/components/NarrativePanel.tsx:3
**Issue:** The same `ERROR_LABELS: Record<string, string>` object is defined identically in both files. When a new error code is added on the server, it must be added in two places and there is no compile-time enforcement that they stay in sync.
**Recommendation:** Move `ERROR_LABELS` (and the helper that resolves an `ErrorMessage` to a display string) to `src/utils/errorLabels.ts` or into `src/types/messages.ts` as an exported constant. Both `App.tsx` and `NarrativePanel.tsx` import from there.

### MEDIUM — LocationPanel parses a server-encoded string in the view layer

**File:** frontend/src/components/LocationPanel.tsx:8
**Issue:** The `locKey` function splits the `decker.location` string by scanning for prefixes `'RTG: '`, `'LTG: '`, etc. The component is tightly coupled to the exact string format the server uses to encode the location. If the server ever changes the separator or capitalisation, the visual breakdown silently breaks without any TypeScript error. Additionally, `LocationPanel` performs a linear search over `visibleObjects` to find the matching location node (line 75-81), which is data-retrieval logic embedded inside a render function.
**Recommendation:** Promote the location to a structured type in `messages.ts` (`location: { prefix: string; name: string }` or a discriminated union), and let the server send it pre-structured. If the server cannot be changed, move `locKey` and the `visibleObjects` lookup into a selector function in `useWebSocket` or a dedicated `selectors.ts` so that `LocationPanel` receives an already-resolved `locationNode: MatrixObjectDto | null` prop.

### MEDIUM — ActionsPanel conflates operation classification, param building, form state, and rendering

**File:** frontend/src/components/ActionsPanel.tsx:26
**Issue:** `ActionsPanel` owns four distinct concerns in one 168-line component: (1) hardcoded operation-name predicates (`needsPrecision`, `needsPasscode`, `needsScanner`, `needsEdit`) that classify server-sent strings; (2) `buildParams`, which maps form state to `ActionParams` DTOs; (3) per-action form state (`cardStates` + `patchState`); and (4) the full render tree including inline controls. The operation names (`'LOCATE_FILE'`, `'MAKE_COMCALL'`, etc.) are string literals here, creating a hidden compile-time-invisible coupling to server-side operation naming that is not surfaced in `messages.ts`.
**Recommendation:** (a) Add the set of parameter-bearing operation names as a typed constant or enum to `messages.ts` or a new `src/domain/operations.ts`, co-located with the server contract. (b) Extract `buildParams` and the predicate functions to the same domain module so they are testable independently. (c) Consider extracting an `ActionCard` component that takes one `AvailableActionDto` and manages its own `CardState`, removing the `Record<number, CardState>` map from the parent and reducing `ActionsPanel` to a list container.

### MEDIUM — StateMessage carries role (session context) alongside game state

**File:** frontend/src/types/messages.ts:76
**Issue:** `StateMessage` has a `role` field alongside `decker`, `visibleObjects`, and `availableActions`. Role is session metadata already managed through `ControlMessage`; embedding it in every state snapshot means the client must reconcile role from two message types. In `useWebSocket`, both `case 'CONTROL'` and `case 'STATE'` update `state.role`, which means a stale `state` message could silently overwrite a role transition that arrived via a more recent `control` message if messages are reordered.
**Recommendation:** Remove `role` from `StateMessage` if the server can be adjusted, or document clearly that `control` messages are authoritative and the `role` in `StateMessage` is informational-only, and update the reducer so that `STATE` never overwrites `role` (i.e. drop `role: action.msg.role` from the `STATE` case).

### LOW — React.ReactNode used without importing React in EntitiesPanel.tsx

**File:** frontend/src/components/EntitiesPanel.tsx:15
**Issue:** The `EF` component's prop type references `React.ReactNode` but `React` is not imported — only `useState` is imported from `'react'`. With the modern JSX transform (`react-jsx`) the React namespace is not available implicitly. This is a TypeScript compilation error in a strict setup, or will break silently if tsconfig is relaxed.
**Recommendation:** Change the type annotation to `import type { ReactNode } from 'react'` and use `ReactNode` directly, consistent with the pattern already used in `LocationPanel.tsx` (line 1).

### LOW — Layout properties defined in both CSS and inline style props

**File:** frontend/src/components/LocationPanel.tsx:86 and frontend/src/App.css:265
**Issue:** `LocationPanel` sets `flexDirection: 'row'`, `flexWrap: 'wrap'`, `gap: '14px 24px'`, and `alignItems: 'flex-start'` via an inline `style` prop on the `panel-body` div. The same declarations are repeated in `App.css` under `.location-panel .panel-body`. The inline style takes precedence, making the CSS block dead code. A similar issue exists in `DeckerPanel.tsx` line 38 with a hardcoded `style={{ fontSize: 24, ... }}`.
**Recommendation:** Remove the inline `style` props and let the scoped CSS classes own all layout. Use BEM modifier classes (e.g. `panel-body--row`) for layout variants that apply to multiple panels, keeping a single authoritative source for each style declaration.

### LOW — JoinScreen component is defined inside App.tsx

**File:** frontend/src/App.tsx:17
**Issue:** `JoinScreen` is a standalone component with its own state and props interface. It is co-located in `App.tsx` purely by convention rather than by coupling. It is non-trivial (handles connection status, error display, name input, and keyboard submission) and would benefit from its own file for discoverability and independent testing.
**Recommendation:** Move `JoinScreen` to `src/components/JoinScreen.tsx`. The `ERROR_LABELS` fix (see finding above) naturally accompanies this refactor since `JoinScreen` would no longer share scope with the duplicate constant in `App.tsx`.

### LOW — EntityCard uses prop presence to compute a CSS class name

**File:** frontend/src/components/EntitiesPanel.tsx:33
**Issue:** `EntityCard` sets `clickable` in the class string based on `!!onClick`. This conflates prop-driven behaviour with presentation: the component infers a visual state from whether a callback was passed rather than receiving an explicit `isClickable` or `variant` prop. It also means the `focused` card (which receives no `onClick`) silently loses the cursor style without an explicit contract.
**Recommendation:** Either accept an explicit `variant: 'focused' | 'compact'` prop and derive the cursor style entirely from it, or keep the current approach and document the prop convention. Either way, remove the `clickable` class and let the `compact` class (already applied) drive the cursor, since all compact cards are clickable.

### INFO — ActionParams is a single flat interface for all operation-specific parameters

**File:** frontend/src/types/messages.ts:8
**Issue:** All possible operation parameters live in one optional-field bag. There is no type-level enforcement that, for example, `EDIT_FILE` always sends `newContent` or that `LOCATE_FILE` never sends `scannerDeviceRating`. `buildParams` in `ActionsPanel` is correct at runtime but TypeScript cannot verify it.
**Recommendation:** If type safety is a priority, replace `ActionParams` with a discriminated union keyed on operation name, mirroring the pattern used for `AvailableActionDto`. This is a bigger refactor and may not be worth it for the current scope, but note it as a future hardening opportunity.

### INFO — Event log cap is implemented with a magic number in the reducer

**File:** frontend/src/hooks/useWebSocket.ts:48
**Issue:** `events.slice(-19)` (combined with the pushed item = 20 total) is the event log size limit, but `19` is a raw literal with no named constant.
**Recommendation:** Extract `const MAX_EVENTS = 20` near the reducer and use `slice(-(MAX_EVENTS - 1))`.

## Clean Areas

- `messages.ts` cleanly separates client-to-server and server-to-client contracts with discriminated unions and no circular imports. The type for `ServerMessage` as a union of all inbound variants is easy to extend.
- `App.tsx` orchestration is thin: it reads from `useWebSocket` and routes to either `JoinScreen` or the game grid with no logic of its own beyond the `isRegistered` guard.
- `useWebSocket`'s reducer is pure and easy to audit; all state transitions are in one `switch` block with no mutations.
- `DeckerPanel.tsx` is a well-scoped pure display component. The `DamageMonitor` sub-component is a clean extraction with a single responsibility.
- `NarrativePanel.tsx` is a pure display component with no state of its own.
- `vite.config.ts` is minimal and correct; the dev-proxy configuration cleanly abstracts the backend address without leaking it into application code.
- The CSS uses CSS custom properties throughout for the colour palette, making global theme changes a one-line edit in `:root`.
- The grid layout in `App.css` uses named `grid-template-areas`, keeping the spatial layout readable without pixel arithmetic.
---
