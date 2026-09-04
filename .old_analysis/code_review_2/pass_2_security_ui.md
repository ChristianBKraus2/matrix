# Security Review — ui

## Summary

The UI layer has no `dangerouslySetInnerHTML` usage and renders all server-supplied strings through React's JSX text nodes, which means classic reflected XSS is not possible. However, the WebSocket message handler performs no runtime schema validation — all incoming JSON is blindly cast to TypeScript types that are never enforced at runtime — and several fields from the server flow directly into safety-sensitive code paths: a server-controlled integer drives an unbounded `Array.from` that can trivially freeze the browser tab, server-sent strings fall through lookup tables and are displayed raw, and server-provided enum values are interpolated directly into CSS `className` strings without validation. The application also has no client-side guards on outbound numeric or text payloads before they are sent back to the server. The overall threat surface is constrained by the private/single-operator nature of the game server, but the absence of any defensive layer means a compromised or malicious server can cause denial-of-service and UI spoofing with no client-side resistance.

---

## Findings

### [HIGH] No runtime validation of WebSocket messages — all data trusted as typed

**File:** `frontend/src/hooks/useWebSocket.ts:89`

**Issue:** Incoming WebSocket frames are parsed and immediately cast with `JSON.parse(ev.data as string) as ServerMessage`. TypeScript union types and interfaces are compile-time constructs only; they impose zero runtime constraints. Any field on any message — including `role`, `physicalMaxBoxes`, `mentalMaxBoxes`, `alertStatus`, `securityCode`, `actionType`, and `details` — can carry an arbitrary value sent by the server (or injected by a network intermediary) and will flow unchecked into rendering and logic code. Every other finding below is a downstream consequence of this root cause.

**Recommendation:** Add a lightweight runtime validation step (a hand-rolled type guard, or a library such as Zod) that checks the `type` discriminant and validates critical numeric fields (e.g. maxBoxes ≤ 50) and enum fields before the message enters the reducer. Reject or sanitise malformed frames rather than silently ignoring only fully-unparseable ones.

---

### [HIGH] Denial of service via unbounded `maxBoxes` array construction

**File:** `frontend/src/components/DeckerPanel.tsx:20`

**Issue:** `Array.from({ length: maxBoxes }, ...)` creates a `<span>` element for every damage box. Both `physicalMaxBoxes` and `mentalMaxBoxes` arrive from the server with no upper bound check. A server (or MitM) sending `"physicalMaxBoxes": 5000000` causes React to attempt rendering five million DOM nodes, hanging or crashing the browser tab. This is a trivially easy denial-of-service requiring only a single crafted WebSocket frame.

**Recommendation:** Clamp the value before use:
```ts
const safeMaxBoxes = Math.min(maxBoxes, 50)
Array.from({ length: safeMaxBoxes }, ...)
```
Apply the same guard to `mentalMaxBoxes`. A hard cap of 50 (or whatever the game's design ceiling is) has no impact on normal gameplay.

---

### [MEDIUM] Server-controlled `details` string rendered directly in the narrative

**File:** `frontend/src/components/NarrativePanel.tsx:41`

**Issue:** `ev.msg.details` is a free-form `string` field on `ResultMessage` that originates entirely from the server. It is rendered as React text content (no script injection), but the server has full control over what narrative text the player reads. A compromised server can display fabricated game outcomes ("You succeeded — file downloaded") to deceive the player. The field has no maximum length restriction either, so an extremely long `details` string could cause layout breakage.

**Recommendation:** Truncate the string to a reasonable display length (e.g. 300 characters) before rendering. Document the trust model: since this is an operator-controlled server, content spoofing is an accepted risk — but the truncation guard is cheap and removes the layout attack.

---

### [MEDIUM] Raw server-sent error code shown when fallback lookup misses

**File:** `frontend/src/components/NarrativePanel.tsx:48` and `frontend/src/App.tsx:35`

**Issue:** Both sites use the pattern `ERROR_LABELS[ev.msg.message] ?? ev.msg.message`. The `message` field is typed as the `ErrorCode` union, but at runtime the server can send any string. If the code is absent from the lookup table the raw server string is displayed verbatim. The two tables are also inconsistent: `NarrativePanel` has four entries while `App.tsx` has seven, so codes like `name_too_long` and `bad_request` reach the fallback in the narrative context and are shown as raw snake_case to the player. A malicious server could display arbitrary text via this path.

**Recommendation:** Unify the error label tables into a shared constant (e.g. in `messages.ts` or a separate `errorLabels.ts`) and replace the fallback with a static sentinel string such as `'Unknown error'` rather than the raw server value:
```ts
ERROR_LABELS[ev.msg.message] ?? 'Unknown error'
```

---

### [LOW] CSS class name injection via unvalidated server enum values

**File:** `frontend/src/components/LocationPanel.tsx:31`, `:61` and `frontend/src/components/ActionsPanel.tsx:91`

**Issue:** Server-supplied enum fields are interpolated directly into `className` strings:
- `` `alert-${obj.alertStatus}` `` (LocationPanel line 31)
- `` `sec-${obj.securityCode}` `` (LocationPanel line 61)
- `` `action-type ${action.actionType}` `` (ActionsPanel line 91)

TypeScript types these as narrow string literals, but there is no runtime guard. A server sending `"alertStatus": "NO_ALERT injected-class"` would add `injected-class` to the element's class list. Class injection cannot execute JavaScript in React, but it can trigger unintended CSS rules (including animations and visibility changes), making it a visual spoofing vector.

**Recommendation:** Validate enum values against a whitelist before use, or map them through a lookup object that returns a safe default for unknown values:
```ts
const ALERT_CLASS: Record<AlertStatus, string> = {
  NO_ALERT: 'alert-NO_ALERT',
  PASSIVE_ALERT: 'alert-PASSIVE_ALERT',
  ACTIVE_ALERT: 'alert-ACTIVE_ALERT',
}
// usage: cls={ALERT_CLASS[obj.alertStatus] ?? 'alert-UNKNOWN'}
```

---

### [LOW] `scannerDeviceRating` stepper has no upper bound

**File:** `frontend/src/components/ActionsPanel.tsx:136–143`

**Issue:** The stepper for `TAP_COMCALL` clamps only at zero on the way down (`Math.max(0, rating - 1)`) but has no ceiling. A user can increment to arbitrarily large values, which are serialised and sent to the server as `scannerDeviceRating`. If server-side validation is absent or weak, a very large integer could trigger unexpected behaviour in game logic.

**Recommendation:** Add an upper bound consistent with the game's design maximum (e.g. device rating 12 in Shadowrun 2nd Ed):
```ts
onClick={() => patchState(action.index, { scannerDeviceRating: Math.min(12, cs.scannerDeviceRating + 1) })}
```

---

### [LOW] `newContent` textarea has no client-side length limit

**File:** `frontend/src/components/ActionsPanel.tsx:150–155`

**Issue:** The EDIT_FILE textarea accepts unbounded input. There is no `maxLength` attribute on the `<textarea>`. The full content is sent to the server as `newContent` in the action command. This relies entirely on server-side enforcement; a client-side limit provides defence-in-depth and improves UX (users get immediate feedback).

**Recommendation:** Add a `maxLength` consistent with whatever the server enforces for file content size. If no limit is defined server-side, define one now (e.g. 10,000 characters) and enforce it in both places.

---

### [LOW] Reconnect banner driven entirely by a server-sent flag

**File:** `frontend/src/hooks/useWebSocket.ts:43`, `frontend/src/App.tsx:103`

**Issue:** The "SESSION RESTORED — reconnected to active game" banner is shown when the server sends `reconnect: true` in a control message. There is no client-side check (e.g. verifying a prior connection existed). A malicious server, or a frame injected into a fresh connection, could display this banner to a player who has never previously connected, creating misleading UI.

**Recommendation:** Track whether the client has previously been in a `registered_decker` or `active_controller` role in the current browser session (e.g. with a local ref flag set on first registration). Only honour `reconnect: true` if that flag is set, so the banner cannot be shown on a genuinely fresh connection.

---

## No Issues Found In

- **`dangerouslySetInnerHTML`** — not used anywhere in the codebase.
- **Script/eval injection** — no `eval()`, `Function()`, `innerHTML`, or `insertAdjacentHTML` usage.
- **Credential or token exposure** — no auth tokens, session cookies, or passwords are stored in React state or `localStorage`/`sessionStorage`.
- **WebSocket URL construction** — `useWebSocket.ts:78–79` derives protocol and host from `window.location`, which is the correct same-origin pattern and avoids hardcoded origins.
- **Decker name input** — `App.tsx:62` enforces `maxLength={32}` at the DOM level, consistent with the server's stated limit.
- **Outbound message construction** — `join`, `sendAction`, and the action command builder only serialise well-typed objects; no raw string concatenation into JSON.
- **Reconnect storm** — exponential backoff (3 s → 30 s cap) prevents the client from hammering a recovering server.
- **Third-party script loading** — no external scripts, CDN resources, or dynamic `import()` from untrusted origins visible in source.
