# Spec Baseline

Built from: prd_core.md (481 lines), prd_game.md (39 lines), prd_ui.md (160 lines), protocol.md (213 lines), combat.md (772 lines), creation.md (319 lines), cyberdeck_and_program_mechanics.md (631 lines), game.md (448 lines), design_ui.md (476 lines), design.md (140 lines), start.md (35 lines), missing.md (143 lines), movement.md (370 lines), operations.md (908 lines), ord.md (612 lines).

Session: 2026-09-04. Supplements but never replaces reading the actual docs each session.

---

## Enum Variants

### SecurityCode
`BLUE`, `GREEN`, `ORANGE`, `RED`

### AlertStatus
`NO_ALERT`, `PASSIVE_ALERT`, `ACTIVE_ALERT`

### DamageLevel
`LIGHT` (=1 box), `MODERATE` (=3 boxes), `SERIOUS` (=6 boxes), `DEADLY` (=10 boxes)

### TopologyType
`OPEN_ACCESS`, `TIERED`, `HOST_HOST`, `PRIVATE_GRID`

### SubsystemType
`ACCESS`, `CONTROL`, `INDEX`, `FILES`, `SLAVE`

### PersonaAttributeType (Crippler/Ripper targets)
`BOD` (Acid/Acid-Rip), `EVASION` (Binder/Bind-Rip), `SENSOR` (Jammer/Jam-Rip), `MASKING` (Marker/Mark-Rip)

### PersonaStatus
`LEGITIMATE`, `INTRUDING`

### IcBehavior
`PROACTIVE`, `REACTIVE`

### OperationCategory
`STANDARD`, `INTERROGATION`, `ONGOING`, `MONITORED`

### QueryPrecision (enum + TN modifier)
`VERY_VAGUE` (+2), `VAGUE` (+1), `NORMAL` (0), `SPECIFIC` (-1), `VERY_SPECIFIC` (-2)

### NullOperationModifier (enum + bonus to host SV)
`UNDER_TEN_SECONDS` (0), `TEN_SECONDS_TO_ONE_MINUTE` (+1), `ONE_MINUTE_TO_ONE_HOUR` (+2), `ONE_HOUR_TO_TWELVE_HOURS` (+4); beyond 12hr: +1 per additional 12hr

### ActionType (wire)
`FREE`, `SIMPLE`, `COMPLEX`

### Role (wire)
`observer`, `registered_decker`, `active_controller`

### HitcherJackType
`ELECTRODE_NET`, `DATAJACK_FEED`

### IcDetectionResult thresholds
0 successes → Undetected; 1 → PresenceOnly; 2 → TypeKnown; 3+ → FullyLocated

---

## Formulas

| Formula | Source |
|---|---|
| `hackingPool = floor((intelligence + mpcp) / 3)` | prd_core.md |
| `detectionFactor = ceil((masking + sleaze.currentRating) / 2)` if Sleaze active; else `ceil(masking / 2)` | prd_core.md CD-18 |
| `effectiveDetectionFactor = max(2, detectionFactor - suppressionDfPenalty)` | prd_core.md CD-18a |
| `personaReaction = baseReaction + (responseIncrease × 2)` | prd_core.md |
| `actionsPerTurn = ceil(personaReaction / 10.0) + responseIncrease` | SO-01/SO-02 |
| Initiative decker: `personaReaction + (1 + responseIncrease)D6` | prd_core.md |
| Initiative IC: `ndDice D6 + ic.rating`; n = 1/2/3/4 for BLUE/GREEN/ORANGE/RED | CC-05 |
| `uploadTurns = ceil(utility.mpSize / ioSpeedMpPerTurn)` | CD-10 |
| `mpSize = rating² × multiplier` | prd_core.md |
| `stage(base, net) = clamp(base + net/2, LIGHT, DEADLY)` (integer div, toward zero) | combat.md |
| `DamageLevel.boxes`: LIGHT=1, MODERATE=3, SERIOUS=6, DEADLY=10 | combat.md |
| `responseincrease ≤ min(3, floor(mpcp / 4))` | CD-02 |
| Interrogation `locateFile/locateAccessNode` threshold: 5 accumulated successes | SO-05 |
| Interrogation `locateSlave` threshold: 3 accumulated successes | SO-07 |
| Attack TN: Intruding: BLUE=6, GREEN=5, ORANGE=4, RED=3; Legitimate: BLUE=3, GREEN=4, ORANGE=5, RED=6 | CC-24 |
| Medic TN: 1–3 boxes=4, 4–6 boxes=5, 7–9 boxes=6, 10 boxes=cannot use | cyberdeck_and_program_mechanics.md |
| Dump shock: Power = host security value; Level: BLUE=LIGHT, GREEN=MODERATE, ORANGE=SERIOUS, RED=DEADLY | CC-32 |
| `sparkyMpcpTn = max(2, hardening + mcpRating + 2)` | combat.md |
| `blasterMpcpTn = max(2, hardening + mcpRating)` | combat.md |
| Crippler floor: `max(1, attribute - reduction)` | combat.md |
| Ripper floor: `max(0, attribute - reduction)` (can hit 0, triggers MPCP test) | combat.md |
| `locationCycleTurns = ceil(10.0 / netSuccesses)` | combat.md |
| Hacking Pool: NOT usable in Body/Willpower tests resisting gray/black IC physical damage | prd_core.md |
| `responseIncrease`: each point adds +2 Reaction and +1D6 Initiative | prd_core.md |

---

## Domain Types and Their Fields

### Decker
Fields: `intelligence`, `body`, `willpower`, `reaction`, `computerSkill`, `currentLocation`, `persona`, `cyberdeck`, `jackpoint`, `blackIcPin`, `trackState`, `suppressedIc`, `detectedIcons`, `interrogationStates`
- `suppressionDfPenalty = suppressedIc.size`
- `isPinnedByBlackIc = blackIcPin != null`
- `meatworldComm: Boolean` (affects initiative group)
- `interrogationStates: Map<String, InterrogationState>` — key format `"OPERATION_NAME@CONTEXT"` (e.g. `"LOCATE_FILE@HOST"`, `"LOCATE_ACCESS_NODE@GRID"`)

### Cyberdeck
Fields: `mpcp`, `hardening`, `activeMemoryMp`, `storageMemoryMp`, `ioSpeedMpPerTurn`, `responseIncrease`, `activeUtilities`, `storedUtilities`, `pendingUploads`, `isCyberterminal`
- Cyberterminal constraint: `mpcp ≤ 4`, `responseIncrease = 0` always, programs run at −1 rating
- `usedActiveMemoryMp = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }`

### Persona
Fields: `bod`, `evasion`, `masking`, `sensor`, `conditionMonitor`, `status`, `reaction`, `currentNode`
- All four attributes derived from PersonaProgram `currentRating` (NOT `rating`)
- `reaction = baseReaction + (responseIncrease × 2)` (persona reaction, not physical)
- Physical enhancements do NOT affect matrix initiative

### PersonaProgram (extends Program)
Fields: `rating` (immutable), `attributeType`
- Always firmware-resident; NOT in `activeUtilities`; do NOT consume Active Memory
- Constraints: each `≤ mpcp`; `sum of all four ≤ mpcp × 3`

### Utility (extends Program)
Fields: `rating` (immutable stored), `currentRating` (starts = `rating`, decremented by degradation), `sourceCode: Boolean = false`, `attackDamageLevel: DamageLevel?`
- All game effects use `currentRating`
- `mpSize = rating² × multiplier` (uses immutable `rating`)
- Degradation: Armor −1 per damage bleed-through (CD-19); Medic −1 per invocation (CD-20)
- `currentRating == 0` → auto-unload from both `activeUtilities` AND `storedUtilities`

### Utility Multipliers (operational/special/offensive/defensive)
- Analyze ×3, Browse ×1, Commlink ×1, Deception ×2, Decrypt ×1, Read/Write ×2, Relocate ×2, Scanner ×3, Spoof ×3
- Sleaze ×3, Track ×8
- Attack(LIGHT)×2, Attack(MODERATE)×3, Attack(SERIOUS)×4, Attack(DEADLY)×5, BlackHammer×20, Killjoy×10, Slow×4
- Armor ×3, Cloak ×3, LockOn ×3, Medic ×4

### PendingUpload
Fields: `utility`, `turnsRemaining`
- Counts against Active Memory immediately
- No game-mechanical effect until transitioned to `activeUtilities`

### LoadUtilityResult (sealed)
`Success(decker)`, `InsufficientMemory(decker, requiredMp, availableMp)`

### Host
Fields: `securityRating` (code + value), `subsystemRatings` (access/control/index/files/slave), `securityTally`, `alertStatus`, `topologyType`, `offline`, `nodes`, `icPrograms`, `dataFiles`, `remoteDevices`, `sans`, `securitySheaf`, `connectedHosts`
- Exactly 5 nodes (one per SubsystemType)
- Passive Alert: all 5 subsystem ratings +2 permanently for session; not reversed if tally drops

### Grid (RTG / LTG / PLTG)
Fields: `securityRating` (code + value), `securityTally`, `alertStatus`, `subsystemRatings` (access/control/index/files/slave)
- LTG ratings default to parent RTG unless overridden
- PLTGs under RTG (not under LTG) in config: replicated to all child LTGs at load time

### MatrixLocation (sealed)
`OnLTG(ltg)`, `OnRTG(rtg)`, `OnPLTG(pltg)`, `OnHost(host)`, null = jacked out

### Node
Fields: `subsystemType: SubsystemType`, `description: String = ""` (default empty string)

### DataFile
Fields: `name`, `isScrambleProtected`, `isPointer`, `sizeMp`, `pointerToHost?`, `pointerTargetFile?`
- Equality: `name + isScrambleProtected + sizeMp` only (pointer fields excluded)

### RemoteDevice
Fields: `name`, `systemAddress: String`
- Device kind is free-form string, NOT a typed enum

### SAN
May be guarded by Scramble IC (0:1).

### SecuritySheaf
Ordered list of TriggerSteps. Multiple trigger steps crossed at once → ALL fire simultaneously.

### TriggerStep
Fields: `tallyThreshold`, `description`, `activatedIc`, `alertTransition: AlertStatus?`, `securityDeckerCount: Int = 0`

### Jackpoint
Types: `LEGAL_ACCESS`, `ILLEGAL_ACCESS`, `TELECOM`, `ILLEGAL_JUNCTION_BOX`, `WORKSTATION`, `REMOTE_DEVICE`, `CONSOLE`

### BlackIcPinState
Field: `pinningIc: BlackIC`

### TrackState
Fields: `trackingIcRating`, `locationCycleTurnsRemaining`, `opponentSensorRating`, `trackerMcpRating`
- Decrement `locationCycleTurnsRemaining` in `advanceCombatTurn()`; null at 0

### IcSuppressionState
Fields: `ic`, `icRating`

### DownloadHandle
Fields: `file`, `totalMp`, `ioSpeedMpPerTurn`, `turnsRemaining`, `active: Boolean = true`

### UploadHandle
Fields: `file`, `totalMp`, `ioSpeedMpPerTurn`, `turnsRemaining`, `active: Boolean = true`

### MonitoredOperationHandle
Fields: `operation`, `target: MonitoredTarget`, `active: Boolean = true`, `needsMaintenance: Boolean = false`

### InterrogationState
Fields: `operation`, `query`, `accumulatedSuccesses: Int = 0`
- Accumulated total never decreases (negative net contributes 0)

### PointerChain
Fields: `links: List<Host>` (1D6 length), `finalFile: DataFile`

### MedicResult
Fields: `updatedDecker`, `boxesRepaired`, `medicRating`

### MovementResult (sealed classes in MovementResult.kt)
LogonResult: `Success(decker, location, deckerSuccesses, hostSuccesses)`, `Failure(decker, location?, deckerSuccesses, hostSuccesses)`
LogoffResult: `GracefulSuccess(decker)`, `JackOut(decker, dumpShock: Boolean)`

### SystemTestOutcome
Fields: `deckerSuccesses`, `hostSuccesses`, `deckerWins`
- `deckerWins = deckerSuccesses >= hostSuccesses`

### ConditionMonitor
Fields: `damage`, `maxBoxes` (=10)
- `isCrashed = damage >= maxBoxes`

### CombatInitiative
Fields: `score`, `initiativePasses`

### AttackResult (sealed)
`Hit(attackerSuccesses, rawDamageLevel, stagedDamageLevel, rawWeaponPower, power)`, `Miss`

### IcDamageResult
Fields: `updatedDecker`, `iconDamage`, `simsenseOverload?`, `dumpShockTriggered`, `mpcpReductionOnKill = 0`, `personaOnlyCrashed = false`

### CripplerResult
Fields: `updatedDecker`, `targetAttribute`, `reduction`

### TarBabyResult
Fields: `updatedDecker`, `bothCrashed`, `deckerNoticed`

### SlowResult
Fields: `actionsLost`, `icInert`

### SimsenseOverloadResult
Fields: `willpowerTestPassed`, `stressBoxesApplied`

### JackOutPinResult
Fields: `succeeded`, `finalIcAttackTriggered`

### ManeuverResult (sealed)
`Success(netSuccesses)`, `Failure`

### ManeuverParticipant
Fields: `evasion`, `sensor`, `cloakRating = 0`, `lockOnRating = 0`, `hackingPool = 0`

### AttackParticipant
Fields: `attackDicePool`, `weaponPower = attackDicePool`, `hackingPool = 0`, `rawDamageLevel`, `modifiers = CombatModifiers()`

### DefenderParticipant
Fields: `bod`, `armorCurrentRating = 0`, `personaStatus`, `securityCode`

### CombatModifiers
Fields: `parryAttackBonus = 0`, `positionAttackTnBonus = 0`, `positionAttackPowerBonus = 0`
- Invariant: `positionAttackTnBonus == 0 || positionAttackPowerBonus == 0`

### ActionResult (sealed)
`IcAttack(message)`, `IcMoved(message)`, `NoTarget`, `DeckerAction`

### ActiveIconState
Fields: `icon`, `currentInitiative`

### GameContext
Fields: `host`, `securityCode`, `deckers`, `matrix`, `activeIc`
- Methods: `checkTriggers`, `applyDeckerOperationResult`, `addToSecurityTally`, `updateDecker`, `updateHost`, `addIc`, `removeIc`

### DeckCatalogEntry
Fields: `model`, `mpcp`, `hardening`, `activeMemoryMp`, `storageMemoryMp`, `ioSpeedMpPerTurn`, `costNuyen`
- `responseIncrease` absent by design

### DownloadDestination (sealed, deferred)
`ActiveMemory`, `StorageMemory`, `OfflineStorage(accessory)`

### Accessory (sealed)
`OfflineStorage(capacityMp)`, `VidScreen`, `HitcherJack(type: HitcherJackType)`

---

## SystemOperation — Complete Table

| Constant | testType | utility | actionType | category |
|---|---|---|---|---|
| ANALYZE_HOST | CONTROL | ANALYZE | COMPLEX | STANDARD |
| ANALYZE_IC | CONTROL | ANALYZE | FREE | STANDARD |
| ANALYZE_ICON | CONTROL | ANALYZE | FREE | STANDARD |
| ANALYZE_SECURITY | CONTROL | ANALYZE | SIMPLE | STANDARD |
| ANALYZE_SUBSYSTEM | null (dynamic) | ANALYZE | SIMPLE | STANDARD |
| CONTROL_SLAVE | SLAVE | SPOOF | COMPLEX | MONITORED |
| EDIT_SLAVE | SLAVE | SPOOF | COMPLEX | MONITORED |
| MONITOR_SLAVE | SLAVE | SPOOF | SIMPLE | MONITORED |
| DECRYPT_ACCESS | ACCESS | DECRYPT | SIMPLE | STANDARD |
| DECRYPT_FILE | FILES | DECRYPT | SIMPLE | STANDARD |
| DECRYPT_SLAVE | SLAVE | DECRYPT | SIMPLE | STANDARD |
| DOWNLOAD_DATA | FILES | READ_WRITE | SIMPLE | ONGOING |
| EDIT_FILE | FILES | READ_WRITE | SIMPLE | STANDARD |
| UPLOAD_DATA | FILES | READ_WRITE | SIMPLE | ONGOING |
| LOCATE_ACCESS_NODE | INDEX | BROWSE | COMPLEX | INTERROGATION |
| LOCATE_DECKER | INDEX | SCANNER | COMPLEX | STANDARD | ← DEFERRED (excluded from availableActions) |
| LOCATE_FILE | INDEX | BROWSE | COMPLEX | INTERROGATION |
| LOCATE_IC | INDEX | ANALYZE | COMPLEX | STANDARD |
| LOCATE_SLAVE | INDEX | BROWSE | COMPLEX | INTERROGATION |
| MAKE_COMCALL | FILES | COMMLINK | COMPLEX | MONITORED |
| TAP_COMCALL | null (dynamic) | COMMLINK | COMPLEX | MONITORED |
| NULL_OPERATION | CONTROL | DECEPTION | COMPLEX | STANDARD |
| RELOCATE_ICON | CONTROL | RELOCATE | SIMPLE | STANDARD |
| INVOKE_MEDIC | CONTROL | null | COMPLEX | STANDARD |
| SWAP_MEMORY | — | — | SIMPLE | ONGOING | ← DEFERRED (excluded from availableActions) |
| GRACEFUL_LOGOFF | ACCESS | DECEPTION | COMPLEX | STANDARD |

---

## Wire Field Names (protocol.md)

### Message type discriminators
`control`, `state`, `result`, `error`, `join`, `action`

### ControlMessage fields
`type`, `role`, `deckerName?`, `reconnectToken?`
- `reconnectToken` only on `registered_decker` role

### StateMessage fields
`type`, `role`, `decker`, `visibleObjects`, `availableActions`

### ResultMessage fields
`type`, `success`, `deckerSuccesses`, `hostSuccesses`, `details`
- `deckerSuccesses` and `hostSuccesses` always present, never null

### ErrorMessage fields
`type`, `message` (ErrorCode), `details?`

### JoinMessage fields
`type`, `deckerName`, `reconnectToken?`

### ActionCommand fields
`type`, `actionIndex`, `params?`

### DeckerStateDto fields
`name`, `location`, `locationIndex`, `isPinnedByBlackIc`, `mcpRating`, `hackingPool`, `activeUtilities`, `physicalDamage`, `mentalDamage`, `physicalMaxBoxes`, `mentalMaxBoxes`

### MatrixObjectDto kinds
`GridNode`, `LocalGrid`, `PrivateGrid`, `HostNode`, `HostSubsystem`, `IcProgram`, `File`, `Device`

### MatrixObjectDto fields by kind
- GridNode: `kind`, `index`, `name`, `region`, `securityCode`, `alertStatus`, `securityTally`, `ltgCount`, `connectedRtgCount`
- LocalGrid: `kind`, `index`, `name`, `parentRtgName`, `alertStatus`, `securityTally`, `hostCount`, `pltgCount`
- PrivateGrid: `kind`, `index`, `name`, `owner`, `parentLtgName`, `securityCode`, `alertStatus`, `hostCount`
- HostNode: `kind`, `index`, `name`, `topologyType`, `offline`, `alertStatus`, `securityCode`, `securityTally`
- HostSubsystem: `kind`, `index`, `subsystemType`, `description` (rating omitted)
- IcProgram: `kind`, `index`, `name`, `analyzed`, `rating?`, `behavior?`, `guardedNodeType?`
- File: `kind`, `index`, `name`, `isScrambleProtected`, `isPointer`, `sizeMp`
- Device: `kind`, `index`, `name`, `systemAddress`

### AvailableActionDto kinds and fields
- LogonToRtg: `kind`, `index`, `actionType`, `rtgName`
- LogonToLtg: `kind`, `index`, `actionType`, `ltgName`
- LogonToPltg: `kind`, `index`, `actionType`, `pltgName`
- LogonToHost: `kind`, `index`, `actionType`, `hostName`
- GracefulLogoff: `kind`, `index`, `actionType`
- JackOut: `kind`, `index`, `actionType`
- Operation: `kind`, `index`, `actionType`, `operation`, `targetKind?`, `targetName?`, `paramKind?`

### paramKind values
`"precision"`, `"hasValidPasscode"`, `"scannerDeviceRating"`, `"newContent"`, `"dataSize"`, null

### ErrorCode values
`not_your_turn`, `no_action_pending`, `already_registered`, `name_already_taken`, `name_too_long`, `unknown_message_type`, `bad_request`, `server_full`

### MAX_CONNECTIONS = 32

---

## Key Constraints / Invariants

| Constraint | Rule |
|---|---|
| `responseIncrease ≤ min(3, floor(mpcp/4))` | CD-02; re-clamp after any MPCP reduction |
| `sum(personaPrograms) ≤ mpcp × 3` | CD-01 |
| `utility.rating ≤ mpcp` | CD-01 |
| After MPCP → 0 and data was downloaded: delete all downloaded data | ICC-11/ICC-12 |
| Persona programs NOT in activeUtilities, NOT counted against Active Memory | CD-04 |
| Sleaze: passive; contributes to DF only when fully in activeUtilities | CD-17 |
| DF recalculated at each System Test (not cached) | CD-18 |
| Track IC: raises Graceful Logoff TN by `trackingIcRating` | CC-33 |
| `interrogationStates` cleared on logoff/jackout/dump | prd_game.md |
| Hacking Pool: NOT in Body/Willpower tests vs. gray/black IC physical damage | prd_core.md |
| Passive Alert: subsystem +2 not reversed if tally drops | AL-01 |
| Trigger steps crossing multiple thresholds simultaneously: all fire | AL-01 |
| Probe successes added to security tally IMMEDIATELY (ICC-03) | prd_core.md |
| Crashing IC: add ic.rating to tally UNLESS suppressed | CC-22 |
| IC rating escalation: +2 on persona-only crash (NonLethal + Lethal) | game.md |
| `dumpShock = !decker.cyberdeck.isCyberterminal` in jackOut/gracefulLogoff fail | CT-04 |
| `Node.description` default = `""` | design.md |
| Decker name max 32 characters | protocol.md |
| Reconnect timeout: 3s initial, 30s cap | design_ui.md |
| events capped at 20 | design_ui.md |
| Turn timeout: 120 seconds | protocol.md |
| Locate File / Locate Access Node threshold: 5 successes | SO-05 |
| Locate Slave threshold: 3 successes | SO-07 |
| Deferred: SWAP_MEMORY, LOCATE_DECKER excluded from availableActions | deferred.md |
| `locationIndex` always 0 when jacked in (stub) | deferred.md #4 |
| `detectedIcons` never populated (deferred) | deferred.md #12 |
| Scramble IC reactive trigger not wired up (deferred) | deferred.md #13 |

---

## Deferred Items (do not flag as discrepancies)

1. Decker action callback — `Decker.action()` returns `DeckerAction` immediately (no-op)
2. `SWAP_MEMORY` — excluded from `availableActions`
3. `LOCATE_DECKER` — excluded from `availableActions`
4. `locationIndex` — always 0 when jacked in
5. `source_code: true` utilities — parsed and stored but upgrade/modification deferred
6. `DownloadHandle.destination` — `OfflineStorage` routing not wired
7. `ANALYZE_ICON` for File and Device targets — only IcProgram supported
8. Black IC companion plug-pull scenario (ICC-10)
11. Security decker spawning in GameContext (GC-2)
12. `detectedIcons` persistence — never populated; `visibleObjects` shows all IC unconditionally
13. Scramble IC reactive trigger — `Scramble.action()` is no-op
