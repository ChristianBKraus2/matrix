# UI Design: Matrix of Shadowrun

## Technology Stack

| Concern | Choice |
|---|---|
| Framework | React 18 + TypeScript |
| Build tool | Vite 5 |
| Styling | Plain CSS (CSS custom properties, no UI library) |
| State | React hooks only (`useState`, `useReducer`, `useEffect`) |
| WebSocket | Native browser `WebSocket` API wrapped in a custom hook |
| Font | [VT323](https://fonts.google.com/specimen/VT323) (Google Fonts) — retro terminal monospace |

No external component libraries. The retro aesthetic is achieved with plain CSS.

---

## Project Layout

```
matrix/                          ← existing Kotlin/Gradle project
├── frontend/                    ← new React project (Vite)
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── App.css              ← global layout + CSS variables
│       ├── types/
│       │   └── messages.ts      ← all WebSocket message types
│       ├── hooks/
│       │   └── useWebSocket.ts  ← connection + state machine
│       └── components/
│           ├── DeckerPanel.tsx
│           ├── LocationPanel.tsx
│           ├── EntitiesPanel.tsx
│           ├── ActionsPanel.tsx
│           └── NarrativePanel.tsx
├── build.gradle.kts             ← add buildFrontend + copy tasks
└── src/main/kotlin/.../server/
    └── MatrixServer.kt          ← add staticResources route
```

Vite is configured to output to `../build/resources/main/static` so the JAR picks it up without committing build artifacts.

---

## Gradle Integration

Two new tasks in `build.gradle.kts`:

```kotlin
tasks.register("buildFrontend") {
    doLast {
        exec {
            workingDir = file("frontend")
            commandLine("cmd", "/c", "npm install && npm run build")
        }
    }
}

tasks.register<Copy>("copyFrontendBuild") {
    from("frontend/dist")
    into(layout.buildDirectory.dir("resources/main/static"))
    dependsOn("buildFrontend")
}

tasks.named("processResources") {
    dependsOn("copyFrontendBuild")
}
```

Vite `outDir` is set to `../build/resources/main/static` so the copy task is actually redundant but kept for clarity. Either approach works; the output must land in `build/resources/main/static/` before the JAR is assembled.

---

## Ktor Static Serving

Add inside `routing { }` in `MatrixServer.kt`, **before** the `webSocket` route:

```kotlin
staticResources("/", "static") {
    default("index.html")
}
```

`http://localhost:8080/` → serves React SPA from classpath `static/index.html`.
`ws://localhost:8080/decker/ws` → WebSocket (unchanged).

---

## Visual Theme

```css
:root {
  --green:        #00ff41;   /* primary text */
  --green-dim:    #00b32c;   /* secondary / borders */
  --green-faint:  #003a0f;   /* subtle backgrounds */
  --bg:           #000000;
  --bg-panel:     #020c02;
  --red-alert:    #ff2020;   /* danger / pinned indicator */
  --amber:        #ffb000;   /* warnings / SIMPLE actions */
  --font:         'VT323', 'Courier New', monospace;
  --glow:         0 0 6px #00ff41, 0 0 14px #00b32c44;
}
```

**Panel borders:** `1px solid var(--green-dim)` with `box-shadow: var(--glow)`.

**Damage monitors:** A row of small squares — filled green for healthy, red for damaged, empty/dim for remaining capacity. Example for physical monitor (10 boxes, 3 damaged):
```
PHYS  [■][■][■][□][□][□][□][□][□][□]
```

**Alert status colors:**
- `NO_ALERT` → `--green`
- `PASSIVE_ALERT` → `--amber`
- `ACTIVE_ALERT` → `--red-alert` + `animation: blink 1s step-end infinite`

**Active controller indicator:** Middle panel gets `border: 2px solid var(--green)` + `animation: pulse-border 0.8s ease-in-out infinite alternate` when `role === "active_controller"`.

---

## Screen Layout (CSS Grid)

```
┌────────────────────────────────────────────────────────┐
│                  TOP — Location panel                  │
├───────────────┬────────────────────┬───────────────────┤
│               │                    │                   │
│  LEFT         │  MIDDLE            │  RIGHT            │
│  Decker       │  Narrative         │  Entities         │
│               │                    │                   │
├───────────────┴────────────────────┴───────────────────┤
│                  BOTTOM — Actions panel                │
└────────────────────────────────────────────────────────┘
```

CSS Grid template:

```css
.app {
  display: grid;
  grid-template-rows: auto 1fr auto;
  grid-template-columns: 1fr 2fr 1fr;
  grid-template-areas:
    "top    top      top"
    "left   middle   right"
    "bottom bottom   bottom";
  height: 100vh;
  gap: 4px;
}
```

Left and right columns are equal width (`1fr` each). Middle is wider (`2fr`). Top and bottom height is content-driven (`auto`).

---

## TypeScript Types (`src/types/messages.ts`)

```typescript
// ── Client → Server ──────────────────────────────────────

export interface JoinMessage {
  type: 'join';
  deckerName: string;
  reconnectToken?: string;  // optional; used to re-associate a returning client with their prior session
}

export interface ActionParams {
  newContent?: string | null;
  precision?: 'VERY_VAGUE' | 'VAGUE' | 'NORMAL' | 'SPECIFIC' | 'VERY_SPECIFIC';
  inactivitySeconds?: number;
  query?: string;  // search query string for LOCATE_ACCESS_NODE
}

export interface ActionCommand {
  type: 'action';
  actionIndex: number;
  params?: ActionParams;
}

// ── Server → Client ──────────────────────────────────────

export type Role = 'observer' | 'registered_decker' | 'active_controller';

export interface ControlMessage {
  type: 'control';
  role: Role;
  deckerName?: string;
  reconnectToken?: string;  // server-assigned token; present on first registration, absent on role changes
}

export interface ActiveUtility {
  type: string;
  rating: number;
}

export interface DeckerStateDto {
  name: string;
  location: string;
  jackedIn: boolean;  // typed jack-in flag; true iff currentLocation != null. Preferred over parsing the `location` string for jack-in logic.
  isPinnedByBlackIc: boolean;
  physicalDamage: number;
  physicalMaxBoxes: number;
  mentalDamage: number;
  mentalMaxBoxes: number;
  hackingPool: number;
  mcpRating: number;
  activeUtilities: ActiveUtility[];
  locationIndex: number | null;  // index into visibleObjects for the current location; null when not jacked in; currently always 0 when jacked in (stub)
}

export type AlertStatus = 'NO_ALERT' | 'PASSIVE_ALERT' | 'ACTIVE_ALERT';
export type SecurityCode = 'BLUE' | 'GREEN' | 'ORANGE' | 'RED';
export type TopologyType = 'OPEN_ACCESS' | 'TIERED' | 'HOST_HOST' | 'PRIVATE_GRID';
export type SubsystemType = 'ACCESS' | 'CONTROL' | 'INDEX' | 'FILES' | 'SLAVE';

export type MatrixObjectDto =
  | { kind: 'GridNode';      index: number; name: string; region: string; securityCode: SecurityCode; alertStatus: AlertStatus; securityTally: number; ltgCount: number; connectedRtgCount: number }
  | { kind: 'LocalGrid';     index: number; name: string; parentRtgName: string; alertStatus: AlertStatus; securityTally: number; hostCount: number; pltgCount: number }
  | { kind: 'PrivateGrid';   index: number; name: string; owner: string; parentLtgName: string; securityCode: SecurityCode; alertStatus: AlertStatus; hostCount: number }
  | { kind: 'HostNode';      index: number; name: string; topologyType: TopologyType; offline: boolean; alertStatus: AlertStatus; securityCode: SecurityCode; securityTally: number }
  | { kind: 'HostSubsystem'; index: number; subsystemType: SubsystemType; description: string }
  | { kind: 'IcProgram';     index: number; name: string; analyzed: boolean; rating: number | null; behavior: 'PROACTIVE' | 'REACTIVE' | null; guardedNodeType: string | null }
  | { kind: 'File';          index: number; name: string; isScrambleProtected: boolean; isPointer: boolean; sizeMp: number }
  | { kind: 'Device';        index: number; name: string; systemAddress: string };

export type ActionType = 'FREE' | 'SIMPLE' | 'COMPLEX';

export type AvailableActionDto =
  | { kind: 'LogonToRtg';    index: number; actionType: ActionType; rtgName: string }
  | { kind: 'LogonToLtg';    index: number; actionType: ActionType; ltgName: string }
  | { kind: 'LogonToPltg';   index: number; actionType: ActionType; pltgName: string }
  | { kind: 'LogonToHost';   index: number; actionType: ActionType; hostName: string }
  | { kind: 'GracefulLogoff';index: number; actionType: ActionType }
  | { kind: 'JackOut';       index: number; actionType: ActionType }
  | { kind: 'Operation';     index: number; actionType: ActionType; operation: SystemOperation; targetKind: string | null; targetName: string | null; paramKind: "precision" | "newContent" | "dataSize" | null };

export interface StateMessage {
  type: 'state';
  role: Role;
  decker: DeckerStateDto;
  visibleObjects: MatrixObjectDto[];
  availableActions: AvailableActionDto[];
}

export interface ResultMessage {
  type: 'result';
  success: boolean;
  deckerSuccesses: number;
  hostSuccesses: number;
  details: string;
}

export type ErrorCode =
  | 'not_your_turn'
  | 'no_action_pending'
  | 'already_registered'
  | 'name_already_taken'
  | 'name_too_long'           // decker name exceeds server-enforced maximum length
  | 'unknown_message_type'    // server received a message with an unrecognised type field
  | 'bad_request'             // message was parseable but semantically invalid
  | 'server_full'             // maximum number of registered deckers already reached
  ;

export interface ErrorMessage {
  type: 'error';
  message: ErrorCode;
  details?: string;  // optional human-readable description of the error cause
}

export type ServerMessage = ControlMessage | StateMessage | ResultMessage | ErrorMessage;
```

---

## WebSocket Hook (`src/hooks/useWebSocket.ts`)

### Connection lifecycle

```
connect
  └─► receive ControlMessage(role="observer")
      └─► send JoinMessage(deckerName)
          └─► receive ControlMessage(role="registered_decker"|error)
              └─► wait for StateMessage
                  ├─ role="active_controller" → enable action submission
                  └─ role other → display state, await next StateMessage
```

### State managed by the hook

```typescript
interface WsState {
  connected: boolean;
  role: Role | null;
  deckerName: string | null;
  gameState: StateMessage | null;
  events: GameEvent[];  // GameEvent wraps a result or error; capped at last 20
}
```

### Reconnection

On `onclose` or `onerror`: schedule reconnect after 3 s. Exponential backoff up to 30 s. Re-send `JoinMessage` automatically after re-connection if `deckerName` is known.

**Implementation guards:**
1. Only schedule a reconnect if the hook is still in `CONNECTING` or `OPEN` state — do not attempt reconnect after an intentional `disconnect()` call.
2. Before calling `ws.close()` during a manual disconnect, set `ws.onclose = null` and `ws.onerror = null` to prevent the reconnect logic from firing.

**`reconnectToken` flow:** When the server sends `ControlMessage(role="registered_decker", reconnectToken="...")`, the hook stores the token in `WsState`. On reconnect, `JoinMessage` includes the stored `reconnectToken` so the server can re-associate the returning client with their prior session (hacking pool, turn state, etc.). The token is cleared when the user deliberately logs out.

### `sendAction(index, params?)`

Only callable when `role === "active_controller"`. Sends `ActionCommand` over the socket.

---

## Panel Design Details

### Left — DeckerPanel

Sections (top to bottom):

1. **Identity header** — decker name in large font. If `isPinnedByBlackIc`, show a blinking `⚠ PINNED BY BLACK IC` badge in red below the name.
2. **Condition monitors** — two rows of boxes:
   ```
   PHYS  [■][■][□][□][□][□][□][□][□][□]   2/10
   MENT  [■][■][■][□][□][□][□][□][□][□]   3/10
   ```
3. **Hacking pool** — `HACKING POOL: 6d` displayed prominently.
4. **Cyberdeck** — `MCP RATING: 8`.
5. **Programs** — table of loaded utilities:
   ```
   ANALYZE    ••••  (4)
   ATTACK     •••   (3)
   ```
   Bullet dots or a rating bar make the value scannable at a glance.

### Top — LocationPanel

Parse `decker.location`:
- Prefix `"RTG: "` → find `GridNode` with matching `name` in `visibleObjects`
- Prefix `"LTG: "` → find `LocalGrid`
- Prefix `"PLTG: "` → find `PrivateGrid`
- Prefix `"Host: "` → find `HostNode`
- `"not jacked in"` → show `[ NOT JACKED IN ]` placeholder

> **Fragility note:** prefix-matching on a display `name` string is brittle — a name containing `": "` can break parsing. `locationIndex: number | null` is already present on `DeckerStateDto` as a stub (always `0` when jacked in) so the panel can look up the matched object directly by index instead of scanning `visibleObjects` by name prefix. Full implementation of index-based lookup is a future improvement.

Display all fields of the matched object as a horizontal strip of labelled values:

```
▶ RTG: SEATTLE     REGION: Pacific Northwest     SEC: GREEN     ALERT: ██ ACTIVE     TALLY: 7     LTGs: 3     RTGs: 2
```

Alert status drives color: `NO_ALERT` = green, `PASSIVE_ALERT` = amber, `ACTIVE_ALERT` = red + blinking.

### Right — EntitiesPanel

Only entity kinds are shown: `HostSubsystem`, `IcProgram`, `File`, `Device`. Location-kind objects are excluded.

- The focused entity is the first card and rendered at 1.5× font size with all fields visible.
- Other entities render as compact cards below.
- Clicking a compact card promotes it to focus (moves it to top).
- If no entities exist, show `[ NO ENTITIES VISIBLE ]`.

**IcProgram card fields:** name, `analyzed` status badge (`[ANALYZED]` / `[UNKNOWN]`), and — only when `analyzed === true` — rating, behavior, guardedNodeType. When not yet analyzed, rating/behavior/guardedNodeType are null and must not be rendered.
**HostSubsystem card fields:** subsystemType, description.
**File card fields:** name, sizeMp, `[SCRAMBLED]` badge if `isScrambleProtected`, `[POINTER]` if `isPointer`.
**Device card fields:** name, systemAddress.

### Bottom — ActionsPanel

Horizontal scroll row of cards. Each card shows:
- Top-left: formatted label — for `Logon` variants: `LOGON <NETWORK TYPE>: <name>` (e.g. `LOGON RTG: UCAS`); for `Operation` cards: the formatted operation name (e.g. `LOCATE FILE`); for other actions (`GRACEFUL LOGOFF`, `JACK OUT`): the action name directly.
- Top-right: cost badge — `FREE` (green), `SIMPLE` (amber), `COMPLEX` (red)
- Below header (operation cards only): `▸ targetName` when a target is present.

**Inline controls (rendered inside the card):**

The `paramKind` field on `Operation` actions declares which inline control (if any) the card must render. Null means no params needed. Map:

| `paramKind` | Control |
|---|---|
| `"precision"` | Text input (SEARCH TERM) + five-position selector |
| `"newContent"` | Text area |
| `"dataSize"` | Numeric stepper (Mp) |
| `null` | *(no inline control)* |

Full inline control specs per operation:

| Operation | Control |
|---|---|
| `LOCATE_FILE` / `LOCATE_SLAVE` / `LOCATE_ACCESS_NODE` | Text input `[SEARCH TERM]` (blank on new operation, ignored on continuation) + five-position selector `[VERY VAGUE]` / `[VAGUE]` / `[NORMAL]` / `[SPECIFIC]` / `[VERY SPECIFIC]` — NORMAL selected by default |
| `EDIT_FILE` | Text area that expands when the card is focused; empty = erase file |

`MAKE_COMCALL` and `TAP_COMCALL` render no inline control (`paramKind: null`): `MAKE_COMCALL` takes no
params and just runs a System Test, and `TAP_COMCALL`'s dataline-scanner rating is resolved
server-side, not entered by the client.

Pressing any card (or a confirm button on `EDIT_FILE`) calls `sendAction(card.index, params)`.

When `role !== "active_controller"` all cards are dimmed and non-interactive.

### Middle — NarrativePanel

Scrollable log of recent events (newest at bottom). Each entry is one of:
- **Result:** `[✓ SUCCESS]` or `[✗ FAILURE]` in green/red, decker/host dice counts, then `details` text.
- **Error:** `[ERROR] <human-readable message>` in red.

**Error message mapping:**
| Code | Human-readable |
|---|---|
| `not_your_turn` | Not your turn |
| `no_action_pending` | No action pending |
| `already_registered` | Already registered |
| `name_already_taken` | Decker name already taken |
| `name_too_long` | Decker name too long (max 32 characters) |
| `unknown_message_type` | Unknown message type |
| `bad_request` | Bad request |
| `server_full` | Server at capacity |

When `role === "active_controller"`, the Middle panel's outer border pulses green (`animation: pulse-border 0.8s ease-in-out infinite alternate`) to signal it is this client's turn to act.

---

## Join Flow (App-level)

On first load, show a **Join screen** overlaid above the game grid:

```
╔══════════════════════════════╗
║   MATRIX OF SHADOWRUN v1.0   ║
║                              ║
║  DECKER HANDLE: [__________] ║
║                              ║
║         [ JACK IN ]          ║
╚══════════════════════════════╝
```

On submit: send `JoinMessage`. On `ControlMessage(role="registered_decker")` dismiss the overlay and show the game. On error show the message and keep the overlay.

---

## CSS Animation Definitions

```css
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}

@keyframes pulse-border {
  from { box-shadow: 0 0 4px var(--green); }
  to   { box-shadow: 0 0 20px var(--green), 0 0 40px var(--green-dim); }
}
```

---

## Verification

1. `powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat test"` — existing tests pass.
2. `powershell -Command "cd 'C:\VSCode\private\matrix'; .\gradlew.bat run"` — starts server.
3. Open `http://localhost:8080` → Join screen appears.
4. Enter a decker name and jack in → game grid appears with all 5 panels.
5. Verify: location panel shows current node, decker panel shows stats, actions panel shows available actions.
6. Press an action card → result appears in the Middle panel.
7. Test the inline-param actions (LOCATE_FILE, EDIT_FILE) render their controls correctly.
8. Open a second browser tab with a different decker name → verify observer/registered_decker role behaviour, blinking border only appears on the active controller tab.
