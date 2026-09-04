# Frontend & Cross-Layer Findings

All 9 frontend files reviewed: hook `useWebSocket.ts` + contract `messages.ts` (author),
`main.tsx`, `App.tsx`, `DeckerPanel.tsx`, `LocationPanel.tsx`, `ActionsPanel.tsx`,
`EntitiesPanel.tsx`, `NarrativePanel.tsx` (subagent). `tsc --noEmit` clean; no
`dangerouslySetInnerHTML` anywhere — **no XSS exposure** (all server strings rendered through
React's auto-escaping text interpolation).

---

## Cross-layer

### 🟠 X-1 (MEDIUM) — Stringly-typed `"not jacked in"` sentinel across layers

> ✅ **RESOLVED (Step 4, 2026-09-04).** `DeckerStateDto` now carries a typed `jackedIn: Boolean`
> (server sets `jackedIn = currentLocation != null`). All three client dependencies on the magic
> string were switched to `decker.jackedIn` (`useWebSocket.ts` jack-out/reconnect detection and
> `LocationPanel.tsx` ×2). The `location` string remains only as a display label (name/prefix) and
> is no longer load-bearing for logic; the split-source *display* coupling is X-2, still gated on
> the deferred real `locationIndex`.

**Category:** Cross-layer contract / Maintainability
**Where:** [DeckerStateDto.kt:27](../../src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt#L27),
[useWebSocket.ts:116](../../frontend/src/hooks/useWebSocket.ts#L116),
[LocationPanel.tsx:77,91](../../frontend/src/components/LocationPanel.tsx#L77)

Server emits `location = currentLocation?.label() ?: "not jacked in"`. The client depends on that exact
string in **three** places: jack-out detection / reconnect suppression (`useWebSocket.ts:116`) and the
"NOT JACKED IN" rendering (`LocationPanel.tsx:77,91`). No shared constant. A server-side wording change
silently breaks reconnect handling *and* the location panel at once.

Compounding it: `label()` also builds the location *display* string (`"Host: ${host.name}"`, `"RTG: …"`)
which `LocationPanel` prefix-parses (see X-2) — a display string doubling as a protocol value.

**Fix:** Represent jack-in state as a typed field (`jackedIn: Boolean`, or a nullable structured
`location`) on `DeckerStateDto` instead of a magic display string.

### 🟠 X-2 (MEDIUM) — LocationPanel renders name and stat-fields from two different sources

> 🟡 **DEFERRED (reviewed Step 4, 2026-09-04).** The split-source display persists because its fix
> depends on the backend populating a real `locationIndex` (deferred.md #4) instead of the hardcoded
> `0` stub. The jacked-in *gate* is now typed (`decker.jackedIn`, see X-1), but name/prefix is still
> parsed from `decker.location` while fields come from `visibleObjects[locationIndex]`. Correctly
> deferred with the `locationIndex` work; no code change in Step 4.

**Category:** Correctness (deferred-coupled) / Cross-layer
**Where:** [LocationPanel.tsx:75,79-85,99-103](../../frontend/src/components/LocationPanel.tsx#L79-L85),
[DeckerStateDto.kt:28](../../src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt#L28), [deferred.md #4](../../design/deferred.md)

The panel derives the displayed **name/prefix** by string-parsing `decker.location` (`locKey`,
`'RTG: '`/`'LTG: '`/`'PLTG: '`/`'Host: '`), but derives the **SEC/ALERT/TALLY fields** from
`visibleObjects[decker.locationIndex]` — and `locationIndex` is the hardcoded `0` stub (deferred #4).
So the panel shows the *name* of the decker's real location but the *stats* of whatever object sits at
index 0; these need not be the same object. The name-prefix fallback path is currently unreachable while
jacked in — **consistent with deferred.md #4, so not itself a bug** (currency verified). The reportable
issue is the split-source display + brittle format coupling (a `location` format change empties `prefix`
and dumps the raw string as the name).

**Fix:** When the backend populates a real `locationIndex`, drive both name and fields from
`locationObj.name` and delete the `locKey` string parsing.

### 🔵 X-3 (INFO) — Hand-maintained enum parity (currently in sync)

Five enums serialized as raw `Enum.name` and mirrored as TS unions
([MatrixObjectDto.kt:9-13](../../src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt#L9-L13) ↔
[messages.ts:55-65](../../frontend/src/types/messages.ts#L55-L65)): SecurityCode, AlertStatus,
SubsystemType, TopologyType, IcBehavior — **all verified in sync**. Manual coupling (no codegen); a
future enum edit missing one side ships a silent mismatch. The `@SerialName("kind")` discriminated-union
DTOs match the TS `kind`-tagged unions field-for-field.

### 🔵 X-4 (INFO) — `paramKind` → `ActionParams` mapping is correct

Verified in [ActionsPanel.tsx:37-44](../../frontend/src/components/ActionsPanel.tsx#L37-L44): all six
`paramKind` values map to the matching `ActionParams` field with no shape mismatch
(`precision`→`{precision, query}`, `newContent`→`{newContent|null}`, `scannerDeviceRating`,
`hasValidPasscode`, `dataSize`). No defect — recorded as a positive cross-layer contract result.

---

## Frontend

### 🟠 F-1 (MEDIUM) — In-progress action input wiped by every state broadcast

> ✅ **RESOLVED (Step 4, 2026-09-04).** The reset effect no longer depends on the `actions` array
> reference. It now depends on a derived `actionsSignature` (per-index `index:operation`/`kind`),
> so identical re-broadcasts while the controller is typing no longer fire the reset; only a
> genuine change to the available-action set clears card/focus state.

**Category:** Correctness (UI data loss)
**Where:** [ActionsPanel.tsx:52-55](../../frontend/src/components/ActionsPanel.tsx#L52-L55)

```ts
useEffect(() => { setCardStates({}); setFocusedCards(new Set()) }, [actions])
```

`gameState.availableActions` is a fresh array from `JSON.parse` on **every** `STATE` message
([useWebSocket.ts:47](../../frontend/src/hooks/useWebSocket.ts#L47)), so this effect fires on reference
change, not semantic change. Any state re-broadcast arriving while the active controller is typing a
search term, editing file content, or setting a stepper value **silently clears the entered values** and
collapses the open `newContent` editor. Guideline §8 anti-pattern (state derived from other state via
effect).

**Fix:** Key card state by stable action identity (`action.index`+`operation`) and reconcile, or gate the
reset on a content comparison of the action list.

### 🟡 F-2 (LOW) — EntitiesPanel focus tracked by positional index into a re-derived array

> ✅ **RESOLVED (Step 4, 2026-09-04).** Focus is now stored as the entity's stable DTO `index`
> (`focusedIndex`), resolved each render via `entities.find(e => e.index === focusedIndex)` with a
> first-entity fallback. Focus follows the same entity across broadcasts instead of whatever now
> occupies the old slot.

**Where:** [EntitiesPanel.tsx:79-80,90,98](../../frontend/src/components/EntitiesPanel.tsx#L79-L80)

`focusIdx` is a position into `visibleObjects.filter(isEntity)`, re-derived each broadcast. When the
visible set changes, focus silently jumps to whatever entity now occupies that slot. Store the entity's
stable DTO `index` instead.

### 🟡 F-3 (LOW) — List `key` props use array index instead of stable identity

> ✅ **RESOLVED (Step 4, 2026-09-04).** DeckerPanel keys loaded programs by `u.type`; the event log
> keys by a monotonic `id` assigned in the reducer (`eventSeq` counter on `RESULT`/`ERROR`), so
> appends no longer shift keys. (Fixed-length damage boxes and value-keyed precision buttons were
> already fine and left as-is.)

**Where:** [DeckerPanel.tsx:62](../../frontend/src/components/DeckerPanel.tsx#L62),
[NarrativePanel.tsx:40,52](../../frontend/src/components/NarrativePanel.tsx#L40)

`activeUtilities.map((u,i)=>…key={i})` and the event log (a sliding window, `events.slice(-19)`) keyed by
index — every append shifts indices and defeats reconciliation. Key utilities by `u.type`; give events a
monotonic id in the reducer. (Fixed-length damage boxes and value-keyed precision buttons are fine.)

### 🟡 F-4 (LOW) — Clickable `<div>`s lack button semantics / keyboard access

> ✅ **RESOLVED (2026-09-04).** Both clickable `<div>`s now carry button affordances. Action cards
> ([ActionsPanel.tsx](../../frontend/src/components/ActionsPanel.tsx)) set `role="button"`,
> `tabIndex={disabled ? -1 : 0}`, `aria-disabled`, and an `onKeyDown` handler that fires the click on
> Enter/Space (with `preventDefault`). `EntityCard` ([EntitiesPanel.tsx](../../frontend/src/components/EntitiesPanel.tsx))
> conditionally applies `role="button"`/`tabIndex`/`onKeyDown` only when it has an `onClick`, so
> non-interactive cards stay out of the tab order. `npm run lint` clean; `tsc` clean.

**Where:** [ActionsPanel.tsx:95-99](../../frontend/src/components/ActionsPanel.tsx#L95-L99),
[EntitiesPanel.tsx:36](../../frontend/src/components/EntitiesPanel.tsx#L36)

Turn-submitting action cards are `<div onClick>` with no `role="button"`, `tabIndex`, or key handler —
not keyboard-reachable, invisible to assistive tech. Use `<button>` or add the ARIA affordances.

### 🔵 F-5 (INFO) — JoinScreen derives error via `useEffect` and never clears it

> ✅ **RESOLVED (2026-09-04).** The error label is now derived **inline during render** from the last
> event plus an `ackedEventCount` marker (`useState`), replacing the `useEffect`+`setError` pattern
> (guideline §8). Submitting sets `ackedEventCount = events.length`, so a stale error clears on the
> next join attempt instead of lingering. `import { useState } from 'react'` only; `tsc`/lint clean.

**Where:** [App.tsx:33-38](../../frontend/src/App.tsx#L33-L38)

Error label computed in a `useEffect`+`setError` (should be inline during render) and only overwritten,
never cleared on a subsequent non-error event. Minor (events clear on re-registration).

### 🔵 F-6 (INFO) — Server `details` is the surface for raw exception text (no XSS)

**Where:** [NarrativePanel.tsx:47](../../frontend/src/components/NarrativePanel.tsx#L47)

`{ev.msg.details}` is React-escaped (no XSS). But since server `details` can carry raw exception text
(server finding S-4), the narrative log is where that internal text reaches players. Resolved by the S-4
server fix; no UI change needed. **(S-4 resolved in Step 2, 2026-09-04 — `details` now carries only the
generic `"malformed request"`, so no internal text reaches this panel.)**

---

## UI surface of server finding S-1 (not double-counted)

The `hasValidPasscode` "VALID PASSCODE" YES/NO toggle in
[ActionsPanel.tsx:182-192](../../frontend/src/components/ActionsPanel.tsx#L182-L192) (via `buildParams`,
`:41`) is the **client-side face** of the HIGH server auth-bypass (S-1): a player clicks "YES" to assert a
passcode and the server trusts it. Counted once, as S-1. When S-1 is fixed server-side, also remove this
toggle and the `hasValidPasscode` field from `CardState`/`buildParams`; until then the control has no
legitimate use and should be suppressed.

---

## Root-cause consolidation (UI)

F-1, F-2, F-3, and X-2 share one root cause: **UI state keyed by array position / reference rather than by
the stable DTO `index` (or `type`) identity.** Fixing the identity model addresses the input-wipe, the
focus jump, the list-key churn, and part of the LocationPanel split-source problem together.
