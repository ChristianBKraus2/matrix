# Matrix of Shadowrun — WebSocket Protocol

## Transport

Single WebSocket endpoint: `ws://<host>/decker/ws`

All messages are JSON objects. Every message has a `"type"` discriminator field.

### Origin guard (CSWSH mitigation)

The WebSocket upgrade rejects a **present-and-disallowed** `Origin` header, closing the connection
with close code `VIOLATED_POLICY`. A **missing** `Origin` is allowed (non-browser clients, tests);
browsers always send `Origin` on a WS handshake, so this is the standard cross-site-hijacking guard.
The allow-list is `http://localhost:8080` and `http://127.0.0.1:8080` — the UI is served same-origin
from `/`, so these cover the local/production case. Running the Vite dev server (a different port)
requires adding its origin to the allow-list.

This is the Origin-check portion of the client-trust hardening only; a real authentication/handshake
token on join remains deferred.

---

## Message Types

### Server → Client

| Type | When sent |
|---|---|
| `control` | Role change (connect, join, promote, demote) |
| `state` | Before each decker turn — full game snapshot |
| `result` | After each decker action completes |
| `error` | Validation failure or protocol error |

### Client → Server

| Type | When sent |
|---|---|
| `join` | After receiving `control{role:"observer"}`, to register a decker name |
| `action` | After receiving `state{role:"active_controller"}`, to submit an action |

---

## Message Schemas

### `ControlMessage` (server → client)
```json
{ "type": "control", "role": "<Role>", "deckerName": "<string|null>", "reconnectToken": "<string|null>" }
```
`deckerName` is present when `role` is `registered_decker` or `active_controller`. `reconnectToken` is non-null only when `role` is `registered_decker`; use it to reclaim the same decker slot after a disconnect. The token survives disconnect but is cleared on intentional logout (graceful logoff).

### `StateMessage` (server → client)
```json
{
  "type": "state",
  "role": "<Role>",
  "decker": { ... },
  "visibleObjects": [ ... ],
  "availableActions": [ ... ]
}
```
`role` is the receiving session's current role. Only `active_controller` sessions should submit an `action` in response.

### `ResultMessage` (server → client)
```json
{
  "type": "result",
  "success": true,
  "deckerSuccesses": 3,
  "hostSuccesses": 1,
  "details": "narrative string"
}
```
`deckerSuccesses` and `hostSuccesses` are always present (never null).

### `ErrorMessage` (server → client)
```json
{ "type": "error", "message": "<ErrorCode>", "details": "<string|null>" }
```
`details` carries dynamic context for `bad_request` and `unknown_message_type`.

### `JoinMessage` (client → server)
```json
{ "type": "join", "deckerName": "Kylie", "jackPointName": "Seattle-LTG", "reconnectToken": "<string|omit>" }
```
`jackPointName` is the name of the LTG or host the decker is physically connected to. Omitting it (empty string) is valid when no jackIn is needed (e.g. tests).

`reconnectToken` is required when rejoining after a disconnect to reclaim the same decker slot. Omit on first join. If the token is missing or wrong for a disconnected name, the server responds with `BAD_REQUEST`.

After the server sends `ControlMessage(role: "registered_decker")`, it immediately performs the jack-in System Test using the specified jackpoint and broadcasts a `ResultMessage` to all sessions. The first game turn (`StateMessage`) follows only after this jackIn result is sent.

### `ActionCommand` (client → server)
```json
{ "type": "action", "actionIndex": 2, "params": { ... } }
```
`actionIndex` is a 0-based index into the `availableActions` array from the most recent `state` message.

`params` is optional. When present, the relevant fields are:

| Operation / action | params fields |
|---|---|
| `LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE` | `query` (string search term; regex accepted; may be blank). No `precision` — the server derives query precision from the query shape. |
| `AccessLtg`, `AccessHost` | `targetName` (string — the LTG/PLTG or host name chosen from the dropdown) |
| `SelectLocateTarget` | `targetName` (string — the chosen candidate name; empty/omitted cancels the selection, invoking `cancelLocateSelection()`) |
| `EDIT_FILE` | `newContent` (string or null to erase) |
| `UPLOAD_DATA` | `dataSize` (int Mp, default 100) |
| `NULL_OPERATION` | `inactivitySeconds` (int seconds of inactivity, default 0) |

`MAKE_COMCALL` and `TAP_COMCALL` take **no** client params. `MAKE_COMCALL` simply runs a System Test.
For `TAP_COMCALL`, the target's dataline-scanner rating is derived from server-side state — never from
a client-supplied flag — so the client cannot disable scanner detection.

---

## Role State Machine

```
connect
  │
  ▼
observer ──(join)──► registered_decker ──(promoteForTurn)──► active_controller
                           ▲                                        │
                           └──────────────(demoteAfterTurn)─────────┘
                           │
                     (disconnect)
                           │
                           ▼
                       (removed)
```

Transitions:
- **connect → observer**: server sends `ControlMessage(role: "observer")` immediately on connection
- **observer → registered_decker**: client sends `JoinMessage`; server sends `ControlMessage(role: "registered_decker", deckerName: ...)`
- **registered_decker → active_controller**: game engine calls `promoteForTurn`; server sends `ControlMessage(role: "active_controller", deckerName: ...)`
- **active_controller → registered_decker**: after turn completes or times out; server sends `ControlMessage(role: "registered_decker", deckerName: ...)`

---

## Turn Lifecycle

```
Server                                    Client (active_controller)
  │                                              │
  ├── ControlMessage(active_controller) ────────►│
  ├── StateMessage(role: active_controller) ─────►│
  ├── StateMessage(role: observer) ──────────────► (other sessions)
  │                                              │
  │◄─────────────────────────── ActionCommand ───┤
  │                                              │
  ├── ResultMessage ─────────────────────────────►│ (broadcast to all)
  ├── ControlMessage(registered_decker) ──────────►│
  ├── StateMessage (post-action, all roles) ───────► (broadcast to all)
  │                                              │
```

The post-action `StateMessage` is broadcast after demotion and reflects the decker's new location and available actions. Clients should update their UI on this message.

Timeout: if no `ActionCommand` arrives within 120 seconds, the server broadcasts a `ResultMessage(success: false, details: "Action timed out")` and demotes the controller (no post-action StateMessage is sent on timeout).

---

## Error Codes

| Code | Meaning |
|---|---|
| `not_your_turn` | `action` received from a session that is not the active controller |
| `no_action_pending` | `action` received when no future is waiting (turn already resolved or not started) |
| `already_registered` | `join` sent by a session that is already registered |
| `name_already_taken` | `join` with a decker name already held by another session |
| `name_too_long` | decker name exceeds 32 characters |
| `unknown_message_type` | `type` field not recognised; `details` contains the received value |
| `bad_request` | JSON parse or deserialization error; `details` contains the exception message |
| `server_full` | connection refused at WebSocket open (before any client message) because the server has reached `MAX_CONNECTIONS` (32) |

---

## `DeckerStateDto` Fields

The `decker` object within `StateMessage` has the following key fields:

| Field | Type | Notes |
|---|---|---|
| `name` | string | Decker name |
| `location` | string | Human-readable location string (e.g. `"Host: Mitsuhama Pagoda"`) |
| `jackedIn` | bool | True iff the persona is jacked in (`currentLocation != null`). Typed jack-in flag; clients use this rather than string-comparing `location` against `"not jacked in"`. |
| `locationIndex` | int? | Index into `visibleObjects` identifying the current location object; null if not jacked in or object not visible. **Stub:** currently always 0 when jacked in; proper lookup by object identity is deferred. |
| `isPinnedByBlackIc` | bool | True if a Black IC pin is active |
| `mcpRating` | int | Current MPCP rating |
| `hackingPool` | int | Current hacking pool |
| `bod` | int | Persona Bod program rating; falls back to cyberdeck firmware baseline when not jacked in |
| `evasion` | int | Persona Evasion program rating; same fallback |
| `masking` | int | Persona Masking program rating; same fallback |
| `sensor` | int | Persona Sensor program rating; same fallback |
| `activeUtilities` | array | Loaded utility programs |
| `physicalDamage` | int | Physical CM damage boxes filled |
| `mentalDamage` | int | Mental CM damage boxes filled |
| `physicalMaxBoxes` | int | Physical CM capacity |
| `mentalMaxBoxes` | int | Mental CM capacity |
| `knownAddresses` | string[] | Full names of LTGs/PLTGs/hosts the decker may access; gates the `AccessLtg` / `AccessHost` actions |

`locationIndex` is the preferred lookup key. Fall back to name-based matching in `visibleObjects` only if `locationIndex` is null.

---

## `AvailableActionDto` Discriminant

Sealed by `"kind"` field (not `"type"`):

| kind | Fields |
|---|---|
| `LogonToRtg` | `rtgName` |
| `AccessLtg` | `ltgNames` (string[]) — reachable LTG/PLTG names in `knownAddresses`; the per-target logon cards are collapsed into this one action |
| `AccessHost` | `hostNames` (string[]) — reachable host names in `knownAddresses`; collapsed into one action |
| `SelectLocateTarget` | `operation` (SystemOperation), `candidates` (string[]) — the ≤5 located names to choose from |
| `GracefulLogoff` | — |
| `JackOut` | — |
| `Operation` | `operation` (SystemOperation), `targetKind`, `targetName`, `paramKind` (`"query"` / `"newContent"` / `"dataSize"` / null) |

`LogonToLtg` / `LogonToPltg` / `LogonToHost` are no longer emitted — navigation to gated targets uses `AccessLtg` / `AccessHost`. `ActionCommand.params` additionally carries `targetName` (string, nullable), consumed by `AccessLtg` / `AccessHost` / `SelectLocateTarget`.

**Deferred operations** — never appear in `availableActions`:

| Operation | Status |
|---|---|
| `LOCATE_DECKER` | Out of scope — see [out_of_scope.md §4](out_of_scope.md) |
| `SWAP_MEMORY` | Out of scope — see [out_of_scope.md §5](out_of_scope.md) |

## `MatrixObjectDto` Discriminant

Sealed by `"kind"` field:

| kind | Key fields |
|---|---|
| `GridNode` | `name`, `region`, `alertStatus`, `securityCode`, `securityTally`, `ltgCount`, `connectedRtgCount` |
| `LocalGrid` | `name`, `parentRtgName`, `alertStatus`, `securityTally`, `hostCount`, `pltgCount` |
| `PrivateGrid` | `name`, `owner`, `parentLtgName`, `alertStatus`, `securityCode`, `hostCount` |
| `HostNode` | `name`, `topologyType`, `offline`, `alertStatus`, `securityCode`, `securityTally` |
| `HostSubsystem` | `subsystemType`, `description` | Rating intentionally omitted — revealed only after a successful `ANALYZE_HOST` or `ANALYZE_SUBSYSTEM` operation. |
| `IcProgram` | `name`, `analyzed: Boolean`, `rating: Int?` (null when not analyzed), `behavior: String?` (null when not analyzed), `guardedNodeType: String?` (null when not analyzed) |
| `File` | `name`, `isScrambleProtected`, `isPointer`, `sizeMp` |
| `Device` | `name`, `systemAddress` |
