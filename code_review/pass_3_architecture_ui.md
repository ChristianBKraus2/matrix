# Architecture Review — ui

## Summary

The frontend is a lean React/TypeScript WebSocket client with a clear panel-per-concern layout. The overall structure is sound: a single custom hook owns WebSocket state, typed message contracts live in one file, and each panel component is largely presentation-only. The main architectural weaknesses are a duplicated (and diverged) error-label map that violates DRY across two files, a component defined inline in App.tsx that should live in its own file, domain knowledge about operation parameter types baked into the UI layer with no corresponding server guidance, and a location-string parsing convention that silently couples the frontend to a backend formatting contract. None of these are critical, but together they represent recurring SRP and coupling gaps that will grow as the game expands.

## Findings

### [MEDIUM] Duplicated and diverged ERROR_LABELS map

**File:** frontend/src/App.tsx:10-18 and frontend/src/components/NarrativePanel.tsx:3-8
**Issue:** `ERROR_LABELS` is defined twice. The copy in `App.tsx` is complete (7 entries). The copy in `NarrativePanel.tsx` has only 4 entries and is missing `name_too_long`, `unknown_message_type`, and `bad_request`. Any error with those codes will render as a raw snake_case string in the Narrative panel. This is both a DRY violation and a live display bug.
**Recommendation:** Define `ERROR_LABELS` once in `frontend/src/types/messages.ts` (or a dedicated `frontend/src/utils/errorLabels.ts`) and import it wherever needed. The `ErrorCode` union type already lives in `messages.ts`, making it the natural home for its human-readable labels.

---

### [MEDIUM] JoinScreen component defined inline in App.tsx

**File:** frontend/src/App.tsx:20-77
**Issue:** `JoinScreen` is a 58-line component with its own `useState` and `useEffect` defined at the top of `App.tsx`. `App.tsx` is supposed to be the top-level routing/composition layer; embedding a full child component violates SRP and makes the file responsible for both routing logic and join-screen behaviour.
**Recommendation:** Move `JoinScreen` to `frontend/src/components/JoinScreen.tsx`. `App.tsx` should only import and compose its child panels/screens.

---

### [MEDIUM] LocationPanel parses backend-formatted location strings

**File:** frontend/src/components/LocationPanel.tsx:8-14
**Issue:** The `locKey()` function reconstructs structured data by parsing a display string with hardcoded prefixes (`"RTG: "`, `"LTG: "`, `"PLTG: "`, `"Host: "`). The frontend is reversing a formatting decision made by the backend. If the backend changes how it serialises `decker.location`, `locKey` fails silently — the prefix is not found, the whole string is treated as a bare name, and the lookup `visibleObjects.find(o => o.name === name)` returns nothing. There is no type safety over this implicit contract.
**Recommendation:** Have the backend send `location` as a structured DTO (e.g. `{ nodeKind: string, name: string }`) or at minimum provide a separate `locationNodeIndex` field that maps directly to an entry in `visibleObjects`, removing the need for string parsing entirely.

---

### [MEDIUM] ActionsPanel encodes domain knowledge about operation parameter types

**File:** frontend/src/components/ActionsPanel.tsx:26-31
**Issue:** The predicates `needsPrecision`, `needsPasscode`, `needsScanner`, and `needsEdit` are hardcoded lists of `SystemOperation` names that require particular UI controls. This is game domain logic — the knowledge of *which operations take which parameters* — living in the UI layer. If the backend adds or removes a parameterised operation, the frontend must be updated manually and in sync. There is no compile-time enforcement that this list stays current with `SystemOperation` in `messages.ts`.
**Recommendation:** Extend `AvailableActionDto` (specifically the `Operation` variant) with an optional `paramKind` discriminator field (`'precision' | 'passcode' | 'scanner' | 'edit' | null`) set by the server. The frontend then renders the appropriate control from that tag with no hardcoded operation name lists.

---

### [LOW] Monolithic App.css — no component-scoped styles

**File:** frontend/src/App.css:1-455
**Issue:** All 455 lines of CSS for the entire application are in a single file. Styles for DeckerPanel, LocationPanel, NarrativePanel, EntitiesPanel, ActionsPanel, the join screen, damage monitors, entity cards, action cards, and animations are all mixed together. There is no co-location of styles with their components, making it harder to understand what styles belong to what component and easy to cause accidental cross-component side effects.
**Recommendation:** For a project of this size, splitting into per-component CSS files (e.g. `DeckerPanel.css`) or adopting CSS Modules would make ownership clear. At minimum, the existing comment-based sections should be enforced and each component should import only its own slice.

---

### [LOW] useWebSocket conflates transport lifecycle with session and UI state

**File:** frontend/src/hooks/useWebSocket.ts:72-104
**Issue:** The hook manages four distinct concerns in one place: WebSocket transport lifecycle (connect/reconnect/close), inbound message routing (the `switch` in `onmessage`), session persistence (`reconnectTokenRef`), and pending UI state (`pendingNameRef` — the name typed by the user that has not yet been sent). `pendingNameRef` is particularly notable: it is UI-layer state (what the user typed) that has leaked into the network layer so the hook can auto-send a join message the moment the socket opens. This creates an implicit ordering dependency between the join flow and the connection sequence.
**Recommendation:** Separate the auto-send-on-open behaviour from the transport layer. `join()` could simply check `readyState` and queue differently, or the component could re-call `join()` on the `connected` state transition via a `useEffect`. Keeping `pendingNameRef` out of the hook removes one class of subtle race conditions.

---

### [LOW] React.ReactNode referenced without explicit React import

**File:** frontend/src/components/EntitiesPanel.tsx:15
**Issue:** The `EF` helper component annotates its `value` prop as `React.ReactNode` without importing React. This compiles only because the global JSX transform injects React implicitly for JSX, but the namespace `React` is not in scope for non-JSX type references. Whether this is currently an error depends on `tsconfig` settings; it is fragile.
**Recommendation:** Add `import type { ReactNode } from 'react'` and use `ReactNode` directly, as done consistently in `LocationPanel.tsx` (line 1).

---

### [INFO] ActionParams.inactivitySeconds has no corresponding UI control

**File:** frontend/src/types/messages.ts:14
**Issue:** `ActionParams` declares `inactivitySeconds?: number` but `ActionsPanel.tsx` has no control that populates it. It is either dead interface surface or a planned-but-unimplemented feature. Either way it diverges the type contract from the actual behaviour.
**Recommendation:** If the field is not yet used, remove it from `ActionParams` (and the corresponding backend DTO) until it is needed. If it is used server-side for a game mechanic with no UI control, add a comment explaining the intent.

---

## No Issues Found In

- `frontend/src/components/DeckerPanel.tsx` — clean single-responsibility presentation component; `DamageMonitor` sub-component is a well-scoped local helper
- `frontend/src/components/NarrativePanel.tsx` — presentation-only aside from the duplicate error-labels finding above; no logic leakage
- `frontend/src/types/messages.ts` — clean, type-only contract file; the comment cross-referencing Kotlin enum sources is good practice
- `frontend/src/App.tsx` (routing logic) — the `App` component itself is a thin composition layer that correctly delegates all rendering to child panels
