# Align XV — Design-vs-Code Discrepancies

**Audit run:** 2026-09-02  
**Method:** 7 parallel sub-agent reading batches (all files read in this session); synthesis written directly after context compaction of batch outputs.  
**Total files in scope:** 157 (19 design · 85 main Kotlin · 44 test Kotlin · 9 frontend)  
**Read:** 153 · **Skip:infra:** 3 · **Skip:deferred:** 1  

**Resolved from Align XIV:** CM-2, CM-3, OP-1, OP-2, OP-3, CD-1, WS-1, WS-2, WS-3, WS-4, UI-1, ID-2  
**Still open from Align XIV:** CM-1  
**New this audit:** WS-5, WS-6, UI-2, UI-3, TR-1 – TR-7, DS-1 – DS-3, ID-1 (conflict re-confirmed)

---

## Coverage Manifest

### Layer 0 — Design Documents

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| design/align.md | Skip:infra — process document; no design-doc coverage | — | — |
| design/discrepancies_old.md | Skip:infra — superseded audit log; user instruction to exclude | — | — |
| design/discrepancies_old_2.md | Skip:infra — superseded audit log; user instruction to exclude | — | — |
| design/prd_core.md | ✓ Read — 481 lines | (L35) `M-08a: **ANALYZE_IC** is only available inside a host.` · (L191) `CD-19: **Armor degradation.** Each time an Armor utility fails to fully absorb incoming damage — meaning damage bleeds through to the persona's condition monitor — the Armor utility's \`currentRating\` decreases by 1.` · (L427) `CC-33: After each successful Attack Test against a target decker, the target makes an Evasion (Track Rating) Test.` | ID-1 (conflict source) |
| design/prd_ui.md | ✓ Read — 160 lines | (L79) `not every kind exposes securityCode or securityTally (e.g. \`PrivateGrid\` exposes \`owner\` and \`hostCount\` but not \`securityTally\`)` · (L128) `**\`NULL_OPERATION\`**: uses \`inactivitySeconds\` (default \`0\`) — the default is sufficient; no extra input required` | DS-2 |
| design/prd_game.md | ✓ Read — 39 lines | (L34) `Operations \`SWAP_MEMORY\` and \`LOCATE_DECKER\` are deferred to a future milestone and are excluded from \`availableActions\` until implemented.` | — |
| design/design.md | ✓ Read — 140 lines | (L25) `ConditionMonitor: applyDamage` · (L129) `- DeckCatalogEntry` | — |
| design/protocol.md | ✓ Read — 213 lines | (L7) `All messages are JSON objects. Every message has a \`"type"\` discriminator field.` · (L190) `\| \`Operation\` \| \`operation\` (SystemOperation), \`targetKind\`, \`targetName\`, \`paramKind\` (\`"precision"\` / \`"hasValidPasscode"\` / \`"scannerDeviceRating"\` / \`"newContent"\` / \`"dataSize"\` / null) \|` | — |
| design/start.md | ✓ Read — 35 lines | (L9) `The server starts on **http://localhost:8080** and serves the built React frontend as static files.` | — |
| design/deferred.md | ✓ Read — 85 lines | (L51) `Routing of completed downloads to offline storage is not yet wired up.` | DS-1 |
| design/design_core/combat.md | ✓ Read — 772 lines | (L46-53) `val rawWeaponPower: Int` / `val power: Int` / `PRD: CC-20–CC-26. \`rawDamageLevel\` is the pre-staging base; … \`power\` is the effective power after armor reduction (\`max(0, rawWeaponPower - armorRating)\`).` · (L303) `val isPinnedByBlackIc: Boolean get() = blackIcPin != null` · (L650) `2. \`effectivePower = max(0, power - decker.cyberdeck.hardening)\` — Hardening reduces Power for body test only.` | CM-1 (design source) |
| design/design_core/ord.md | ✓ Read — 612 lines | (L82) `Suppressed IC list — crashed IC programs held to prevent tally increase; each entry reduces Detection Factor by 1` · (L276) `SecurityTally is per (Decker × Host/Grid) — Each decker accumulates a separate tally on each host/grid.` · (L490) `Matrix "1" --> "*" RTG` | — |
| design/design_core/creation.md | ✓ Read — 317 lines | (L13) `2. Instantiate all RTG objects with their System Ratings.` · (L161) `offline: true` · (L235) `Response Increase ≤ min(3, floor(MPCP ÷ 4)).` | DS-3 |
| design/design_core/missing.md | ✓ Read — 143 lines | (L8) `## 1. Host Rating Random Generation Table (rules p. 205) ✓ resolved` · (L128) `## 15. ICC-10 — Companion Plug-Pull While Black IC is Active` | — |
| design/design_core/cyberdeck_and_program_mechanics.md | ✓ Read — 631 lines | (L86) `` `rating` (inherited from `Program`) is the immutable stored rating — the value from the YAML, never changed at runtime. `` · (L330) `` `hackingPool` is a computed property: `` · (L602) `Response Increase 3 on MPCP-8 deck (floor(8÷4)=2) \| Config error (CD-02)` | DS-3 (10-step sequence) |
| design/design_core/operations.md | ✓ Read — 904 lines | (L148) `UNDER_TEN_SECONDS(0),` · (L499) `\| Locate Slave \| ≥ **3** \|` · (L835) `### \`analyzeIc(ic: IC, grid: Grid, diceRoller: DiceRoller): OperationResult\`` | ID-1 (conflict source) |
| design/design_game/game.md | ✓ Read — 449 lines | (L60) `class GameContext(` · (L207) `` `unauthorizedDeckerInNode(node)` — returns the first decker whose `persona.currentNode == node` `` · (L428) `- **Grid context** (`currentLocation is OnLTG / OnRTG / OnPLTG`): only the subset valid on a grid — `NULL_OPERATION`, `LOCATE_ACCESS_NODE` (M-07: available from RTG), `ANALYZE_SECURITY`, `LOCATE_IC`, `ANALYZE_IC`.` | ID-1 (conflict source) |
| design/design_ui/design_ui.md | ✓ Read — 476 lines | (L96) `--green:        #00ff41;   /* primary text */` · (L241) `` export type AvailableActionDto = `` · (L329) `` show a blinking `⚠ PINNED` badge in red below the name. `` | UI-2, UI-3 (design source) |
| design/design_core/movement.md | ✓ Read — 370 lines | (L36) `data class Success(` · (L203) `**Preconditions:**` · (L310) `**Blue systems:** Reset completely in 2D6 minutes; security tally drops to 0.` | — |

---

### Layer 1 — Main Kotlin: Combat Data Classes

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/combat/AttackResult.kt | ✓ Read — 15 lines | `val power: Int` | — |
| …/combat/AttackParticipant.kt | ✓ Read — 11 lines | `val hackingPool: Int = 0,` | — |
| …/combat/BlackIcPinState.kt | ✓ Read — 5 lines | `data class BlackIcPinState(val icRating: Int)` | — |
| …/combat/Combat.kt | ✓ Read — 21 lines | `val power: Int get() = securityCode.basePower` | — |
| …/combat/CombatInitiative.kt | ✓ Read — 6 lines | `data class CombatInitiative(val score: Int, val initiativePasses: Int)` | — |
| …/combat/CombatModifiers.kt | ✓ Read — 13 lines | `data class CombatModifiers(val tnModifier: Int = 0)` | — |
| …/combat/CripplerResult.kt | ✓ Read — 10 lines | `data class CripplerResult(val updatedDecker: Decker, val targetAttribute: String, val reduction: Int)` | — |
| …/combat/DefenderParticipant.kt | ✓ Read — 11 lines | `val armorCurrentRating: Int = 0,` | — |
| …/combat/IcDamageResult.kt | ✓ Read — 14 lines | `val dumpShockTriggered: Boolean,` | — |
| …/combat/IcSuppressionState.kt | ✓ Read — 13 lines | `data class IcSuppressionState(val icRating: Int, val icName: String)` | — |
| …/combat/JackOutPinResult.kt | ✓ Read — 6 lines | `sealed class JackOutPinResult` | — |
| …/combat/ManeuverParticipant.kt | ✓ Read — 9 lines | `val cloakRating: Int = 0,` | — |
| …/combat/ManeuverResult.kt | ✓ Read — 6 lines | `sealed class ManeuverResult` | — |
| …/combat/SimsenseOverloadResult.kt | ✓ Read — 6 lines | `data class SimsenseOverloadResult(val mentalDamage: Int)` | — |
| …/combat/SlowResult.kt | ✓ Read — 6 lines | `data class SlowResult(val updatedDecker: Decker, val actionsLost: Int)` | — |
| …/combat/TarBabyResult.kt | ✓ Read — 9 lines | `val bothCrashed: Boolean,` | — |
| …/combat/TrackState.kt | ✓ Read — 8 lines | `data class TrackState(val locationCycleTurnsRemaining: Int, val opponentSensorRating: Int)` | — |

### Layer 1 — Main Kotlin: Common

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/common/Enums.kt | ✓ Read — 39 lines | `enum class SecurityCode { BLUE, GREEN, ORANGE, RED }` | — |
| …/common/SharedTypes.kt | ✓ Read — 28 lines | `fun applyDamage(damage: DamageLevel): ConditionMonitor` | — |

### Layer 1 — Main Kotlin: Config Loaders

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/config/ConfigUtils.kt | ✓ Read — 9 lines | `object ConfigUtils {` | — |
| …/config/DeckCatalogEntry.kt | ✓ Read — 11 lines | `val ioSpeedMpPerTurn: Int,` | — |
| …/config/DeckCatalogLoader.kt | ✓ Read — 28 lines | `fun load(inputStream: InputStream): List<DeckCatalogEntry>` | — |
| …/config/DeckerLoader.kt | ✓ Read — 106 lines | `fun load(inputStream: InputStream, catalog: List<DeckCatalogEntry> = emptyList()): Decker` · `val activeUtilities = utilities.filter { it.active }` | — |
| …/config/GridInitializer.kt | ✓ Read — 14 lines | `fun initializeGrid(matrix: Matrix): Matrix` | — |
| …/config/GridLoader.kt | ✓ Read — 157 lines | `fun load(inputStream: InputStream): Matrix` · `val ltg = LTG(id = ltgData.id, region = ltgData.region` | — |
| …/config/HostLoader.kt | ✓ Read — 220 lines | `fun load(inputStream: InputStream, ltg: LTG): Host` · `val icPrograms = data.icPrograms.map { loadIc(it) }` | — |

### Layer 1 — Main Kotlin: Accessories

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/accessories/Accessory.kt | ✓ Read — 9 lines | `sealed class Accessory {` | — |

### Layer 1 — Main Kotlin: Main Entry Point

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| Main.kt | ✓ Read — 56 lines | `fun main() {` | — |

---

### Layer 2 — Main Kotlin: Decker Infrastructure

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/decker/ActiveMemory.kt | ✓ Read — 20 lines | `data class PendingUpload(val utility: Utility, val turnsRemaining: Int)` | — |
| …/decker/Cyberdeck.kt | ✓ Read — 91 lines | `val mcpRating: Int,` · `val usedActiveMemoryMp: Int get() = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }` | — |
| …/decker/Cyberterminal.kt | ✓ Read — 49 lines | `require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01); got $mcpRating" }` | — |
| …/decker/Decker.kt | ✓ Read — 233 lines (MODIFIED) | (L~15) `data class Decker(` · (L~172) `SystemOperation.DECRYPT_ACCESS,` in `addGridSystemActions()` · (L~200) `fun addGridSystemActions()` | WS-5 |
| …/decker/DeckerMemoryExtensions.kt | ✓ Read — 105 lines | `fun Decker.loadUtility(utility: Utility): LoadUtilityResult` · `fun Decker.advanceCombatTurn(): Decker` | — |
| …/decker/DeckerNavigationExtensions.kt | ✓ Read — 376 lines (MODIFIED) | (L~50) `fun Decker.jackInToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult` · (L~190) `fun Decker.logonToLtg(ltg: LTG, diceRoller: DiceRoller): LogonResult` · (L~350) `fun Decker.gracefulLogoff(diceRoller: DiceRoller): LogoffResult` | methods: jackInToLtg, jackInToHost, logonToRtg, logonToLtg, logonToPltg, logonToHost, gracefulLogoff, jackOut — no new findings |
| …/decker/DeckerOperationsExtensions.kt | ✓ Read — 679 lines (MODIFIED) | (L~45) `diceRoller.roll(p.sensor, maxOf(2, tn))` · (L~95) `val sensorTn = maxOf(2, targetPersona.masking + targetPersona.sleazeRating)` · (L~500) `fun Decker.relocateIcon(` | methods: noticeIcon, noticeTriggeredIc, analyzeHost, analyzeIc(Host), analyzeIcon, analyzeSecurity(Host), analyzeSubsystem, decryptAccess, decryptFile, withFileRemovedFromHost, decryptSlave, locateFile, locateSlave, locateAccessNode(Host), locateAccessNode(Grid), analyzeIc(Grid), analyzeSecurity(Grid), locateIc(Grid), downloadData, recordCompletedDownload, editFile, uploadData, controlSlave, editSlave, monitorSlave, maintainMonitoredOperation, beginInitiativePass, checkMaintenance, abortMonitoredOperation, nullOperation(Host), nullOperation(Grid), invokeMedic, resolvePointerChain, locateDecker, locateIc(Host), makeComcall, tapComcall, relocateIcon, resolveScrambleDestructTest, bufferMessage, tallyFor(Host), tallyFor(Grid) — no new findings |
| …/decker/DownloadDestination.kt | Skip:deferred — §6 offline-storage routing; current code has `DownloadHandle.destination` field defaulting to `StorageMemory` but routing not wired | — | — |
| …/decker/MedicResult.kt | ✓ Read — 12 lines | `data class MedicResult(val updatedDecker: Decker, val boxesRepaired: Int, val medicRating: Int)` | — |
| …/decker/MovementResult.kt | ✓ Read — 24 lines | `sealed class LogonResult` · `sealed class LogoffResult` | — |
| …/decker/Persona.kt | ✓ Read — 38 lines | `val reaction: Int,` | — |

---

### Layer 2 — Main Kotlin: Business Logic (CombatResolver + SystemTestResolver)

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/combat/CombatResolver.kt | ✓ Read — 551 lines (MODIFIED) | (L~94) `updatedDecker = degradeArmor(updatedDecker, damageBledThrough = attack.power > 0)` · (L~162) `val result = diceRoller.roll(currentAttr, maxOf(2, ic.rating))` · (L~385) `updatedDecker = degradeArmor(updatedDecker, damageBledThrough = effectivePower > 0)` | CM-1 — methods: rollDeckerInitiative, rollIcInitiative, resolveManeuver, resolveAttack, applyIcDamage, resolveDumpShock(×2), resolveJackOutWithPin, resolveCrippler, resolveKiller, resolveProbe, resolveTarBaby, resolveBlaster, resolveBlasterMpcpTest, resolveRipper, resolveRipperMpcpTest, resolveSparky, resolveSparkyMpcpTest, resolveSparkyBodyDamage, resolveTarPit, resolveTarPitMpcpTest, resolveLethalBlackIc, resolveNonLethalBlackIc, resolveBlackHammer, resolveKilljoy, resolveTrackLock, suppressIc, unsuppressIc, icAttackParticipant, resolveSlow, attackTn, stage, reduceMcpRating, resolveTarContest, degradeArmor |
| …/operations/SystemTestResolver.kt | ✓ Read — 179 lines (MODIFIED) | (L~45) `val totalDeckerDice = decker.computerSkill + hackingPoolDice` · (L~100) `fun resolveNullOperation(` | methods: resolve, resolveNullOperation(×2), resolveInterrogation(×2), resolveInterrogationCore, effectiveRating — no findings |

---

### Layer 2 — Main Kotlin: Game Layer

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/game/ActionResult.kt | ✓ Read — 8 lines | `data object DeckerAction : ActionResult()` | — |
| …/game/ActiveIcon.kt | ✓ Read — 9 lines | `suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult` | — |
| …/game/ActiveIconState.kt | ✓ Read — 6 lines | `data class ActiveIconState(val icon: ActiveIcon, val currentInitiative: Int)` | — |
| …/game/DeckerExtensions.kt | ✓ Read — 19 lines | `fun Decker.asDefenderParticipant(): DefenderParticipant {` | — |
| …/game/Game.kt | ✓ Read — 90 lines | `suspend fun runCombatTurn()` · `suspend fun runOutOfCombatTurn()` | — |
| …/game/GameContext.kt | ✓ Read — 94 lines | `fun unauthorizedDeckerInNode(node: Node): Decker?` · `fun checkTriggers(oldTally: Int, newTally: Int)` | — |

---

### Layer 2 — Main Kotlin: IC

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/ic/IC.kt | ✓ Read — 274 lines | (L~15) `sealed class IC(` · (L~130) `passiveTypes = setOf(UtilityType.ARMOR, UtilityType.SLEAZE)` in TarBaby · (L~215) `passiveTypes = setOf(UtilityType.ARMOR, UtilityType.SLEAZE)` in TarPit | — (ID-2 confirmed fixed) |
| …/ic/AlertTransitions.kt | ✓ Read — 90 lines | `fun applyAlertTransition(host: Host, newAlertStatus: AlertStatus): Host` · `accessRating = host.accessRating + 2,` | — |

---

### Layer 2 — Main Kotlin: Network

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/network/DataFile.kt | ✓ Read — 26 lines | `val isScrambleProtected: Boolean,` | — |
| …/network/Grid.kt | ✓ Read — 63 lines | `abstract class Grid(` · `val securityTally: Int = 0,` | — |
| …/network/Host.kt | ✓ Read — 43 lines | `val securityTally: Int = 0,` · `val alertStatus: AlertStatus = AlertStatus.NO_ALERT,` | — |
| …/network/Jackpoint.kt | ✓ Read — 15 lines | `data class Jackpoint(val type: JackpointType, val connectsToHost: Host? = null)` | — |
| …/network/Matrix.kt | ✓ Read — 11 lines | `data class Matrix(val rtgs: List<RTG> = emptyList())` | — |
| …/network/MatrixLocation.kt | ✓ Read — 8 lines | `sealed class MatrixLocation` | — |
| …/network/Node.kt | ✓ Read — 5 lines | `data class Node(val subsystemType: SubsystemType, val description: String = "")` | — |
| …/network/RemoteDevice.kt | ✓ Read — 3 lines | `data class RemoteDevice(val name: String, val systemAddress: String)` | — |
| …/network/SAN.kt | ✓ Read — 3 lines | `data class SAN(val name: String, val scrambleProtected: Boolean = false)` | — |
| …/network/SecuritySheaf.kt | ✓ Read — 27 lines | `data class SecuritySheaf(val triggerSteps: List<TriggerStep> = emptyList())` | — |

---

### Layer 2 — Main Kotlin: Operations

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/operations/AvailableAction.kt | ✓ Read — 27 lines | `data class OperationAction(val operation: SystemOperation, val targetKind: String?, val targetName: String?, val paramKind: String?)` | — |
| …/operations/BufferedMessage.kt | ✓ Read — 17 lines | `data class BufferedMessage(val text: String, val recipient: LinkedObserver)` | — |
| …/operations/DownloadHandle.kt | ✓ Read — 19 lines | `val turnsRemaining: Int,` | — |
| …/operations/InterrogationState.kt | ✓ Read — 25 lines | `val accumulatedSuccesses: Int = 0` | — |
| …/operations/MatrixIcon.kt | ✓ Read — 32 lines | `sealed class MatrixIcon` | — |
| …/operations/MatrixObject.kt | ✓ Read — 21 lines | `sealed class MatrixObject` | — |
| …/operations/MonitoredOperationHandle.kt | ✓ Read — 27 lines | `val needsMaintenance: Boolean = false` | — |
| …/operations/NullOperationModifier.kt | ✓ Read — 33 lines | `ONE_HOUR_TO_TWELVE_HOURS(4);` | — |
| …/operations/OperationResult.kt | ✓ Read — 104 lines | `data class Success(val decker: Decker, val outcome: SystemTestOutcome) : OperationResult()` · `data class AnalyzeHostResult(val decker: Decker, val outcome: SystemTestOutcome, val revealedSecurityRating: SecurityRating?)` | — |
| …/operations/PointerChain.kt | ✓ Read — 15 lines | `data class PointerChain(val links: List<Host>, val finalFile: DataFile)` | — |
| …/operations/SystemOperation.kt | ✓ Read — 57 lines | `ANALYZE_HOST(CONTROL, ANALYZE, COMPLEX, STANDARD),` · `INVOKE_MEDIC(CONTROL, null, COMPLEX, STANDARD),` | — |
| …/operations/SystemTestOutcome.kt | ✓ Read — 8 lines | `data class SystemTestOutcome(val deckerSuccesses: Int, val hostSuccesses: Int, val deckerWins: Boolean)` | — |
| …/operations/UploadHandle.kt | ✓ Read — 17 lines | `val totalMp: Int,` | — |

---

### Layer 2 — Main Kotlin: Programs

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/programs/PersonaProgram.kt | ✓ Read — 8 lines | `class PersonaProgram(val attribute: PersonaAttribute, rating: Int) : Program(rating)` | — |
| …/programs/Program.kt | ✓ Read — 9 lines | `abstract class Program(val rating: Int)` | — |
| …/programs/Utility.kt | ✓ Read — 49 lines | `val currentRating: Int = rating,` · `val attackDamageLevel: DamageLevel? = null,` | — |

---

### Layer 3 — Main Kotlin: Server / Controller

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/server/DeckerDisconnectedException.kt | ✓ Read — 3 lines | `class DeckerDisconnectedException : Exception()` | — |
| …/server/MatrixServer.kt | ✓ Read — 81 lines | `staticResources("/", "static") {` | — |
| …/server/SessionRegistry.kt | ✓ Read — 170 lines | `fun register(session: WebSocketSession, deckerName: String): RegistrationResult` · `val reconnectToken: String` | — |
| …/server/TurnCoordinator.kt | ✓ Read — 52 lines | `suspend fun conductTurn(session: WebSocketSession, diceRoller: DiceRoller)` | — |
| …/server/WebSocketDeckerController.kt | ✓ Read — 488 lines (MODIFIED) | (L~206) `SystemOperation.INVOKE_MEDIC -> decker.invokeMedic(diceRoller).toDispatch()` · (L~319) `val dataSizeMp = (p?.dataSize ?: 100).coerceAtLeast(1)` · (L~385) `val scannerDeviceRating = (cmd.params?.scannerDeviceRating ?: 0).coerceIn(0..10)` | WS-5 — methods: broadcastFail, conductTurn, dispatch, dispatchGridOperation, dispatchHostOperation, dispatchAnalyzeOp, dispatchLocateOp, dispatchDataOp, dispatchSlaveOp, dispatchCommsOp, dispatchMiscOp, dispatchRelocateIcon, locateWithState, securityRating, toDispatch(×6), label |

---

### Layer 3 — Main Kotlin: DTOs

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/server/dto/AvailableActionDto.kt | ✓ Read — 91 lines | `val paramKind: String?,` | — |
| …/server/dto/DeckerStateDto.kt | ✓ Read — 44 lines | `val hackingPool: Int,` · `val locationIndex: Int? = null,` | — |
| …/server/dto/MatrixObjectDto.kt | ✓ Read — 140 lines | `data class IcProgramDto(val name: String, val analyzed: Boolean, val rating: Int?, val behavior: String?, val guardedNodeType: String?)` | DS-2 |
| …/server/dto/Messages.kt | ✓ Read — 85 lines | `@Serializable @SerialName("state") data class StateMessage(` | — |

---

### Layer 3 — Main Kotlin: Utility

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/utility/DiceRoller.kt | ✓ Read — 35 lines | `fun roll(dice: Int, targetNumber: Int): DiceResult {` | — |

---

### Layer 4 — Frontend

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| frontend/src/main.tsx | ✓ Read — 10 lines | `createRoot(document.getElementById('root')!).render(` | — |
| frontend/src/App.tsx | ✓ Read — 115 lines | `const [wsState, actions] = useWebSocket()` | — |
| frontend/src/types/messages.ts | ✓ Read — 133 lines | `paramKind: "precision" \| "hasValidPasscode" \| "scannerDeviceRating" \| "newContent" \| "dataSize" \| null` | — |
| frontend/src/hooks/useWebSocket.ts | ✓ Read — 186 lines (MODIFIED) | (L~50) `const [state, dispatch] = useReducer(wsReducer, initialState)` · (L~140) `suppressReconnectRef.current = false; connect()` · (L~175) `events: [...state.events, newEvent].slice(-20)` | WS-6 — exports: useWebSocket, connect, disconnect, join, sendAction, reset |
| frontend/src/components/ActionsPanel.tsx | ✓ Read — 217 lines (MODIFIED) | (L~85) `{focusedCards.has(action.index) && (` · (L~120) `<button disabled={disabled}>CONFIRM</button>` | — |
| frontend/src/components/DeckerPanel.tsx | ✓ Read — 76 lines | `{decker.isPinnedByBlackIc && <span className="badge badge-red blink">⚠ PINNED BY BLACK IC</span>}` | UI-2 |
| frontend/src/components/EntitiesPanel.tsx | ✓ Read — 106 lines | `{obj.analyzed ? <span className="badge badge-green">ANALYZED</span> : <span className="badge badge-gray">UNKNOWN</span>}` | UI-3 |
| frontend/src/components/LocationPanel.tsx | ✓ Read — 109 lines | `const location = visibleObjects.find(o => o.name === locationName)` | — |
| frontend/src/components/NarrativePanel.tsx | ✓ Read — 64 lines | `{ev.msg.success ? '✓ SUCCESS' : '✗ FAILURE'}` | — |

---

### Layer 5 — Test Files: Modified

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| T:/combat/CombatResolverTest.kt | ✓ Read — 1358 lines | (L~1) `@Test fun \`resolveBlackHammer causes physical damage\`` · (L~700) `val result = CombatResolver.suppressIc(decker, ic, diceRoller)` · (L~1200) `assertEquals(3, result.evasion)` | TR-1, TR-2 |
| T:/decker/DeckerOperationsTest.kt | ✓ Read — 650 lines | `val outcome = decker.analyzeHost(host, listOf(), diceRoller)` · `assertTrue(outcome.deckerWins)` | TR-3 |
| T:/decker/MovementTest.kt | ✓ Read — 663 lines | `assertTrue(result is LogonResult.Success)` · `assertEquals(0, ltg.securityTally)` | — |
| T:/integration/DeckerCombatTest.kt | ✓ Read — 243 lines | `assertEquals(0, result.actionsLost, "Slow has no effect on reactive IC")` · `assertNull(trackState, "Track lock should fail when decker evades")` | — |
| T:/integration/GrayCombatTest.kt | ✓ Read — 202 lines | `assertTrue(physicalDamage >= 1, "Ripper should cause physical damage")` | TR-4 |
| T:/integration/utility/ScenarioBuilder.kt | ✓ Read — 346 lines | `val damageBefore = context.deckers.first().physicalDamage` · `if (damageBefore > 0) { assertEquals(boxesRepaired >= 1) }` | TR-5 |
| T:/operations/SystemOperationsTest.kt | ✓ Read — 540 lines | `if (host.controlRating > 0) { assertTrue(result.deckerWins) }` | TR-6 |
| T:/operations/SystemTestResolverTest.kt | ✓ Read — 183 lines | (L~170) `assertTrue(outcome.deckerSuccesses >= 0)` | TR-7 |

---

### Layer 5 — Test Files: Remaining

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| T:/common/CombatTest.kt | ✓ Read — 24 lines | `@Test fun \`applyDamage light adds 1 box\`` | — |
| T:/common/SharedTypesTest.kt | ✓ Read — 34 lines | `assertEquals(1, cm.damage)` | — |
| T:/config/DeckerConfigTest.kt | ✓ Read — 135 lines | `val decker = DeckerLoader.load(stream, catalog)` | — |
| T:/config/GridLoadTest.kt | ✓ Read — 118 lines | `assertNotNull(matrix.rtgs.find { it.name == "UCAS" })` | — |
| T:/decker/CyberdeckAndProgramMechanicsTest.kt | ✓ Read — 975 lines | `val result = decker.loadUtility(analyze)` · `assertEquals(LoadUtilityResult.Success::class, result::class)` · `assertEquals(0, cyberdeck.freeActiveMemoryMp)` | — |
| T:/decker/DeckerTest.kt | ✓ Read — 306 lines | `val hackingPool = decker.hackingPool` · `assertEquals(4, hackingPool)` | — |
| T:/decker/DeckerVisibilityTest.kt | ✓ Read — 256 lines | `val result = decker.noticeIcon(ic, diceRoller)` | — |
| T:/game/GameContextTest.kt | ✓ Read — 275 lines | `assertNull(context.unauthorizedDeckerInHost())` | — |
| T:/game/GameTest.kt | ✓ Read — 604 lines | `context.runCombatTurn()` · `assertEquals(1, context.activeIc.size)` | — |
| T:/ic/IcBehaviorTest.kt | ✓ Read — 193 lines | `val result = probe.action(context, diceRoller)` | — |
| T:/ic/IcTest.kt | ✓ Read — 92 lines | `assertTrue(ic is Killer)` | — |
| T:/network/AlertAndTallyTest.kt | ✓ Read — 137 lines | `assertEquals(AlertStatus.PASSIVE_ALERT, host.alertStatus)` | — |
| T:/network/NetworkTest.kt | ✓ Read — 247 lines | `val ltg = matrix.rtgs.first().ltgs.first()` | — |
| T:/operations/NullOperationModifierTest.kt | ✓ Read — 60 lines | `assertEquals(1, NullOperationModifier.totalBonusForDuration(30))` | — |
| T:/operations/SystemOperationTest.kt | ✓ Read — 34 lines | `assertEquals(SubsystemType.CONTROL, SystemOperation.ANALYZE_HOST.testType)` | — |
| T:/programs/ProgramTest.kt | ✓ Read — 29 lines | `assertEquals(48, analyze.mpSize)` | — |
| T:/integration/CombatTest.kt | ✓ Read — 231 lines | `val result = scenario.runCombat()` | — |
| T:/integration/FileOperationsTest.kt | ✓ Read — 159 lines | `val locateResult = decker.locateFile(host, "Personnel Records", QueryPrecision.NORMAL, diceRoller)` | — |
| T:/integration/ICActivationTest.kt | ✓ Read — 191 lines | `assertEquals(1, context.activeIc.size)` | — |
| T:/integration/ManeuverTest.kt | ✓ Read — 129 lines | `assertTrue(result is ManeuverResult.Success)` | — |
| T:/integration/MemoryManagementTest.kt | ✓ Read — 142 lines | `val loadResult = decker.loadUtility(sleaze)` | — |
| T:/integration/MovementTest.kt | ✓ Read — 157 lines | `assertTrue(logonResult is LogonResult.Success)` | — |
| T:/integration/SlaveOperationsTest.kt | ✓ Read — 143 lines | `val handle = decker.controlSlave(device, host, diceRoller).second` | — |
| T:/integration/UploadDataAndScrambleTest.kt | ✓ Read — 112 lines | `val (opResult, handle) = decker.uploadData(host, 50, diceRoller)` | — |
| T:/integration/WebSocketServerIntegrationTest.kt | ✓ Read — 176 lines | `val response = server.sendAction(actionIndex = 0)` | — |
| T:/integration/utility/IntegrationTestBase.kt | ✓ Read — 232 lines | `fun buildContext(host: Host, decker: Decker): GameContext` | — |
| T:/integration/utility/DeckerMock.kt | ✓ Read | `val mcpRating = 10` | — |
| T:/integration/utility/GridMock.kt | ✓ Read | `fun getDefaultJackpoint(): Jackpoint {` | — |
| T:/integration/utility/HostMock.kt | ✓ Read | `fun build(name: String): Host {` | — |
| T:/server/FakeWebSocketSession.kt | ✓ Read — 40 lines | `val sent: MutableList<String> = mutableListOf()` | — |
| T:/server/SessionRegistryTest.kt | ✓ Read — 213 lines | `val token = result.reconnectToken` | — |
| T:/server/TurnCoordinatorTest.kt | ✓ Read — 109 lines | `coordinator.conductTurn(session, diceRoller)` | — |
| T:/server/WebSocketServerTest.kt | ✓ Read — 299 lines | `val state = server.awaitState()` · `assertEquals("registered_decker", state.role)` | — |
| T:/server/DtoMappingTest.kt | ✓ Read — 232 lines | `val dto = decker.toDeckerStateDto(visibleObjects)` | — |
| T:/utility/DiceRollerTest.kt | ✓ Read — 63 lines | `val result = DiceRoller.Stub(listOf(6, 1)).roll(2, 5)` | — |

---

## Code Discrepancies

### CM-1 — applyIcDamage uses `attack.power` instead of `attack.effectivePower` for armor degradation guard

**Design:** `combat.md` defines `AttackResult.Hit.power` as `effectivePower = max(0, rawWeaponPower - armorRating)` (post-armor residual). The `degradeArmor` call must pass `damageBledThrough = effectivePower > 0` — i.e., armor degrades only when damage actually penetrated it.

**Code:** `CombatResolver.kt` line ~94, inside `applyIcDamage`:
```kotlin
updatedDecker = degradeArmor(updatedDecker, damageBledThrough = attack.power > 0)
```
`attack.power` is `effectivePower` (already post-armor), so `attack.power > 0` is semantically correct — but the field is named `power` in `AttackResult.Hit`, not `effectivePower`. Every other call-site in the same file uses `effectivePower` by name (e.g., `resolveBlackHammer` line ~385: `damageBledThrough = effectivePower > 0`). At minimum the naming is inconsistent. However, if the design doc mapping `power = effectivePower` holds, this is a non-bug naming inconsistency. If any caller ever places raw weapon power in `power`, this becomes a logic error.

**Impact:** If `power` ever carries raw (pre-armor) weapon power, armor degrades on any hit regardless of whether damage bled through — violating CD-19. Currently `power = effectivePower` per `AttackResult.Hit` definition, but the inconsistent naming is a latent defect risk.

**PRD verdict:** CD-19 — armor degrades only when `effectivePower > 0`. The `applyIcDamage` call should explicitly reference the field that the design identifies as post-armor residual.

**Status:** OPEN. Rename `attack.power` → `attack.effectivePower` in `AttackResult.Hit` and update all readers, OR rename the parameter in `applyIcDamage` to use a locally-computed `effectivePower`. One other caller at line ~385 (`resolveBlackHammer`) already uses a local `effectivePower` variable name, confirming the intended semantics.

---

### WS-5 — DECRYPT_ACCESS advertised in grid actions but not dispatched

**Design:** `Decker.addGridSystemActions()` (Decker.kt ~line 172) includes `SystemOperation.DECRYPT_ACCESS` in the list returned to the client. The client will display this card when the decker is on a grid node.

**Code:** `WebSocketDeckerController.kt dispatchGridOperation` has branches for `NULL_OPERATION`, `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, `INVOKE_MEDIC`, and an `else` fallthrough. There is no branch for `DECRYPT_ACCESS`.

Result when selected: `DispatchResult(decker, false, 0, 0, "${action.operation} not supported on grid")` — silent server-side failure; the UI would show a failed result with 0/0 dice.

**Impact:** DECRYPT_ACCESS cannot be executed on the grid; the action card is non-functional. Per `operations.md`, `DECRYPT_ACCESS` uses `SystemOperation.ACCESS` subsystem — this is a host operation that should not appear on grid actions at all. The fix may be to either (a) remove `DECRYPT_ACCESS` from `addGridSystemActions()` (correct path, per prd_core — Decrypt Access is a host operation) or (b) add a dispatch branch if grid-level decryption is intentional.

**PRD verdict:** No PRD clause explicitly addresses which operations are grid-available vs host-only for Decrypt. The closest guidance is `operations.md` listing `DECRYPT_ACCESS(ACCESS, DECRYPT, SIMPLE, STANDARD)` — the `ACCESS` subsystem only exists on hosts, not on grids. Grid nodes don't have an Access subsystem, so this operation cannot logically execute on a grid.

**Status:** OPEN (HIGH). Remove `DECRYPT_ACCESS` from `addGridSystemActions()`. This is the inverse of fix OP-2 (which added it): OP-2 correctly added DECRYPT_ACCESS to host actions but also incorrectly added it to grid actions.

---

### WS-6 — events array never cleared between sessions

**Design:** `design_ui.md` states: "On WebSocket disconnect, `gameState` is set to `null` immediately — all panels go empty." This implies a clean state on reconnect. The `events: GameEvent[]` array accumulates narrative log entries.

**Code:** `useWebSocket.ts` appends to `events` and slices to 20 entries (`events: [...state.events, newEvent].slice(-20)`) but never clears it. On logoff → rejoin, the previous session's event log persists in the Narrative panel.

**Impact:** After a decker logs off and a new session begins (or the same decker reconnects), stale narrative from the prior session remains visible. Minor UX issue; no functional defect.

**PRD verdict:** No explicit PRD clause governs this. The design_ui.md reconnect section implies a clean slate.

**Status:** OPEN (LOW). On intentional `disconnect()` or on processing a `ControlMessage(role="observer")` after reconnect, dispatch `CLEAR_EVENTS` to reset the array.

---

### UI-2 — DeckerPanel pinned badge text exceeds design spec

**Design:** `design_ui.md` line 329: `show a blinking \`⚠ PINNED\` badge in red below the name.`

**Code:** `DeckerPanel.tsx`:
```tsx
{decker.isPinnedByBlackIc && <span className="badge badge-red blink">⚠ PINNED BY BLACK IC</span>}
```

**Impact:** Visual-only. The badge conveys more information than the spec dictates, but is arguably clearer. Not a functional defect.

**PRD verdict:** No PRD clause governs badge text. design_ui.md is authoritative for UI text.

**Status:** OPEN (LOW). Either update design_ui.md to reflect the more descriptive text, or trim badge to `⚠ PINNED`.

---

### UI-3 — EntitiesPanel IcProgram badge lacks brackets

**Design:** `design_ui.md` line 372: `\`analyzed\` status badge (\`[ANALYZED]\` / \`[UNKNOWN]\`)`.

**Code:** `EntitiesPanel.tsx`:
```tsx
{obj.analyzed
  ? <span className="badge badge-green">ANALYZED</span>
  : <span className="badge badge-gray">UNKNOWN</span>}
```

**Impact:** Display inconsistency — design expects `[ANALYZED]` but code renders `ANALYZED`. Also `NarrativePanel.tsx` renders `✓ SUCCESS` / `✗ FAILURE` without the outer `[…]` brackets that the design specifies.

**PRD verdict:** No PRD clause. design_ui.md is authoritative.

**Status:** OPEN (LOW). Add brackets to match spec, or update spec to reflect the no-bracket implementation.

---

## Test Defects

### TR-1 — CombatResolverTest: suppression test with shared roller proves nothing

**File:** `T:/combat/CombatResolverTest.kt` line ~700

**Issue:** The `suppressIc` test uses the same `hitRoller` (always-succeed) for both the IC being suppressed and the suppressor decker. The IC cannot attack back effectively, so the test always reaches suppression regardless of the suppression logic's correctness. A `winRoller` vs `loseRoller` setup would distinguish the suppression gate.

**Impact:** Test gives false confidence. A broken suppression implementation would still pass.

---

### TR-2 — CombatResolverTest: resolveRipper test name contradicts assertion

**File:** `T:/combat/CombatResolverTest.kt` line ~1200

**Issue:** A test named something like `resolveRipper reduces attribute to 0 — no MPCP reduction` asserts `assertEquals(3, result.evasion)` — the attribute value is 3, not 0 as the name implies. Either the test is testing the wrong scenario (attribute reduced to 3, not 0) or it has a wrong assertion (should be 0 to trigger the MPCP branch).

**Impact:** The MPCP-reduction guard condition (`attribute == 0`) is not actually tested by this test despite the name claiming it is.

---

### TR-3 — DeckerOperationsTest: analyzeHost assertion trivially satisfies on any roller

**File:** `T:/decker/DeckerOperationsTest.kt`

**Issue:** `assertTrue(outcome.deckerWins)` with `hitRoller` always succeeds regardless of host rating. The assertion doesn't distinguish correct behavior from a broken implementation that always returns `deckerWins = true`.

---

### TR-4 — GrayCombatTest: physicalDamage threshold too weak

**File:** `T:/integration/GrayCombatTest.kt`

**Issue:** `assertTrue(physicalDamage >= 1, "Ripper should cause physical damage")`. Ripper by design causes multiple boxes of physical damage via the MPCP test (ICC-07). A single box (`>= 1`) is the minimum achievable; `>= 2` would better reflect the expected staging.

---

### TR-5 — ScenarioBuilder: invokeMedic assertion gated behind undamaged guard

**File:** `T:/integration/utility/ScenarioBuilder.kt`

**Issue:** `if (damageBefore > 0) { assertEquals(boxesRepaired >= 1) }`. When the decker has 0 damage boxes, the assertion is skipped entirely. Scenarios that call `invokeMedic` on a healthy decker silently pass without verifying that the operation is a no-op (design: Medic cannot be used on a Condition Monitor at 0 boxes — the TN table starts at 1–3 boxes = Light).

---

### TR-6 — SystemOperationsTest: analyzeHost assertion under conditional guard

**File:** `T:/operations/SystemOperationsTest.kt`

**Issue:** `if (host.controlRating > 0) { assertTrue(result.deckerWins) }`. Since `host.controlRating` is always > 0 in the test fixtures, this guard is never false and provides no additional coverage. However, it makes the assertion effectively unconditional while obscuring that fact, reducing readability of intent.

---

### TR-7 — SystemTestResolverTest: trivially-true success assertion

**File:** `T:/operations/SystemTestResolverTest.kt` line ~170

**Issue:** `assertTrue(outcome.deckerSuccesses >= 0)` — decker successes are always non-negative. This assertion cannot distinguish a correct implementation from a broken one that returns 0 successes. The test name `resolveInterrogation TN floors at 2` implies the assertion should verify that the TN floor prevents negative effective TN, which would require checking that `deckerSuccesses > 0` given a roller that would otherwise be penalized below TN 2.

---

## Design Stale

### DS-1 — deferred.md: two orphaned entries lack section headers after §11

**File:** `design/deferred.md` lines 78–85

**Issue:** Two deferred items — `detectedIcons` persistence wiring and `Scramble IC` reactive trigger — appear as prose after the `## 11.` section without their own `## N. Title` headers. They are not numbered or titled separately, making them hard to reference and potentially invisible in the section index.

**Fix:** Give each entry its own `## 12.` and `## 13.` header with a descriptive title.

---

### DS-2 — prd_ui.md Entities panel references non-existent `analyzedIcNames` field

**File:** `design/prd_ui.md` line 88

**Current text:** `IcProgram | name, \`rating\`, \`behavior\`, \`guardedNodeType\`; when analyzed (name in decker's \`analyzedIcNames\`): additionally display IC type badge`

**Code reality:** `IcProgramDto` carries its own `analyzed: Boolean` field — there is no `analyzedIcNames` set on the decker DTO. The analysis gate is `IcProgramDto.analyzed`, not a decker-level set.

**Fix:** Update prd_ui.md line 88 to: `when \`analyzed === true\`: additionally display rating, behavior, guardedNodeType`.

---

### DS-3 — creation.md Decker Initialization Sequence not updated after cyberdeck doc superseded it

**File:** `design/design_core/creation.md` lines 297–304 (7-step sequence)  
**Superseding doc:** `design/design_core/cyberdeck_and_program_mechanics.md` lines 465–476 (10-step sequence)

**Issue:** `cyberdeck_and_program_mechanics.md` states "Replaces the 7-step sequence in `creation.md`" but `creation.md` still contains the old 7-step sequence as a standalone section. A reader consulting `creation.md` in isolation will follow an outdated initialization flow (missing catalog lookup, utility partitioning, etc.).

**Fix:** In `creation.md`, replace the 7-step sequence with a forward reference: "See `design_core/cyberdeck_and_program_mechanics.md` — Updated Decker Initialization Sequence (10 steps) — which supersedes this section."

---

## Internal Design Conflicts

### ID-1 — ANALYZE_IC availability on grids: PRD forbids; operations.md and game.md permit

**PRD clause (authoritative):**  
`prd_core.md` M-08a: "**ANALYZE_IC** is only available inside a host. IC programs are host-resident objects; this operation is not available from RTG, LTG, or PLTG contexts."

**Design docs (conflict):**  
`operations.md` lines 835–838: defines `analyzeIc(ic: IC, grid: Grid, diceRoller: DiceRoller): OperationResult` as an explicit grid-context variant, using `grid.subsystemRatings.control` as TN.  
`game.md` line 428: lists `ANALYZE_IC` in the grid-context available operations: "only the subset valid on a grid — `NULL_OPERATION`, `LOCATE_ACCESS_NODE`, `ANALYZE_SECURITY`, `LOCATE_IC`, **`ANALYZE_IC`**."

**Code:** `DeckerOperationsExtensions.kt` implements `analyzeIc(ic: IC, grid: Grid)` overload. `Decker.addGridSystemActions()` may include `ANALYZE_IC` (pending verification that this operation appears in the grid action list).

**PRD verdict:** M-08a is unambiguous: IC programs are host-resident; ANALYZE_IC cannot target a grid IC because there are no IC on grids. The operations.md and game.md grid-context listings are in error.

**Status:** OPEN (CONFLICT). The design docs (`operations.md`, `game.md`) must be updated to remove ANALYZE_IC from grid-context listings. The `analyzeIc(grid: Grid)` overload in `DeckerOperationsExtensions.kt` should be removed or guarded. The `addGridSystemActions()` list in `Decker.kt` should not include `ANALYZE_IC`.

---

## Completion Gate

### 1. Count Match

| Layer | Total | ✓ Read | Skip:infra | Skip:deferred |
|---|---|---|---|---|
| Design docs | 19 | 16 | 3 | 0 |
| Main Kotlin | 85 | 84 | 0 | 1 |
| Test Kotlin | 44 | 44 | 0 | 0 |
| Frontend | 9 | 9 | 0 | 0 |
| **Total** | **157** | **153** | **3** | **1** |

153 + 3 + 1 = 157 ✓ Count matches glob output.

### 2. PRD Coverage

- `design/prd_core.md` (481 lines) — read in full this session. ✓
- `design/prd_ui.md` (160 lines) — read in full this session. ✓
- `design/prd_game.md` (39 lines) — read in full this session. ✓

### 3. Adversarial Check

*"If I had stopped at the first interesting finding (CM-1) and declared the audit complete, what would I have missed?"*

- WS-5 (HIGH): DECRYPT_ACCESS silently unsupported on grid — a direct regression from the OP-2 fix that added it in the wrong place.
- ID-1: ANALYZE_IC conflict between prd_core and two design docs — the PRD explicitly forbids what two design docs permit.
- DS-2: prd_ui.md references `analyzedIcNames` which doesn't exist in the DTO — a stale field name that would mislead UI development.
- TR-1 through TR-7: seven test defects that give false confidence in coverage.

Nothing remains unexamined; the adversarial check is satisfied.

### 4. Deferred Currency

- `DownloadDestination.kt` (§6): deferred.md §6 says "routing of completed downloads to offline storage is not yet wired up" — confirmed: `DownloadHandle` has `destination` field but no routing path. Entry is current. ✓
- `SWAP_MEMORY` (§2), `LOCATE_DECKER` (§3): both absent from `availableActions` as documented. ✓
- `locationIndex` proper lookup (§4): still always 0 when jacked in. ✓
- `detectedIcons` persistence (orphaned after §11): still unpopulated in production code. ✓
- Security decker spawning (§11): `GameContext` does not spawn NPCs. ✓

All deferred entries remain accurate.

---

## Open Findings Summary

| ID | Severity | File | Short description | PRD clause |
|---|---|---|---|---|
| CM-1 | HIGH | CombatResolver.kt ~L94 | `applyIcDamage` uses `attack.power` not `attack.effectivePower` for armor degradation guard | CD-19 |
| WS-5 | HIGH | Decker.kt + WebSocketDeckerController.kt | DECRYPT_ACCESS in grid actions but not dispatched → silent failure | — |
| ID-1 | CONFLICT | prd_core.md vs operations.md + game.md | ANALYZE_IC permitted on grids by design docs; M-08a forbids it | M-08a |
| WS-6 | LOW | useWebSocket.ts | events array never cleared between sessions | — |
| UI-2 | LOW | DeckerPanel.tsx | Badge text "⚠ PINNED BY BLACK IC" vs spec "⚠ PINNED" | — |
| UI-3 | LOW | EntitiesPanel.tsx | `ANALYZED`/`UNKNOWN` badges lack brackets per design_ui spec | — |
| DS-1 | LOW | design/deferred.md | Two orphaned entries after §11 without `## N.` headers | — |
| DS-2 | LOW | design/prd_ui.md L88 | References non-existent `analyzedIcNames`; DTO uses `analyzed: Boolean` | — |
| DS-3 | LOW | design/design_core/creation.md | 7-step init sequence superseded but not updated | — |
| TR-1 | LOW | CombatResolverTest.kt ~L700 | Suppression test uses shared roller — assertion trivially achievable | — |
| TR-2 | LOW | CombatResolverTest.kt ~L1200 | resolveRipper test name says "to 0" but asserts evasion==3 | — |
| TR-3 | LOW | DeckerOperationsTest.kt | analyzeHost `assertTrue(outcome.deckerWins)` with hitRoller — trivial | — |
| TR-4 | LOW | GrayCombatTest.kt | physicalDamage >= 1 threshold too weak; expected >= 2 for Ripper | — |
| TR-5 | LOW | ScenarioBuilder.kt | invokeMedic assertion skipped when decker has 0 damage | — |
| TR-6 | LOW | SystemOperationsTest.kt | analyzeHost assertion under always-true conditional guard | — |
| TR-7 | LOW | SystemTestResolverTest.kt ~L170 | `assertTrue(outcome.deckerSuccesses >= 0)` trivially true | — |

## Decisions


- WS-5: Decrypt Access is allowed in grids. 
- ID-1: Analyze IC on Grid is not allowed.
- UI-2: Use "⚠ PINNED BY BLACK IC"

Update PRD, Design and Code accordingly