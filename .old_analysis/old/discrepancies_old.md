# Design-vs-Code Discrepancies

Audit run: 2026-09-02. Process: `design/align.md`. Deferred items excluded per `design/deferred.md`.
Six agents read all 156 files in parallel (general-purpose, no Explore subagent).

---

## Coverage Manifest

Files found: **156** (85 main Kotlin · 44 test Kotlin · 9 frontend · 18 design).
✓ rows: **154** · Skip rows: **2** → count matches.

Path prefixes abbreviated: `…/` = `src/main/kotlin/com/shadowrun/matrix/`, `T/` = `src/test/kotlin/com/shadowrun/matrix/`.

### Design files

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| design/align.md | Skip:infra — audit process document, not a design spec | — | — |
| design/deferred.md | ✓ Read | `DownloadHandle does not include a destination field; all downloads route to deck storage.` | Exclusion reference |
| design/design.md | ✓ Read | `DeckCatalogLoader` | None |
| design/design_core/combat.md | ✓ Read | `resolveBlackHammer(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult` | None |
| design/design_core/creation.md | ✓ Read | `RTG-level PLTG replication: PLTGs declared under an RTG in grid.yaml … are replicated to **all** child LTGs of that RTG at load time.` | None |
| design/design_core/cyberdeck_and_program_mechanics.md | ✓ Read | `CD-17/CD-18: DF = ceil((Masking + Sleaze.currentRating) / 2)` | None |
| design/design_core/missing.md | ✓ Read | `## 8. Evade Detection — IC Re-Detection Timing (rules p. 224–225)` | None |
| design/design_core/movement.md | ✓ Read | `logonToPltg uses LOGON_TO_LTG SystemOperation` | None |
| design/design_core/operations.md | ✓ Read | `data class AccessNodeTarget(val query: String) : LocatedTarget()` | None |
| design/design_core/ord.md | ✓ Read | `**Grid** (abstract base; subtypes: RTG, LTG, PLTG)` | None |
| design/design_game/game.md | ✓ Read | `suspend fun runOutOfCombatTurn()` | None |
| design/design_ui/design_ui.md | ✓ Read | `paramKind field on Operation actions declares which inline control (if any) the card must render.` | None |
| design/discrepancies_old.md | Skip:infra — not read per user instruction | — | — |
| design/prd_core.md | ✓ Read | `CD-14: When a System Test is resolved … subtract its currentRating from the base target number. The target number floor is 2` | None |
| design/prd_game.md | ✓ Read | `interrogationStates: Map<String, InterrogationState>` | Reference only |
| design/prd_ui.md | ✓ Read | `UI-04: The token is cleared client-side when the user deliberately logs out.` | Source for WS-3 |
| design/protocol.md | ✓ Read | `` `bad_request` — JSON parse or deserialization error; `details` contains the exception message `` | None |
| design/start.md | ✓ Read | `The server starts on **http://localhost:8080** and serves the built React frontend as static files.` | None |

### Main source — accessories / combat / common / config

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/Main.kt | ✓ Read | `securityCode = SecurityCode.GREEN,` | None |
| …/accessories/Accessory.kt | ✓ Read | `data class HitcherJack(val type: HitcherJackType) : Accessory()` | None |
| …/combat/AttackParticipant.kt | ✓ Read | `val modifiers: CombatModifiers = CombatModifiers()` | None |
| …/combat/AttackResult.kt | ✓ Read | `data object Miss : AttackResult()` | None |
| …/combat/BlackIcPinState.kt | ✓ Read | `data class BlackIcPinState(val pinningIc: BlackIC)` | None |
| …/combat/Combat.kt | ✓ Read | `data class SimsenseOverload(val damageLevel: DamageLevel)` | None |
| …/combat/CombatInitiative.kt | ✓ Read | `data class CombatInitiative(val score: Int, val initiativePasses: Int)` | None |
| …/combat/CombatModifiers.kt | ✓ Read | `require(positionAttackTnBonus == 0 || positionAttackPowerBonus == 0)` | None |
| …/combat/CombatResolver.kt | ✓ Read | `fun resolveBlackHammer(targetDecker: Decker, attack: AttackResult.Hit, diceRoller: DiceRoller): IcDamageResult {` | None |
| …/combat/CripplerResult.kt | ✓ Read | `data class CripplerResult(val updatedDecker: Decker, val targetAttribute: PersonaAttributeType, val reduction: Int)` | None |
| …/combat/DefenderParticipant.kt | ✓ Read | `data class DefenderParticipant(val bod: Int, val armorCurrentRating: Int = 0, val personaStatus: PersonaStatus, val securityCode: SecurityCode)` | None |
| …/combat/IcDamageResult.kt | ✓ Read | `val personaOnlyCrashed: Boolean = false` | None |
| …/combat/IcSuppressionState.kt | ✓ Read | `val icRating: Int   // rating at crash moment; used if the decker later unsuppresses` | None |
| …/combat/JackOutPinResult.kt | ✓ Read | `data class JackOutPinResult(val succeeded: Boolean, val finalIcAttackTriggered: Boolean)` | None |
| …/combat/ManeuverParticipant.kt | ✓ Read | `data class ManeuverParticipant(val evasion: Int, val sensor: Int, val cloakRating: Int = 0, val lockOnRating: Int = 0, val hackingPool: Int = 0)` | None |
| …/combat/ManeuverResult.kt | ✓ Read | `data object Failure : ManeuverResult()` | None |
| …/combat/SimsenseOverloadResult.kt | ✓ Read | `data class SimsenseOverloadResult(val willpowerTestPassed: Boolean, val stressBoxesApplied: Int)` | None |
| …/combat/SlowResult.kt | ✓ Read | `data class SlowResult(val actionsLost: Int, val icInert: Boolean)` | None |
| …/combat/TarBabyResult.kt | ✓ Read | `data class TarBabyResult(val updatedDecker: Decker, val bothCrashed: Boolean, val deckerNoticed: Boolean)` | None |
| …/combat/TrackState.kt | ✓ Read | `val opponentSensorRating: Int = 0, val trackerMcpRating: Int = 0` | None |
| …/common/Enums.kt | ✓ Read | `val DamageLevel.boxes: Int get() = when (this) { DamageLevel.LIGHT -> 1` | None |
| …/common/SharedTypes.kt | ✓ Read | `val isCrashed: Boolean get() = isDestroyed` | None |
| …/config/ConfigUtils.kt | ✓ Read | `fun parseSubsystemRatings(value: Any?): Map<String, Int>` | None |
| …/config/DeckCatalogEntry.kt | ✓ Read | `val ioSpeedMpPerTurn: Int,` | None |
| …/config/DeckCatalogLoader.kt | ✓ Read | `model = data["model"] as String,` | None |
| …/config/DeckerLoader.kt | ✓ Read | `storedUtilities = utilities  // all utilities live in storage` | None |
| …/config/GridInitializer.kt | ✓ Read | `fun initialize(): Matrix {` | None |
| …/config/GridLoader.kt | ✓ Read | `name = data["id"] as String,` | None |
| …/config/HostLoader.kt | ✓ Read | `internal fun buildFromMap(data: Map<String, Any>): Host {` | None |

### Main source — decker

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/decker/ActiveMemory.kt | ✓ Read | `data class PendingUpload(val utility: Utility, val turnsRemaining: Int)` | None |
| …/decker/Cyberdeck.kt | ✓ Read | `val maxResponseIncrease: Int get() = minOf(3, mcpRating / 4)` | None |
| …/decker/Cyberterminal.kt | ✓ Read | `require(mcpRating <= 4)` | None |
| …/decker/Decker.kt | ✓ Read | `val detectionFactor: Int get() { val masking = cyberdeck.personaPrograms.firstOrNull { it.attributeType == PersonaAttributeType.MASKING }?.rating ?: 0` | None |
| …/decker/DeckerMemoryExtensions.kt | ✓ Read | `fun Decker.loadUtility(utility: Utility): LoadUtilityResult` | None |
| …/decker/DeckerNavigationExtensions.kt | ✓ Read | `status = com.shadowrun.matrix.common.PersonaStatus.INTRUDING` | None |
| …/decker/DeckerOperationsExtensions.kt | ✓ Read | `val handle = UploadHandle(file = DataFile(name = "upload to ${host.name}", sizeMp = dataSizeMp),` | None |
| …/decker/DownloadDestination.kt | Skip:deferred — deferred.md §6 (offline-storage routing) | `OfflineStorage(val accessory: Accessory.OfflineStorage)` | — |
| …/decker/MedicResult.kt | ✓ Read | `data class MedicResult(val updatedDecker: Decker, val boxesRepaired: Int, val medicRating: Int)` | None |
| …/decker/MovementResult.kt | ✓ Read | `data class Success(val deckerSuccesses: Int = 0, val hostSuccesses: Int = 0)` | None |
| …/decker/Persona.kt | ✓ Read | `val sleazeRating: Int = 0,` | None |

### Main source — game / ic / network / operations / programs / server / utility

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/game/ActionResult.kt | ✓ Read | `data class IcAttack(val message: String) : ActionResult()` | None |
| …/game/ActiveIcon.kt | ✓ Read | `interface ActiveIcon {` | None |
| …/game/ActiveIconState.kt | ✓ Read | `data class ActiveIconState(` | None |
| …/game/DeckerExtensions.kt | ✓ Read | `fun Decker.asDefenderParticipant(): DefenderParticipant {` | None |
| …/game/Game.kt | ✓ Read | `suspend fun runCombatTurn() {` | None |
| …/game/GameContext.kt | ✓ Read | `val matrix: Matrix = Matrix()` | None |
| …/ic/IC.kt | ✓ Read | `class TarBaby(…) : WhiteIC("Tar Baby", rating, IcBehavior.REACTIVE, …)` | None |
| …/network/AlertTransitions.kt | ✓ Read | `fun checkGridTriggers(grid: Grid, oldTally: Int, newTally: Int): GridTriggerResult` | None |
| …/network/DataFile.kt | ✓ Read | `return name == other.name && isScrambleProtected == other.isScrambleProtected && sizeMp == other.sizeMp` | None |
| …/network/Grid.kt | ✓ Read | `sealed class Grid(open val name: String, open val securityRating: SecurityRating, …)` | None |
| …/network/Host.kt | ✓ Read | `val offline: Boolean = false` | None |
| …/network/Jackpoint.kt | ✓ Read | `require((connectsToLtg == null) != (connectsToHost == null))` | None |
| …/network/Matrix.kt | ✓ Read | `fun getHost(rtgName: String, ltgName: String, hostName: String): Host?` | None |
| …/network/MatrixLocation.kt | ✓ Read | `data class OnPLTG(val pltg: PLTG) : MatrixLocation()` | None |
| …/network/Node.kt | ✓ Read | `data class Node(val subsystemType: SubsystemType, val description: String = "")` | None |
| …/network/RemoteDevice.kt | ✓ Read | `data class RemoteDevice(val name: String, val systemAddress: String)` | None |
| …/network/SAN.kt | ✓ Read | `data class SAN(val name: String, val isScrambleProtected: Boolean = false)` | None |
| …/network/SecuritySheaf.kt | ✓ Read | `val securityDeckerCount: Int = 0` | None |
| …/operations/AvailableAction.kt | ✓ Read | `data class JackOut(override val actionType: ActionType = FREE) : AvailableAction()` | None |
| …/operations/BufferedMessage.kt | ✓ Read | `data class BufferedMessage(val text: String, val recipient: LinkedObserver)` | None |
| …/operations/DownloadHandle.kt | ✓ Read | `val destination: DownloadDestination = DownloadDestination.StorageMemory` | None (destination field deferred §6) |
| …/operations/InterrogationState.kt | ✓ Read | `val accumulatedSuccesses: Int = 0` | None |
| …/operations/MatrixIcon.kt | ✓ Read | `data class PersonaIcon(val persona: Persona, val sleazeRating: Int = 0) : Icon()` | None |
| …/operations/MatrixObject.kt | ✓ Read | `data class IcProgram(val ic: IC, val analyzed: Boolean = false) : MatrixObject()` | None |
| …/operations/MonitoredOperationHandle.kt | ✓ Read | `val needsMaintenance: Boolean = false` | None |
| …/operations/NullOperationModifier.kt | ✓ Read | `fun totalBonusForDuration(seconds: Int): Int { val base = forDuration(seconds).bonus` | None |
| …/operations/OperationResult.kt | ✓ Read | `data class AccessNodeTarget(val address: String) : LocatedTarget()` | None |
| …/operations/PointerChain.kt | ✓ Read | `data class PointerChain(val links: List<Host>, val finalFile: DataFile)` | None |
| …/operations/SystemOperation.kt | ✓ Read | `ANALYZE_SUBSYSTEM(null, UtilityType.ANALYZE, SIMPLE, STANDARD)` | None |
| …/operations/SystemTestOutcome.kt | ✓ Read | `val deckerWins: Boolean` | None |
| …/operations/SystemTestResolver.kt | ✓ Read | `val clampedBase = maxOf(2, baseSubsystemRating - utilityRating)` | None |
| …/operations/UploadHandle.kt | ✓ Read | `data class UploadHandle(val file: DataFile, val totalMp: Int, val ioSpeedMpPerTurn: Int, val turnsRemaining: Int, val active: Boolean = true)` | None |
| …/programs/PersonaProgram.kt | ✓ Read | `class PersonaProgram(val attributeType: PersonaAttributeType, rating: Int) : Program(name = attributeType.name, rating = rating, multiplier = 1)` | None |
| …/programs/Program.kt | ✓ Read | `val mpSize: Int get() = rating * rating * multiplier` | None |
| …/programs/Utility.kt | ✓ Read | `LOCK_ON(3, DEFENSIVE)` | None |
| …/server/DeckerDisconnectedException.kt | ✓ Read | `class DeckerDisconnectedException : Exception("decker disconnected mid-turn")` | None |
| …/server/MatrixServer.kt | ✓ Read | `ErrorMessage(message = ErrorCode.BAD_REQUEST, details = null)` | None |
| …/server/SessionRegistry.kt | ✓ Read | `if (storedToken != null && (msg.reconnectToken == null || msg.reconnectToken != storedToken))` | None |
| …/server/TurnCoordinator.kt | ✓ Read | `if (f == null || f.isCompleted) return@withLock null to "NO_ACTION_PENDING"` | None |
| …/server/WebSocketDeckerController.kt | ✓ Read | `decker.controlSlave(device, host, diceRoller).first.toDispatch()` | None |
| …/server/dto/AvailableActionDto.kt | ✓ Read | `SystemOperation.UPLOAD_DATA -> "dataSize"` | None |
| …/server/dto/DeckerStateDto.kt | ✓ Read | `locationIndex = if (currentLocation != null) 0 else null,` | Skip:deferred §4 |
| …/server/dto/MatrixObjectDto.kt | ✓ Read | `@JsonClassDiscriminator("kind")` | None |
| …/server/dto/Messages.kt | ✓ Read | `@SerialName("bad_request") BAD_REQUEST,` | None |
| …/utility/DiceRoller.kt | ✓ Read | `do { face = random.nextInt(1, 7)` | None |

### Test files

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| T/combat/CombatResolverTest.kt | ✓ Read | `` fun `resolveNonLethalBlackIc MPCP death blow fires on icon CM crash`() `` | None |
| T/combat/CombatTest.kt | ✓ Read | `` fun `DumpShock level maps correctly to security code`() `` | None |
| T/common/SharedTypesTest.kt | ✓ Read | `` fun `ConditionMonitor applyDamage does not exceed maxBoxes`() `` | None |
| T/config/DeckerConfigTest.kt | ✓ Read | `assertEquals(6, decker.cyberdeck.detectionFactor(masking, sleaze))` | None |
| T/config/GridLoadTest.kt | ✓ Read | `assertTrue(matrix.rtgs.size >= 19, "Expected at least 19 RTGs …")` | None |
| T/decker/CyberdeckAndProgramMechanicsTest.kt | ✓ Read | `` fun `SystemOperation has 29 entries`() { assertEquals(29, SystemOperation.entries.size) } `` | None |
| T/decker/DeckerOperationsTest.kt | ✓ Read | `val highMaskTarget = Persona(bod = 4, evasion = 4, masking = 8, sensor = 4, sleazeRating = 4)` | None |
| T/decker/DeckerTest.kt | ✓ Read | Basic model tests for Decker/Cyberdeck/Persona | None |
| T/decker/DeckerVisibilityTest.kt | ✓ Read | `LOCATE_DECKER correctly excluded from availableActions` | None |
| T/decker/MovementTest.kt | ✓ Read | Tally accumulation, dump shock, interrogation state clearing on logoff | None |
| T/game/GameContextTest.kt | ✓ Read | `` fun `checkTriggers spawns IC when threshold crossed`() `` | None |
| T/game/GameTest.kt | ✓ Read | `assertIs<ActionResult.DeckerAction>(result)` | None |
| T/ic/IcBehaviorTest.kt | ✓ Read | `val roller = DiceRoller(stubRandom(1, 1, 1, 1, 1, 1, 6, 1, 6, 1, 6, 1, 6, 1, 1, 1, 1, 1, 1, 1))` | None |
| T/ic/IcTest.kt | ✓ Read | `` fun `withConditionMonitor returns new IC with updated conditionMonitor preserving other fields`() `` | None |
| T/integration/AlertAndTallyTest.kt | ✓ Read | `assertEquals(0, loc.rtg.securityTally, "Tally should reset to 0 on a fresh RTG …")` | None |
| T/integration/CombatTest.kt | ✓ Read | `` fun `Killer IC attacks the decker after crossing threshold 10 in FILES node`() `` | None |
| T/integration/DeckerCombatTest.kt | ✓ Read | `` fun `suppressIc adds IC to suppressedIc list and reduces effectiveDetectionFactor`() `` | None |
| T/integration/FileOperationsTest.kt | ✓ Read | `assertIs<LocateResult.Located>(result.second, "Should accumulate 5+ successes and locate the file")` | None |
| T/integration/GrayCombatTest.kt | ✓ Read | `` fun `Ripper IC can reduce a persona attribute to zero`() `` | None |
| T/integration/ICActivationTest.kt | ✓ Read | `icon.assertAlertStatus(AlertStatus.NO_ALERT)` | None |
| T/integration/ManeuverTest.kt | ✓ Read | `private fun weakOpponent() = ManeuverParticipant(evasion = 1, sensor = 1)` | None |
| T/integration/MemoryManagementTest.kt | ✓ Read | `assertTrue(result.decker.cyberdeck.activeUtilities.none { it.type == UtilityType.ARMOR } \|\| result.decker.cyberdeck.pendingUploads.none { it.utility.type == UtilityType.ARMOR }` | None |
| T/integration/MovementTest.kt | ✓ Read | `` fun `integration - jack in to LTG, traverse RTGs, enter host, logoff via game layer`() `` | None |
| T/integration/SlaveOperationsTest.kt | ✓ Read | `assertIs<LocateResult.Located>(result.second, "Should accumulate 3+ successes and locate the device")` | None |
| T/integration/UploadDataAndScrambleTest.kt | ✓ Read | `assertTrue(result.dataDestroyed, "Scramble IC should destroy data when it rolls successes")` | None |
| T/integration/WebSocketServerIntegrationTest.kt | ✓ Read | `assertEquals(setOf("UCAS-SEA", "UCAS-CHI", "UCAS-NYC", "UCAS-BOS"), ltgNames.toSet())` | None |
| T/integration/utility/DeckerMock.kt | ✓ Read | `mcpRating = 10,` | None |
| T/integration/utility/GridMock.kt | ✓ Read | `fun getDefaultJackpoint() : Jackpoint {` | None |
| T/integration/utility/HostMock.kt | ✓ Read | `fun build(name: String) : Host {` | None |
| T/integration/utility/IntegrationTestBase.kt | ✓ Read | `protected fun failRoller() = DiceRoller(object : Random() {` | None |
| T/integration/utility/ScenarioBuilder.kt | ✓ Read | `fun step(name: String, block: StepContext.() -> Unit) {` | None |
| T/network/AlertTransitionsTest.kt | ✓ Read | `` fun `PASSIVE_ALERT applied twice stacks the +2 bonus`() `` | None |
| T/network/NetworkTest.kt | ✓ Read | `` fun `Host allows multiple nodes of the same subsystem type`() `` | None |
| T/operations/NullOperationModifierTest.kt | ✓ Read | `assertEquals(5, NullOperationModifier.totalBonusForDuration(86400))` | None |
| T/operations/SystemOperationTest.kt | ✓ Read | `assertEquals(29, SystemOperation.entries.size)` | None |
| T/operations/SystemOperationsTest.kt | ✓ Read | `val result = d.editFile(file, h, byteArrayOf(1, 2, 3), winRoller, attemptAuthentication = false)` | None |
| T/operations/SystemTestResolverTest.kt | ✓ Read | `assertEquals(0, outcome.hostSuccesses.coerceAtMost(0))  // just confirming no crash; real assertion below` | None |
| T/programs/ProgramTest.kt | ✓ Read | `assertEquals(4 * 4 * 5, deadlyAttack.mpSize)  // multiplier = 3+2 = 5` | None |
| T/server/FakeWebSocketSession.kt | ✓ Read | `suspend fun nextText(): String = (withTimeout(5_000) { _outgoing.receive() } as Frame.Text).readText()` | None |
| T/server/SessionRegistryTest.kt | ✓ Read | `` `receiveJoin with 33-char name sends name_too_long error` `` | None |
| T/server/TurnCoordinatorTest.kt | ✓ Read | `` `claimAction returns NO_ACTION_PENDING when pendingAction is already completed` `` | None |
| T/server/WebSocketServerTest.kt | ✓ Read | `` `JoinMessage reconnect with wrong token is rejected` `` | None |
| T/server/dto/DtoMappingTest.kt | ✓ Read | `assertNull(dto.rating)` | None |
| T/utility/DiceRollerTest.kt | ✓ Read | `` fun `exploding six adds extra roll and may exceed target`() `` | None |

### Frontend files

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| frontend/src/App.tsx | ✓ Read | `<div className="join-title">MATRIX OF SHADOWRUN</div>` | None |
| frontend/src/components/ActionsPanel.tsx | ✓ Read | `function buildParams(paramKind: string \| null, cs: CardState): ActionParams \| undefined {` | None |
| frontend/src/components/DeckerPanel.tsx | ✓ Read | `{decker.isPinnedByBlackIc && ( <div className="pinned-alert">⚠ PINNED BY BLACK IC</div> )}` | None |
| frontend/src/components/EntitiesPanel.tsx | ✓ Read | `{obj.isPointer && <span className="badge badge-amber">PTR</span>}` | None |
| frontend/src/components/LocationPanel.tsx | ✓ Read | `decker.locationIndex != null ? (visibleObjects[decker.locationIndex] as MatrixObjectDto \| undefined) ?? null` | None |
| frontend/src/components/NarrativePanel.tsx | ✓ Read | `<span className="event-badge">{ev.msg.success ? 'SUCCESS' : 'FAILURE'}</span>` | None |
| frontend/src/hooks/useWebSocket.ts | ✓ Read | `case 'DISCONNECTED': return { ...state, connected: false, role: null, gameState: null, events: [] }` | None |
| frontend/src/main.tsx | ✓ Read | `ReactDOM.createRoot(document.getElementById('root')!).render(` | None |
| frontend/src/types/messages.ts | ✓ Read | `export type SystemOperation = \| 'ANALYZE_HOST' \| 'ANALYZE_IC' … \| 'INVOKE_MEDIC'` | None |

---

## Discrepancies

None — all findings resolved.

---

## Files Read with No Discrepancies Found

**Design:** design.md · design_core/creation.md · design_core/missing.md · design_core/movement.md · design_ui/design_ui.md · design_game/game.md · protocol.md · start.md · prd_core.md · prd_game.md · prd_ui.md · deferred.md

---

## Open Findings

None.
