# Matrix of Shadowrun — WebSocket Protocol

## Transport

Single WebSocket endpoint: `ws://<host>/decker/ws`

All messages are JSON objects. Every message has a `"type"` discriminator field.

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
{ "type": "control", "role": "<Role>", "deckerName": "<string|null>" }
```
`deckerName` is present when `role` is `registered_decker` or `active_controller`.

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
{ "type": "join", "deckerName": "Kylie" }
```

### `ActionCommand` (client → server)
```json
{ "type": "action", "actionIndex": 2, "params": { ... } }
```
`actionIndex` is a 0-based index into the `availableActions` array from the most recent `state` message.

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
  │                                              │
```

Timeout: if no `ActionCommand` arrives within 120 seconds, the server broadcasts a `ResultMessage(success: false, details: "Action timed out")` and demotes the controller.

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

---

## `AvailableActionDto` Discriminant

Sealed by `"kind"` field (not `"type"`):

| kind | Fields |
|---|---|
| `LogonToRtg` | `rtgName` |
| `LogonToLtg` | `ltgName` |
| `LogonToPltg` | `pltgName` |
| `LogonToHost` | `hostName` |
| `GracefulLogoff` | — |
| `JackOut` | — |
| `Operation` | `operation` (SystemOperation), `targetKind`, `targetName` |

## `MatrixObjectDto` Discriminant

Sealed by `"kind"` field:

| kind | Key fields |
|---|---|
| `GridNode` | `name`, `region`, `alertStatus`, `ltgCount` |
| `LocalGrid` | `name`, `parentRtgName`, `hostCount` |
| `PrivateGrid` | `name`, `owner`, `parentLtgName` |
| `HostNode` | `name`, `topologyType`, `securityCode`, `securityTally` |
| `HostSubsystem` | `subsystemType`, `description` |
| `IcProgram` | `name`, `rating`, `behavior` |
| `File` | `name`, `isScrambleProtected`, `sizeMp` |
| `Device` | `name`, `systemAddress` |
