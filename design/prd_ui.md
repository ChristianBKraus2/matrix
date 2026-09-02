# User Interface

## Overview

This document describes the User Interface (UI) for the game described in [design.md](design.md). The UI is build based on React and communicates with the backend based on WebSockets. This Websocket interface is documented in depth by [protocol.md](protocol.md).

The UI displays the environment and triggers the actions in the backend. The game loop is defined in the backend which (after connection) gives the control to one of the (potentially) several UI instances that are connected to the backend.

## Theme

The UI should look like a screen from the 1990 and be enspired by the Cyberpunk genre especially Matrix the film. It should use wireframes and text displayed in green letters on a black background. 

## Layout

The UI is separated into the following areas:

- **Left**: On the left hand side the decker and his tools are displayed
- **Right**: On the right hand side the other entities are displayed.
- **Up**: Between these areas and on the top of the screen the current location and its porperties is displayed.
- **Botton**: Between these areas and at the bottom of the screen the currently possible actions are displayed. 
- **Middle**: The middle of the screen is reserved for a description of the current location or a description of what is happeing currently.

The left and right hand side shoudl have the same width. The top and bottom can have different heights.

### Decker

On the left hand side of the screen we have the area reserved for the decker and his tools. From top to bottom is should display:

- attributes of the decker focusing on the most important attributes
- attributes of the cyberdeck
- the various programs (incl. whether they are loaded)

In this context the things with a focus should be displayed bigget then the things without focus, but all attributes should be visible one way or the other.

### Entities

Besides the decker there are other entities visible. These case be IC, but also files, devices and so on. On the right hand side there should be a list of these entities shown as cards having all the relevant information. On entity is in focus. This one is shown as the top of the list and is bigger then the others. This card shiud display all information of the entity and with a larger font.

### Location

The current location is displayed on the top. This can be the current Grid (LTG, RTG, ...) or the current node of the host. All relevant information is displayed.

### Actions

All possible action are dispayed as card on the bottom. Pressing the card should trigger the corresponding action. Together with an information selected on the left from the decker and / or the selected entity on the right all required information to trigger the action should be present.

## State Details

This section maps every field from the WebSocket `StateMessage` (and related messages) to a UI area. See [protocol.md](protocol.md) for the full protocol specification.

### Left — Decker panel

All fields from the `decker` object of `StateMessage`:

| Field | Description |
|---|---|
| `name` | Decker handle — identity header |
| `isPinnedByBlackIc` | Critical status indicator |
| `physicalDamage` / `physicalMaxBoxes` | Physical condition monitor |
| `mentalDamage` / `mentalMaxBoxes` | Mental condition monitor |
| `hackingPool` | Current hacking pool dice |
| `mcpRating` | Cyberdeck MCP rating |
| `activeUtilities` (`type`, `rating`) | Loaded programs list |

`decker.location` is also read here to identify the current node for the Top area (see below).

### Top — Location panel

The `visibleObjects` array contains polymorphic objects. The one whose `name` matches the current `decker.location` string is rendered in the Top area. Matching is done by prefix:

| `decker.location` prefix | `visibleObjects` kind |
|---|---|
| `"RTG: <name>"` | `GridNode` with matching `name` |
| `"LTG: <name>"` | `LocalGrid` with matching `name` |
| `"PLTG: <name>"` | `PrivateGrid` with matching `name` |
| `"Host: <name>"` | `HostNode` with matching `name` |
| `"not jacked in"` | no match — show default state |

All fields exposed by the matched object's DTO are displayed in the Top area. Available fields vary by kind — not every kind exposes securityCode or securityTally (e.g. `PrivateGrid` exposes `owner` and `hostCount` but not `securityTally`).

### Right — Entities panel

Entity-type objects from `visibleObjects` are displayed as cards in the Right panel:

| Kind | Displayed fields |
|---|---|
| `HostSubsystem` | `subsystemType`, `description` |
| `IcProgram` | `name`, `rating`, `behavior`, `guardedNodeType`; when analyzed (name in decker's `analyzedIcNames`): additionally display IC type badge |
| `File` | `name`, `isScrambleProtected`, `isPointer`, `sizeMp` |
| `Device` | `name`, `systemAddress` |

Location-type objects (`GridNode`, `LocalGrid`, `PrivateGrid`, `HostNode`) that are navigation targets (i.e. do not match the current location) are **not** shown in the Right panel — their handling is defined separately.

### Bottom — Actions panel

All entries from `availableActions` are rendered as cards. Each card shows the action kind, type (`FREE` / `SIMPLE` / `COMPLEX`), and any target name. Pressing a card submits the corresponding `ActionCommand`.

### Middle — Narrative / event area

| Source | Content |
|---|---|
| `ResultMessage` | `details` string; `deckerSuccesses` and `hostSuccesses` are always present non-optional integers |
| `ErrorMessage` | `message` error code (displayed as human-readable text) |
| `ControlMessage.role` | When `active_controller`: Middle area shows a **pulsing green glow border** (`pulse-border` animation) to signal it is this client's turn. No border for `registered_decker` or `observer`. |

On WebSocket disconnect, `gameState` is set to `null` immediately — all panels (location, decker, entities, actions) go empty. The hook schedules a reconnect with exponential backoff (3 s initial, capped at 30 s). If `deckerName` is already known, a `JoinMessage` is re-sent automatically after the connection is re-established. Once a `StateMessage` arrives the panels are restored.

#### Session Reconnection Token

- UI-01: When the server accepts a new decker registration it must issue a `reconnectToken` (opaque string) in the `ControlMessage(role="registered_decker")` response.
- UI-02: The client stores the token and includes it as `reconnectToken` in any subsequent `JoinMessage` for the same decker name. This allows the server to re-associate the returning session with the prior one (hacking pool, turn state, suppressed IC, etc.).
- UI-03: On reconnect the server validates the token. If it matches the stored token for the given `deckerName`, the session is re-associated and a new token is issued. If the token does not match, a `BAD_REQUEST` error is returned.
- UI-04: The token is cleared client-side when the user deliberately logs out.

# Actions - Details

## How action cards work

Each entry in `availableActions` becomes one card in the Bottom area. The card displays the action kind, the cost (`FREE` / `SIMPLE` / `COMPLEX`), and — for `Operation` actions — the target name. Pressing the card submits an `ActionCommand` with the card's `actionIndex`. The server resolves the target from the index; no separate entity selection is required.

## Actions that need no extra input

The following actions are fully determined by pressing the card alone:

- **Navigation** (excluded from general analysis — handled separately): `LogonToRtg`, `LogonToLtg`, `LogonToPltg`, `LogonToHost`
- **Exit actions**: `GracefulLogoff`, `JackOut`
- **All `Operation` actions** where `params` is ignored by the server — this covers the majority of operations: `ANALYZE_HOST`, `ANALYZE_IC`, `ANALYZE_ICON`, `ANALYZE_SECURITY`, `ANALYZE_SUBSYSTEM`, `CONTROL_SLAVE`, `DECRYPT_ACCESS`, `DECRYPT_FILE`, `DECRYPT_SLAVE`, `DOWNLOAD_DATA`, `EDIT_SLAVE`, `GRACEFUL_LOGOFF`, `INVOKE_MEDIC`, `LOCATE_IC`, `MAKE_COMCALL`, `MONITOR_SLAVE`, `RELOCATE_ICON`
- **`NULL_OPERATION`**: uses `inactivitySeconds` (default `0`) — the default is sufficient; no extra input required

## Actions that require inline parameter input

Four operation types need additional input that is not derivable from the card, the decker panel, or the entity panel. For each, the required input is provided by an inline control shown on the card itself:

### `LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE`

Param: `precision` — one of `"VERY_VAGUE"`, `"VAGUE"`, `"NORMAL"`, `"SPECIFIC"`, `"VERY_SPECIFIC"` (default `"NORMAL"`)

**UI control:** A 5-position selector on the card. The selected value is sent with the `ActionCommand`.

### `MAKE_COMCALL`

Param: `hasValidPasscode` — boolean (default `false`)

**UI control:** A yes/no toggle or checkbox on the card indicating whether the decker has a valid passcode for the call.

### `TAP_COMCALL`

Param: `scannerDeviceRating` — number (default `0`)

**UI control:** A numeric stepper on the card. The Device entity shown in the Right panel has no `rating` field, so this value must be entered manually. Default `0` means no scanner device is used.

### `EDIT_FILE`

Param: `newContent` — string or `null` (null erases the file)

**UI control:** When this action card is selected (focused), a text input area expands on or above the card. The user enters the new file content before pressing confirm. Leaving the field empty sends `null`, which erases the file.

## Note on decker and entity selection

The Left (decker) and Right (entity) panels are informational context for the player. They do **not** supply parameters to the `ActionCommand` — all required parameters come either from the card index or from the inline controls described above.