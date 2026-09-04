# Iteration 2 — Design Doc Audit: System Operations

## Coverage Table

| File | Lines | Verbatim excerpts | Notes/findings |
|---|---|---|---|
| `design/design_core/operations.md` | 908 | (1) L184 `ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD),` — (2) L491 `\| Locate Slave \| ≥ **3** \|` — (3) L894 `\| Tap Comcall with 3 scanners (ratings 4, 6, 7) \| Only rating 7 used for the scanner test \|` | Read in full L1→L908. 11 candidate findings (DOC-1..DOC-11). Internal comment/enum mismatches on QueryPrecision, testType nullability, TAP_COMCALL runtime type, noticeIcon signature, richer-payload wrapper, AnalyzeHost reveal model, enum group comments, SO-13/SO-14 references, download storage target, upload handle fields. |

Excerpts are ≥50 source lines apart (184 / 491 / 894), one per third of a 908-line file.

---

## Distilled spec additions

Checkable facts (operations.md line refs). This is the reference for later `SystemOperation.kt` / `SystemTestResolver.kt` / `Decker.kt` / `DeckerOperationsExtensions.kt` iterations.

### New types & files (L17–164)
- `OperationResult` sealed class — `Success(decker: Decker, outcome: SystemTestOutcome)`, `Failure(decker, outcome)`; file `operations/OperationResult.kt` (L19–39). Success = decker ≥ hostSuccesses; Failure = host > deckerSuccesses (L28,L34).
- `AnalyzeHostResult(decker, outcome, revealedSecurityRating: SecurityRating?, revealedSubsystemRatings: Map<SubsystemType, Int>)` (L50–56). 7+ net successes fills all six fields (L58).
- `LocatedTarget` sealed: `FileTarget(file: DataFile)`, `SlaveTarget(device: RemoteDevice)`, `AccessNodeTarget(address: String)` (L67–71).
- `LocateResult` sealed: `Ongoing(accumulatedSuccesses: Int)`, `Located(target: LocatedTarget, accumulatedSuccesses: Int)`, `object NotFound` (≥3 successes, host confirms absence) (L77–84).
- `InterrogationState(operation: SystemOperation, query: String, accumulatedSuccesses: Int = 0)`; file `operations/InterrogationState.kt` (L91–101).
- `MonitoredTarget` sealed: `SlaveDevice(device: RemoteDevice)`, `ComcallHost(host: Host)` (L112–116).
- `MonitoredOperationHandle(operation, target: MonitoredTarget, active: Boolean = true, needsMaintenance: Boolean = false)`; file `operations/MonitoredOperationHandle.kt` (L127–133). `active=false` → aborted; `needsMaintenance=true` set each Initiative Pass (L135–136). Maintenance via `Decker.beginInitiativePass()` (arms) + `Decker.maintainMonitoredOperation()` (clears) (L138).
- `NullOperationModifier` enum (`bonus: Int`): UNDER_TEN_SECONDS(0), TEN_SECONDS_TO_ONE_MINUTE(1), ONE_MINUTE_TO_ONE_HOUR(2), ONE_HOUR_TO_TWELVE_HOURS(4); `totalBonusForDuration(seconds)`: <10→0, <60→1, <3600→2, else→4 (L147–161). +1 per additional 12h beyond first; applied to host Security Value not TN (L164).

### SystemOperation enum (L170–225)
- `OperationCategory { STANDARD, INTERROGATION, ONGOING, MONITORED }` (L177).
- Enum field order: `testType: SubsystemType`, `utility: UtilityType?`, `actionType: ActionType`, `category: OperationCategory` (L174).
- Full entries (testType, utility, actionType, category):
  - `ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD)` (L184)
  - `ANALYZE_IC(CONTROL, ANALYZE, FREE, STANDARD)` (L185)
  - `ANALYZE_ICON(CONTROL, ANALYZE, FREE, STANDARD)` (L186)
  - `ANALYZE_SECURITY(CONTROL, ANALYZE, SIMPLE, STANDARD)` (L187)
  - `ANALYZE_SUBSYSTEM(null, ANALYZE, SIMPLE, STANDARD)` — testType null, passed at call time (L188)
  - `CONTROL_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED)` (L191)
  - `EDIT_SLAVE(SLAVE, SPOOF, COMPLEX, MONITORED)` (L192)
  - `MONITOR_SLAVE(SLAVE, SPOOF, SIMPLE, MONITORED)` (L193)
  - `DECRYPT_ACCESS(ACCESS, DECRYPT, SIMPLE, STANDARD)` (L196)
  - `DECRYPT_FILE(FILES, DECRYPT, SIMPLE, STANDARD)` (L197)
  - `DECRYPT_SLAVE(SLAVE, DECRYPT, SIMPLE, STANDARD)` (L198)
  - `DOWNLOAD_DATA(FILES, READ_WRITE, SIMPLE, ONGOING)` (L201)
  - `EDIT_FILE(FILES, READ_WRITE, SIMPLE, STANDARD)` (L202)
  - `UPLOAD_DATA(FILES, READ_WRITE, SIMPLE, ONGOING)` (L203)
  - `LOCATE_ACCESS_NODE(INDEX, BROWSE, COMPLEX, INTERROGATION)` (L206)
  - `LOCATE_DECKER(INDEX, SCANNER, COMPLEX, STANDARD)` (L207)
  - `LOCATE_FILE(INDEX, BROWSE, COMPLEX, INTERROGATION)` (L208)
  - `LOCATE_IC(INDEX, ANALYZE, COMPLEX, STANDARD)` (L209)
  - `LOCATE_SLAVE(INDEX, BROWSE, COMPLEX, INTERROGATION)` (L210)
  - `MAKE_COMCALL(FILES, COMMLINK, COMPLEX, MONITORED)` (L213)
  - `TAP_COMCALL(FILES, COMMLINK, COMPLEX, MONITORED)` — comment "test type varies per step" (L214)
  - `NULL_OPERATION(CONTROL, DECEPTION, COMPLEX, STANDARD)` (L217)
  - `RELOCATE_ICON(CONTROL, RELOCATE, SIMPLE, STANDARD)` (L218)
  - `INVOKE_MEDIC(CONTROL, null, COMPLEX, STANDARD)` — not a System Test; testType nominal (L221)

### SystemTestResolver overloads (L228–279)
- `resolveNullOperation(decker, host, inactivitySeconds, diceRoller): SystemTestOutcome` (L237–242). Algorithm (L246–249): modifier from duration; decker base TN = `host.controlRating - deception.currentRating` floor 2; host effective Security Value = `host.securityRating.value + modifier`; roll standard.
- `resolveInterrogation(decker, operation, host, state, queryPrecision, diceRoller): Pair<SystemTestOutcome, InterrogationState>` (L254–261).
- `QueryPrecision(val modifier)`: VERY_VAGUE(+2), VAGUE(+1), NORMAL(0), SPECIFIC(-1), VERY_SPECIFIC(-2) (L267–269).
- Interrogation algorithm (L272–276): apply precision modifier to base TN (subsystem rating − utility rating, clamp ≥2); resolve; `netSuccesses = deckerSuccesses - hostSuccesses`; accumulated += `max(0, netSuccesses)` (never decreases, SO-06). Caller checks total ≥5 or host threshold (L278).

### Decker action economy (L290–295, L813–818)
- `actionsPerTurn get() = ceil(persona!!.reaction / 10.0).toInt() + cyberdeck.responseIncrease` (L292–293, L815). PRD SO-01/SO-02. `persona.reaction` already = base + Response Increase × 2 (L295,L818). Only callable when `persona != null`.

### Matrix perception (L299–378)
- `noticeIcon(icon, diceRoller): SensorTestResult` — file `decker/Decker.kt` (L301). `SensorTestResult { object Undetected; Detected(icon: Icon, successes: Int) }` (L308–311). TN: Persona → `icon.masking + icon.sleazeRating`; IC/program → `icon.rating` (L320–321). Roll `persona.sensor` dice, no utility mod (L322). 0→Undetected; ≥1→Detected (L324–325). Friendly reveal (MP-09): `friendlyReveal: Boolean` flag bypasses test, returns `Detected(icon, 1)` (L329).
- Persistent visibility (MP-04): `val detectedIcons: Set<Icon> = emptySet()` (L341); engine adds on Detected, removes on Evade Detection / leaves area / logoff (L346–351).
- `noticeTriggeredIc(ic: IC, diceRoller): IcDetectionResult` (L355). `IcDetectionResult { Undetected; PresenceOnly(successes); TypeKnown(ic, successes); FullyLocated(ic, successes) }` (L362–367). Roll `persona.sensor` vs `ic.rating`: 0→Undetected, 1→PresenceOnly, 2→TypeKnown, 3+→FullyLocated (L371–376). Made once at IC activation (MP-08, L378).

### Operation implementations (L382–745)
- `analyzeHost(host, requestedItems: List<HostInfoItem>, diceRoller): AnalyzeHostResult` (L393–397); precondition `currentLocation is OnHost` (L390). Algorithm L400–406: resolve; net = decker−host; net≤0 reveal nothing; net≥7 reveal all 6; else first `net` distinct requestedItems. `HostInfoItem { object SecurityRating; Subsystem(type: SubsystemType) }` (L411–414).
- `analyzeIcon(icon, host, diceRoller): OperationResult` (L426). Sensor-reduced TN = `max(2, host.controlRating - persona.sensor)`, then Analyze utility reduces inside resolve (floor 2) (L430–431).
- `analyzeSecurity(host, diceRoller): AnalyzeSecurityResult` (L443). `AnalyzeSecurityResult(decker, outcome, securityRating: SecurityRating, currentTally: Int, alertStatus: AlertStatus)` (L447–453).
- Locate interrogation trio (L462–482): `locateFile/locateSlave/locateAccessNode(host, query, precision, diceRoller): Pair<OperationResult, LocateResult>`. Thresholds: Locate File ≥5, Locate Access Node ≥5, Locate Slave ≥3 (L487–491). Blank query on first call → server `bad_request` (L494). ≥3 with absent target → "not found" (L498).
- `locateDecker(host, targetPersona: Persona, diceRoller): LocateDeckerResult` (L509–513). `LocateDeckerResult(decker, outcome, located: Boolean, targetNotified: Boolean)`; targetNotified always true when located (MP-10) (L517–522). Two-step: Index Test (`host.indexRating`) then open-ended Sensor Test `roll persona!!.sensor` vs `sensorTn = max(2, masking + sleazeRating)`; ≥1 success → located (L526–530).
- `downloadData(file, host, diceRoller): Pair<OperationResult, DownloadHandle?>` (L540). `DownloadHandle(file, totalMp, ioSpeedMpPerTurn, turnsRemaining, active=true)` (L548–554). Rate = `cyberdeck.ioSpeedMpPerTurn`; turns = `ceil(file.sizeMp / ioSpeedMpPerTurn)` (L557). `advanceCombatTurn()` decrements; at 0 file → `cyberdeck.storedUtilities` (L559). Abort → corrupted copy (SO-12).
- `uploadData(host, dataSizeMp: Int, diceRoller): Pair<OperationResult, UploadHandle?>` (L569). `UploadHandle(file, totalMp, ioSpeedMpPerTurn, turnsRemaining, active=true)` (L577–583). turns = `ceil(dataSizeMp / ioSpeedMpPerTurn)` (L586).
- `editFile(file, host, newContent: ByteArray?, attemptAuthentication: Boolean=false, diceRoller): EditFileResult` (L609–615). `EditFileResult(decker, outcome, authenticationSuccesses: Int?)` (L617–621). null newContent = deletion (L612). Header auth = Control Test reduced by Read/Write rating; tamper detection: checker must exceed authenticator successes, else any success detects (L603–606).
- `controlSlave(device: RemoteDevice, host, diceRoller): Pair<OperationResult, MonitoredOperationHandle?>` (L631–635). Maintain via Free Action `maintainMonitoredOperation(handle)` each pass or aborts (L638). Optional `effectiveSkill: Int?`; null → Computer Skill; process = avg(Computer, B/R or Knowledge) (L640).
- `nullOperation(host, inactivitySeconds, diceRoller): OperationResult` (L649–653). GM-initiated, uses `resolveNullOperation` (L656).
- Make/Tap Comcall (L660–676): licensed decker w/ valid passcode skips all tests — `decker.hasValidPasscode(rtg)` (L669). Tap scanner test = Computer Skill vs highest Device Rating (not sum), Commlink reduces TN floor 2, 0 succ → tap fails, ≥1 → succeeds; no security-tally effect (L662–663). Encrypt/decrypt sub-test = opposed Computer vs Device Rating, Decrypt reduces TN, +2 TN per failed try; no tally effect (L665). Tap detection = opposed Sensor vs Device Rating, decker wins on ≥ equal (L670). Persistent monitoring: tapped commcode needs no new Index Test; trace+tap still per call (L674–676).
- `relocateIcon()` = Success Contest: decker Computer Test (TN = opponent Sensor − Relocate rating), tracker MPCP vs Relocate; uses `trackState.opponentSensorRating` else `host.subsystemRatings.control` (L682–684).
- Decrypt ops (L688–720): standard System Test per target subsystem. Scramble IC destruct on failed decrypt: `resolveScrambleDestructTest(ic: Scramble, decker, file, diceRoller): ScrambleDestructResult` (L705). `ScrambleDestructResult(dataDestroyed: Boolean, icRating: Int)` (L709–712). Roll `ic.rating` vs `max(2, decker.computerSkill)`; ≥1 success → destroyed; no tally increment (L717–718, L702).
- `bufferMessage(text, recipient: LinkedObserver): BufferedMessage` (L731). `BufferedMessage(text, recipient)` (L735–738). Free Action, delivered end of Combat Turn (L741).

### Alert transitions (L749–784)
- `applyAlertTransition(host, newAlertStatus: AlertStatus): Host` (L756). PASSIVE_ALERT: all five subsystem ratings +2, permanent for session (L759–769). ACTIVE_ALERT: set status + spawn `securityDeckerCount` NPC deckers (L772). `TriggerStep(tallyThreshold, description, activatedIc=emptyList(), alertTransition: AlertStatus?=null, securityDeckerCount: Int=0)` (L775–781). `spawnSecurityDeckers(host, count, diceRoller)` (L784).

### Distributed databases (L788–805)
- `resolvePointerChain(file, diceRoller): PointerChain` (L795). `PointerChain(links: List<Host>, finalFile: DataFile)`, length = 1D6 (L799–802). Triggered when located `DataFile.isPointer == true` (L792).

### Grid-context variants (L824–862)
- `analyzeSecurity(grid, diceRoller)` uses `grid.subsystemRatings.control` TN, `grid.securityRating.value` (L829–831).
- `analyzeIc(ic, grid, ...)` — **Removed** per M-08a (IC host-resident); overload deleted (L835–837).
- `decryptAccess(grid, diceRoller)` uses `grid.subsystemRatings.access` (L839–841).
- `locateAccessNode(grid, query, precision, diceRoller): Pair<OperationResult, LocateResult>` — node pool: LTG→`ltg.hosts`, RTG→all hosts across child LTGs, PLTG→`pltg.hosts`; thresholds ≥5 locate / ≥3 absent → NotFound (L844–857).
- `locateIc(grid, diceRoller): OperationResult` uses `grid.subsystemRatings.index` (L859–861).

### Scope boundary
- Movement ops (Logon to LTG/RTG/Host, **Graceful Logoff**, Jack Out) designed in `movement.md`, NOT here (L13). Active-memory mgmt (Swap Memory, Load/Unload Utility) in `cyberdeck_and_program_mechanics.md` (L13).

---

## Candidate findings

### DOC-1 — QueryPrecision signature comment omits VERY_VAGUE and mislabels values
**Line 259** signature comment: `queryPrecision: QueryPrecision,   // VAGUE, NORMAL, SPECIFIC, VERY_SPECIFIC` — lists only 4 values. The actual enum (L267–269) has 5: `VERY_VAGUE(+2), VAGUE(+1), NORMAL(0), SPECIFIC(-1), VERY_SPECIFIC(-2)`. The verification table (L880) "Very vague query on locateFile | TN +2 applied" relies on VERY_VAGUE(+2). Comment is stale/incomplete.

### DOC-2 — `testType` declared non-nullable `SubsystemType` but entries pass `null`
**Line 174**: "The enum already carries `testType: SubsystemType`" (non-nullable). But `ANALYZE_SUBSYSTEM(null, ANALYZE, SIMPLE, STANDARD)` (L188) passes `null` as testType. A non-nullable `SubsystemType` field cannot accept null; the type must be `SubsystemType?`. Internal contradiction between the field-type statement and the enum entries.

### DOC-3 — TAP_COMCALL: enum hardcodes FILES testType yet text says it is a runtime parameter
**Line 214**: `TAP_COMCALL(FILES, COMMLINK, COMPLEX, MONITORED)` fixes testType = FILES. But **line 224**: "`ANALYZE_SUBSYSTEM` and `TAP_COMCALL` accept the relevant subsystem type as a runtime parameter rather than a fixed enum field, since the test type varies by context." Unlike ANALYZE_SUBSYSTEM (which passes `null`), TAP_COMCALL is given a fixed FILES field, contradicting the stated "not a fixed enum field."

### DOC-4 — `noticeIcon` signature lacks the `friendlyReveal` parameter it is said to receive
**Line 301**: `fun Decker.noticeIcon(icon: Icon, diceRoller: DiceRoller): SensorTestResult` — no friendly-reveal parameter. **Line 329**: "The game engine passes a `friendlyReveal: Boolean` flag to `noticeIcon`; when true the test is bypassed." The documented signature cannot receive the flag it describes.

### DOC-5 — "richer payloads use OperationResult.Success with wrapper" contradicts actual return types
**Line 41–42**: "Operations that return richer payloads (e.g. Analyze Host, Locate File) use `OperationResult.Success` with the payload stored in a dedicated wrapper." But `analyzeHost` returns `AnalyzeHostResult` (L397) and `locateFile` returns `Pair<OperationResult, LocateResult>` (L468) — neither is `OperationResult.Success` carrying a wrapper. Stale description of the payload pattern.

### DOC-6 — AnalyzeHostResult reveal model: automatic per-success vs. caller priority list
**Lines 53–54** describe automatic reveal: `revealedSecurityRating` "revealed if ≥ 1 net success" and `revealedSubsystemRatings` "one entry per net success." But `analyzeHost` algorithm (L405) reveals "the first `net` distinct items from `requestedItems`" — a caller-supplied priority-ordered wish list (L394). The data-class comments (automatic) and the algorithm (caller-directed) describe two different reveal mechanics.

### DOC-7 — "Locate group ... Interrogation" comment misclassifies LOCATE_DECKER and LOCATE_IC
**Line 205** group comment: "Locate group — Index Test, Complex Action, Interrogation." But within that group `LOCATE_DECKER` (L207) and `LOCATE_IC` (L209) are category `STANDARD`, not `INTERROGATION`. Only LOCATE_ACCESS_NODE/FILE/SLAVE are INTERROGATION. Comment overgeneralizes the category.

### DOC-8 — "Analyze group — Free or Simple Action" comment contradicts ANALYZE_HOST = COMPLEX
**Line 183** group comment: "Analyze group — Control Test, Free or Simple Action." But `ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD)` (L184) is a COMPLEX action, consistent with the Analyze Host section (L389 "Action: Complex"). The group comment's action-cost claim is wrong for ANALYZE_HOST.

### DOC-9 — SO-13 / SO-14 PRD references are muddled between maintenance-abort and Null Operation
**Line 136**: `maintainMonitoredOperation` "must be called before the pass ends or the operation aborts (**SO-13**)." **Line 138**: "Missing a maintenance call causes the operation to abort (**SO-14**)." Two adjacent sentences cite SO-13 and SO-14 for the same abort behavior. **Line 646**: Null Operation "PRD: **SO-13** category" — reuses SO-13 for a different concept. The SO-13/SO-14 attributions are internally inconsistent.

### DOC-10 — Downloaded DataFile placed into `cyberdeck.storedUtilities`
**Line 559**: on completion "the file copy moves to `cyberdeck.storedUtilities` (or off-line storage if specified)." A downloaded `DataFile` (data, not a utility/program) being stored in a collection named `storedUtilities` is a naming/semantics mismatch — later code should be checked for a distinct data-storage field vs. the utility store.

### DOC-11 — UploadHandle carries `file: DataFile` but `uploadData` receives only `dataSizeMp: Int`
**Line 571**: `uploadData(host, dataSizeMp: Int, diceRoller)` takes no `DataFile`. But `UploadHandle` (L577–583) has a `file: DataFile` field and a `totalMp` field (vs. the `dataSizeMp` input). The handle requires a file/`totalMp` that the operation signature never provides — source-of-`file` gap and `dataSizeMp`↔`totalMp` naming inconsistency.
