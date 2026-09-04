# Coverage Manifest — Design-vs-Code Alignment Audit

Living completeness ledger (align.md §Step 0). An audit is not complete until every row has a
✓ or a justified Skip. Status: `✓ Read — N lines` / `Skip:deferred (cite)` / `Skip:infra (why)` /
`☐ pending`.

`find` counts (this session): **129** Kotlin `.kt`, **9** frontend `.ts/.tsx`, **20** design `.md` = **158** files.

Session log:
- **S1 (2026-09-03):** Iter 1 (PRDs) + Iter 0 (toolchain). Read all PRDs/protocol/deferred. Ran
  `gradlew.bat test integrationTest` → 19 integration failures. Root-caused to GL-1. Read the
  dice/resolver/navigation + integration harness files to confirm. Created audit artifacts.
- **S2 (2026-09-03):** Resolved GL-1 (Option B — pool optional): stripped `hackingPoolDice = hackingPool`
  from 32 call sites (2 nav + 30 ops); resolver keeps opt-in param. Fixed 18/19. Remaining failure
  unmasked GL-2 (Align XV tightened `resolveSlow` assertion to `>0` with an all-zero stub) — recalibrated
  stub. `gradlew.bat test integrationTest` → **BUILD SUCCESSFUL**. Iteration 0 gate now met.
- **S3 (2026-09-03):** Iter 2 remaining design docs delegated to 6 small-scope background agents
  (combat/operations/cyberdeck/ord/movement+game/ui — one doc each, ~sized to the 637-line batch that
  succeeded, to stay under the stream watchdog). In parallel began Iter 3 domain model directly: read
  common/Enums.kt, common/SharedTypes.kt, operations/SystemOperation.kt, operations/SystemTestOutcome.kt,
  operations/OperationResult.kt in full. All PRD-consistent; op-table utility/action mappings flagged
  for cross-check vs operations.md distillation (in flight).

---

## Design docs & PRDs

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| design/prd_core.md | ✓ Read — 481 lines (S1) | `Detection Factor = ceil((Masking + Sleaze rating) ÷ 2)` (L119); `CD-14 ... target number floor is **2**` (L166); `ICC-11 ... (IC Rating)M for Blue/Green` (L460) | see GL-1 (PRD "may" for Hacking Pool) |
| design/prd_game.md | ✓ Read — 38 lines (S1) | `interrogationStates: Map<String, InterrogationState>` … `"LOCATE_FILE@HOST"` (L38) | none yet |
| design/prd_ui.md | ✓ Read — 159 lines (S1) | `UI-01 ... must issue a reconnectToken (opaque string)` (L110); `paramKind ("precision" / ...)` (L190 in protocol) | none yet |
| design/protocol.md | ✓ Read — 212 lines (S1) | `Sealed by "kind" field (not "type")` (L180); `server_full ... MAX_CONNECTIONS (32)` (L152) | none yet |
| design/deferred.md | ✓ Read — 88 lines (S1) | `12. detectedIcons persistence wiring ... never populated in production code` (L78) | verify currency in later iters |
| design/align.md | Skip:infra (audit process doc, not a spec of the product) | — | — |
| design/design.md | ✓ Read — 140 lines (S2, batch A) | `- [Urility(Utility)]` (L8); `Node (data class Node(val subsystemType: SubsystemType, val description: String = ""))` (L114) | DOC-1 (typo), DOC-2 (Node YAML `type` vs field `subsystemType`) |
| design/start.md | ✓ Read — 35 lines (S2, batch A) | `ws://localhost:8080/decker/ws` (L10); `Only one decker session is supported at a time (HeadCrash loaded from headcrash.yaml)` (L33) | DOC-3 (decker yaml filename casing) |
| design/design_core/combat.md | ✓ Read — 772 lines (S3, agent→iter2_combat.md) | `val cloakRating: Int = 0,` (L82); `moverTn = max(2, opponent.sensor - mover.cloakRating)` (L408); `cycleTurns = ceil(10.0 / net).toInt()` (L695) | **13 findings (CMB-1..13 in iter2_combat.md)** — mostly design-doc-internal: stale PRD clause refs (CC-27..30 vs CC-31..33 in Verification table), "nine IC subtypes" vs 11, Scramble unspecified, pseudocode signature defects. Cross-checked vs IC.kt: code implements exactly 11 IC subtypes + BlackHammer/Killjoy/Slow as resolver methods — **conformant**. |
| design/design_core/creation.md | ✓ Read — 319 lines (S2, batch A) | `ratings: { access: 6, control: 8, index: 6, files: 6, slave: 6 }` (L48); `The alert_transition values must match the AlertStatus enum exactly` (L163); `> **Superseded.** The canonical 10-step sequence is in design_core/cyberdeck_and_program_mechanics.md` (L298) | DOC-4 (superseded 7-step init retained) |
| design/design_core/cyberdeck_and_program_mechanics.md | ✓ Read — 631 lines (S3, agent→iter2_cyberdeck.md) | `require(activeUtilities.all { it.rating <= mcpRating }) {` (L107); `get() = (decker.intelligence + cyberdeck.mcpRating) / 3` (L331); `require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01)..." }` (L502) | **7 findings (CYB-1..7 in iter2_cyberdeck.md)** — Mp-size formula & Armor degradation referenced but never specified in this doc; 2 internal contradictions in SystemTestResolver algo. Cross-check vs Cyberdeck.kt/Decker.kt pending Iter 3/4. |
| design/design_core/missing.md | ✓ Read — 143 lines (S2, batch A) | `## 1. Host Rating Random Generation Table (rules p. 205) ✓ resolved` (L7); `PRD ICC-10: "If a companion at the jackpoint manually pulls the plug while Black IC is active..."` (L128) | DOC-5 (items #8,#15 unresolved), DOC-6 (numbering) |
| design/design_core/movement.md | ✓ Read — 370 lines (S3, agent→iter2_move_game.md) | `data class OnLTG(val ltg: LTG) : MatrixLocation()` … `OnHost(val host: Host)` (~L19); `Tally persistence (M-09): if target ltg shares the same parent RTG as current LTG, the RTG tally is unchanged` (~L206); `\| PLTG-to-PLTG hop \| New PLTG tally starts at 0 — no carry-over from source PLTG \|` (~L299) | **findings MG-1..3** — contradiction between `logonToLtg` preconditions & its step-3 "current LTG" tally rule and the summary "sibling LTG" row; dubious passcode-devalidation Kotlin snippet. Cross-check vs DeckerNavigationExtensions.kt pending Iter 4. |
| design/design_core/operations.md | ✓ Read — 908 lines (S3, agent→iter2_operations.md) | `ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD),` (L184); `\| Locate Slave \| ≥ **3** \|` (L491); `\| Tap Comcall with 3 scanners (ratings 4, 6, 7) \| Only rating 7 used for the scanner test \|` (L894) | **11 findings (OPS-1..11 in iter2_operations.md)** — QueryPrecision comment/enum mismatch, testType nullability, TAP_COMCALL runtime type, noticeIcon sig, AnalyzeHost reveal model, SO-13/14 refs, download storage target, upload handle fields. **Cross-check vs SystemOperation.kt done for the op table** (see that row). |
| design/design_core/ord.md | ✓ Read — 612 lines (S3, agent→iter2_ord.md) | `Equality is based on the name field alone for RTG, LTG, PLTG, and Host. For DataFile ... (pointerToHost and pointerTargetFile excluded ...)` (L66); `Host → AlertStatus (1:1) ... Passive Alert raises all Subsystem Ratings by 2` (L254); `class PersonaProgram { +attributeType PersonaAttributeType }` (L450-452) | **7 findings (ORD-1..7 in iter2_ord.md)** — enum-name & field-name inconsistencies between prose field lists, Implementation Notes, and the ERD. Cross-check vs domain model pending Iter 3. |
| design/design_game/game.md | ✓ Read — 449 lines (S3, agent→iter2_move_game.md) | `fun initiative(context, diceRoller): CombatInitiative` / `suspend fun action(...): ActionResult` (~L24); `protected fun moveIfNeeded(target: Decker, context): ActionResult.IcMoved?` (~L216); `IC action dispatch: all 11 subtypes (Killer, Crippler, Probe, Scramble, TarBaby, Blaster, Ripper, Sparky, TarPit, LethalBlackIC, NonLethalBlackIC)` (~L445) | **findings MG-4..8** — `initiative()` returns CombatInitiative but `ActiveIconState.currentInitiative` is Int; IC-move side-effect never persisted by any caller; `withRatingBonus` only on NonLethalBlackIC (but see IC.kt: it IS on both Lethal & NonLethal — **doc stale**); out-of-combat loop multiplies a no-op. game.md L445 confirms the 11 IC subtypes match IC.kt. |
| design/design_ui/design_ui.md | ✓ Read — 476 lines (S3, agent→iter2_ui.md) | `--font: 'VT323', 'Courier New', monospace;` (L105); `locationIndex: number \| null; // ... currently always 0 when jacked in (stub)` (L214); `LOCATE_FILE/LOCATE_SLAVE/LOCATE_ACCESS_NODE ... five-position selector [VERY VAGUE]/[VAGUE]/[NORMAL]/[SPECIFIC]/[VERY SPECIFIC] — NORMAL selected by default` (L401) | **6 findings (UI-1..6 in iter2_ui.md)** — cross-check vs frontend pending Iter 6. |
| design/discrepancies_old*.md (×3) | Skip:infra (archived prior-run logs, not specs) | — | — |

---

## Iteration 3 — Domain model (Kotlin)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/main/.../Main.kt | ✓ Read — 56 lines (S3, agent→iter3_ops_programs.md) | `GameContext(host, securityCode = host.securityRating.code, deckers = listOf(decker), matrix = matrix)` (L~) | none — Rule 9: all required ctor fields supplied, activeIc legitimately defaulted |
| src/main/.../accessories/Accessory.kt | ✓ Read — 9 lines (S3, agent→iter3_ops_programs.md) | `sealed class Accessory { OfflineStorage(capacityMp) / VidScreen / HitcherJack(type) }`; `enum HitcherJackType { ELECTRODE_NET, DATAJACK_FEED }` | none — matches iter2_cyberdeck L556-570 |
| src/main/.../combat/AttackParticipant.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | per-file excerpts in iter3_combat_domain.md coverage table | none |
| src/main/.../combat/AttackResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | `AttackResult.Hit(... effectivePower)` (L10) | **D3C-1** — 5th field named `effectivePower` (code) vs `power` (combat.md L46/spec); same semantics (post-armor power) → **doc-naming only, code clearer** |
| src/main/.../combat/BlackIcPinState.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/Combat.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/CombatInitiative.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/CombatModifiers.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | init-require TN-XOR-Power (verbatim match) | none |
| src/main/.../combat/CombatResolver.kt | ✓ Read — see Iteration 4 row (business logic, audited S3) | | |
| src/main/.../combat/CripplerResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/DefenderParticipant.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/IcDamageResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | carries `personaOnlyCrashed=false` + `mpcpReductionOnKill=0` | none — refutes combat DOC-11 |
| src/main/.../combat/IcSuppressionState.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/JackOutPinResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/ManeuverParticipant.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/ManeuverResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/SimsenseOverloadResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/SlowResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | `SlowResult(actionsLost, icInert)` | none |
| src/main/.../combat/TarBabyResult.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../combat/TrackState.kt | ✓ Read (S3, agent→iter3_combat_domain.md) | see iter3_combat_domain.md | none |
| src/main/.../common/Enums.kt | ✓ Read — 40 lines (S3) | `enum class AlertStatus { NO_ALERT, PASSIVE_ALERT, ACTIVE_ALERT }` (L5); `enum class TopologyType { OPEN_ACCESS, TIERED, HOST_HOST, PRIVATE_GRID }` (L25); `val DamageLevel.boxes ... LIGHT->1 MODERATE->3 SERIOUS->6 DEADLY->10` (L34-39) | none — enums match PRD/creation.md sets (AlertStatus, TopologyType, IntrusionDifficulty, SubsystemType, DamageLevel boxes 1/3/6/10 per SR3) |
| src/main/.../common/SharedTypes.kt | ✓ Read — 29 lines (S3) | `data class SecurityRating(val code: SecurityCode, val value: Int)` (L3); `ConditionMonitor(val maxBoxes: Int = 10, val damage: Int = 0)` (L21); `fun applyDamage(boxes) = copy(damage = (damage+boxes).coerceAtMost(maxBoxes))` (L26) | none — 10-box monitor (CC-30), SubsystemRatings.get() covers all 5 types |
| src/main/.../decker/ActiveMemory.kt | ✓ Read — 20 lines (S3, agent→iter3_decker_domain.md) | see iter3_decker_domain.md | none |
| src/main/.../decker/Cyberdeck.kt | ✓ Read — 92 lines (S3, agent→iter3_decker_domain.md) | Cyberterminal constraints & active/storage memory limits enforced | none (D3D-2 `name` vs `model` naming, low) |
| src/main/.../decker/Cyberterminal.kt | ✓ Read — 49 lines (S3, agent→iter3_decker_domain.md) | MPCP≤4, no RI, −1 utility ratings | none |
| src/main/.../decker/Decker.kt | ✓ Read — 232 lines (S3, agent→iter3_decker_domain.md) | HackingPool=⌊(Int+MPCP)/3⌋, DF=⌈(Masking+Sleaze)/2⌉, effectiveDetectionFactor=max(2, DF−penalty), RI clamp min(3,⌊MPCP/4⌋) all match; `detectionFactor` reads live Sleaze currentRating | **none — refutes combat DOC-10** (`effectiveDetectionFactor` is a real property) |
| src/main/.../decker/DownloadDestination.kt | ✓ Read — 10 lines (S3, agent→iter3_decker_domain.md) | see iter3_decker_domain.md | D3D-3 unused type (low) |
| src/main/.../decker/MedicResult.kt | ✓ Read — 12 lines (S3, agent→iter3_decker_domain.md) | see iter3_decker_domain.md | D3D-1 wrong PRD citation in comment (low, doc) |
| src/main/.../decker/MovementResult.kt | ✓ Read — 24 lines (S3, agent→iter3_decker_domain.md) | see iter3_decker_domain.md | none |
| src/main/.../decker/Persona.kt | ✓ Read — 38 lines (S3, agent→iter3_decker_domain.md) | see iter3_decker_domain.md | D3D-4 reaction/sleaze defaults 0 (low) |
| src/main/.../ic/IC.kt | ✓ Read — 274 lines (S3) | `sealed class IC(name, rating, behavior, guardedNode, conditionMonitor) : ActiveIcon` (L20-26); `initiativeDice: BLUE->1 GREEN->2 ORANGE->3 RED->4` (L35-40); White(Crippler/Killer/Probe/Scramble/TarBaby) Gray(Blaster/Ripper/Sparky/TarPit) Black(LethalBlackIC/NonLethalBlackIC) — 11 classes | **none** — 11 subtypes match combat.md & game.md L445; init dice CC-07; Lethal & NonLethal both have `withRatingBonus` (refutes game.md MG-6); TarBaby/TarPit exclude passive Armor/Sleaze (ICC-05/09); dumpShock→MPCP-test wiring per variant matches iter2_combat distill. BlackHammer/Killjoy/Slow correctly NOT IC subtypes (resolver methods). |
| src/main/.../network/AlertTransitions.kt | ✓ Read (S3, agent→iter3_network_domain.md) | Passive→+2 to all 5 subsystems (Host & Grid overloads); values match AlertStatus | none |
| src/main/.../network/DataFile.kt | ✓ Read (S3, agent→iter3_network_domain.md) | equality excludes pointerToHost/pointerTargetFile (ord.md L66) | none — refutes ORD DOC-2/3/4 (doc-stale) |
| src/main/.../network/Grid.kt | ✓ Read (S3, agent→iter3_network_domain.md) | RTG/LTG/PLTG subtypes | none |
| src/main/.../network/Host.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | **D3N-1** — `require` permits duplicate nodes per subsystem type vs ord.md "exactly one per type" (low; no PRD count invariant) |
| src/main/.../network/Jackpoint.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | none |
| src/main/.../network/Matrix.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | none |
| src/main/.../network/MatrixLocation.kt | ✓ Read (S3, agent→iter3_network_domain.md) | OnRTG/OnLTG/OnPLTG/OnHost | none |
| src/main/.../network/Node.kt | ✓ Read (S3, agent→iter3_network_domain.md) | `subsystemType: SubsystemType` + `description=""` (refutes design.md DOC-2 doc-side) | none |
| src/main/.../network/RemoteDevice.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | none |
| src/main/.../network/SAN.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | none |
| src/main/.../network/SecuritySheaf.kt | ✓ Read (S3, agent→iter3_network_domain.md) | see iter3_network_domain.md | none |
| src/main/.../operations/AvailableAction.kt | ✓ Read — 27 lines (S3, agent→iter3_ops_programs.md) | `sealed class AvailableAction { … JackOut(actionType=FREE) … Operation(operation, target=null, actionType=operation.actionType) }` | none — variant set matches protocol AvailableActionDto `kind`; DTO-only fields (targetKind/targetName/paramKind) belong to mapping layer |
| src/main/.../operations/BufferedMessage.kt | ✓ Read — 17 lines (S3, agent→iter3_ops_programs.md) | `data class LinkedObserver(name)`; `data class BufferedMessage(text, recipient: LinkedObserver)` | none — matches iter2 L731-738 |
| src/main/.../operations/DownloadHandle.kt | ✓ Read — 19 lines (S3, agent→iter3_ops_programs.md) | `DownloadHandle(file, totalMp, ioSpeedMpPerTurn, turnsRemaining, active=true, destination: DownloadDestination = StorageMemory)` | **D3O-1** — `destination` field present, contradicts ACC-01 L600 "not yet included" → **doc-stale, code advanced; reconciles/stales D3D-3** |
| src/main/.../operations/InterrogationState.kt | ✓ Read — 25 lines (S3, agent→iter3_ops_programs.md) | `InterrogationState(operation, query, accumulatedSuccesses=0)`; `QueryPrecision { VERY_VAGUE(+2)…VERY_SPECIFIC(-2) }` | none — fields + all 5 precision modifiers match iter2 L91-101 (SO-07); max(0,net) accrual lives in resolver (SO-06) |
| src/main/.../operations/MatrixIcon.kt | ✓ Read — 32 lines (S3, agent→iter3_ops_programs.md) | `sealed Icon { PersonaIcon(persona, sleazeRating=0); IcIcon(ic) }`; `IcDetectionResult { Undetected/PresenceOnly/TypeKnown/FullyLocated }` | none — SensorTestResult + 4-variant IcDetectionResult match iter2 L308-311/L362-367 |
| src/main/.../operations/MatrixObject.kt | ✓ Read — 21 lines (S3, agent→iter3_ops_programs.md) | `sealed MatrixObject { GridNode(rtg) … IcProgram(ic, analyzed=false) … Device(device) }` — 8 variants | none — all 8 `kind` variants match protocol MatrixObjectDto; detail fields (rating/behavior/guardedNodeType) belong to mapping layer |
| src/main/.../operations/MonitoredOperationHandle.kt | ✓ Read — 27 lines (S3, agent→iter3_ops_programs.md) | `sealed MonitoredTarget { SlaveDevice/ComcallHost(host) }`; `MonitoredOperationHandle(operation, target, active=true, needsMaintenance=false)` | none — matches iter2 L112-133 |
| src/main/.../operations/NullOperationModifier.kt | ✓ Read — 33 lines (S3, agent→iter3_ops_programs.md) | `UNDER_TEN_SECONDS(0)…ONE_HOUR_TO_TWELVE_HOURS(4)`; `extraIncrements = (seconds-43200)/43200` | none — bonuses 0/1/2/4 + thresholds + +1 per extra 12h match iter2 L147-164 |
| src/main/.../operations/OperationResult.kt | ✓ Read — 104 lines (S3) | `sealed class OperationResult { abstract val decker; abstract val outcome }` (L11); `AnalyzeHostResult ... On 7+ net successes all six pieces ... revealed` (L34-44); `LocateResult.NotFound ... (≥ 3 successes with no data present)` (L80-81) | none — AnalyzeHost 7+→all 6 items, LocateResult Ongoing/Located/NotFound≥3 match interrogation baseline (SO-05..09) |
| src/main/.../operations/PointerChain.kt | ✓ Read — 15 lines (S3, agent→iter3_ops_programs.md) | `data class PointerChain(links: List<Host>, finalFile: DataFile)` | none — matches iter2 L799-802 (chain length 1D6 set by resolver) |
| src/main/.../operations/SystemOperation.kt | ✓ Read — 57 lines (S3) | `enum class SystemOperation(testType: SubsystemType?, utility: UtilityType?, actionType, category)` (L20-25); `LOCATE_FILE(INDEX, BROWSE, COMPLEX, INTERROGATION)` (L43); `SWAP_MEMORY(null, null, SIMPLE, STANDARD)` + `LOCATE_DECKER` both marked deferred/undispatched (L41-54) | **cross-check vs operations.md (agent in flight):** verify per-op utility+actionType+category mappings (esp. ANALYZE_IC/ICON=FREE, MAKE/TAP_COMCALL=FILES/COMMLINK/MONITORED, CONTROL/EDIT_SLAVE=SPOOF/MONITORED, GRACEFUL_LOGOFF=ACCESS/DECEPTION, NULL_OPERATION=CONTROL/DECEPTION/STANDARD). LOCATE_DECKER & SWAP_MEMORY correctly deferred (baseline §wire) |
| src/main/.../operations/SystemTestOutcome.kt | ✓ Read — 8 lines (S3) | `deckerWins: Boolean` + `/** true when deckerSuccesses >= hostSuccesses (decker wins ties) */` (L6-7) | none — ties→decker (M-04) |
| src/main/.../operations/UploadHandle.kt | ✓ Read — 17 lines (S3, agent→iter3_ops_programs.md) | `UploadHandle(file: DataFile, totalMp, ioSpeedMpPerTurn, turnsRemaining, active=true)` | **D3O-2** — resolves OPS DOC-11: `uploadData` synthesizes `file=DataFile(...,sizeMp=dataSizeMp)` + `totalMp=dataSizeMp` (DeckerOperationsExtensions.kt:404); type conforms → **doc-stale, no code bug** |
| src/main/.../programs/PersonaProgram.kt | ✓ Read — 8 lines (S3, agent→iter3_ops_programs.md) | `PersonaProgram(attributeType, rating) : Program(name=attributeType.name, rating, multiplier=1)` | none — Bod/Evasion/Masking/Sensors multiplier=1 (resolves ORD DOC-6 doc-side: mult defined) |
| src/main/.../programs/Program.kt | ✓ Read — 9 lines (S3, agent→iter3_ops_programs.md) | `val mpSize: Int get() = rating * rating * multiplier` | none — Mp=Rating²×mult (ord.md L116); stored `rating` (fixed hardware size) |
| src/main/.../programs/Utility.kt | ✓ Read — 49 lines (S3, agent→iter3_ops_programs.md) | `ANALYZE(3, OPERATIONAL)…BLACK_HAMMER(20)/KILLJOY(10)/SLOW(4) OFFENSIVE`; `multiplier = if ATTACK+damageLevel then ordinal+2 else type.multiplier` | none — ALL multipliers match ord.md L128-131 verbatim; ATTACK L/M/S/D=2/3/4/5; categories match |

## Iteration 4 — Business logic (Kotlin)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/main/.../combat/CombatResolver.kt | ✓ Read — 552 lines (S3) | `numDice = max(1, 1 + decker.cyberdeck.responseIncrease - commPenalty)` (L39); `val net = icSuccesses - deckerSuccesses; val reduction = max(0, net / 2); val newValue = max(1, currentAttr - reduction)` (resolveCrippler L163-165); `icInert = (icInitiative.initiativePasses - actionsLost) <= 0` (resolveSlow L488) | **none** — all 29 methods match iter2_combat.md resolver distill (initiative, maneuver, attack, applyIcDamage, dumpShock, jackOutPin, Crippler/Killer/Probe/TarBaby, Blaster/Ripper/Sparky/TarPit +MpcpTests, Lethal/NonLethal BlackIC, BlackHammer/Killjoy, TrackLock, suppress/unsuppress, icAttackParticipant, Slow, attackTn CC-24, stage, degradeArmor CD-19). **Refutes combat DOC-8/9/10/11/12 as doc-only** (code has correct signatures & sets personaOnlyCrashed). Minor: `resolveManeuver` ignores its `maneuver` param (matches doc's single generic formula). |
| src/main/.../decker/DeckerMemoryExtensions.kt | ✓ Read — 105 lines (S3, agent→iter4_game.md) | active/storage memory swap, load/unload utility per iter2_cyberdeck | none — fully conforms |
| src/main/.../decker/DeckerNavigationExtensions.kt | ✓ Read — 376 lines (S1) | post-GL-1 fix: `resolve(this, operation, accessRating, securityValue, diceRoller)` (L301), `resolve(..., GRACEFUL_LOGOFF, ..., diceRoller)` (L239) — no hackingPool arg; `withDestinationTallyEmbedded` (L343) | **GL-1 (resolved)** |
| src/main/.../decker/DeckerOperationsExtensions.kt | ✓ Read — 678 lines (S3, agent→iter4_ops_ext.md) | all 28 `SystemTestResolver.resolve*` call sites — **none passes hackingPoolDice** (GL-1 Option-B revert intact); `UploadHandle(file=DataFile(...,sizeMp=dataSizeMp), totalMp=dataSizeMp)` (L404) | none (code) — **D4O-1..5 all doc-stale/cleared**: resolve iter2 DOC-4/10/11 + editFile param-order doc mismatch; D4O-5 invokeMedic Deadly early-return cleared vs CD-20. Rule 11 math (≥5 locate / Slave ≥3 / NotFound ≥3-no-data / host successes always tallied / ties→decker / grid-host distinct keys) all conformant |
| src/main/.../game/ActionResult.kt | ✓ Read — 8 lines (S3, agent→iter4_game.md) | `sealed ActionResult { IcAttack(msg); IcMoved(msg); NoTarget; DeckerAction }` | none — matches game.md L39-44 |
| src/main/.../game/ActiveIcon.kt | ✓ Read — 9 lines (S3, agent→iter4_game.md) | `interface ActiveIcon { initiative(context, diceRoller): CombatInitiative; suspend action(...): ActionResult }` | none — matches game.md L24-27 |
| src/main/.../game/ActiveIconState.kt | ✓ Read — 6 lines (S3, agent→iter4_game.md) | `data class ActiveIconState(icon: ActiveIcon, currentInitiative: Int)` | none |
| src/main/.../game/DeckerExtensions.kt | ✓ Read — 19 lines (S3, agent→iter4_game.md) | `asDefenderParticipant()` requires non-null persona + OnHost; armor from ARMOR utility | none — matches game.md L405-416 |
| src/main/.../game/Game.kt | ✓ Read — 90 lines (S3, agent→iter4_game.md) | `currentInitiative` from `CombatInitiative.score` (L86); action loop gates on `currentInitiative > 0`, decrements 10 | **D4G-3 (REAL BUG, high when wired)** — `runCombatTurn` discards `action()`'s `IcMoved`; no caller replaces IC in `activeIc`, so a displaced IC moves forever & never attacks (confirms MG-4/DOC-4). **D4G-4 (REAL BUG)** — fixed per-turn init list lets an IC that called `removeIc(this)` be re-selected & act again. **NOTE (S3 verify): `runCombatTurn`/`runOutOfCombatTurn` are NOT called anywhere in main source — the Game loop is not wired into the production WebSocket flow (deferred.md #1), so D4G-3/D4G-4 are DORMANT in prod, exercised only by tests.** **D4G-1** — out-of-combat loop repeats no-op `Decker.action()` (= deferred.md #1). **D4G-2 doc-stale** — `.score` resolves MG-5/DOC-5 |
| src/main/.../game/GameContext.kt | ✓ Read — 94 lines (S3, agent→iter4_game.md) | API: updateDecker/removeIc/addIc/addToSecurityTally/checkTriggers/unauthorizedDeckerInNode/Host per game.md L74-96 | **D4G-5 (cosmetic)** — ctor field order `activeIc` before `matrix` vs iter2 distill reversed; MG-7 = game.md doc gap (interrogation state lives on Decker, not GameContext) |
| src/main/.../operations/SystemTestResolver.kt | ✓ Read — 178 lines (S1) | `val totalDeckerDice = decker.computerSkill + hackingPoolDice` (L43); `deckerWins = deckerResult.successes >= hostResult.successes` (L54) | **GL-1** |
| src/main/.../utility/DiceRoller.kt | ✓ Read — 35 lines (S1) | `do { face = random.nextInt(1, 7); total += face } while (face == 6)` (L29-32) | none — behaves as designed |

## Iteration 5 — Server / controller / DTO (Kotlin)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/main/.../server/DeckerDisconnectedException.kt | ✓ Read — 3 lines (S3, agent→iter5_server.md) | exception type for mid-turn disconnect | none |
| src/main/.../server/MatrixServer.kt | ✓ Read — 81 lines (S3, agent→iter5_server.md) | Ktor bootstrap, WS route `/decker/ws`, static resources before WS | none |
| src/main/.../server/SessionRegistry.kt | ✓ Read — 171 lines (S3, agent→iter5_server.md) | reconnectToken issuance/lookup; MAX_CONNECTIONS 32; name validation | none (see D5S-1/2 doc reconciliation) |
| src/main/.../server/TurnCoordinator.kt | ✓ Read — 52 lines (S3, agent→iter5_server.md) | turn ownership / active-controller handoff | none |
| src/main/.../server/WebSocketDeckerController.kt | ✓ Read — 488 lines (S3, agent→iter5_server.md) | dispatch branches join/action; error codes; `(p?.dataSize ?: 100).coerceAtLeast(1)` (L320); ResultMessage successes non-null | **D5S-3 (REAL protocol violation)** — grid `LOCATE_ACCESS_NODE` path missing the first-call blank-`query` → `bad_request` guard that the host path enforces. **D5S-1/D5S-2** — protocol.md/prd_ui.md ambiguities to reconcile (+2 code-quality obs). Deferred ops (LOCATE_DECKER, SWAP_MEMORY) correctly excluded from availableActions |
| src/main/.../server/dto/AvailableActionDto.kt | ✓ Read — 92 lines (S3, agent→iter5_dto.md) | sealed, discriminated by `kind` (not `type`); common `index` + `actionType` fields; `Operation.paramKind` map matches protocol L190 | **D5D-1** — protocol.md field tables omit common `index` + `actionType`; code-correct → doc-stale |
| src/main/.../server/dto/DeckerStateDto.kt | ✓ Read — 44 lines (S3, agent→iter5_dto.md) | fields per protocol L205-213; `locationIndex: Int?`, `activeUtilities` present | none |
| src/main/.../server/dto/MatrixObjectDto.kt | ✓ Read — 141 lines (S3, agent→iter5_dto.md) | 8 `kind` variants; `IcProgram.rating: Int?` null-until-analyzed, `behavior/guardedNodeType` nullable | none — resolves iter2_ui DOC-1/2/3/6 as frontend/doc-stale (backend `ActionParams` DOES carry `dataSize`) |
| src/main/.../server/dto/Messages.kt | ✓ Read — 86 lines (S3, agent→iter5_dto.md) | ControlMessage `reconnectToken: String?` nullable; ResultMessage `deckerSuccesses`/`hostSuccesses` non-null | **D5D-2** — prd_ui.md self-contradicts on MAKE_COMCALL params (L127 vs L141); DTO follows protocol → doc-stale |

## Iteration 7 — Config loaders (Kotlin)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/main/.../config/ConfigUtils.kt | ✓ Read — 9 lines (S3, agent→iter7_config.md) | `parseSubsystemRatings` requireNotNull + unchecked `as Map<String,Int>` | none |
| src/main/.../config/DeckCatalogEntry.kt | ✓ Read — 11 lines (S3, agent→iter7_config.md) | 7 fields (model/mpcp/hardening/activeMemoryMp/storageMemoryMp/ioSpeedMpPerTurn/costNuyen) | none — matches iter2_cyberdeck L53-61; no responseIncrease by design |
| src/main/.../config/DeckCatalogLoader.kt | ✓ Read — 28 lines (S3, agent→iter7_config.md) | YAML keys model/mpcp/hardening/active_memory/storage_memory/io_speed/cost_nuyen (CD-25); `cost_nuyen ?: 0` | none (D7C-1 charged to DeckerLoader path, not catalog schema) |
| src/main/.../config/DeckerLoader.kt | ✓ Read — 106 lines (S3, agent→iter7_config.md) | `Cyberdeck(...)` always built, no `isCyberterminal` arg (L73); `sensor`→SENSORS mapping | **D7C-1 (REAL gap)** — `type: cyberterminal` never handled; `Cyberterminal` factory never called from config → CT-01..05 unreachable via config path |
| src/main/.../config/GridInitializer.kt | ✓ Read — 14 lines (S3, agent→iter7_config.md) | loads classpath `grid.yaml`, delegates to GridLoader | none |
| src/main/.../config/GridLoader.kt | ✓ Read — 157 lines (S3, agent→iter7_config.md) | LTG inherits sec/ratings from parent RTG; PLTG requires explicit `security`; grid `securitySheaf` defaulted | **D7C-2** (buildHost never wires connectedHosts — see HostLoader), **D7C-3 (REAL gap)** grid SecuritySheaf not loadable (no `security_sheaf` parse) → grid alert escalation can't fire; **D7C-5** PLTG doesn't inherit security from RTG (low) |
| src/main/.../config/HostLoader.kt | ✓ Read — 220 lines (S3, agent→iter7_config.md) | Host+TriggerStep+IC ctors supplied (IC param order verified); `nodes = nodes` raw list (L93) | **D7C-2 (REAL, medium)** — `connectedHosts` never populated → grid.yaml TIERED/HOST_HOST hosts unreachable (Decker.availableActions logon-to-connected always empty); **D7C-4** intrusion_difficulty/topology loader-defaulted (low); **D7C-6** DataFile pointer fields not loadable (verify vs deferred); **D7C-7** duplicate subsystem nodes pass through (warned, not dropped) |

## Iteration 6 — Frontend (TS/TSX)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| frontend/src/App.tsx | ✓ Read — 116 lines (S3, agent→iter6_frontend.md) | `ERROR_LABELS: Record<ErrorCode,string>` (L10); `isRegistered = role==='registered_decker'||'active_controller'` (L82); panel wiring | none — ERROR_LABELS + layout match iter2_ui |
| frontend/src/main.tsx | ✓ Read — 11 lines (S3, agent→iter6_frontend.md) | `ReactDOM.createRoot(...).render(` | none — bootstrap only |
| frontend/src/types/messages.ts | ✓ Read — 133 lines (S3, agent→iter6_frontend.md) | `dataSize?: number` (L16); `MatrixObjectDto = { kind: 'GridNode'; ...}` (L67); `GameEvent = { kind:'result'... }` (L130) | **D6F-1** — `inactivitySeconds` orphan field (low, confirms iter2_ui DOC-3); resolves DOC-1/2/4 doc-stale. `kind` discriminators correct |
| frontend/src/hooks/useWebSocket.ts | ✓ Read — 191 lines (S3, agent→iter6_frontend.md) | `if (msg.reconnectToken) reconnectTokenRef.current = msg.reconnectToken` (L99 — ignores null, resolves D5S-1 client-side); backoff `min(*2, 30000)` (L145) | **D6F-3** — reconnect suppression via flag not handler-nulling (doc-stale/quality); lifecycle+token+gating conform |
| frontend/src/components/ActionsPanel.tsx | ✓ Read — 217 lines (S3, agent→iter6_frontend.md) | `{precision,query,scannerDeviceRating,newContent,hasValidPasscode,dataSize:100}` (L34); `paramKind==='dataSize'` stepper (L194) | none — all 5 paramKind controls present; resolves iter2_ui DOC-1/DOC-2 doc-stale |
| frontend/src/components/DeckerPanel.tsx | ✓ Read — 76 lines (S3, agent→iter6_frontend.md) | `⚠ PINNED BY BLACK IC` (L42); PHYS/MENT monitors, `{hackingPool}d`, MCP, program bar | none |
| frontend/src/components/EntitiesPanel.tsx | ✓ Read — 106 lines (S3, agent→iter6_frontend.md) | `ENTITY_KINDS = ['HostSubsystem','IcProgram','File','Device']` (L9); `obj.analyzed && <EF RATING>` (L46) | none — analyzed-gating + focus/compact/empty match L363-375 |
| frontend/src/components/LocationPanel.tsx | ✓ Read — 109 lines (S3, agent→iter6_frontend.md) | `decker.locationIndex != null ? visibleObjects[decker.locationIndex] : visibleObjects.find(...name===name)` (L79) | **D6F-2 (REAL, medium)** — panel PREFERS stubbed `locationIndex` (backend `=0` when jacked in, DeckerStateDto.kt:28) → renders `visibleObjects[0]` unconditionally, name-match fallback is dead code; correct only if server guarantees location@index0 (protocol doesn't). **Stales deferred.md #4** (which claims name-prefix workaround) |
| frontend/src/components/NarrativePanel.tsx | ✓ Read — 64 lines (S3, agent→iter6_frontend.md) | `[{deckerSuccesses}d vs {hostSuccesses}h]` (L45); newest-at-bottom, ✓/✗, error branch, active-turn pulse | none |

## Iteration 8 — Tests (Kotlin)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/test/.../combat/CombatResolverTest.kt | ✓ Read — 1358 lines (S3, agent→iter8_tests_a.md) | attack-TN table labeled "CC-21"; stub math per iter2_combat | **D8T-1 (low)** — TN table labeled CC-21, spec attributes CC-24; asserted values correct (doc-label only). **D8T-2 (moderate)** — `resolveSlow net 0` test comment "IC dice succeed" false (face 5 @ TN 6 = 0 succ); silently tests 0-vs-0 tie, not the IC-wins path it claims (trivially passing) |
| src/test/.../combat/CombatTest.kt | ✓ Read — 24 lines (S3, agent→iter8_tests_a.md) | see iter8_tests_a.md | none |
| src/test/.../common/SharedTypesTest.kt | ✓ Read — 34 lines (S3, agent→iter8_tests_a.md) | see iter8_tests_a.md | none |
| src/test/.../config/DeckerConfigTest.kt | ✓ Read — (S3, self→iter8_tests_f.md) | HackingPool ⌊(6+8)/3⌋=4 (L80); detectionFactor ⌈(6+5)/2⌉=6 (L93) | **D8TF-2** "sleaze active" test never activates sleaze; **D8TF-3** D7C-1 cyberterminal path uncovered |
| src/test/.../config/GridLoadTest.kt | ✓ Read — (S3, self→iter8_tests_f.md) | RTG count ≥19 (L68); LTG inherits sec+subsystem from RTG (L97-102) | **D8TF-1** dead `winRoller`/`buildDecker` helpers + wrong-face comment; **D8TF-3** D7C-2/3/5 uncovered |
| src/test/.../decker/CyberdeckAndProgramMechanicsTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | **D8TB-5** Crippler/Ripper attribute tests use trivially-true `<=` asserts; **D8TB-6** `CD-14 reduces TN` discards resolve result, never verifies reduction (coverage weakness) |
| src/test/.../decker/DeckerOperationsTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../decker/DeckerTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../decker/DeckerVisibilityTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../decker/MovementTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../game/GameContextTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../game/GameTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | IC-move test asserts only `IcMoved` return, never loop-level persistence | **D8TB-1** D4G-3 uncovered; **D8TB-2** D4G-4 uncovered; **D8TB-3** `higher initiative icon more actions` reimplements loop inline, never calls `runCombatTurn` (false confidence); **D8TB-4** `completes when no active IC` asserts nothing |
| src/test/.../ic/IcBehaviorTest.kt | ✓ Read — 194 lines (S3, agent→iter8_tests_a.md) | drives `IC.action()` directly (removeIc-on-win works), never `Game.runCombatTurn` | **D8T-3 (coverage gap)** — D4G-3/D4G-4 NOT covered (manifest only through IC.action, not the buggy Game loop) |
| src/test/.../ic/IcTest.kt | ✓ Read — 92 lines (S3, agent→iter8_tests_a.md) | see iter8_tests_a.md | none |
| src/test/.../integration/AlertAndTallyTest.kt | ✓ Read — (S3, agent→iter8_tests_c.md) | setupRoller `winThenRoller(zeroCalls=26, thenValue=3)` (L60/L126) | **D8TC-1** L119 permissive assertion (both-zero disjunct weakens RTG-tally independence proof) |
| src/test/.../integration/CombatTest.kt | ✓ Read — (S3, agent→iter8_tests_c.md) | `winThenRoller(zeroCalls=26)` (L32); attack TN ORANGE-intruding=4 (CC-24) | **D8TC-5** L142 comment overstates IC attack dice (uses host SV pool not ic.rating); D4G-3/D4G-4 coverage gaps (**D8TC-3/4**) |
| src/test/.../integration/DeckerCombatTest.kt | ✓ Read — (S3, agent→iter8_tests_c.md) | `resolveSlow` stub `winThenRoller(zeroCalls=5, thenValue=5)` (L229) | **GL-2** consistent; **D8TC-2** L179 stale comment names wrong roller/outcome |
| src/test/.../integration/FileOperationsTest.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | locateFile ≥5 (code L218); download turns ⌈sizeMp/io⌉→runDownloadedFiles | none (dead locals `state` L41/L57 code-quality only) |
| src/test/.../integration/GrayCombatTest.kt | ✓ Read — (S3, agent→iter8_tests_c.md) | `winThenRoller(zeroCalls=26)` (L193); Sparky MPCP test TN=hardening+mcp+2 | none (Ripper floor 0 vs Crippler floor 1, TarPit, NonLethal Black IC all conformant) |
| src/test/.../integration/ICActivationTest.kt | ✓ Read — 191 lines (S1) | `winThenRoller(zeroCalls = 26, thenValue = 3)` (L29); comment L135-140 asserts winRoller gives "8 decker successes" — contradicts DiceRoller (all-zero → 0 successes) | **GL-1**; stale comment |
| src/test/.../integration/ManeuverTest.kt | ✓ Read — (S3, agent→iter8_tests_c.md) | mover TN=max(2,sensor−cloak); ties→opponent (combat.md L736) | none (maneuver ties→fail distinct from System Test ties→decker; hackingPool add within PRD "may") |
| src/test/.../integration/MemoryManagementTest.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | mpSize 6²×3=108, turns=⌈Mp/io⌉; promotion rating3→27Mp | none |
| src/test/.../integration/MovementTest.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | `winThenRoller(zeroCalls=12/24/25)` budgets (hackingPool=0) | **D8TD-1** no M-09..M-15 tally-persistence asserts; **D8TD-2** dump-shock tests assert only "logged off", never `dumpShock`/CM damage |
| src/test/.../integration/SlaveOperationsTest.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | locateSlave threshold ≥3 (code L245); MonitoredOperationHandle | none |
| src/test/.../integration/UploadDataAndScrambleTest.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | uploadData M-05 tally-always-added (code L396); Scramble TN=max(2,skill) | **D8TD-3** L92 comment says failRoller face=4 (actual 3) — comment defect only |
| src/test/.../integration/WebSocketServerIntegrationTest.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | **D8TE-3** 120s timeout untested (uses 5s); **D8TE-4** D5S-3 grid blank-query uncovered (no params ever sent); **D8TE-6** paramKind/params untested |
| src/test/.../integration/utility/DeckerMock.kt | ✓ Read — 117 lines (S1) | `HIGH_END = "Quicksilver"`; `intelligence = 7 ... mcpRating = 12 ... computerSkill = 8` (L40-51) → HackingPool=6 | supports GL-1 |
| src/test/.../integration/utility/GridMock.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | builds real grid via `GridInitializer.initialize()` | none (infra) |
| src/test/.../integration/utility/HostMock.kt | ✓ Read — (S3, agent→iter8_tests_d.md) | placeholder host GREEN/3, SubsystemRatings(3,3,3,3,3) | none (infra) |
| src/test/.../integration/utility/IntegrationTestBase.kt | ✓ Read — 232 lines (S1) | `winThenRoller(zeroCalls: Int, thenValue: Int) ... return if (call <= zeroCalls) 0 else thenValue` (L54-61) | **GL-1** |
| src/test/.../integration/utility/ScenarioBuilder.kt | ✓ Read — 347 lines (S1) | `assertIs<LogonResult.Success>(r, "$name failed")` (L189); `old.analyzeSubsystem(host, subsystem, roller)` (L238) | none — asserts drive GL-1 symptom |
| src/test/.../network/AlertTransitionsTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../network/NetworkTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../operations/NullOperationModifierTest.kt | ✓ Read — (S3, agent→iter8_tests_a.md) | buckets <10→0/<60→1/<3600→2/else→4; +1 per 12h (86400→5, 129600→6) | none (matches operations.md L147-161) |
| src/test/.../operations/SystemOperationTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../operations/SystemOperationsTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../operations/SystemTestResolverTest.kt | ✓ Read — (S3, agent→iter8_tests_b.md) | see iter8_tests_b.md | none |
| src/test/.../programs/ProgramTest.kt | ✓ Read — (S3, agent→iter8_tests_a.md) | mpSize=Rating²×mult; ANALYZE×3=27; Attack L×2/D×5 | none |
| src/test/.../server/FakeWebSocketSession.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | none (test double / infra) |
| src/test/.../server/SessionRegistryTest.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | **D8TE-2** SERVER_FULL wire + MAX_CONNECTIONS=32 untested (only `maxConnections=1` boolean) |
| src/test/.../server/TurnCoordinatorTest.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | none (all claimAction error keys conformant) |
| src/test/.../server/WebSocketServerTest.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | **D8TE-1** UNKNOWN_MESSAGE_TYPE untested; **D8TE-3** 120s timeout untested; **D8TE-5** D5S-2 graceful-logoff token-clear uncovered; **O1** no fresh-token-on-reconnect assert |
| src/test/.../server/dto/DtoMappingTest.kt | ✓ Read — (S3, agent→iter8_tests_e.md) | see iter8_tests_e.md | **D8TE-6** paramKind + ResultMessage successes mapping untested |
| src/test/.../utility/DiceRollerTest.kt | ✓ Read — (S3, agent→iter8_tests_a.md) | exploding d6 (6→reroll); success=face≥TN; invalid dice/TN throw | none |

---

## Completion Gate (SATISFIED — 2026-09-03)

1. **Count match: Met.** Every source, config, frontend, server and test row is ✓ Read (no `☐ pending`
   remains). All layers covered (Rule 7): domain (Iter 3–4), server/DTO (Iter 5), frontend (Iter 6),
   config loaders (Iter 7), and the full test suite (Iter 8 batches A–F, incl. the two unassigned
   config tests audited directly). Zero-finding files logged per Rule 5.
2. **PRD coverage: Met.** prd_core, prd_game, prd_ui all read in full this session.
3. **Adversarial check: Met.** Re-challenged the "doc-stale" dismissals for hidden bugs. The material
   ones were *escalated*, not dismissed: DOC-6 → **UI-1/D6F-2** (real stub-trust bug), the grid/host
   LOCATE symmetry → **PR-1/D5S-3** (real protocol gap), and the config happy-path assumptions →
   **CD-1/GR-1**. Confirmed the dormant game-loop bugs (GL-3) are genuine code defects, not doc drift,
   and that they are production-unreachable only because the loop is unwired (deferred #1).
4. **Deferred currency: Met (Rule 11).** `deferred.md` #4 corrected (LocationPanel now prefers the stub
   index, inverting the old "name-match workaround" wording — per D6F-2); #11 annotated
   (`security_decker_count` now parsed into `TriggerStep` but never consumed). #1 (=D4G-1), #6 (=D3O-1)
   confirmed still accurate against current code. D7C-6 (DataFile pointers) noted in GR-1 as **not**
   tracked in deferred — flagged to defer explicitly or implement.
5. **Root-cause consolidation: Met.** All new findings consolidated into
   `discrepancies_without_prd.md` by root cause with correct prefixes: **GL-3** (unfinished/unwired
   game loop → D4G-3+D4G-4), **PR-1** (D5S-3), **RT-1** (D5S-2), **UI-1** (D6F-2), **CD-1** (D7C-1),
   **GR-1** (D7C-2/3/5/6/7 config-loader gaps), **MC-1** (test coverage gaps). Doc-stale findings
   (D4G-2/5, D3O-1/2, D5D-1/2, D5S-1, D6F-1/3, D7C-4) recorded in their iter files, not escalated.

**Audit complete.** No code changes were made in this pass (audit + documentation only); the tree is
unchanged from the GL-1/GL-2 fixes (BUILD SUCCESSFUL). Actionable follow-ups are captured as GL-3,
PR-1, RT-1, UI-1, CD-1, GR-1, MC-1 in `discrepancies_without_prd.md`.
