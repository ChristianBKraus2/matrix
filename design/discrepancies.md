# Design vs Implementation — Discrepancy Audit

Each entry compares a non-PRD design document to the implementation, then notes whether the PRD
(`prd_core.md`, `prd_game.md`, `prd_ui.md`) supports the **design**, the **code**, or **neither**.

---

## Object Model (`ord.md`)

### D-01 — `Host` is not a subtype of `Grid`
**Design (`ord.md`):** `Grid <|-- Host` — Host is listed as a Grid subtype ("abstract base; subtypes: RTG, LTG, PLTG, Host"), inheriting all Grid fields.  
**Code:** `Host` is a separate `data class`; the `Grid` sealed hierarchy contains only `RTG`, `LTG`, `PLTG`.  
**PRD:** PRDs describe Grid and Host properties independently without specifying an inheritance relationship. **→ neither**

**Todo:** Update Design

**Status:** Resolved — removed `Host` from Grid subtype list in ord.md text, classDiagram, and flowchart; gave Host its own full field set.

---

### D-02 — `RemoteDevice` missing `DeviceType` field
**Design (`ord.md`):** `DeviceType: Camera | Door | Elevator | FactorySystem | MedicalScanner | Other`  
**Code:** `data class RemoteDevice(val name: String, val systemAddress: String)` — no `DeviceType`.  
**PRD (`prd_core.md`):** Does not mention a DeviceType enum on RemoteDevice. **→ supports code**

**Todo:** Update Design to clarify that these are examples. The code is string

**Status:** Resolved — replaced `DeviceType` enum with `SystemAddress: string` in ord.md text and ER diagram; added note that device kind labels are free-form strings.

---

### D-03 — `DataFile` missing `isEncoded` field
**Design (`ord.md`):** `IsEncoded: bool` — "file is encrypted; decker must succeed at Decrypt File before downloading or editing."  
**Code:** `DataFile` has `isScrambleProtected`, `pointerToHost`, `sizeMp` — no `isEncoded`.  
**PRD (`prd_core.md`):** Defines the Decrypt File operation but does not define `DataFile` object model fields. **→ neither**

**Todo:** Update Design

**Status:** Resolved — removed `IsEncoded` field from DataFile in ord.md text and ER diagram.

---

### D-04 — `DataFile` missing `pointerTargetFile` field
**Design (`ord.md`):** `PointerTargetFile: DataFile?` — the specific file on the target host, enabling true pointer-chain traversal (file → file, not just file → host).  
**Code:** `DataFile` only carries `pointerToHost: Host?`; no per-file pointer target.  
**PRD (`prd_core.md` SO-03/04):** Describes pointer-chain traversal but does not specify the data model fields. **→ neither**

**Todo:** Update Code

**Status:** Resolved — added `pointerTargetFile: DataFile? = null` to `DataFile.kt`; updated ord.md Implementation Notes to note it is excluded from equals/hashCode.

---

### D-05 — `Scramble` IC missing `ProtectionScope` field
**Design (`ord.md`):** `ProtectionScope: SAN | DataFile | DataStore | FilesSubsystem | RemoteDevice | SlaveSubsystem | AccessEntry`  
**Code:** `class Scramble(rating: Int, guardedNode: Node? = null)` — no `ProtectionScope`.  
**PRD (`prd_core.md` ICC-04):** Describes Scramble mechanics but does not require a typed `ProtectionScope` enum. **→ supports code**

**Todo:** Update Design

**Status:** Resolved — removed `ProtectionScope` from Scramble in ord.md text and ER diagram.

---

### D-06 — `Cyberterminal`: inheritance (ord.md) vs. factory function (cyberdeck doc)
**Design (`ord.md`):** `Cyberdeck <|-- Cyberterminal` — inheritance shown in ER diagram.  
**Design (`cyberdeck_and_program_mechanics.md`):** "Cyberterminal is a standalone file — not a subclass of Cyberdeck. Because `Cyberdeck` is a `data class` (final in Kotlin), subclassing is not possible. Expose a factory function that constructs a `Cyberdeck`."  
**Code:** Factory function in `Cyberterminal.kt` — follows the cyberdeck doc; contradicts `ord.md`. Internal design inconsistency.  
**PRD (`prd_core.md` CT-01–05):** Defines Cyberterminal rules but not the implementation approach. **→ neither**

**Todo:** Update Ord.md

**Status:** Resolved — updated Cyberterminal description to factory pattern in ord.md text, replaced `Cyberdeck <|-- Cyberterminal` with `Cyberterminal ..> Cyberdeck : factory` in both ER diagrams.

---

### D-07 — `IC` has no explicit `Color` field
**Design (`ord.md`):** `IC` base class has `Color: White | Gray | Black` as an explicit field.  
**Code:** Color is encoded via the sealed class hierarchy (`WhiteIC` / `GrayIC` / `BlackIC`); no `color` property exists on `IC`.  
**PRD:** Does not require an explicit `color` field. **→ neither**

**Todo:** Update Design

**Status:** Resolved — removed `Color` field from IC base class in ord.md text and ER diagram; noted that color is expressed by the sealed class hierarchy.

---

## System Operations

### D-08 — `SWAP_MEMORY` has wrong test subsystem in enum
**Design (`operations.md`, `cyberdeck_and_program_mechanics.md` CD-13):** `SWAP_MEMORY` is not a `SystemOperation`; handled by `Decker.swapUtility()`. "No System Test."  
**Code:** `SWAP_MEMORY(CONTROL, null, SIMPLE, STANDARD)` — present in the enum with `CONTROL` as test subsystem.  
**PRD (`prd_core.md` SO table):** "Swap Memory | None | None | Simple" — no test, no utility. **→ supports design**

**Todo:** Update Code

**Status:** Resolved — removed `SWAP_MEMORY` from `SystemOperation` enum; removed it from the named operations list in ord.md; updated affected tests (count 29 → 27).

---

### D-09 — `LOGON_TO_PLTG` is a dead enum entry
**Design (`movement.md`):** `logonToPltg` resolves the system test with `SystemOperation.LOGON_TO_LTG` (confirmed in `DeckerNavigationExtensions.kt` line 172).  
**Code:** `LOGON_TO_PLTG(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD)` exists in `SystemOperation` but is never passed to `SystemTestResolver`. Dead code.  
**PRD:** Does not address whether PLTG navigation needs a distinct operation enum entry. **→ neither**

**Todo:** Remove dead code

**Status:** Resolved — removed `LOGON_TO_PLTG` from `SystemOperation` enum (confirmed zero refs outside the file); count updated.

---

### D-10 — `ANALYZE_SUBSYSTEM` hardcoded to `CONTROL` test subsystem
**Design (`operations.md`):** "ANALYZE_SUBSYSTEM accepts the relevant subsystem type as a runtime parameter rather than a fixed enum field, since the test type varies by context."  
**Code:** `ANALYZE_SUBSYSTEM(CONTROL, UtilityType.ANALYZE, SIMPLE, STANDARD)` — hardcoded to `CONTROL`.  
**PRD (`prd_core.md` SO table):** "Analyze Subsystem | Targeted Subsystem | Analyze | Simple" — test type is the subsystem being analyzed, not fixed to Control. **→ supports design**

**Todo:** Update Code

**Status:** Resolved — changed `testType` to `SubsystemType?`; set `ANALYZE_SUBSYSTEM(null, ...)` to express that the subsystem is determined at call time; updated `SystemTestResolver` to use `!!` for interrogation operations.

---

## Cyberdeck Mechanics

### D-11 — `DownloadDestination.OfflineStorage` typed to `Accessory` not `Accessory.OfflineStorage`
**Design (`cyberdeck_and_program_mechanics.md`):** `data class OfflineStorage(val accessory: Accessory.OfflineStorage)` — typed to the specific subclass.  
**Code:** `data class OfflineStorage(val accessory: Accessory)` — accepts any `Accessory` subtype, losing compile-time type safety.  
**PRD (`prd_core.md` ACC-01):** Does not specify the type. **→ neither**

**Todo:** Update Design

**Status:** Resolved — changed `Accessory.OfflineStorage` to `Accessory` in `cyberdeck_and_program_mechanics.md` DownloadDestination code snippet.

---

## UI / Protocol

### D-12 — Frontend declares `reconnect: boolean` on `ControlMessage`; server never sends it
**Design (`protocol.md`):** `ControlMessage` schema is `{ type, role, deckerName, reconnectToken }` — no boolean `reconnect` field.  
**Server code:** `ControlMessage(type, role, deckerName, reconnectToken)` — matches protocol.  
**Frontend (`messages.ts`):** Declares `reconnect?: boolean` on `ControlMessage`. Used in `useWebSocket.ts` to set a `reconnected` state — which is always false because the server never emits the field.  
**PRD (`prd_ui.md`):** Only mentions `reconnectToken`; no boolean reconnect flag. **→ supports design**

**Todo:** Update Code

**Status:** Resolved — removed `reconnect?: boolean` from `ControlMessage` in `messages.ts`; removed `reconnected` state from `useWebSocket.ts`; removed dead `showReconnected` effect and banner from `App.tsx`.

---

### D-13 — `MAKE_COMCALL` — `hasValidPasscode` never sent in `ActionParams`
**Design (`prd_ui.md`):** "MAKE_COMCALL Param: `hasValidPasscode` — boolean (default false). UI control: A yes/no toggle or checkbox on the card."  
**Server (`Messages.kt`):** `ActionParams.hasValidPasscode: Boolean?` is declared and consumed.  
**Frontend (`ActionsPanel.tsx`):** No toggle is rendered for MAKE_COMCALL; `buildParams()` returns `undefined` for that operation, so `hasValidPasscode` is never sent. The server always receives `null` (treated as `false`).  
**PRD (`prd_ui.md`):** Explicitly requires the toggle and the param. **→ supports design**

**Todo:** Update Code

**Status:** Resolved — added `hasValidPasscode?: boolean` to `ActionParams` in `messages.ts`; added `needsPasscode` function, `hasValidPasscode` to `CardState`, and yes/no toggle UI to `ActionsPanel.tsx`; `buildParams` now includes the field for `MAKE_COMCALL`.

---

### D-14 — `TAP_COMCALL` — scanner stepper shown in UI but `scannerDeviceRating` never sent
**Design (`prd_ui.md`):** "TAP_COMCALL Param: `scannerDeviceRating` — number (default 0). UI control: A numeric stepper on the card."  
**Frontend (`ActionsPanel.tsx`):** The stepper widget is rendered correctly for TAP_COMCALL, but `buildParams()` has no branch for `needsScanner(op)` — the value is never included in the `ActionCommand`. The server always receives `null` (defaults to 0).  
**PRD (`prd_ui.md`):** Explicitly requires the param. **→ supports design**

**Todo:** Update Code

**Status:** Resolved — added `scannerDeviceRating?: number` to `ActionParams` in `messages.ts`; added `needsScanner` branch in `buildParams` so `TAP_COMCALL` sends the stepper value.

---

### D-15 — `paramKind` sent by server but absent from frontend TypeScript types
**Design (`design_ui.md`):** The UI should read `paramKind` from the action card DTO to determine which inline controls to render.  
**Server (`AvailableActionDto.kt`):** `Operation.paramKind: String?` is populated and serialised for each action that needs inline input.  
**Frontend (`messages.ts`):** `AvailableActionDto` `Operation` type omits `paramKind`. The frontend instead uses hardcoded operation-name checks (`needsPrecision`, `needsScanner`, etc.) — functionally equivalent for the current set of operations but fragile as new operations are added.  
**PRD (`prd_ui.md`):** Does not name `paramKind` explicitly (design detail). **→ neither**

**Todo:** Update Frontend code

**Status:** Resolved — added `paramKind: string | null` to the `Operation` variant of `AvailableActionDto` in `messages.ts`.

---

## Quick Reference

| ID | Area | Summary | PRD verdict |
|---|---|---|---|
| D-01 | ord.md | `Host` not a subtype of `Grid` | neither |
| D-02 | ord.md | `RemoteDevice` missing `DeviceType` field | supports code |
| D-03 | ord.md | `DataFile` missing `isEncoded` field | neither |
| D-04 | ord.md | `DataFile` missing `pointerTargetFile` field | neither |
| D-05 | ord.md | `Scramble` missing `ProtectionScope` field | supports code |
| D-06 | ord.md / cyberdeck doc | `Cyberterminal`: `ord.md` shows inheritance, cyberdeck doc + code use factory | neither |
| D-07 | ord.md | `IC` has no explicit `Color` field | neither |
| D-08 | operations | `SWAP_MEMORY` in enum with `CONTROL` subsystem (design + PRD: None) | supports design |
| D-09 | operations | `LOGON_TO_PLTG` dead enum entry | neither |
| D-10 | operations | `ANALYZE_SUBSYSTEM` hardcoded to `CONTROL` | supports design |
| D-11 | cyberdeck | `DownloadDestination.OfflineStorage` typed to `Accessory` not `Accessory.OfflineStorage` | neither |
| D-12 | UI/protocol | Frontend `ControlMessage.reconnect` boolean never sent by server | supports design |
| D-13 | UI | `MAKE_COMCALL` `hasValidPasscode` never sent | supports design |
| D-14 | UI | `TAP_COMCALL` `scannerDeviceRating` shown but never sent | supports design |
| D-15 | UI | `paramKind` in server DTO, absent from frontend types | neither |
