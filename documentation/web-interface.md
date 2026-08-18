# Matrix WebSocket Interface

This document fully specifies the WebSocket interface exposed by the Matrix server. A UI can be implemented against this specification without reading any server source code.

---

## 1. Connection

```
ws://<host>/decker/ws
```

Plain WebSocket (no subprotocol). All messages are JSON text frames. The server sends a `ControlMessage` immediately on connect and then is silent until the game engine triggers a turn.

---

## 2. Session roles

There are three roles:

| Role | Description |
|---|---|
| `observer` | Connected, no `JoinMessage` sent. Receives all broadcasts. Cannot send `ActionCommand`. |
| `registered_decker` | Sent a valid `JoinMessage` and claimed a decker identity. Waiting for their turn. |
| `active_controller` | Currently promoted for the running turn. Must respond with `ActionCommand` before timeout. |

Every session begins as `observer` on connect. Transitions are server-driven:

- `observer` → `registered_decker` via a valid `JoinMessage`
- `registered_decker` → `active_controller` when the game engine fires that decker's turn
- `active_controller` → `registered_decker` after the action resolves or times out

---

## 3. Message type overview

Every message carries a `"type"` string field.

| `type`    | Direction       | When                                                    |
|-----------|----------------|---------------------------------------------------------|
| `control` | server → client | On connect; on join; on promotion; on demotion          |
| `join`    | client → server | Once per session, to claim a decker identity            |
| `state`   | server → client | Each game tick when a decker's turn arrives (broadcast) |
| `result`  | server → client | After every action resolves (broadcast)                 |
| `error`   | server → client | Protocol violation by the sending client                |
| `action`  | client → server | Active controller submitting a chosen action            |

---

## 3a. JoinMessage (client → server)

Sent once per connection to claim a decker identity. Must be sent before the game engine can promote the session.

```json
{ "type": "join", "deckerName": "Kylie" }
```

| Field        | Type   | Required | Description                        |
|--------------|--------|----------|------------------------------------|
| `type`       | string | yes      | Always `"join"`                    |
| `deckerName` | string | yes      | The decker handle to claim (non-empty) |

**On success:** server sends `ControlMessage { "role": "registered_decker", "deckerName": "Kylie" }`.

**On failure:** server sends `ErrorMessage` — see §8.

A session may send `JoinMessage` exactly once. Sending it again returns `"already_registered"`.

---

## 4. ControlMessage (server → client)

Sent to a specific session (unicast) at four points in its lifecycle.

```json
{ "type": "control", "role": "observer" }
{ "type": "control", "role": "registered_decker", "deckerName": "Kylie" }
{ "type": "control", "role": "active_controller", "deckerName": "Kylie" }
```

| Field        | Type   | Present when                                          | Description                                                     |
|--------------|--------|-------------------------------------------------------|-----------------------------------------------------------------|
| `type`       | string | always                                                | Always `"control"`                                              |
| `role`       | string | always                                                | `"observer"` \| `"registered_decker"` \| `"active_controller"` |
| `deckerName` | string | `role` is `registered_decker` or `active_controller`  | The claimed decker name                                         |

**Sent when:**
1. Client connects → `role: "observer"`
2. `JoinMessage` accepted → `role: "registered_decker"`
3. Game engine promotes the session for its turn → `role: "active_controller"`
4. Turn resolves or times out → `role: "registered_decker"` (demotion)

---

## 5. StateMessage (server → client)

Broadcast to all connected clients at the start of a decker's turn. Each client receives the same payload; only the `role` field varies. The `active_controller` client must respond with an `ActionCommand` before the turn times out.

### 5a. Top-level shape

```json
{
  "type": "state",
  "role": "active_controller",
  "decker": { ... },
  "visibleObjects": [ ... ],
  "availableActions": [ ... ]
}
```

### 5b. decker object

Snapshot of the decker's current state.

```json
{
  "name": "Kylie",
  "location": "Host: Aztechnology-Seattle",
  "isPinnedByBlackIc": false,
  "physicalDamage": 0,
  "physicalMaxBoxes": 10,
  "mentalDamage": 2,
  "mentalMaxBoxes": 10,
  "hackingPool": 7,
  "mcpRating": 4,
  "activeUtilities": [
    { "type": "ANALYZE", "rating": 5 },
    { "type": "BROWSE",  "rating": 4 }
  ]
}
```

| Field              | Type            | Description                                      |
|--------------------|-----------------|--------------------------------------------------|
| `name`             | string          | Decker's handle                                  |
| `location`         | string          | Human-readable location label (see below)        |
| `isPinnedByBlackIc`| boolean         | If `true`, JackOut will fail                     |
| `physicalDamage`   | number          | Current physical damage boxes filled             |
| `physicalMaxBoxes` | number          | Maximum physical condition monitor boxes         |
| `mentalDamage`     | number          | Current mental (stun) damage boxes filled        |
| `mentalMaxBoxes`   | number          | Maximum mental condition monitor boxes           |
| `hackingPool`      | number          | Current hacking pool dice                        |
| `mcpRating`        | number          | Cyberdeck MCP rating                             |
| `activeUtilities`  | array           | Currently loaded utilities                       |

**`location` values:** `"not jacked in"` | `"RTG: <name>"` | `"LTG: <name>"` | `"PLTG: <name>"` | `"Host: <name>"`

**Utility `type` values:** `ANALYZE` | `BROWSE` | `COMMLINK` | `DECRYPT` | `DECEPTION` | `READ_WRITE` | `RELOCATE` | `SCANNER` | `SPOOF`

### 5c. visibleObjects array

Each entry is a polymorphic object. The `kind` field identifies the variant. The `index` is the object's position in this array (0-based) and is used in `availableActions` to indicate a target.

#### GridNode

```json
{
  "index": 0,
  "kind": "GridNode",
  "name": "UCAS",
  "region": "North America",
  "alertStatus": "PASSIVE",
  "securityTally": 0,
  "ltgCount": 4,
  "connectedRtgCount": 2
}
```

| Field               | Type   | Description                         |
|---------------------|--------|-------------------------------------|
| `name`              | string | RTG name                            |
| `region`            | string | Geographic region                   |
| `alertStatus`       | string | `PASSIVE` / `ALERT` / `SHUTDOWN`    |
| `securityTally`     | number | Accumulated security tally          |
| `ltgCount`          | number | Number of child LTGs                |
| `connectedRtgCount` | number | Number of peer RTGs                 |

#### LocalGrid

| Field           | Type   | Description                         |
|-----------------|--------|-------------------------------------|
| `name`          | string | LTG name                            |
| `parentRtgName` | string | Name of the parent RTG              |
| `alertStatus`   | string | `PASSIVE` / `ALERT` / `SHUTDOWN`    |
| `securityTally` | number |                                     |
| `hostCount`     | number | Number of hosts on this LTG         |
| `pltgCount`     | number | Number of private LTGs              |

#### PrivateGrid

| Field          | Type   | Description                  |
|----------------|--------|------------------------------|
| `name`         | string | PLTG name                    |
| `owner`        | string | Owning organisation          |
| `parentLtgName`| string | Name of the parent LTG       |
| `alertStatus`  | string | `PASSIVE` / `ALERT` / `SHUTDOWN` |
| `hostCount`    | number | Number of hosts on this PLTG |

#### HostNode

| Field          | Type    | Description                                              |
|----------------|---------|----------------------------------------------------------|
| `name`         | string  | Host system name                                         |
| `topologyType` | string  | Host topology (e.g. `STAR`, `TOKEN_RING`, `BUS`, `MATRIX`, `DISTRIBUTED`) |
| `offline`      | boolean | `true` if the host is currently offline                  |
| `alertStatus`  | string  | `PASSIVE` / `ALERT` / `SHUTDOWN`                         |
| `securityCode` | string  | `GREEN` / `YELLOW` / `ORANGE` / `RED` / `BLACK`          |
| `securityTally`| number  |                                                          |

#### HostSubsystem

| Field           | Type   | Description                                              |
|-----------------|--------|----------------------------------------------------------|
| `subsystemType` | string | `ACCESS` / `CONTROL` / `INDEX` / `FILES` / `SLAVE`       |
| `description`   | string | Human-readable description of the node                   |

#### IcProgram

| Field            | Type          | Description                                    |
|------------------|---------------|------------------------------------------------|
| `name`           | string        | IC program name                                |
| `rating`         | number        | IC rating                                      |
| `behavior`       | string        | `PROACTIVE` / `REACTIVE`                       |
| `guardedNodeType`| string / null | Subsystem type this IC guards, or `null`       |

#### File

| Field                | Type    | Description                            |
|----------------------|---------|----------------------------------------|
| `name`               | string  | File name                              |
| `isScrambleProtected`| boolean | `true` if the file is encrypted        |
| `isPointer`          | boolean | `true` if this is a pointer file       |
| `sizeMp`             | number  | File size in Mp                        |

#### Device

| Field           | Type   | Description              |
|-----------------|--------|--------------------------|
| `name`          | string | Device name              |
| `systemAddress` | string | Hardware system address  |

### 5d. availableActions array

Each entry has `index`, `kind`, and `actionType` (`FREE` / `SIMPLE` / `COMPLEX`). The controller must echo back the `index` in the `ActionCommand`.

#### Navigation actions

```json
{ "index": 0, "kind": "LogonToHost", "actionType": "COMPLEX", "hostName": "Aztechnology-Seattle" }
```

| kind            | Extra field             |
|-----------------|-------------------------|
| `LogonToRtg`    | `rtgName: string`       |
| `LogonToLtg`    | `ltgName: string`       |
| `LogonToPltg`   | `pltgName: string`      |
| `LogonToHost`   | `hostName: string`      |
| `GracefulLogoff`| —                       |
| `JackOut`       | —                       |

#### Operation actions

```json
{
  "index": 3,
  "kind": "Operation",
  "actionType": "SIMPLE",
  "operation": "ANALYZE_IC",
  "targetKind": "IcProgram",
  "targetName": "Killer-7"
}
```

| Field        | Type          | Description                                                 |
|--------------|---------------|-------------------------------------------------------------|
| `operation`  | string        | `SystemOperation` name — see §6 for the full list           |
| `targetKind` | string / null | `MatrixObjectDto` subclass name of the target, or `null`    |
| `targetName` | string / null | Human-readable target label (for display only), or `null`   |

---

## 6. ActionCommand (client → server)

The active controller sends this to submit a chosen action.

```json
{ "type": "action", "actionIndex": 3 }
```

```json
{ "type": "action", "actionIndex": 7, "params": { "newContent": "alert(1)" } }
```

| Field        | Type   | Required | Description                                             |
|--------------|--------|----------|---------------------------------------------------------|
| `type`       | string | yes      | Always `"action"`                                       |
| `actionIndex`| number | yes      | The `index` from the chosen entry in `availableActions` |
| `params`     | object | no       | Operation-specific parameters (see table below)         |

### params fields

Only certain operations read `params`. All fields are optional within the object; omitting them activates the documented default.

| Operation(s)                                           | Field               | Type           | Default    | Description                                 |
|--------------------------------------------------------|---------------------|----------------|------------|---------------------------------------------|
| `EDIT_FILE`                                            | `newContent`        | string or null | `null`     | UTF-8 text to write; `null` erases the file |
| `NULL_OPERATION`                                       | `inactivitySeconds` | number         | `0`        | Seconds of declared inactivity              |
| `LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE`   | `precision`         | string         | `"NORMAL"` | `"NORMAL"` or `"HIGH"`                      |
| `MAKE_COMCALL`                                         | `hasValidPasscode`  | boolean        | `false`    | Whether the decker has a valid passcode      |
| `TAP_COMCALL`                                          | `scannerDeviceRating`| number        | `0`        | Scanner device rating to use                |

All other operations ignore `params` entirely.

### Operations not supported over WebSocket

The following operations always return `success: false` with a descriptive `details` string:

| Operation       | Reason                                                                         |
|-----------------|--------------------------------------------------------------------------------|
| `LOCATE_DECKER` | Requires a live `Persona` object — not serialisable over the wire              |
| `SWAP_MEMORY`   | Requires utility selection — UI support not yet implemented on the server side |

---

## 7. ResultMessage (server → client)

Broadcast to **all** connected clients after every action resolves.

```json
{
  "type": "result",
  "success": true,
  "deckerSuccesses": 3,
  "hostSuccesses": 1,
  "details": "3 decker vs 1 host"
}
```

| Field             | Type    | Description                                              |
|-------------------|---------|----------------------------------------------------------|
| `success`         | boolean | `true` if the operation succeeded                        |
| `deckerSuccesses` | number  | Net successes rolled by the decker                       |
| `hostSuccesses`   | number  | Net successes rolled by the host                         |
| `details`         | string  | Human-readable outcome summary (for display / logging)   |

`details` examples: `"3 decker vs 1 host"`, `"Logged on to Host: Aztechnology-Seattle"`, `"Action timed out"`, `"Invalid action index 5"`, `"Pinned by Black IC — cannot jack out"`.

---

## 8. ErrorMessage (server → client)

Sent only to the client that caused the error (not broadcast).

```json
{ "type": "error", "message": "not_your_turn" }
```

| `message`              | Cause                                                                          |
|------------------------|--------------------------------------------------------------------------------|
| `"not_your_turn"`      | A non-active-controller session sent an `ActionCommand`                        |
| `"no_action_pending"`  | `ActionCommand` arrived outside of an active state tick                        |
| `"name_already_taken"` | `JoinMessage` named a decker already claimed by another live session           |
| `"already_registered"` | The same session sent a second `JoinMessage`                                   |
| `"name_not_in_game"`   | `JoinMessage` named a decker not present in the current game (optional check)  |

---

## 9. Full interaction flow

```
Kylie-UI              Server                  Shadowcat-UI          Observer-UI
    |─── connect ─────────>|
    |<── control(observer) ─|
    |─── join("Kylie") ────>|
    |<── control(reg,"Kylie")|
    |                       |<─── connect ─────────────────────────|
    |                       |──── control(observer) ───────────────>|
    |                       |<─── connect ────────────────────────────────────|
    |                       |──── control(observer) ──────────────────────────>|
    |                       |<─── join("Shadowcat") ────────────────|
    |                       |──── control(reg,"Shadowcat") ─────────>|
    |                       |
    |  (game tick: Kylie's turn)
    |<── control(active,"Kylie")
    |<── state(active_controller)|── state(registered_decker) ──────>|── state(observer) ──────────>|
    |─── action(index=2) ───>|
    |<── result ─────────────|──── result ──────────────────────────>|──── result ──────────────────>|
    |<── control(reg,"Kylie")|
    |                        |
    |  (game tick: Shadowcat's turn)
    |                        |─── control(active,"Shadowcat") ───────>|
    |<── state(registered_decker)|── state(active_controller) ────────>|── state(observer) ──────────>|
    |                        |<── action(index=5) ────────────────────|
    |<── result ─────────────|──── result ──────────────────────────>|──── result ──────────────────>|
    |                        |──── control(reg,"Shadowcat") ──────────>|
    |                        |
    |  (next tick…)          |
```

Legend: `control(observer)` = `ControlMessage { role: "observer" }`,  `control(reg,"Kylie")` = `ControlMessage { role: "registered_decker", deckerName: "Kylie" }`,  `control(active,"Kylie")` = `ControlMessage { role: "active_controller", deckerName: "Kylie" }`.

**Timeout.** If the active controller sends no `ActionCommand` within the configured timeout (default 120 seconds), the server broadcasts `ResultMessage { "success": false, "details": "Action timed out" }`, demotes the session to `registered_decker`, and advances the game turn.

**No registered session.** If no session has claimed the decker's name when their turn fires, the server immediately broadcasts `ResultMessage { "success": false, "details": "No controller registered for decker <name> — turn skipped" }` and advances.

---

## 10. Promotion and disconnect handling

### Turn-based promotion

When the game engine fires a turn, the server:
1. Looks up the session registered for that decker name.
2. Sends it `ControlMessage { "role": "active_controller", "deckerName": "..." }`.
3. Broadcasts `StateMessage` to all clients (roles vary per session — see §5).
4. After the action resolves or times out, sends the promoted session `ControlMessage { "role": "registered_decker", "deckerName": "..." }`.

### Disconnect handling

| Who disconnects | When | Effect |
|---|---|---|
| Observer | any time | Removed from broadcast list; no game state change |
| Registered decker | between turns | Name released; a new session may re-claim it via `JoinMessage` |
| Registered decker | before their turn arrives | Same as above; their turn will be skipped when it arrives |
| Active controller | mid-turn | Server broadcasts `ResultMessage { "success": false, "details": "Decker disconnected — turn forfeit" }` and advances the turn; name released |
