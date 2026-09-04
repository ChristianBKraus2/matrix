# Iteration 2 — UI Design Doc Audit (`design/design_ui/design_ui.md`)

This is the checkable reference for later iterations comparing the React/TS frontend
(`App.tsx`, `types/messages.ts`, `hooks/useWebSocket.ts`, `components/*Panel.tsx`) against the design.

## Coverage table

| File | Lines | Verbatim excerpts | Notes/findings |
|---|---|---|---|
| `design/design_ui/design_ui.md` | 476 | (1, opening third, L105) `--font:         'VT323', 'Courier New', monospace;` — (2, middle third, L214) `locationIndex: number | null;  // index into visibleObjects for the current location; null when not jacked in; currently always 0 when jacked in (stub)` — (3, closing third, L401) ``| `LOCATE_FILE` / `LOCATE_SLAVE` / `LOCATE_ACCESS_NODE` | Text input `[SEARCH TERM]` (blank on new operation, ignored on continuation) + five-position selector `[VERY VAGUE]` / `[VAGUE]` / `[NORMAL]` / `[SPECIFIC]` / `[VERY SPECIFIC]` — NORMAL selected by default |`` | 6 candidate findings (DOC-1..DOC-6); read in full L1–476 |

---

## Distilled spec additions

### Component / panel inventory (L35–44, L323–429)
- Five panels exported from `frontend/src/components/`: `DeckerPanel.tsx`, `LocationPanel.tsx`, `EntitiesPanel.tsx`, `ActionsPanel.tsx`, `NarrativePanel.tsx` (L35–40).
- Layout mapping (L126–158): TOP = LocationPanel, LEFT = DeckerPanel, MIDDLE = NarrativePanel ("Narrative"), RIGHT = EntitiesPanel, BOTTOM = ActionsPanel. Grid areas `top/left middle right/bottom`; columns `1fr 2fr 1fr` (L148–154).
- Hook: `frontend/src/hooks/useWebSocket.ts` (L37, L281). Types: `frontend/src/types/messages.ts` (L34, L161).

### WebSocket message contract as UI consumes it (L163–277)
Client→Server:
- `JoinMessage` — `type:'join'`, `deckerName:string`, `reconnectToken?:string` (L166–170).
- `ActionCommand` — `type:'action'`, `actionIndex:number`, `params?:ActionParams` (L181–185).
- `ActionParams` fields (L172–179): `newContent?:string|null`, `precision?:'VERY_VAGUE'|'VAGUE'|'NORMAL'|'SPECIFIC'|'VERY_SPECIFIC'`, `hasValidPasscode?:number`(bool actually), `scannerDeviceRating?:number`, `inactivitySeconds?:number`, `query?:string`.

Server→Client (`ServerMessage = ControlMessage | StateMessage | ResultMessage | ErrorMessage`, L276):
- `Role = 'observer' | 'registered_decker' | 'active_controller'` (L189).
- `ControlMessage` — `type:'control'`, `role:Role`, `deckerName?:string`, `reconnectToken?:string` (present on first registration, absent on role changes) (L191–197).
- `StateMessage` — `type:'state'`, `role:Role`, `decker:DeckerStateDto`, `visibleObjects:MatrixObjectDto[]`, `availableActions:AvailableActionDto[]` (L243–249).
- `ResultMessage` — `type:'result'`, `success:boolean`, `deckerSuccesses:number`, `hostSuccesses:number`, `details:string` (L251–257).
- `ErrorMessage` — `type:'error'`, `message:ErrorCode`, `details?:string` (L270–274).

### `DeckerStateDto` fields (L203–215)
`name`, `location`, `isPinnedByBlackIc`, `physicalDamage`, `physicalMaxBoxes`, `mentalDamage`, `mentalMaxBoxes`, `hackingPool`, `mcpRating`, `activeUtilities:ActiveUtility[]`, `locationIndex:number|null`.
`ActiveUtility` = `{ type:string; rating:number }` (L198–201).

### `MatrixObjectDto` discriminated union `kind` variants (L222–230)
`GridNode` (index,name,region,securityCode,alertStatus,securityTally,ltgCount,connectedRtgCount) · `LocalGrid` (index,name,parentRtgName,alertStatus,securityTally,hostCount,pltgCount) · `PrivateGrid` (index,name,owner,parentLtgName,securityCode,alertStatus,hostCount) · `HostNode` (index,name,topologyType,offline,alertStatus,securityCode,securityTally) · `HostSubsystem` (index,subsystemType,description) · `IcProgram` (index,name,analyzed,rating,behavior,guardedNodeType) · `File` (index,name,isScrambleProtected,isPointer,sizeMp) · `Device` (index,name,systemAddress).
Enums: `AlertStatus='NO_ALERT'|'PASSIVE_ALERT'|'ACTIVE_ALERT'` (L217); `SecurityCode='BLUE'|'GREEN'|'ORANGE'|'RED'` (L218); `TopologyType='OPEN_ACCESS'|'TIERED'|'HOST_HOST'|'PRIVATE_GRID'` (L219); `SubsystemType='ACCESS'|'CONTROL'|'INDEX'|'FILES'|'SLAVE'` (L220). `IcProgram.behavior` = `'PROACTIVE'|'REACTIVE'|null` (L228).

### `AvailableActionDto` discriminated union `kind` variants (L234–241)
`LogonToRtg`(rtgName) · `LogonToLtg`(ltgName) · `LogonToPltg`(pltgName) · `LogonToHost`(hostName) · `GracefulLogoff` · `JackOut` · `Operation`(operation:SystemOperation, targetKind:string|null, targetName:string|null, paramKind).
All carry `index:number` and `actionType:ActionType` where `ActionType='FREE'|'SIMPLE'|'COMPLEX'` (L232).
`Operation.paramKind` enum = `"precision" | "hasValidPasscode" | "scannerDeviceRating" | "newContent" | "dataSize" | null` (L241).

### `ErrorCode` enum + human-readable map (L259–268, L417–426)
`not_your_turn`→"Not your turn", `no_action_pending`→"No action pending", `already_registered`→"Already registered", `name_already_taken`→"Decker name already taken", `name_too_long`→"Decker name too long (max 32 characters)", `unknown_message_type`→"Unknown message type", `bad_request`→"Bad request", `server_full`→"Server at capacity".

### Hook state model & lifecycle (L281–319)
- `WsState` = `{ connected:boolean; role:Role|null; deckerName:string|null; gameState:StateMessage|null; events:GameEvent[] }`; `events` capped at last 20 (L297–304).
- Connection lifecycle (L285–293): connect → receive `ControlMessage(role="observer")` → send `JoinMessage` → receive `ControlMessage(role="registered_decker"|error)` → wait for `StateMessage`; `active_controller` enables action submission, other roles display state only.
- Reconnect (L309): on `onclose`/`onerror` schedule reconnect after 3 s, exponential backoff up to 30 s; auto re-send `JoinMessage` if `deckerName` known. Guards (L311–313): (1) only reconnect if hook still CONNECTING/OPEN, not after manual `disconnect()`; (2) before manual `ws.close()` set `ws.onclose=null` and `ws.onerror=null`.
- `reconnectToken` flow (L315): stored on `ControlMessage(role="registered_decker", reconnectToken)`; included in `JoinMessage` on reconnect; cleared on deliberate logout.
- `sendAction(index, params?)` — only callable when `role==="active_controller"`; sends `ActionCommand` (L317–319).

### Rendering rules
- DeckerPanel sections top→bottom (L325–342): identity header (blinking `⚠ PINNED BY BLACK IC` badge red if `isPinnedByBlackIc`); condition monitors PHYS/MENT box rows with `damage/max`; `HACKING POOL: Nd`; `MCP RATING: N`; programs table (bullet dots/rating bar).
- LocationPanel (L344–361): parse `decker.location` by prefix — `"RTG: "`→GridNode, `"LTG: "`→LocalGrid, `"PLTG: "`→PrivateGrid, `"Host: "`→HostNode, `"not jacked in"`→`[ NOT JACKED IN ]`. Display all matched-object fields as horizontal labelled strip; alert status drives color.
- EntitiesPanel (L363–375): only `HostSubsystem`,`IcProgram`,`File`,`Device` shown (location kinds excluded); focused entity = first card at 1.5× font, others compact; clicking promotes to focus; `[ NO ENTITIES VISIBLE ]` if empty. IcProgram: name + `[ANALYZED]`/`[UNKNOWN]` badge, and only when `analyzed===true` render rating/behavior/guardedNodeType. HostSubsystem: subsystemType, description. File: name, sizeMp, `[SCRAMBLED]` if isScrambleProtected, `[POINTER]` if isPointer. Device: name, systemAddress.
- ActionsPanel (L377–408): horizontal card row. Label formatting — Logon variants `LOGON <NETWORK TYPE>: <name>`; Operation cards formatted operation name; others (`GRACEFUL LOGOFF`,`JACK OUT`) name directly. Cost badge top-right: FREE green / SIMPLE amber / COMPLEX red. Operation cards show `▸ targetName` when target present. Inline control chosen by `paramKind` (L386–395). Pressing card (or confirm on EDIT_FILE) calls `sendAction(card.index, params)`. When `role!=="active_controller"` all cards dimmed/non-interactive.
- Inline-control per-operation specs (L399–405): LOCATE_FILE/LOCATE_SLAVE/LOCATE_ACCESS_NODE = SEARCH TERM text input + five-position precision selector (NORMAL default); MAKE_COMCALL = `VALID PASSCODE:[ ]` toggle; TAP_COMCALL = `SCANNER RATING:[−]0[+]` stepper; EDIT_FILE = text area, empty = erase file.
- NarrativePanel (L410–428): scrollable log newest at bottom; Result entries `[✓ SUCCESS]`/`[✗ FAILURE]` + dice counts + details; Error entries `[ERROR] <human-readable>`. Middle panel border pulses green when `role==="active_controller"`.

### Theme/CSS constants (L96–122, L452–462)
CSS vars `--green #00ff41`, `--green-dim #00b32c`, `--green-faint #003a0f`, `--bg #000`, `--bg-panel #020c02`, `--red-alert #ff2020`, `--amber #ffb000`. Alert colors: NO_ALERT green, PASSIVE_ALERT amber, ACTIVE_ALERT red+`blink 1s`. Active controller: middle panel `border:2px solid var(--green)` + `pulse-border 0.8s`. Keyframes `blink`, `pulse-border` (L453–461).

### Infra spec (L46–91)
Vite `outDir` = `../build/resources/main/static` (L46, L75). Gradle tasks `buildFrontend`, `copyFrontendBuild` (dependsOn buildFrontend), `processResources dependsOn copyFrontendBuild` (L54–73). Ktor `staticResources("/", "static"){ default("index.html") }` before webSocket route (L83–90). WS endpoint `ws://localhost:8080/decker/ws` (L90).

---

## Candidate findings

**DOC-1 — `dataSize` paramKind has no carrier field in `ActionParams`.**
L394 (inline-control map) lists `| `dataSize` | Numeric stepper (Mp) |` and L241 declares `paramKind: "...|"dataSize"|null`, but `ActionParams` (L172–179) defines no `dataSize` field. Verbatim L394: `` | `"dataSize"` | Numeric stepper (Mp) | `` — verbatim L172–179 fields: `newContent`, `precision`, `hasValidPasscode`, `scannerDeviceRating`, `inactivitySeconds`, `query` (no `dataSize`). The advertised paramKind cannot be sent by the documented `ActionParams` shape.

**DOC-2 — SEARCH TERM text value routing is inconsistent between `query` field and `precision` paramKind.**
L401 says all three LOCATE ops render a `[SEARCH TERM]` text input, but their advertised paramKind is `"precision"` (L390: `` | `"precision"` | Text input (SEARCH TERM) + five-position selector | ``). The only text field plausibly carrying the search term is `query?` (L178: `query?: string;  // search query string for LOCATE_ACCESS_NODE`), which is documented for LOCATE_ACCESS_NODE only — not for LOCATE_FILE / LOCATE_SLAVE. So the text input for LOCATE_FILE/LOCATE_SLAVE has no documented `ActionParams` field, and the `query` field is scoped narrower than the control that produces it.

**DOC-3 — `inactivitySeconds` in `ActionParams` is orphaned — no paramKind, no operation maps to it.**
L177: `inactivitySeconds?: number;`. No `paramKind` value references it (L241 enum), and no operation in the inline-control tables (L388–405) uses it. Appears to be a stale/leftover field with no UI dispatch path.

**DOC-4 — `GameEvent` type used in `WsState` is never defined in the messages.ts listing.**
L303: `events: GameEvent[];  // GameEvent wraps a result or error; capped at last 20`. The `messages.ts` type section (L163–277) defines no `GameEvent` type; the comment describes it but the union/interface is absent from the doc's authoritative type block. Later frontend audit must confirm the actual `GameEvent` definition.

**DOC-5 — `copyFrontendBuild` Gradle task documented as redundant, contradicting its own inclusion.**
L46 states Vite outputs to `../build/resources/main/static` "so the JAR picks it up"; L75: "Vite `outDir` is set to `../build/resources/main/static` so the copy task is actually redundant but kept for clarity." The doc simultaneously prescribes `copyFrontendBuild` (L64–68) copying `frontend/dist`→`resources/main/static` while stating the Vite outDir already lands output there (a different source path than `frontend/dist`). The two mechanisms disagree on the source directory (`frontend/dist` vs Vite outDir); at most one is correct.

**DOC-6 — `locationIndex` is a documented permanent stub (always 0), and LocationPanel prefers brittle name-prefix parsing over it.**
L214: `locationIndex: number | null; // ... currently always 0 when jacked in (stub)`. L353 acknowledges the fragility of prefix-matching on the display `name` and says index-based lookup is "a future improvement." The design ships a stub field the panel is told not to use, and the panel's chosen parse strategy (L346–351) is self-described as brittle. Flag for later comparison against actual `LocationPanel.tsx` / server DTO behavior.
