# Design-vs-Code Discrepancies

Audit run: 2026-09-02. Process: `design/align.md`. Deferred items excluded per `design/deferred.md`.
Six agents read all 156 files in parallel (general-purpose, no Explore subagent).

---

## Coverage Manifest

Files found: **156** (85 main Kotlin · 44 test Kotlin · 9 frontend · 18 design).
✓ rows: **153** · Skip rows: **3** → count matches.

Path prefixes abbreviated: `…/` = `src/main/kotlin/com/shadowrun/matrix/`, `T/` = `src/test/kotlin/com/shadowrun/matrix/`.

### Design files

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| design/align.md | Skip:infra — audit process document, not a design spec | — | — |
| design/deferred.md | ✓ Read | `DownloadHandle does not include a destination field; all downloads route to deck storage.` | Exclusion reference; DS-4 |
| design/design.md | ✓ Read | `"Urility(Utility)" [sic]` | None |
| design/design_core/combat.md | ✓ Read | `For decker attacks, attackDicePool and weaponPower are both the offensive program's currentRating.` | None |
| design/design_core/creation.md | ✓ Read | `PLTGs declared under an RTG in grid.yaml … are replicated to all child LTGs of that RTG at load time.` | DS-11 |
| design/design_core/cyberdeck_and_program_mechanics.md | ✓ Read | `CD-18a: effectiveDetectionFactor — the Detection Factor used by the host in System Tests — equals max(2, detectionFactor - suppressionDfPenalty).` | None |
| design/design_core/missing.md | ✓ Read | `## 8. Evade Detection — IC Re-Detection Timing (rules p. 224–225)` | DS-6 |
| design/design_core/movement.md | ✓ Read | `If current location is OnPLTG (PLTG-to-PLTG hop): inheritedTally = 0. No tally is carried from the source PLTG.` | None |
| design/design_core/operations.md | ✓ Read | `Grid-context variants: Four operations accept a Grid (LTG, RTG, or PLTG) in place of a Host.` | ID-1 |
| design/design_core/ord.md | ✓ Read | `PointerTargetHost: Host? — the host where the actual data resides (non-null when IsPointer = true)` | DS-2 |
| design/design_game/game.md | ✓ Read | `only the subset valid on a grid — NULL_OPERATION, LOCATE_ACCESS_NODE, ANALYZE_SECURITY, LOCATE_IC, ANALYZE_IC.` | ID-1 |
| design/design_ui/design_ui.md | ✓ Read | `IcProgram card fields: name, analyzed status badge ([ANALYZED] / [UNKNOWN]), and — only when analyzed === true — rating, behavior, guardedNodeType.` | None |
| design/discrepancies_old.md | Skip:infra — not read per user instruction | — | — |
| design/prd_core.md | ✓ Read | `Hacking Pool dice may be added to any test made in the Matrix — System Tests, Attack or Defense tests, maneuvers, or Attribute Tests.` | None |
| design/prd_game.md | ✓ Read | `each decker's action count per turn follows SO-01/SO-02: ⌈Persona Reaction ÷ 10⌉ + Response Increase` | None |
| design/prd_ui.md | ✓ Read | `UI-01: When the server accepts a new decker registration it must issue a reconnectToken` | DS-8, DS-9, DS-10 |
| design/protocol.md | ✓ Read | `reconnectToken is required when rejoining after a disconnect to reclaim the same decker slot. Omit on first join.` | None |
| design/start.md | ✓ Read | `The server starts on http://localhost:8080 and serves the built React frontend as static files.` | None |

### Main source — Main.kt / accessories / combat / common / config

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/Main.kt | ✓ Read | `val controller = WebSocketDeckerController(registry, decker)` | None (deferred §1) |
| …/accessories/Accessory.kt | ✓ Read | `data class HitcherJack(val type: HitcherJackType) : Accessory()` | None |
| …/combat/AttackParticipant.kt | ✓ Read | `val rawDamageLevel: DamageLevel, val modifiers: CombatModifiers = CombatModifiers()` | None |
| …/combat/AttackResult.kt | ✓ Read | `val rawWeaponPower: Int, val power: Int` | DS-1 |
| …/combat/BlackIcPinState.kt | ✓ Read | `data class BlackIcPinState(val pinningIc: BlackIC)` | None |
| …/combat/Combat.kt | ✓ Read | `val level: DamageLevel get() = when (securityRating.code) { SecurityCode.BLUE -> DamageLevel.LIGHT` | None |
| …/combat/CombatInitiative.kt | ✓ Read | `data class CombatInitiative(val score: Int, val initiativePasses: Int)` | None |
| …/combat/CombatModifiers.kt | ✓ Read | `require(positionAttackTnBonus == 0 \|\| positionAttackPowerBonus == 0)` | None |
| …/combat/CombatResolver.kt | ✓ Read | `degradeArmor(updatedDecker, damageBledThrough = attack.power > 0)` · `updatedUtilities[armorIdx] = Utility(armorUtil.type, armorUtil.rating, armorUtil.attackDamageLevel, armorUtil.currentRating - 1, armorUtil.sourceCode)` · methods: rollDeckerInitiative, rollIcInitiative, resolveManeuver, resolveAttack, applyIcDamage, resolveDumpShock (×2), resolveJackOutWithPin, resolveCrippler, resolveKiller, resolveProbe, resolveTarBaby, resolveBlaster, resolveBlasterMpcpTest, resolveRipper, resolveRipperMpcpTest, resolveSparky, resolveSparkyMpcpTest, resolveSparkyBodyDamage, resolveTarPit, resolveTarPitMpcpTest, resolveLethalBlackIc, resolveNonLethalBlackIc, resolveBlackHammer, resolveKilljoy, resolveTrackLock, suppressIc, unsuppressIc, icAttackParticipant, resolveSlow, attackTn, stage, reduceMcpRating, resolveTarContest, degradeArmor | CM-1, CM-2, CM-3 |
| …/combat/CripplerResult.kt | ✓ Read | `val targetAttribute: PersonaAttributeType, val reduction: Int` | None |
| …/combat/DefenderParticipant.kt | ✓ Read | `val personaStatus: PersonaStatus, val securityCode: SecurityCode` | None |
| …/combat/IcDamageResult.kt | ✓ Read | `val mpcpReductionOnKill: Int = 0, val personaOnlyCrashed: Boolean = false` | None |
| …/combat/IcSuppressionState.kt | ✓ Read | `val ic: IC, val icRating: Int   // rating at crash moment; used if the decker later unsuppresses` | None |
| …/combat/JackOutPinResult.kt | ✓ Read | `val succeeded: Boolean, val finalIcAttackTriggered: Boolean` | None |
| …/combat/ManeuverParticipant.kt | ✓ Read | `val cloakRating: Int = 0, val lockOnRating: Int = 0, val hackingPool: Int = 0` | None |
| …/combat/ManeuverResult.kt | ✓ Read | `data class Success(val netSuccesses: Int) : ManeuverResult()` | None |
| …/combat/SimsenseOverloadResult.kt | ✓ Read | `val willpowerTestPassed: Boolean, val stressBoxesApplied: Int` | None |
| …/combat/SlowResult.kt | ✓ Read | `data class SlowResult(val actionsLost: Int, val icInert: Boolean)` | None |
| …/combat/TarBabyResult.kt | ✓ Read | `val bothCrashed: Boolean, val deckerNoticed: Boolean` | None |
| …/combat/TrackState.kt | ✓ Read | `val trackingIcRating: Int, val locationCycleTurnsRemaining: Int, val opponentSensorRating: Int, val trackerMcpRating: Int` | None |
| …/common/Enums.kt | ✓ Read | `val DamageLevel.boxes: Int get() = when (this) { DamageLevel.LIGHT -> 1` | None |
| …/common/SharedTypes.kt | ✓ Read | `fun applyDamage(boxes: Int): ConditionMonitor = copy(damage = (damage + boxes).coerceAtMost(maxBoxes))` | None |
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
| …/decker/ActiveMemory.kt | ✓ Read | `data class InsufficientMemory(val decker: Decker, val requiredMp: Int, val availableMp: Int` | None |
| …/decker/Cyberdeck.kt | ✓ Read | `val maxResponseIncrease: Int get() = minOf(3, mcpRating / 4)` | None |
| …/decker/Cyberterminal.kt | ✓ Read | `require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01), got $mcpRating" }` | None |
| …/decker/Decker.kt | ✓ Read | `val hackingPool: Int get() = (intelligence + cyberdeck.mcpRating) / 3` | OP-2 |
| …/decker/DeckerMemoryExtensions.kt | ✓ Read | `fun Decker.loadUtility(utility: Utility): LoadUtilityResult` · `completedDownloads.forEach { handle -> result = result.recordCompletedDownload(handle.file) }` · methods: loadUtility, unloadUtility, swapUtility, advanceCombatTurn | None |
| …/decker/DeckerNavigationExtensions.kt | ✓ Read | `status = com.shadowrun.matrix.common.PersonaStatus.LEGITIMATE` · `else -> this` · methods: jackInToLtg, jackInToHost, logonToRtg, logonToLtg, logonToPltg, logonToHost, gracefulLogoff, jackOut, requireNotJackedIn, requireJackpoint, performLogon, accessRatingAndSecurityValue, withDestinationTallyEmbedded | None |
| …/decker/DeckerOperationsExtensions.kt | ✓ Read | `diceRoller.roll(decker.persona.sensor, masking / 2)` · `is MatrixLocation.OnPLTG -> if (loc.pltg === grid) loc.pltg.securityTally else 0` · methods: noticeIcon, noticeTriggeredIc, analyzeHost, analyzeIc (×2), analyzeIcon, analyzeSecurity (×2), analyzeSubsystem, decryptAccess, decryptFile, withFileRemovedFromHost, decryptSlave, locateFile, locateSlave, locateAccessNode (×2), locateIc (×2), downloadData, recordCompletedDownload, editFile, uploadData, controlSlave, editSlave, monitorSlave, maintainMonitoredOperation, beginInitiativePass, checkMaintenance, abortMonitoredOperation, nullOperation (×2), invokeMedic, resolvePointerChain, locateDecker, makeComcall, tapComcall, relocateIcon, resolveScrambleDestructTest, bufferMessage, tallyFor (×2) | CD-1, OP-3 |
| …/decker/DownloadDestination.kt | Skip:deferred §6 — offline-storage routing | `sealed class DownloadDestination` | — |
| …/decker/MedicResult.kt | ✓ Read | `val updatedDecker: Decker, val boxesRepaired: Int, val medicRating: Int` | None |
| …/decker/MovementResult.kt | ✓ Read | `data class GracefulSuccess(val decker: Decker) : LogoffResult()` | None |
| …/decker/Persona.kt | ✓ Read | `val status: PersonaStatus = PersonaStatus.LEGITIMATE` | DS-5 |

### Main source — game / ic / network / operations / programs / server / utility

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| …/game/ActionResult.kt | ✓ Read | `data class IcAttack(val message: String) : ActionResult()` | None |
| …/game/ActiveIcon.kt | ✓ Read | `suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult` | None |
| …/game/ActiveIconState.kt | ✓ Read | `data class ActiveIconState(val icon: ActiveIcon, val currentInitiative: Int)` | None |
| …/game/DeckerExtensions.kt | ✓ Read | `fun Decker.asDefenderParticipant(): DefenderParticipant {` | None |
| …/game/Game.kt | ✓ Read | `suspend fun runCombatTurn() {` | None |
| …/game/GameContext.kt | ✓ Read | `val newlyTriggered = host.securitySheaf.triggerSteps` | None |
| …/ic/IC.kt | ✓ Read | `fun initiativeDice(securityCode: SecurityCode): Int = when (securityCode) { SecurityCode.BLUE -> 1` | None |
| …/network/AlertTransitions.kt | ✓ Read | `applyAlertTransition applies +2 to all 5 subsystems on Passive Alert` | None |
| …/network/DataFile.kt | ✓ Read | `val pointerToHost: Host? = null` | DS-2 |
| …/network/Grid.kt | ✓ Read | `val parentLtg: LTG` (PLTG field) | DS-3 |
| …/network/Host.kt | ✓ Read | `val resetTimeMinutes: Int? = null` | None |
| …/network/Jackpoint.kt | ✓ Read | `require((connectsToLtg == null) != (connectsToHost == null))` | None |
| …/network/Matrix.kt | ✓ Read | `fun getRTG(name: String): RTG? = rtgs.firstOrNull { it.name == name }` | None |
| …/network/MatrixLocation.kt | ✓ Read | `data class OnPLTG(val pltg: PLTG) : MatrixLocation()` | None |
| …/network/Node.kt | ✓ Read | `data class Node(val subsystemType: SubsystemType, val description: String = "")` | None |
| …/network/RemoteDevice.kt | ✓ Read | `data class RemoteDevice(val name: String, val systemAddress: String)` | None |
| …/network/SAN.kt | ✓ Read | `data class SAN(val name: String, val isScrambleProtected: Boolean = false)` | None |
| …/network/SecuritySheaf.kt | ✓ Read | `val securityDeckerCount: Int = 0` | None |
| …/operations/AvailableAction.kt | ✓ Read | `data class Operation(val operation: SystemOperation, val target: MatrixObject? = null` | None |
| …/operations/BufferedMessage.kt | ✓ Read | `data class BufferedMessage(val text: String, val recipient: LinkedObserver)` | None |
| …/operations/DownloadHandle.kt | ✓ Read | `val destination: DownloadDestination = DownloadDestination.StorageMemory` | DS-4 |
| …/operations/InterrogationState.kt | ✓ Read | `val accumulatedSuccesses: Int = 0` | None |
| …/operations/MatrixIcon.kt | ✓ Read | `data class IcIcon(val ic: IC) : Icon()` | None |
| …/operations/MatrixObject.kt | ✓ Read | `data class IcProgram(val ic: IC, val analyzed: Boolean = false) : MatrixObject()` | None |
| …/operations/MonitoredOperationHandle.kt | ✓ Read | `val needsMaintenance: Boolean = false` | None |
| …/operations/NullOperationModifier.kt | ✓ Read | `fun totalBonusForDuration(seconds: Int): Int { val base = forDuration(seconds).bonus` | None |
| …/operations/OperationResult.kt | ✓ Read | `data class ScrambleDestructResult(val dataDestroyed: Boolean, val icRating: Int)` | None |
| …/operations/PointerChain.kt | ✓ Read | `data class PointerChain(val links: List<Host>, val finalFile: DataFile)` | None |
| …/operations/SystemOperation.kt | ✓ Read | `GRACEFUL_LOGOFF(ACCESS, UtilityType.DECEPTION, COMPLEX, STANDARD)` | None |
| …/operations/SystemTestOutcome.kt | ✓ Read | `val deckerWins: Boolean` | None |
| …/operations/SystemTestResolver.kt | ✓ Read | `val deckerResult = diceRoller.roll(decker.computerSkill, effectiveTn)` · `val clampedBase = maxOf(2, baseSubsystemRating - utilityRating)` · methods: resolve, resolveNullOperation (×2), resolveInterrogation (×2), resolveInterrogationCore, effectiveRating | OP-1 |
| …/operations/UploadHandle.kt | ✓ Read | `val active: Boolean = true` | None |
| …/programs/PersonaProgram.kt | ✓ Read | `: Program(name = attributeType.name, rating = rating, multiplier = 1)` | None |
| …/programs/Program.kt | ✓ Read | `val mpSize: Int get() = rating * rating * multiplier` | None |
| …/programs/Utility.kt | ✓ Read | `LOCK_ON(3, DEFENSIVE)` | None |
| …/server/DeckerDisconnectedException.kt | ✓ Read | `class DeckerDisconnectedException : Exception("decker disconnected mid-turn")` | None |
| …/server/MatrixServer.kt | ✓ Read | `private const val MAX_FRAME_SIZE = 65_536L` | None |
| …/server/SessionRegistry.kt | ✓ Read | `if (storedToken != null && (msg.reconnectToken == null \|\| msg.reconnectToken != storedToken))` | None |
| …/server/TurnCoordinator.kt | ✓ Read | `if (f == null \|\| f.isCompleted) return@withLock null to "NO_ACTION_PENDING"` | None |
| …/server/WebSocketDeckerController.kt | ✓ Read | `decker.controlSlave(device, host, diceRoller).first.toDispatch()` · `"Medic repaired $boxesRepaired box(es); remaining rating: $medicRating"` · methods: broadcastFail, conductTurn, dispatch, dispatchGridOperation, dispatchHostOperation, dispatchAnalyzeOp, dispatchLocateOp, dispatchDataOp, dispatchSlaveOp, dispatchCommsOp, dispatchMiscOp, dispatchRelocateIcon, locateWithState, securityRating, toDispatch (×6), label · dispatchHostOperation branches: ANALYZE_HOST/IC/ICON/SECURITY/SUBSYSTEM, LOCATE_FILE/SLAVE/ACCESS_NODE/IC, DOWNLOAD_DATA/EDIT_FILE/UPLOAD_DATA/DECRYPT_ACCESS/DECRYPT_FILE/DECRYPT_SLAVE, CONTROL_SLAVE/EDIT_SLAVE/MONITOR_SLAVE, MAKE_COMCALL/TAP_COMCALL, NULL_OPERATION/RELOCATE_ICON/INVOKE_MEDIC · dispatchGridOperation branches: NULL_OPERATION, RELOCATE_ICON (unsupported), LOCATE_ACCESS_NODE, ANALYZE_SECURITY, LOCATE_IC, ANALYZE_IC, else | WS-1, WS-2, WS-3 |
| …/server/dto/AvailableActionDto.kt | ✓ Read | `SystemOperation.UPLOAD_DATA -> "dataSize"` | None |
| …/server/dto/DeckerStateDto.kt | ✓ Read | `locationIndex = if (currentLocation != null) 0 else null,` | Skip:deferred §4 (locationIndex stub intentional) |
| …/server/dto/MatrixObjectDto.kt | ✓ Read | `@JsonClassDiscriminator("kind")` | None |
| …/server/dto/Messages.kt | ✓ Read | `@SerialName("bad_request") BAD_REQUEST,` | None |
| …/utility/DiceRoller.kt | ✓ Read | `do { face = random.nextInt(1, 7)` | None |

### Test files

| File | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| T/combat/CombatResolverTest.kt | ✓ Read | `` fun `resolveNonLethalBlackIc MPCP death blow fires on icon CM crash`() `` | TR-9 |
| T/combat/CombatTest.kt | ✓ Read | `` fun `DumpShock level maps correctly to security code`() `` | None |
| T/common/SharedTypesTest.kt | ✓ Read | `` fun `ConditionMonitor applyDamage does not exceed maxBoxes`() `` | None |
| T/config/DeckerConfigTest.kt | ✓ Read | `assertEquals(6, decker.cyberdeck.detectionFactor(masking, sleaze))` | None |
| T/config/GridLoadTest.kt | ✓ Read | `assertTrue(matrix.rtgs.size >= 19, "Expected at least 19 RTGs …")` | None |
| T/decker/CyberdeckAndProgramMechanicsTest.kt | ✓ Read | `` fun `SystemOperation has 29 entries`() { assertEquals(29, SystemOperation.entries.size) } `` | None |
| T/decker/DeckerOperationsTest.kt | ✓ Read | `val highMaskTarget = Persona(bod = 4, evasion = 4, masking = 8, sensor = 4, sleazeRating = 4)` | TR-3, TR-4 |
| T/decker/DeckerTest.kt | ✓ Read | `personaPrograms: List<PersonaProgram> = emptyList(), activeUtilities: List<Utility> = emptyList()` | None |
| T/decker/DeckerVisibilityTest.kt | ✓ Read | `private val secRating = SecurityRating(SecurityCode.GREEN, 4)` | None |
| T/decker/MovementTest.kt | ✓ Read | `private fun easyRatings() = SubsystemRatings(4, 4, 4, 4, 4)` | TR-6 |
| T/game/GameContextTest.kt | ✓ Read | `` fun `checkTriggers spawns IC when threshold crossed`() `` | None |
| T/game/GameTest.kt | ✓ Read | `assertIs<ActionResult.DeckerAction>(result)` | None |
| T/ic/IcBehaviorTest.kt | ✓ Read | `val roller = DiceRoller(stubRandom(1, 1, 1, 1, 1, 1, 6, 1, 6, 1, 6, 1, 6, 1, 1, 1, 1, 1, 1, 1))` | None |
| T/ic/IcTest.kt | ✓ Read | `` fun `withConditionMonitor returns new IC with updated conditionMonitor preserving other fields`() `` | None |
| T/integration/AlertAndTallyTest.kt | ✓ Read | `assertEquals(0, loc.rtg.securityTally, "Tally should reset to 0 on a fresh RTG …")` | None |
| T/integration/CombatTest.kt | ✓ Read | `` fun `Killer IC attacks the decker after crossing threshold 10 in FILES node`() `` | None |
| T/integration/DeckerCombatTest.kt | ✓ Read | `` fun `suppressIc adds IC to suppressedIc list and reduces effectiveDetectionFactor`() `` | TR-1, TR-2 |
| T/integration/FileOperationsTest.kt | ✓ Read | `assertIs<LocateResult.Located>(result.second, "Should accumulate 5+ successes and locate the file")` | None |
| T/integration/GrayCombatTest.kt | ✓ Read | `` fun `Ripper IC can reduce a persona attribute to zero`() `` | TR-5 |
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
| T/integration/utility/ScenarioBuilder.kt | ✓ Read | `fun step(name: String, block: StepContext.() -> Unit) {` | TR-8 |
| T/network/AlertTransitionsTest.kt | ✓ Read | `` fun `PASSIVE_ALERT applied twice stacks the +2 bonus`() `` | None |
| T/network/NetworkTest.kt | ✓ Read | `` fun `Host allows multiple nodes of the same subsystem type`() `` | None |
| T/operations/NullOperationModifierTest.kt | ✓ Read | `assertEquals(5, NullOperationModifier.totalBonusForDuration(86400))` | None |
| T/operations/SystemOperationTest.kt | ✓ Read | `assertEquals(29, SystemOperation.entries.size)` | None |
| T/operations/SystemOperationsTest.kt | ✓ Read | `val result = d.editFile(file, h, byteArrayOf(1, 2, 3), winRoller, attemptAuthentication = false)` | TR-7 |
| T/operations/SystemTestResolverTest.kt | ✓ Read | `assertEquals(0, outcome.hostSuccesses.coerceAtMost(0))` | TR-10 |
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
| frontend/src/App.tsx | ✓ Read | `const isRegistered = ws.role === 'registered_decker' \|\| ws.role === 'active_controller'` | None |
| frontend/src/components/ActionsPanel.tsx | ✓ Read | `{paramKind === 'newContent' && (<div className="action-control" onClick={e => e.stopPropagation()}><textarea` | UI-1 |
| frontend/src/components/DeckerPanel.tsx | ✓ Read | `<div className="pinned-alert">⚠ PINNED BY BLACK IC</div>` | UI-2 |
| frontend/src/components/EntitiesPanel.tsx | ✓ Read | `{obj.analyzed ? <span className="badge badge-green">ANALYZED</span> : <span className="badge badge-gray">UNKNOWN</span>}` | UI-3 |
| frontend/src/components/LocationPanel.tsx | ✓ Read | `decker.locationIndex != null ? (visibleObjects[decker.locationIndex] as MatrixObjectDto \| undefined) ?? null` | None (deferred §4) |
| frontend/src/components/NarrativePanel.tsx | ✓ Read | `<span className="event-badge">{ev.msg.success ? '✓ SUCCESS' : '✗ FAILURE'}</span>` | UI-3 |
| frontend/src/hooks/useWebSocket.ts | ✓ Read | `if (!isJackedIn && wasJackedInRef.current) { reconnectTokenRef.current = null; suppressReconnectRef.current = true; ws.close() }` | WS-4 |
| frontend/src/main.tsx | ✓ Read | `ReactDOM.createRoot(document.getElementById('root')!).render(` | None |
| frontend/src/types/messages.ts | ✓ Read | `export type AvailableActionDto = \| { kind: 'Operation'; … paramKind: 'precision' \| 'hasValidPasscode' \| 'scannerDeviceRating' \| 'newContent' \| 'dataSize' \| null … }` | None |

---

## Code Discrepancies

### CM-1 — Armor degrades on fully-absorbed hits

**Design:** `prd_core.md` CD-19: Armor degrades only when damage bleeds through — i.e., when effective power after armor reduction `max(0, power − armorRating)` is greater than zero.

**Code:** `CombatResolver.kt`:
```kotlin
updatedDecker = degradeArmor(updatedDecker, damageBledThrough = attack.power > 0)
```
`attack.power` is the raw pre-armor power. `attack.effectivePower` (the post-armor value) is available on the same `AttackResult.Hit` object. `attack.power > 0` is always true for any real attack.

**Impact:** Every IC attack that is fully stopped by armor still degrades the armor. Persona armor is consumed at a higher rate than designed and becomes useless earlier. This affects all combat IC types that route through `applyIcDamage` (Black Hammer, Killjoy, etc.).

**PRD verdict:** CD-19 is unambiguous. The condition must be `attack.effectivePower > 0`.

**Status:** Fix required — change `attack.power > 0` to `attack.effectivePower > 0` in the `degradeArmor` call.

---

### CM-2 — Missing TN floor in `resolveCrippler` / `resolveRipper` decker-defence roll

**Design:** `prd_core.md` CD-14 / SR3 core rule: all dice rolls require TN ≥ 2. `DiceRoller.roll` enforces this with `require(tn >= 2)`, throwing on violation.

**Code:** `CombatResolver.kt`:
```kotlin
// resolveCrippler
val deckerSuccesses = diceRoller.roll(currentAttr, ic.rating).successes
// resolveRipper
val deckerSuccesses = if (currentAttr > 0) diceRoller.roll(currentAttr, ic.rating).successes else 0
```
`ic.rating` is passed directly as TN without a `max(2, …)` floor.

**Impact:** Any Crippler or Ripper IC with rating 1 causes `IllegalArgumentException` at runtime during combat resolution — the decker cannot defend and the process crashes.

**PRD verdict:** CD-14. Fix: `max(2, ic.rating)` as TN.

**Status:** Fix required — wrap TN argument with `maxOf(2, ic.rating)`.

---

### CM-3 — Missing TN floor in `resolveJackOutWithPin`

**Design:** `prd_core.md` CD-14 / SR3 core rule: TN ≥ 2.

**Code:** `CombatResolver.kt`:
```kotlin
val successes = diceRoller.roll(decker.willpower, pin.pinningIc.rating).successes
```
No `max(2, …)` guard on `pin.pinningIc.rating`.

**Impact:** A Black IC with rating 1 causes `IllegalArgumentException` when the decker attempts to jack out while pinned.

**PRD verdict:** CD-14. Fix: `maxOf(2, pin.pinningIc.rating)` as TN.

**Status:** Fix required.

---

### OP-1 — Hacking Pool dice cannot be added to any System Test

**Design:** `prd_core.md` CC-01: "Hacking Pool dice may be added to any test made in the Matrix — System Tests, Attack or Defense tests, maneuvers, or Attribute Tests." This is one of the central mechanics of SR3 Matrix rules.

**Code:** `SystemTestResolver.kt` `resolve` method rolls only `decker.computerSkill` dice for the decker's side of every System Test. There is no parameter, field, or code path by which the caller can add Hacking Pool dice to a System Test roll.

**Impact:** Deckers cannot apply their primary combat resource (Hacking Pool) to System Tests. Every logon, operation, and graceful logoff undercounts decker successes by up to `decker.hackingPool` dice. High-MPCP builds are disproportionately penalised.

**PRD verdict:** CC-01 is explicit. `resolve` should accept `hackingPoolDice: Int = 0`; the decker roll should be `decker.computerSkill + hackingPoolDice`.

**Status:** Fix required — add optional `hackingPoolDice` parameter to `SystemTestResolver.resolve` and all call sites.

---

### OP-2 — `DECRYPT_ACCESS` not offered from grid context; scramble-protected SANs unbypassable

**Design:** `prd_core.md` operations table, Decrypt Access: "Access Test, Decrypt utility, Simple. Defeats scramble IC on a SAN; required before Logon to Host on a scrambled SAN." The operation must precede `Logon to Host`, meaning it is needed while the decker is still on an LTG/PLTG looking at the SAN from outside the host.

**Code:** `Decker.kt` `addGridSystemActions()`:
```kotlin
private fun MutableList<AvailableAction>.addGridSystemActions() {
    add(AvailableAction.Operation(SystemOperation.NULL_OPERATION))
    add(AvailableAction.Operation(SystemOperation.LOCATE_ACCESS_NODE))
    add(AvailableAction.Operation(SystemOperation.ANALYZE_SECURITY))
    add(AvailableAction.Operation(SystemOperation.LOCATE_IC))
}
```
`DECRYPT_ACCESS` appears only in `addHostSystemActions()`. No path from `OnLTG`, `OnRTG`, or `OnPLTG` offers it.

**Impact:** A decker facing a host with a scramble-protected SAN has no `DECRYPT_ACCESS` action available and cannot bypass the SAN. Scramble protection on LTG-attached hosts is effectively permanent.

**PRD verdict:** Operations table places Decrypt Access before Logon to Host, implying a pre-entry context.

**Status:** Fix required — add `DECRYPT_ACCESS` to the grid-context available actions when the current location has reachable hosts containing scramble-protected SANs.

---

### OP-3 — Missing TN floor in `noticeIcon` / `noticeTriggeredIc` sensor tests

**Design:** `prd_core.md` CD-14 / SR3 core rule: TN ≥ 2.

**Code:** `DeckerOperationsExtensions.kt` — both `noticeIcon` and `noticeTriggeredIc` pass a computed TN (derived from `masking / 2` or similar) directly to `diceRoller.roll` without `maxOf(2, …)`.

**Impact:** If target Masking is 2 or lower the derived TN can be 1, causing `IllegalArgumentException` at runtime.

**PRD verdict:** CD-14.

**Status:** Fix required — wrap all TN expressions passed to `diceRoller.roll` in these methods with `maxOf(2, …)`.

---

### CD-1 — `locateDecker` reads stale `Persona.sleazeRating` instead of active Sleaze utility

**Design:** `prd_core.md` MP-02: "If the icon is a decker: target = that decker's Masking Rating + Sleaze utility rating (if any)." `design_core/operations.md` `locateDecker` algorithm step 3: `sensorTn = max(2, targetPersona.masking + targetPersona.sleazeRating)`. The Sleaze contribution must be dynamic, matching the design principle from CD-18 that "Detection Factor is recalculated at the moment each System Test is resolved."

**Code:** `Persona.kt`:
```kotlin
val sleazeRating: Int = 0,
```
No code in `Decker.kt` or `Persona.kt` updates `persona.sleazeRating` when a Sleaze utility is loaded or unloaded. The field defaults to 0 and stays 0 throughout a run. `locateDecker()` reads `targetPersona.sleazeRating` and therefore always computes TN = masking + 0 regardless of active Sleaze.

**Impact:** A decker running Sleaze-5 with Masking-6 should have TN 11 for Locate Decker; the code produces TN 6. Active Sleaze provides no protection against Locate Decker.

**PRD verdict:** MP-02 explicitly includes Sleaze in the TN. The same dynamic-recalculation principle used for `detectionFactor` must apply here.

**Status:** Fix required — change `locateDecker()` to read the active Sleaze utility's `currentRating` from `targetDecker.cyberdeck.activeUtilities` directly, rather than from `targetPersona.sleazeRating`.

---

### WS-1 — `INVOKE_MEDIC` missing from `dispatchGridOperation`; silently fails on grid

**Design:** `prd_core.md` operations table — Invoke Medic: "Complex. Repairs the decker's icon Condition Monitor." No host restriction. The decker's persona and its Condition Monitor exist from the moment of jack-in, at any grid location.

**Code:** `WebSocketDeckerController.kt` `dispatchGridOperation` has no branch for `INVOKE_MEDIC`:
```kotlin
return when (action.operation) {
    SystemOperation.NULL_OPERATION     -> ...
    SystemOperation.LOCATE_ACCESS_NODE -> ...
    SystemOperation.ANALYZE_SECURITY   -> ...
    SystemOperation.LOCATE_IC          -> ...
    SystemOperation.ANALYZE_IC         -> ...
    else -> DispatchResult(decker, false, 0, 0, "${action.operation} not supported on grid")
}
```
`INVOKE_MEDIC` falls through to `else` → `success = false`, no repair applied.

**Impact:** If `availableActions()` correctly exposes `INVOKE_MEDIC` while the decker is on a grid (which it should, since healing is not location-restricted), the action silently fails and the decker cannot heal between hosts.

**PRD verdict:** Operations table (Invoke Medic row); CD-20 (Medic degradation applies regardless of location).

**Status:** Fix required — add `SystemOperation.INVOKE_MEDIC -> decker.invokeMedic(diceRoller).toDispatch()` to `dispatchGridOperation`.

---

### WS-2 — `UPLOAD_DATA` `dataSize` has no lower-bound guard

**Design:** `protocol.md` params table: `UPLOAD_DATA | dataSize (int Mp, default 100)`. A non-positive value is meaningless (zero bytes to upload; negative causes undefined arithmetic in `ceil(dataSizeMp / ioSpeedMpPerTurn)`).

**Code:** `WebSocketDeckerController.kt`:
```kotlin
val dataSizeMp = p?.dataSize ?: 100
val (result, handle) = decker.uploadData(host, dataSizeMp, diceRoller)
```
No `.coerceAtLeast(1)` guard. Compare: `NULL_OPERATION` applies `.coerceAtLeast(0)` to `inactivitySeconds`.

**Impact:** A client sending `dataSize: 0` passes it to `uploadData`, producing `turnsRemaining = 0` (instant completion with no data transferred). A negative value produces a negative ceiling result, corrupting the ongoing-operation handle.

**PRD verdict:** SO-10–SO-12 (ongoing operations; transfer size governs duration).

**Status:** Fix required — add `.coerceAtLeast(1)` to `dataSizeMp` before passing to `uploadData`.

---

### WS-3 — `scannerDeviceRating` not clamped to protocol range 0–10

**Design:** `protocol.md` params table: `TAP_COMCALL | scannerDeviceRating (int, 0–10)`.

**Code:** `WebSocketDeckerController.kt`:
```kotlin
val (opResult, handle) = decker.tapComcall(host, cmd.params?.scannerDeviceRating ?: 0, diceRoller)
```
No `.coerceIn(0..10)` guard. A client can send `scannerDeviceRating: 999` or a negative value.

**Impact:** Out-of-range scanner rating is passed directly to the resolver, skewing the scanner dice test. Negative values would cause a dice-roll error at runtime.

**PRD verdict:** Protocol params table.

**Status:** Fix required — add `.coerceIn(0..10)` before passing to `tapComcall`.

---

### WS-4 — Post-logoff `join()` cannot open a new WebSocket; app permanently non-functional until page reload

**Design:** After a graceful logoff or jack-out the UI returns to the Join screen. The user should be able to type a decker handle and press JACK IN to start a new session. `prd_ui.md` UI-04: token is cleared on deliberate logout; the connection lifecycle should restart cleanly.

**Code:** `useWebSocket.ts` — two compounding bugs:

1. On logoff, `suppressReconnectRef.current = true` is set and **never reset**. The `ws.onclose` handler returns early when `suppressReconnectRef.current` is true, so no reconnect is ever scheduled again for the lifetime of the page.

2. `join()` stores the decker name in `pendingNameRef.current` but does not call `connect()` when the socket is closed. Since reconnect is also suppressed, the socket stays closed indefinitely.

```typescript
// In join():
pendingNameRef.current = name
if (wsRef.current?.readyState === WebSocket.OPEN) {
    // send immediately
}
// no else — closed socket is never reopened
```

**Impact:** After any `GracefulLogoff` or `JackOut` completes, typing a name and pressing JACK IN does nothing. The app is permanently non-functional until a page reload. This is triggered on every normal session end.

**PRD verdict:** UI-04 (token cleared on logout); implied by the normal session lifecycle.

**Status:** Fix required — in `join()`, when the socket is not OPEN: reset `suppressReconnectRef.current = false`, then call `connect()`. The existing `pendingNameRef` mechanism will send the JoinMessage once the connection is established.

---

### UI-1 — `EDIT_FILE` textarea always visible; no confirm button

**Design:** `design_ui.md` — "When this action card is selected (focused), a text input area expands on or above the card. Pressing any card (or **a confirm button on `EDIT_FILE`**) calls `sendAction(card.index, params)`."

**Code:** `ActionsPanel.tsx`:
```tsx
{paramKind === 'newContent' && (
  <div className="action-control" onClick={e => e.stopPropagation()}>
    <textarea … />
    <div className="edit-hint">Leave empty to erase file</div>
  </div>
)}
```
1. The textarea is always rendered whenever `paramKind === 'newContent'` — not only when the card is focused.
2. There is no confirm button; submission requires clicking the card's outer `div` while the textarea has content, which is unintuitive and easy to trigger accidentally.

**Impact:** (1) EDIT_FILE cards always show the textarea, cluttering the actions panel regardless of focus state. (2) No explicit send affordance; accidental file corruption is likely while composing content.

**PRD verdict:** `design_ui.md` confirm-button requirement; paramKind expand-on-focus behaviour spec.

**Status:** Fix required — add a confirm button; render textarea only when card is focused.

---

## Test Defects

### TR-1 — Black Hammer condition-monitor assertion trivially true

**Design:** CC-28: `resolveBlackHammer` always applies damage when called with a non-Miss attack. With `stagedDamageLevel = LIGHT` (2 boxes) and `hitRoller()` giving 0 decker defence successes, the CM damage must increase by at least 1.

**Test:** `integration/DeckerCombatTest.kt`:
```kotlin
assertTrue(result.updatedDecker.persona!!.conditionMonitor.damage >= 0,
    "Condition monitor should not be negative")
```
CM damage is a non-negative `Int`; `>= 0` is always true. A bug that zeros CM damage on every call would pass.

**PRD verdict:** CC-28. Assertion should be `>= 1`.

**Status:** Fix required — change assertion to `assertTrue(result.updatedDecker.persona!!.conditionMonitor.damage >= 1)`.

---

### TR-2 — `resolveSlow` `actionsLost` assertion trivially true

**Design:** CC-21: When the decker wins the Slow contest against a proactive IC with `winRoller()`, `actionsLost` must be `> 0`.

**Test:** `integration/DeckerCombatTest.kt`:
```kotlin
assertTrue(result.actionsLost >= 0, "actionsLost should be non-negative")
```
`actionsLost` is non-negative; `>= 0` is always true. A silent regression removing all Slow effects would pass.

**PRD verdict:** CC-21. Assertion should be `> 0`.

**Status:** Fix required — change to `assertTrue(result.actionsLost > 0)`.

---

### TR-3 — `analyzeIc` security tally assertion trivially true

**Design:** System Test results always add host successes to the security tally; the tally after the call must be strictly greater than before.

**Test:** `decker/DeckerOperationsTest.kt`:
```kotlin
assertTrue(tally >= 0)
```
`tally` is a non-negative running total. `>= 0` is always true. A regression stopping tally accumulation (leaving it at 0) would pass.

**PRD verdict:** Host successes always added to tally.

**Status:** Fix required — change to `assertTrue(tally > 0)`.

---

### TR-4 — `uploadData` security tally assertion trivially true

**Design:** SO-10: `uploadData` runs a System Test; host successes are added to the security tally.

**Test:** `decker/DeckerOperationsTest.kt`:
```kotlin
assertTrue(tally >= 0)
```
Same structural issue as TR-3.

**PRD verdict:** SO-10.

**Status:** Fix required — change to `assertTrue(tally > 0)`.

---

### TR-5 — Ripper floor-at-0 assertions trivially true

**Design:** ICC-07: Ripper's attribute-reduction floor is 0 (unlike Crippler whose floor is 1). With target BOD = 1 and a high-rating Ripper winning with `hitRoller()`, `bod` should reach 0 and `reduction` should be ≥ 1.

**Test:** `integration/GrayCombatTest.kt`:
```kotlin
assertTrue(result.updatedDecker.persona!!.bod >= 0, "Ripper floor is 0")
assertTrue(result.reduction >= 0, "Reduction should be non-negative")
```
`bod` is always ≥ 0; `reduction` is always ≥ 0. A regression where Ripper stops reducing attributes (`reduction = 0`) or where the floor is set to 1 (matching Crippler) would pass.

**PRD verdict:** ICC-07. Should be `assertEquals(0, result.updatedDecker.persona!!.bod)` and `assertTrue(result.reduction >= 1)`.

**Status:** Fix required — replace both assertions with concrete bounds.

---

### TR-6 — `jackInToLtg accumulates security tally on failure` does not assert tally

**Design:** M-05: On a failed jack-in, the host's successes are recorded on the LTG's security tally carried in `LogonResult.Failure.location`.

**Test:** `decker/MovementTest.kt` — the test asserts only `assertNull(result.decker.persona)`. A comment in the test body says "Security tally must have increased" but no assertion follows.

**Impact:** A regression where tally is not stored in `Failure.location` would pass. The test name creates false confidence about M-05 coverage.

**PRD verdict:** M-05.

**Status:** Fix required — add tally assertion on `(result.location as MatrixLocation.OnLTG).ltg.securityTally > 0`, or rename to accurately describe what is tested.

---

### TR-7 — `analyzeSecurity` tally assertion trivially true

**Design:** SO-02: `analyzeSecurity` returns the current security tally.

**Test:** `operations/SystemOperationsTest.kt`:
```kotlin
assertTrue(result.currentTally >= 0)
```
The test fixture uses a PASSIVE_ALERT host, which has an accumulated tally > 0. `>= 0` is always true. A regression returning tally = 0 would pass.

**PRD verdict:** SO-02.

**Status:** Fix required — change to `assertTrue(result.currentTally > 0)`.

---

### TR-8 — `invokeMedic` `boxesRepaired` assertion trivially true in `ScenarioBuilder`

**Design:** CD-20: `invokeMedic` repairs icon CM boxes; at least 1 box must be repaired when the decker has damage and the roll succeeds.

**Test:** `integration/utility/ScenarioBuilder.kt`:
```kotlin
assertTrue(result.boxesRepaired >= 0, "$name: boxesRepaired should be non-negative")
```
`boxesRepaired` is non-negative. Any scenario step that calls `invokeMedic` via `ScenarioBuilder` never verifies that Medic actually repaired anything.

**PRD verdict:** CD-20.

**Status:** Fix required — change to `>= 1` in contexts where the decker has at least one box of CM damage.

---

### TR-9 — `resolveSlow` test name says 4 net successes / 2 actions lost; body uses 6 net / 3 actions

**Design:** CC-21: `actionsLost = netSuccesses / 2` (integer division). 4 net → 2 lost; 6 net → 3 lost.

**Test:** `combat/CombatResolverTest.kt` — the test named `` `resolveSlow with 4 net successes loses 2 actions` `` supplies 6 Slow-dice successes vs. 5 IC dice that all fail (6 net successes → `actionsLost == 3`). The assertion checks `actionsLost == 3`, not 2.

**Impact:** The name actively misdocuments the rule being tested. Readers will derive the wrong threshold from the test name.

**PRD verdict:** Test logic is correct for 6 net; name must be corrected.

**Status:** Fix required — rename to `` `resolveSlow with 6 net successes loses 3 actions` ``.

---

### TR-10 — Wrong comment in `SystemTestResolverTest` claims `face = 8`

**Test:** `operations/SystemTestResolverTest.kt` — the comment "With face=8 (open-ended) decker always wins" appears in a test body that uses `fixedRoller(5)` (face = 5). Face 8 would trigger exploding-dice; face 5 does not.

**Impact:** Comment is factually wrong about the dice mechanics being exercised; no assertion is incorrect.

**PRD verdict:** Comment error only.

**Status:** Fix required — correct the comment to read `fixedRoller(5) → face=5, decker wins without exploding dice`.

---

## Design Stale

### DS-1 — `combat.md` missing `rawWeaponPower` field in `AttackResult.Hit`

**Design:** `design_core/combat.md` defines `Hit` with four fields: `attackerSuccesses`, `rawDamageLevel`, `stagedDamageLevel`, `power`.

**Code:** `AttackResult.kt` adds `rawWeaponPower: Int` (pre-armor weapon power) between `rawDamageLevel` and `power`. The field is a useful addition with no PRD conflict.

**Status:** Design stale — update `combat.md` to document `rawWeaponPower` as the pre-armor weapon power.

---

### DS-2 — `DataFile.pointerToHost` inconsistent with ORD name `PointerTargetHost`

**Design:** `ord.md` names the field `PointerTargetHost: Host?`; the sibling field is `pointerTargetFile: DataFile?`. Both follow the `PointerTarget*` prefix.

**Code:** `DataFile.kt` uses `pointerToHost: Host?` (breaks the prefix convention) while `pointerTargetFile` is correct.

**Status:** Design stale — either rename `pointerToHost` → `pointerTargetHost` in code for consistency, or update `ord.md` to reflect `pointerToHost`.

---

### DS-3 — `ord.md` PLTG multi-entry-point model vs per-LTG instance implementation

**Design:** `ord.md`: "A PLTG may attach to one or more public LTGs as entry points."

**Code:** `Grid.kt` PLTG has `val parentLtg: LTG` — a single required parent. `creation.md` explains the reconciliation: PLTGs are replicated at load time, one instance per LTG. Runtime topology is equivalent but object identity differs from the ORD.

**Status:** Design stale — update `ord.md` to document the per-LTG replication model: "PLTG instances are per-LTG; the same physical PLTG is represented as one PLTG object per attached LTG."

---

### DS-4 — `deferred.md` §6 says `destination` field absent; field now exists in `DownloadHandle`

**Design:** `deferred.md` §6: "DownloadHandle does not include a `destination` field."

**Code:** `DownloadHandle.kt` has `val destination: DownloadDestination = DownloadDestination.StorageMemory`. The field exists; the default preserves the intended deferred behavior.

**Status:** Design stale — update `deferred.md` §6: the field exists with `StorageMemory` as default; remaining deferred work is wiring `OfflineStorage` routing, not adding the field.

---

### DS-5 — `Persona.status` default `LEGITIMATE` is misleading

**Design:** CC-24: Legitimate status requires a valid passcode. The natural state for a hacking persona is INTRUDING.

**Code:** `Persona.kt`:
```kotlin
val status: PersonaStatus = PersonaStatus.LEGITIMATE,
```
Navigation code (`DeckerNavigationExtensions.kt`) correctly overrides this: passcode paths set `LEGITIMATE`, hacking paths set `INTRUDING`. The runtime behaviour is correct; the default is never used for a real hacking persona.

**Status:** Design stale (low risk) — consider changing the default to `PersonaStatus.INTRUDING` to accurately represent the common case. Any code path creating a Persona without specifying status would otherwise accidentally grant LEGITIMATE status to hacking personas.

---

### DS-6 — `missing.md` item 8 (Evade Detection — IC Re-Detection Timing) lacks ✓ resolved marker

**Design:** `design_core/missing.md` item 8 has no "✓ resolved" marker, yet `design_core/combat.md` `resolveManeuver` section contains a full "Evade Detection — IC re-detection countdown" note that covers the content of item 8.

**Status:** Design stale — add `✓ resolved` marker to `missing.md` item 8 with reference to the combat.md section.

---

### DS-7 — `deferred.md` items 9 and 10 lack `##` section headers

**Design:** `deferred.md` lists items 1–8 and 11 with `## N. Title` headers. Two further issues appear after item 11 as plain paragraphs with `**Source:**` lines only — identifiable as the Matrix Perception / icon-visibility wiring (item 9) and the Scramble IC reactive trigger (item 10, SAN-1).

**Status:** Design stale — add `## 9.` and `## 10.` headers matching the convention of the existing entries.

---

### DS-8 — `prd_ui.md` references non-existent file `web-interface.md`

**Design:** `prd_ui.md`: "This Websocket interface is documented in depth by [web-interface.md](web-interface.md)." The actual file is `design/protocol.md`.

**Status:** Design stale — update the link in `prd_ui.md` to `[protocol.md](protocol.md)`.

---

### DS-9 — `prd_ui.md` action-names section uses informal names not matching any `SystemOperation` enum value

**Design:** `prd_ui.md` lists action names such as `CRASH_PROGRAM`, `READ_FILE`, `DOWNLOAD_FILE`, `RELOCATE`, `DECRYPT`, `DECEPTION`, `COMMLINK`, `SCANNER`, `SPOOF`. None match `SystemOperation` enum values. Correct names include `ANALYZE_IC`, `DOWNLOAD_DATA`, `RELOCATE_ICON`, `DECRYPT_ACCESS`, `DECRYPT_FILE`, etc.

**Status:** Design stale — replace informal names in `prd_ui.md` with the actual `SystemOperation` enum values as listed in `design_core/operations.md`.

---

### DS-10 — `prd_ui.md` IcProgram field table omits `analyzed` conditional display gate

**Design:** `prd_ui.md` right-panel entities table shows IcProgram fields as `name, rating, behavior, guardedNodeType` — no conditional logic.

**Code / `design_ui.md` / `protocol.md`:** IcProgram exposes `analyzed: Boolean`; `rating`, `behavior`, and `guardedNodeType` are null when not analyzed and must not be rendered. This display rule is correctly implemented in `EntitiesPanel.tsx`.

**Status:** Design stale — update `prd_ui.md` IcProgram table to include the `analyzed` field and the conditional display rule.

---

### DS-11 — `creation.md` 7-step init sequence superseded without deprecation notice

**Design:** `design_core/creation.md` "Decker Initialization Sequence" (steps 1–7) is superseded by the 10-step sequence in `design_core/cyberdeck_and_program_mechanics.md` ("Replaces the 7-step sequence in `creation.md`"). The old sequence remains in `creation.md` without a deprecation notice.

**Status:** Design stale — add a notice to the `creation.md` init section: "⚠ Superseded by the 10-step sequence in `cyberdeck_and_program_mechanics.md`."

---

### DS-12 — `ord.md` omits `floor()` in Response Increase cap formula

**Design:** `prd_core.md`: "Response Increase (0–3 points; max = **floor**(MPCP ÷ 4))". `ord.md` Cyberdeck entry: "Response Increase (0–3 points; max = MPCP ÷ 4)" — no `floor()`. For odd MPCP values (e.g. MPCP = 9: `floor(9÷4) = 2`), integer division and true floor are equivalent, but the omission could invite a `/ 2.0` misread.

**Status:** Design stale — add `floor()` to the `ord.md` formula.

---

## Internal Design Conflicts

### ID-1 — `ANALYZE_IC` permitted on grids in design, prohibited on grids in PRD

**PRD:** `prd_core.md` M-08a: "ANALYZE_IC is only available inside a host. IC programs are host-resident objects; this operation is not available from RTG, LTG, or PLTG contexts."

**Design:** `design_game/game.md` grid-context available actions list explicitly includes `ANALYZE_IC`. `design_core/operations.md` provides an `analyzeIc(ic: IC, grid: Grid, …)` overload.

**Impact:** Code implementation follows the design docs (ANALYZE_IC available on grids), directly contradicting the PRD. One of the two must change.

**Status:** Design conflict — resolution required. If the PRD is authoritative, remove `ANALYZE_IC` from `addGridSystemActions()` in `Decker.kt`, remove the grid overload from `analyzeIc`, and update `design_game/game.md` and `design_core/operations.md` to align with M-08a.

---

### ID-2 — `TarBaby.action()` does not exclude passive utilities (Armor, Sleaze) as PRD requires

**PRD:** `prd_core.md` ICC-05: "Passive utilities (Armor, Sleaze) are **not** valid Tar Baby/Tar Pit trigger targets."

**Design:** `design_game/game.md` `TarBaby.action()` and `TarPit.action()`:
```
target.cyberdeck.activeUtilities.firstOrNull { it.type.category == targetCategory }
```
No exclusion of `DEFENSIVE` (Armor) or `SPECIAL` (Sleaze) category utilities.

**Impact:** If a Tar Baby is configured with `targetCategory = DEFENSIVE`, it will target and crash Armor, which ICC-05 explicitly forbids. The passive-utility exclusion exists in the PRD but is absent from the game-layer design and therefore from the code.

**Status:** Design conflict — resolution required. Either add the passive-utility exclusion to `design_game/game.md` and implement it in code (filter out `UtilityType.ARMOR` and `UtilityType.SLEAZE` from candidate targets), or add a PRD note clarifying the intent.

---

## Completion Gate

### 1. Count match
Files returned by `find`: **156** (85 main Kotlin · 44 test Kotlin · 9 frontend · 18 design).  
✓ rows: **153** · Skip rows: **3** (design/align.md Skip:infra · design/discrepancies_old.md Skip:infra · …/decker/DownloadDestination.kt Skip:deferred §6).  
**153 + 3 = 156. Count matches. ✓**

### 2. PRD coverage
All three PRDs read in full in this session: `prd_core.md`, `prd_game.md`, `prd_ui.md`. ✓

### 3. Adversarial check
*"If I had done a spot check and stopped after the first N interesting findings, what would I have missed?"*

Stopping after CM-1 (armor degradation bug, the first high-severity combat finding) would have missed:
- **WS-4** (post-logoff join is permanently broken — a critical frontend regression exercised on every normal session end, found only by auditing `useWebSocket.ts`)
- **OP-1** (Hacking Pool completely absent from System Tests — a fundamental mechanic omission, found only by reading `SystemTestResolver.kt` in full)
- **OP-2** (scramble-protected SANs unbypassable — found in `Decker.availableActions()`, a detail easily missed when scanning for algorithm bugs)
- **WS-1** (INVOKE_MEDIC silently fails on grids — found only by checking every `when` branch in the controller dispatch)
- All 10 TR- test defects (trivially-true assertions provide false confidence; would have been missed without a test-layer audit)
- ID-1 and ID-2 (design conflicts that require explicit PRD-vs-design-doc cross-referencing)

Nothing in the codebase was left unexamined.

---

## Open Findings

| ID | Layer | Severity | Summary |
|---|---|---|---|
| CM-1 | Business logic | HIGH | `attack.power` instead of `attack.effectivePower` in armor-degrade guard — armor consumed on every hit |
| WS-1 | Server | HIGH | `INVOKE_MEDIC` not dispatched from grid context — silently fails |
| WS-4 | Frontend | CRITICAL | Post-logoff `join()` never reopens socket; `suppressReconnectRef` never reset |
| OP-1 | Business logic | MEDIUM | Hacking Pool dice unreachable in System Test resolver |
| OP-2 | Business logic | MEDIUM | `DECRYPT_ACCESS` absent from grid `availableActions` — scramble-protected SANs unbypassable |
| CD-1 | Business logic | MEDIUM | `locateDecker` reads `persona.sleazeRating` (always 0) instead of active Sleaze utility |
| WS-2 | Server | MEDIUM | `UPLOAD_DATA` `dataSize` no lower-bound guard |
| WS-3 | Server | MEDIUM | `scannerDeviceRating` not clamped to 0–10 |
| UI-1 | Frontend | MEDIUM | `EDIT_FILE` textarea always visible; no confirm button |
| CM-2 | Business logic | LOW | `resolveCrippler`/`resolveRipper` TN floor missing → crash on rating-1 IC |
| CM-3 | Business logic | LOW | `resolveJackOutWithPin` TN floor missing → crash on rating-1 Black IC |
| OP-3 | Business logic | LOW | `noticeIcon`/`noticeTriggeredIc` TN floor missing → crash on Masking ≤ 2 |
| TR-1 | Tests | — | Black Hammer CM assertion `>= 0` trivially true; should be `>= 1` |
| TR-2 | Tests | — | `resolveSlow` `actionsLost >= 0` trivially true; should be `> 0` |
| TR-3 | Tests | — | `analyzeIc` tally `>= 0` trivially true; should be `> 0` |
| TR-4 | Tests | — | `uploadData` tally `>= 0` trivially true; should be `> 0` |
| TR-5 | Tests | — | Ripper floor assertions trivially true; should assert `bod == 0` and `reduction >= 1` |
| TR-6 | Tests | — | `jackInToLtg failure` test name promises tally check; body has none |
| TR-7 | Tests | — | `analyzeSecurity` tally `>= 0` trivially true; should be `> 0` |
| TR-8 | Tests | — | `invokeMedic` `boxesRepaired >= 0` trivially true; should be `>= 1` |
| TR-9 | Tests | — | `resolveSlow` test name says 4 net / 2 lost; body uses 6 net / 3 lost |
| TR-10 | Tests | — | Comment says `face=8`; roller is `fixedRoller(5)` |
| UI-2 | Frontend | LOW | `DeckerPanel` pinned badge text "⚠ PINNED BY BLACK IC" vs spec "⚠ PINNED" |
| UI-3 | Frontend | LOW | Badge text missing `[]` brackets throughout `EntitiesPanel` and `NarrativePanel` |
| DS-1–DS-12 | Design | — | Design document updates (see Design Stale section) |
| ID-1 | Design conflict | — | ANALYZE_IC on grids: PRD M-08a prohibits; design_game + operations permit |
| ID-2 | Design conflict | — | Tar Baby passive-utility exclusion: ICC-05 requires; game.md does not enforce |
