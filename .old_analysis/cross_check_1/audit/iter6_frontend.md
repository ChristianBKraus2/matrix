# Iteration 6 — Frontend (React/TypeScript) Conformance Audit

Audited `frontend/src/**/*.{ts,tsx}` against `design/audit/iter2_ui.md` (authoritative UI
distill) and `design/audit/spec_baseline.md` §Wire protocol. Backend DTOs confirmed conformant
in `iter5_dto.md` (sealed unions discriminated by `kind`; `ActionParams` carries `dataSize`;
`ResultMessage` successes non-null) — used here to resolve iter2_ui DOC-1/2/3/4/6 as
frontend-bug vs doc-stale.

Glob confirmed the assigned set is complete: 9 source files (excluding node_modules, tests,
config). All read in full from line 1.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `frontend/src/types/messages.ts` | 133 | (L16) `dataSize?: number` — (L67) `export type MatrixObjectDto =` … `| { kind: 'GridNode'; index: number;` — (L130) `export type GameEvent =` `| { kind: 'result'; msg: ResultMessage }` | `kind` used correctly for `MatrixObjectDto`/`AvailableActionDto`/`GameEvent`; `type` for message envelope. DOC-1, DOC-2, DOC-4 all resolved doc-stale (see Findings). D6F-1 (inactivitySeconds orphan). |
| `frontend/src/hooks/useWebSocket.ts` | 191 | (L15) `interface WsState { connected: boolean; role: Role \| null; deckerName: string \| null; gameState: StateMessage \| null; events: GameEvent[] }` — (L99) `if (msg.reconnectToken) reconnectTokenRef.current = msg.reconnectToken` — (L145) `reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30000)` | exports: `useWebSocket`, `connect`, `join`, `sendAction`, reducer. Lifecycle observer→join→registered→state OK; token store/resend/clear OK; 3s→30s backoff OK; active_controller gating OK. D6F-3 (guard mechanism differs from design). |
| `frontend/src/main.tsx` | 11 | (L6) `ReactDOM.createRoot(document.getElementById('root')!).render(` | Infra bootstrap only. No discrepancies found. |
| `frontend/src/App.tsx` | 116 | (L10) `const ERROR_LABELS: Record<ErrorCode, string> = {` — (L82) `const isRegistered = ws.role === 'registered_decker' \|\| ws.role === 'active_controller'` — (L103) `<div className="game-grid">` … `<LocationPanel gameState={gameState} />` | exports: `App`, `JoinScreen`. ERROR_LABELS map matches iter2 exactly; panel wiring matches layout inventory. No discrepancies found. |
| `frontend/src/components/ActionsPanel.tsx` | 217 | (L34) `return { precision: 'NORMAL', query: '', scannerDeviceRating: 0, newContent: '', hasValidPasscode: false, dataSize: 100 }` — (L108) `{paramKind === 'precision' && (` … `<div className="ctrl-label">SEARCH TERM</div>` — (L194) `{paramKind === 'dataSize' && (` … `<div className="ctrl-label">DATA SIZE (Mp)</div>` | exports: `ActionsPanel`, `actionLabel`, `formatEnum`, `defaultCardState`, `buildParams`. All 5 paramKind controls present incl. dataSize stepper (DOC-1 doc-stale); precision sends `{precision,query}` (DOC-2 doc-stale); scanner 0–10; role gating on click + confirm. No real divergence. |
| `frontend/src/components/DeckerPanel.tsx` | 76 | (L42) `<div className="pinned-alert">⚠ PINNED BY BLACK IC</div>` | exports: `DeckerPanel`, `DamageMonitor`. PHYS/MENT monitors, `{hackingPool}d`, `MCP RATING`, program rating bar all match L325–342. No discrepancies found. |
| `frontend/src/components/EntitiesPanel.tsx` | 106 | (L9) `const ENTITY_KINDS: EntityKind[] = ['HostSubsystem', 'IcProgram', 'File', 'Device']` — (L46) `{obj.analyzed && <EF label="RATING" value={obj.rating} />}` | exports: `EntitiesPanel`, `EntityCard`, `EF`, `isEntity`. Location kinds excluded; analyzed-gating on rating/behavior/guardedNodeType correct; focus/compact/empty rules match L363–375. No discrepancies found. |
| `frontend/src/components/LocationPanel.tsx` | 109 | (L9) `const prefixes = ['RTG: ', 'LTG: ', 'PLTG: ', 'Host: ']` — (L79) `decker.locationIndex != null ? (visibleObjects[decker.locationIndex] as MatrixObjectDto \| undefined) ?? null : visibleObjects.find(` | exports: `LocationPanel`, `LocationFields`, `locKey`, `Field`. Panel now PREFERS `locationIndex` over name-match (DOC-6 inverted). D6F-2: backend `locationIndex` is a permanent stub `=0`, so name-match fallback is dead code and `visibleObjects[0]` is trusted unconditionally. |
| `frontend/src/components/NarrativePanel.tsx` | 64 | (L45) `[{ev.msg.deckerSuccesses}d vs {ev.msg.hostSuccesses}h]` | exports: `NarrativePanel`. Newest-at-bottom autoscroll; `[✓ SUCCESS]`/`[✗ FAILURE]` + dice + details; error branch; `active-turn` border pulse. successes rendered directly (non-null, iter5). No discrepancies found. |

Total: 9 files, 1023 lines.

---

## Findings

### D6F-1 — `inactivitySeconds` is an orphaned `ActionParams` field with no paramKind and no control (confirms iter2_ui DOC-3)

**File:** `frontend/src/types/messages.ts:13` — `inactivitySeconds?: number`.

**Verbatim:** the `Operation.paramKind` union (`messages.ts:94`) is
`'precision' | 'hasValidPasscode' | 'scannerDeviceRating' | 'newContent' | 'dataSize' | null`
— no `inactivitySeconds`. `ActionsPanel.buildParams` (`ActionsPanel.tsx:37-43`) has no branch for
it and `CardState`/`defaultCardState` (`ActionsPanel.tsx:24-35`) has no field for it.

**Protocol clause:** `spec_baseline.md` L68 — `NULL_OPERATION {inactivitySeconds=0}`.

**Impact:** Low. The field can never be populated by the UI; `NULL_OPERATION` cards carry
`paramKind=null` and send no params, and the server defaults `inactivitySeconds=0`, so behaviour
is correct by default. The field is dead in the frontend. Documentation-only.

### D6F-2 — LocationPanel trusts the stubbed `locationIndex` (always `0`); the name-match fallback is dead code (inverts iter2_ui DOC-6)

**File:** `frontend/src/components/LocationPanel.tsx:77-85`.

**Verbatim (L79-85):**
```
: decker.locationIndex != null
  ? (visibleObjects[decker.locationIndex] as MatrixObjectDto | undefined) ?? null
  : visibleObjects.find(
      (o) =>
        (o.kind === 'GridNode' || o.kind === 'LocalGrid' || o.kind === 'PrivateGrid' || o.kind === 'HostNode') &&
        o.name === name
    ) ?? null
```

**Code (backend):** `src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt:28` —
`locationIndex = if (currentLocation != null) 0 else null`. The value is a permanent stub: it is
`0` whenever the decker is jacked in, `null` otherwise.

**Impact:** Real divergence. Because `locationIndex` is never `null` while jacked in, the panel
always resolves the location object as `visibleObjects[0]` and the `.name`-based fallback branch
is unreachable dead code. LocationPanel therefore renders the location strip for whatever object
happens to sit at index 0 of `visibleObjects`, regardless of the decker's actual location. This
is only correct if the server guarantees the current location node is always element 0 of
`visibleObjects` — an ordering contract that is not stated in the protocol. The panel gave up the
name-safety check (which matched `decker.location`) in exchange for a stub value.

**iter2_ui verdict:** DOC-6 predicted the panel would *prefer brittle name-parsing*; the actual
code inverts this — it prefers the stub index and treats name-parsing as fallback. DOC-6 is
doc-stale as written, but the underlying stub concern is now a live frontend risk. Either the
backend must populate a real `locationIndex`, or the frontend should prefer the name-match path
while `locationIndex` remains a stub.

### D6F-3 — Reconnect suppression uses a flag rather than the design-prescribed handler-nulling (doc-stale / quality)

**File:** `frontend/src/hooks/useWebSocket.ts:118-121, 140-150, 155-159`.

**Verbatim (L119-120):** `suppressReconnectRef.current = true` … `ws.close()`; and
onclose guard (L141-143) `if (!isMountedRef.current) return` / `if (suppressReconnectRef.current) return`.

**Design:** iter2_ui L311-313 guard (2): "before manual `ws.close()` set `ws.onclose=null` and
`ws.onerror=null`."

**Impact:** None functional. The hook achieves the same intent (no auto-reconnect after
deliberate logout/unmount) via `suppressReconnectRef` + `isMountedRef` guards checked inside
`onclose`, instead of detaching the handlers. Guard (1) ("only reconnect while CONNECTING/OPEN")
is satisfied by the `isMountedRef`/`connect()` readyState check (L82-83). Documentation-only —
update design_ui to describe the flag-based mechanism, or note both are acceptable.

---

## Resolutions of prior candidate findings (frontend confirmed conformant → doc-stale)

- **DOC-1 (`dataSize` has no carrier field)** — RESOLVED, doc-stale. `messages.ts:16` declares
  `dataSize?: number`; `ActionsPanel.tsx:194-209` renders the Mp stepper (default 100, step 10,
  floor 1) and `buildParams` (L42) emits `{ dataSize }`. Backend `ActionParams` carries `dataSize`
  (iter5_dto). Full path present.
- **DOC-2 (SEARCH TERM / `query` vs `precision` routing)** — RESOLVED, doc-stale. For any
  `paramKind==='precision'` card, `buildParams` (`ActionsPanel.tsx:38`) sends BOTH
  `{ precision, query }`, so all three LOCATE ops carry the search term. Matches
  `spec_baseline.md` L68 `LOCATE_* {precision, query}`.
- **DOC-4 (`GameEvent` never defined)** — RESOLVED, doc-stale. `messages.ts:130-133` defines
  `GameEvent = { kind:'result'; msg:ResultMessage } | { kind:'error'; msg:ErrorMessage }`, consumed
  by `useWebSocket` `WsState.events` and `NarrativePanel`.
- **DOC-6** — see D6F-2 (partially inverted; residual stub risk is now a live frontend finding).

## Zero-finding files (Rule 5)
`main.tsx`, `App.tsx`, `DeckerPanel.tsx`, `EntitiesPanel.tsx`, `NarrativePanel.tsx`,
`ActionsPanel.tsx` — no discrepancies found (ActionsPanel resolves DOC-1/DOC-2 as conformant).

## Note (non-finding)
`DeckerStateDto` field order in `messages.ts` (locationIndex at position 3) differs from the
backend declaration order (locationIndex position 2, after `location`), and from iter2_ui's listing
(locationIndex last). JSON deserialisation is order-independent, so no functional impact.
