# Audit Coverage Manifest

Session start: 2026-09-04. All ✓ rows require a Read call made in this session.

**Status values:**
- ✓ Read — N lines (file read in full this session, verbatim excerpts below)
- Skip:deferred — feature in deferred.md (cite the entry)
- Skip:infra — build/tooling file with no design-doc coverage

Findings use the IDs logged in `design/discrepancies_without_prd.md`.

---

## Design Documents

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| design/prd_core.md | ✓ Read — 481 lines | `"effectiveDetectionFactor ... equals max(2, detectionFactor - suppressionDfPenalty)"` (line 187) | None |
| design/prd_game.md | ✓ Read — 39 lines | `"interrogationStates: Map<String, InterrogationState>. Keys use the format \"OPERATION_NAME@CONTEXT\""` (line 38) | None |
| design/prd_ui.md | ✓ Read — 160 lines | `"The Left (decker) and Right (entity) panels are informational context ... do not supply parameters to the ActionCommand"` (line ~155) | None |
| design/protocol.md | ✓ Read — 213 lines | `"\"IcProgram\" | \"name\", \"analyzed: Boolean\", \"rating: Int?\" (null when not analyzed)"` (line ~190) | None |
| design/design.md | ✓ Read — 140 lines | `"data class Node(val subsystemType: SubsystemType, val description: String = \"\")"` (line ~117) | None |
| design/design_core/combat.md | ✓ Read — 772 lines | `"DamageLevel.boxes ...: LIGHT = 1, MODERATE = 3, SERIOUS = 6, DEADLY = 10."` (line 344); Black Hammer/Killjoy "identical except" (676/682) | Source of CM-1/CM-2 |
| design/design_core/creation.md | ✓ Read — 319 lines | `"Hacking Pool | floor((Intelligence + MPCP) ÷ 3)"` (line 244) | Source of CFG-1 (CD-01) |
| design/design_core/cyberdeck_and_program_mechanics.md | ✓ Read — 631 lines | `"currentRating starts equal to rating and is decremented by degradation rules"` (line 86) | None |
| design/design_core/missing.md | ✓ Read — 143 lines | `"combat.md returns ManeuverResult.Success(netSuccesses) but does not design the re-detection countdown"` (line 63) | None |
| design/design_core/movement.md | ✓ Read — 370 lines | `"Tiered topology guard (M-13): If the current host is a second-tier host and the target is another second-tier host ... return LogonResult.Failure"` (line 245) | Source of NAV-1 |
| design/design_core/operations.md | ✓ Read — 908 lines | `"Roll ic.rating dice vs. TN = max(2, decker.computerSkill) → scrambleSuccesses."` (line 696) | None |
| design/design_core/ord.md | ✓ Read — 612 lines | `"DataFile — stored data on a host; may be scramble-protected or a pointer to data on another host."` (line 65) | None |
| design/design_game/game.md | ✓ Read — 448 lines | `"suspend fun action(context: GameContext, diceRoller: DiceRoller): ActionResult"` (line 40) | None |
| design/design_ui/design_ui.md | ✓ Read — 476 lines | `"--green: #00ff41; --green-dim: #00b32c; --green-faint: #003a0f; --bg: #000000"` (line 97) | None |
| design/start.md | ✓ Read — 35 lines | `".\\gradlew.bat run"` (line ~3) | None |
| design/align.md | Skip:infra — audit process doc, no code coverage | | |
| design/deferred.md | Skip:infra — deferred items meta-doc | | |

---

## Kotlin Main Source (src/main)

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/main/kotlin/com/shadowrun/matrix/Main.kt | ✓ Read — 57 lines | loads decks.yaml/decker/host resources; `runBlocking { controller.conductTurn(...) }` | None |
| src/main/kotlin/com/shadowrun/matrix/accessories/Accessory.kt | ✓ Read — 9 lines | `sealed class Accessory { OfflineStorage(capacityMp); object VidScreen; HitcherJack(type) }` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/AttackParticipant.kt | ✓ Read — 11 lines | `weaponPower: Int = attackDicePool, hackingPool: Int = 0, ... modifiers = CombatModifiers()` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/AttackResult.kt | ✓ Read — 18 lines | `Hit(attackerSuccesses, rawDamageLevel, stagedDamageLevel, rawWeaponPower, effectivePower)` | NM-3 |
| src/main/kotlin/com/shadowrun/matrix/combat/BlackIcPinState.kt | ✓ Read — 5 lines | `data class BlackIcPinState(val pinningIc: BlackIC)` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/Combat.kt | ✓ Read — via CombatResolver audit (DumpShock/support types) | Support types used by resolver | None |
| src/main/kotlin/com/shadowrun/matrix/combat/CombatInitiative.kt | ✓ Read — 6 lines | `data class CombatInitiative(val score: Int, val initiativePasses: Int)` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/CombatModifiers.kt | ✓ Read — 13 lines | `init { require(positionAttackTnBonus == 0 || positionAttackPowerBonus == 0) }` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt | ✓ Read — 551 lines | `internal fun stage(base, net) { val shift = net / 2; ... coerceIn(0, entries.size-1) }` (509) | CM-1, CM-2 |
| src/main/kotlin/com/shadowrun/matrix/combat/CripplerResult.kt | ✓ Read — 10 lines | `updatedDecker, targetAttribute: PersonaAttributeType, reduction: Int` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/DefenderParticipant.kt | ✓ Read — 11 lines | `bod, armorCurrentRating = 0, personaStatus, securityCode` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/IcDamageResult.kt | ✓ Read — 14 lines | `mpcpReductionOnKill: Int = 0, personaOnlyCrashed: Boolean = false` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/IcSuppressionState.kt | ✓ Read — 13 lines | `data class IcSuppressionState(val ic: IC, val icRating: Int)` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/JackOutPinResult.kt | ✓ Read — 6 lines | `succeeded: Boolean, finalIcAttackTriggered: Boolean` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/ManeuverParticipant.kt | ✓ Read — 9 lines | `evasion, sensor, cloakRating = 0, lockOnRating = 0, hackingPool = 0` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/ManeuverResult.kt | ✓ Read — 6 lines | `Success(netSuccesses); data object Failure` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/SimsenseOverloadResult.kt | ✓ Read — 6 lines | `willpowerTestPassed: Boolean, stressBoxesApplied: Int` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/SlowResult.kt | ✓ Read — 6 lines | `actionsLost: Int, icInert: Boolean` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/TarBabyResult.kt | ✓ Read — 9 lines | `updatedDecker, bothCrashed: Boolean, deckerNoticed: Boolean` | None |
| src/main/kotlin/com/shadowrun/matrix/combat/TrackState.kt | ✓ Read — 8 lines | `trackingIcRating, locationCycleTurnsRemaining, opponentSensorRating, trackerMcpRating` | None |
| src/main/kotlin/com/shadowrun/matrix/common/Enums.kt | ✓ Read — 39 lines | `enum class PersonaAttributeType { BOD, EVASION, MASKING, SENSORS }`; `DamageLevel.boxes` 1/3/6/10 | NM-2 |
| src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt | ✓ Read — 28 lines | `val isCrashed: Boolean get() = isDestroyed` (`damage >= maxBoxes`), `maxBoxes = 10` | None |
| src/main/kotlin/com/shadowrun/matrix/config/ConfigUtils.kt | ✓ Read — 9 lines | `parseSubsystemRatings(value): Map<String, Int>` | None |
| src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogEntry.kt | ✓ Read — 11 lines | `model, mpcp, hardening, activeMemoryMp, storageMemoryMp, ioSpeedMpPerTurn, costNuyen` (no response_increase) | None |
| src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogLoader.kt | ✓ Read — 28 lines | `activeMemoryMp = data["active_memory"] as Int, ... ioSpeedMpPerTurn = data["io_speed"]` | None |
| src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt | ✓ Read — 132 lines | `resolvedMcp = data["mpcp"] ... ?: error("mpcp required")`; buildPersonaPrograms; no rating/storage validation | CFG-1 |
| src/main/kotlin/com/shadowrun/matrix/config/GridInitializer.kt | ✓ Read — 14 lines | `getResourceAsStream("grid.yaml") ... GridLoader.load(it)` | None |
| src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt | ✓ Read — 288 lines | PLTG replication: `ltgs.map { ltg -> ... ltg.copy(pltgs = ltg.pltgs + pltgsForLtg) }` (72) | None |
| src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt | ✓ Read — 220 lines | `securityDeckerCount = (data["security_decker_count"] as? Int) ?: 0`; nodes default all 5 types | None |
| src/main/kotlin/com/shadowrun/matrix/decker/ActiveMemory.kt | ✓ Read — 21 lines | `PendingUpload(utility, turnsRemaining)`; `LoadUtilityResult.Success/InsufficientMemory` | None |
| src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt | ✓ Read — 91 lines | `val maxResponseIncrease get() = minOf(3, mcpRating / 4)`; `init { require(responseIncrease <= maxResponseIncrease) }` | NM-1 |
| src/main/kotlin/com/shadowrun/matrix/decker/Cyberterminal.kt | ✓ Read — 49 lines | `require(mcpRating <= 4)`; `responseIncrease = 0`; `isCyberterminal = true` | NM-1 |
| src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt | ✓ Read — 232 lines | `hackingPool get() = (intelligence + cyberdeck.mcpRating) / 3`; `effectiveDetectionFactor = maxOf(2, detectionFactor - suppressionDfPenalty)` (67) | NM-1 |
| src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt | ✓ Read — 106 lines | `turnsRequired = ceil(mpSize/ioSpeed)`; advanceCombatTurn partitions `currentRating > 0` | None |
| src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt | ✓ Read — 376 lines | `OnLTG -> loc.ltg.parentRtg.securityTally; OnPLTG -> 0`; gracefulLogoff `effectiveTn = accessRating + trackPenalty` | NAV-1 |
| src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt | ✓ Read — 678 lines | invokeMedic TN table 4/5/6, `filled >= 10 -> return(this,0,...)`, `newMedicRating = medic.currentRating - 1` | None |
| src/main/kotlin/com/shadowrun/matrix/decker/DownloadDestination.kt | ✓ Read — 10 lines | `ActiveMemory; StorageMemory; OfflineStorage(accessory)` | None (deferred #6) |
| src/main/kotlin/com/shadowrun/matrix/decker/MedicResult.kt | ✓ Read — 12 lines | `updatedDecker, boxesRepaired, medicRating` | None |
| src/main/kotlin/com/shadowrun/matrix/decker/MovementResult.kt | ✓ Read — 24 lines | `LogonResult.Success/Failure(location?)`; `LogoffResult.GracefulSuccess/JackOut(dumpShock)` | None |
| src/main/kotlin/com/shadowrun/matrix/decker/Persona.kt | ✓ Read — 38 lines | `bod, evasion, masking, sensor, reaction = 0, sleazeRating = 0, conditionMonitor, status, currentNode` | None |
| src/main/kotlin/com/shadowrun/matrix/game/ActionResult.kt | ✓ Read — 8 lines | `IcAttack(message); IcMoved(message); NoTarget; DeckerAction` | None |
| src/main/kotlin/com/shadowrun/matrix/game/ActiveIcon.kt | ✓ Read — 9 lines | `interface ActiveIcon { suspend fun action(...); fun initiative(...) }` | None |
| src/main/kotlin/com/shadowrun/matrix/game/ActiveIconState.kt | ✓ Read — 7 lines | `data class ActiveIconState(val icon: ActiveIcon, val currentInitiative: Int)` | None |
| src/main/kotlin/com/shadowrun/matrix/game/DeckerExtensions.kt | ✓ Read — 20 lines | `asDefenderParticipant()`: armor from first ARMOR utility `currentRating`; `securityCode = loc.host.securityRating.code` | None |
| src/main/kotlin/com/shadowrun/matrix/game/Game.kt | ✓ Read — 91 lines | runCombatTurn: proactive IC+non-meatworld sorted desc, decrement 10; reactive IC once; advanceCombatTurn end | None |
| src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt | ✓ Read — 95 lines | `checkTriggers`: filter `tallyThreshold in (oldTally+1)..newTally`; `updateHost` rewires OnHost refs | None |
| src/main/kotlin/com/shadowrun/matrix/ic/IC.kt | ✓ Read — 274 lines | 11 subtypes (White: Crippler/Killer/Probe/Scramble/TarBaby; Gray: Blaster/Ripper/Sparky/TarPit; Black: Lethal/NonLethal); `withRatingBonus` | None |
| src/main/kotlin/com/shadowrun/matrix/network/AlertTransitions.kt | ✓ Read — 91 lines | PASSIVE_ALERT: all 5 subsystems `+ 2`; `NO_ALERT -> host` | None |
| src/main/kotlin/com/shadowrun/matrix/network/DataFile.kt | ✓ Read — 26 lines | equality on `name + isScrambleProtected + sizeMp`; `isPointer get() = pointerToHost != null` | None |
| src/main/kotlin/com/shadowrun/matrix/network/Grid.kt | ✓ Read — 63 lines | `securityRating: SecurityRating` (code+value); RTG/LTG/PLTG equals by name | None |
| src/main/kotlin/com/shadowrun/matrix/network/Host.kt | ✓ Read — 43 lines | `init { require(coveredTypes == SubsystemType.entries.toSet()) }` (covers types, not count==5) | HOST-1 |
| src/main/kotlin/com/shadowrun/matrix/network/Jackpoint.kt | ✓ Read — 15 lines | `init { require((connectsToLtg == null) != (connectsToHost == null)) }` | None |
| src/main/kotlin/com/shadowrun/matrix/network/Matrix.kt | ✓ Read — 11 lines | RTG container | None |
| src/main/kotlin/com/shadowrun/matrix/network/MatrixLocation.kt | ✓ Read — 8 lines | `OnLTG/OnRTG/OnPLTG/OnHost` | None |
| src/main/kotlin/com/shadowrun/matrix/network/Node.kt | ✓ Read — 5 lines | `data class Node(val subsystemType: SubsystemType, val description: String = "")` | None |
| src/main/kotlin/com/shadowrun/matrix/network/RemoteDevice.kt | ✓ Read — 3 lines | `data class RemoteDevice(val name: String, val systemAddress: String)` | None |
| src/main/kotlin/com/shadowrun/matrix/network/SAN.kt | ✓ Read — 3 lines | `data class SAN(val name: String, val isScrambleProtected: Boolean = false)` | None |
| src/main/kotlin/com/shadowrun/matrix/network/SecuritySheaf.kt | ✓ Read — 27 lines | `TriggerStep(tallyThreshold, description, activatedIc, alertTransition?, securityDeckerCount = 0)` | None (field `triggerSteps`, see adversarial note) |
| src/main/kotlin/com/shadowrun/matrix/operations/AvailableAction.kt | ✓ Read — 27 lines | `sealed class AvailableAction { ... Operation(operation, target?, actionType = operation.actionType) }` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/BufferedMessage.kt | ✓ Read — 17 lines | `data class BufferedMessage(val text: String, val recipient: LinkedObserver)` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/DownloadHandle.kt | ✓ Read — 19 lines | `file, totalMp, ioSpeedMpPerTurn, turnsRemaining, active = true, destination = StorageMemory` | None (deferred #6) |
| src/main/kotlin/com/shadowrun/matrix/operations/InterrogationState.kt | ✓ Read — 25 lines | `operation, query, accumulatedSuccesses: Int = 0` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/MatrixIcon.kt | ✓ Read — 32 lines | `Icon.PersonaIcon(persona, sleazeRating=0); Icon.IcIcon(ic)` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/MatrixObject.kt | ✓ Read — 22 lines | 8 variants GridNode/LocalGrid/PrivateGrid/HostNode/HostSubsystem/IcProgram/File/Device | None |
| src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt | ✓ Read — 27 lines | `operation, target: MonitoredTarget, active = true, needsMaintenance = false` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/NullOperationModifier.kt | ✓ Read — 33 lines | `UNDER_TEN_SECONDS(0), ...(1), ...(2), ONE_HOUR_TO_TWELVE_HOURS(4)`; applied to host SV | None |
| src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt | ✓ Read — 103 lines | `Success/Failure(decker, outcome)` over abstract SystemTestOutcome | None |
| src/main/kotlin/com/shadowrun/matrix/operations/PointerChain.kt | ✓ Read — 15 lines | `links: List<Host>, finalFile: DataFile` | None |
| src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt | ✓ Read — 57 lines | 29 entries; RELOCATE_ICON(CONTROL,RELOCATE,SIMPLE,STANDARD), INVOKE_MEDIC(CONTROL,null,COMPLEX,STANDARD), SWAP_MEMORY/LOCATE_DECKER deferred | None |
| src/main/kotlin/com/shadowrun/matrix/operations/SystemTestOutcome.kt | ✓ Read — 8 lines | `deckerSuccesses, hostSuccesses, deckerWins` (>= ties to decker) | None |
| src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt | ✓ Read — 179 lines | `effectiveTn = maxOf(2, accessRating - utilityRating)`; CT-03 `effectiveRating = maxOf(0, currentRating-1)`; NullOp bonus to SV | None |
| src/main/kotlin/com/shadowrun/matrix/operations/UploadHandle.kt | ✓ Read — 17 lines | `file, totalMp, ioSpeedMpPerTurn, turnsRemaining, active = true` (no destination) | None |
| src/main/kotlin/com/shadowrun/matrix/programs/PersonaProgram.kt | ✓ Read — 8 lines | `class PersonaProgram(attributeType, rating): Program(name=attributeType.name, rating, multiplier=1)` | None |
| src/main/kotlin/com/shadowrun/matrix/programs/Program.kt | ✓ Read — 9 lines | `abstract class Program(name, rating, multiplier) { val mpSize get() = rating*rating*multiplier }` | None |
| src/main/kotlin/com/shadowrun/matrix/programs/Utility.kt | ✓ Read — 49 lines | `UtilityType(multiplier, category)`: ANALYZE(3), TRACK(8), BLACK_HAMMER(20), KILLJOY(10), MEDIC(4)...; single ATTACK w/ computed multiplier | None |
| src/main/kotlin/com/shadowrun/matrix/server/DeckerDisconnectedException.kt | ✓ Read — 3 lines | `class DeckerDisconnectedException : Exception("decker disconnected mid-turn")` | None |
| src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt | ✓ Read — 81 lines | `MAX_CONNECTIONS = 32`; `webSocket("/decker/ws")`; join/action/else dispatch | None |
| src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt | ✓ Read — 174 lines | `if (name.length > 32) ... NAME_TOO_LONG`; reconnectToken only for REGISTERED_DECKER | None |
| src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt | ✓ Read — 52 lines | active session / pending action coordinator | None |
| src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt | ✓ Read — 499 lines | `actionTimeoutSeconds: Long = 120`; `withTimeoutOrNull`; params dispatch correct; SWAP_MEMORY/LOCATE_DECKER fall to else | None |
| src/main/kotlin/com/shadowrun/matrix/server/dto/AvailableActionDto.kt | ✓ Read — 92 lines | @SerialName LogonToRtg/Ltg/Pltg/Host/GracefulLogoff/JackOut/Operation; paramKind mapping correct | None |
| src/main/kotlin/com/shadowrun/matrix/server/dto/DeckerStateDto.kt | ✓ Read — 45 lines | `locationIndex = if (currentLocation != null) 0 else null` (deferred #4); all 12 fields | None (deferred #4) |
| src/main/kotlin/com/shadowrun/matrix/server/dto/MatrixObjectDto.kt | ✓ Read — 141 lines | IcProgram `rating/behavior/guardedNodeType` null unless analyzed; 8 @SerialName kinds | None |
| src/main/kotlin/com/shadowrun/matrix/server/dto/Messages.kt | ✓ Read — 85 lines | ResultMessage deckerSuccesses/hostSuccesses non-null Int; 8 ErrorCodes; 3 roles; MatrixJson encodeDefaults | None |
| src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt | ✓ Read — 36 lines | `face = random.nextInt(1,7); ... while (face == 6)` (exploding); `require(targetNumber >= 2)` | None |

---

## Kotlin Test Source (src/test)

Iteration 8: all test files audited (two agents, 2026-09-04) — no test contradicts the spec.

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt | ✓ Read — ~1358 lines | Blaster TN=8 (no +2) vs Sparky TN=10 (+2); Black IC death `ic.rating*2`; Ripper floor 0 / Crippler floor 1; attack TN tables | None (spec-consistent) |
| src/test/kotlin/com/shadowrun/matrix/combat/CombatTest.kt | ✓ Read — 24 lines | DumpShock level BLUE→LIGHT…RED→DEADLY; power == security value | None |
| src/test/kotlin/com/shadowrun/matrix/common/SharedTypesTest.kt | ✓ Read — 34 lines | ConditionMonitor maxBoxes=10, applyDamage caps, isDestroyed | None |
| src/test/kotlin/com/shadowrun/matrix/config/DeckerConfigTest.kt | ✓ Read — 135 lines | `(6+8)/3=4` hackingPool; `ceil((6+5)/2)=6` DF | None |
| src/test/kotlin/com/shadowrun/matrix/config/GridLoadTest.kt | ✓ Read — 69 lines | grid/RTG/LTG structure; no formula asserts | None |
| src/test/kotlin/com/shadowrun/matrix/decker/CyberdeckAndProgramMechanicsTest.kt | ✓ Read — 975 lines | upload `ceil(48/50)=1`; Sleaze DF `ceil((6+3)/2)=5`; RI cap MPCP8→2; Medic TN 4/5/6, −1 per use, no-op at 10 | None |
| src/test/kotlin/com/shadowrun/matrix/decker/DeckerOperationsTest.kt | ✓ Read — 650 lines | locateAccessNode threshold 5; TN floors at 2; scanner reductions | None |
| src/test/kotlin/com/shadowrun/matrix/decker/DeckerTest.kt | ✓ Read — 306 lines | `(6+6)/3=4`; `detectionFactor(6,4)=5`; RI cap MPCP6→1; effectiveDF floored 2 | None |
| src/test/kotlin/com/shadowrun/matrix/decker/DeckerVisibilityTest.kt | ✓ Read — 256 lines | visibility / available-action set membership | None |
| src/test/kotlin/com/shadowrun/matrix/decker/MovementTest.kt | ✓ Read — 663 lines | DF formulas; tally accumulation; jackOut always dump shock | None |
| src/test/kotlin/com/shadowrun/matrix/game/GameContextTest.kt | ✓ Read — 275 lines | Passive alert `baseAccess + 2`; does-not-regress | None |
| src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt | ✓ Read — 605 lines | IC dispatch; initiative ordering; defender participant | None |
| src/test/kotlin/com/shadowrun/matrix/ic/IcBehaviorTest.kt | ✓ Read — 193 lines | TarBaby/TarPit contest removal; MPCP unchanged without dump shock | None |
| src/test/kotlin/com/shadowrun/matrix/ic/IcTest.kt | ✓ Read — 92 lines | initiativeDice BLUE=1/GREEN=2/ORANGE=3/RED=4; Probe REACTIVE; BlackIC PROACTIVE | None |
| src/test/kotlin/com/shadowrun/matrix/integration/AlertAndTallyTest.kt | ✓ Read — 137 lines | `assertEquals(before.access + 2, ...)` all 5; `assertEquals(0, loc.rtg.securityTally)` fresh RTG | None |
| src/test/kotlin/com/shadowrun/matrix/integration/CombatTest.kt | ✓ Read — 231 lines | Blaster MPCP TN `hardening(0)+mcpRating(5)=5`; Crippler `bod >= 1`; Medic TN 4 | None |
| src/test/kotlin/com/shadowrun/matrix/integration/DeckerCombatTest.kt | ✓ Read — 245 lines | `dfBefore - 1` / `dfBefore - 2` suppression; trackLock TN `max(2,1)=2` | None |
| src/test/kotlin/com/shadowrun/matrix/integration/FileOperationsTest.kt | ✓ Read — 159 lines | `// 8 net → ≥ 5 → Located`; download completes only after all turns | None |
| src/test/kotlin/com/shadowrun/matrix/integration/GrayCombatTest.kt | ✓ Read — 202 lines | Ripper `bod == 0`; Sparky MPCP TN `hardening(0)+mcpRating+2` | None |
| src/test/kotlin/com/shadowrun/matrix/integration/ICActivationTest.kt | ✓ Read — 191 lines | simultaneous trigger → PASSIVE_ALERT + both Probe & Killer active | None |
| src/test/kotlin/com/shadowrun/matrix/integration/ManeuverTest.kt | ✓ Read — 129 lines | `Mover TN = max(2, sensor - cloak)`; `Opponent TN = max(2, evasion - lockOn)` | None |
| src/test/kotlin/com/shadowrun/matrix/integration/MemoryManagementTest.kt | ✓ Read — 142 lines | upload countdown/promotion; `InsufficientMemory` requiredMp > availableMp | None |
| src/test/kotlin/com/shadowrun/matrix/integration/MovementTest.kt | ✓ Read — 157 lines | gracefulLogoff fail → JackOut w/ dump shock | None |
| src/test/kotlin/com/shadowrun/matrix/integration/SlaveOperationsTest.kt | ✓ Read — 143 lines | `// 8 net → ≥ 3 → Located` (LocateSlave threshold 3) | None |
| src/test/kotlin/com/shadowrun/matrix/integration/UploadDataAndScrambleTest.kt | ✓ Read — 112 lines | `result.icRating == 5` for Scramble(5); destruct on IC success | None |
| src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt | ✓ Read — 223 lines | role strings observer/registered_decker/active_controller; `kind="LogonToLtg"`, `ltgName` | None |
| src/test/kotlin/com/shadowrun/matrix/integration/utility/DeckerMock.kt | ✓ Read — 117 lines (infra) | HIGH_END mcpRating=12/IO=300; STANDARD 6/150; LOW_END 3/50 | None |
| src/test/kotlin/com/shadowrun/matrix/integration/utility/GridMock.kt | ✓ Read — 34 lines (infra) | grid init helpers | None |
| src/test/kotlin/com/shadowrun/matrix/integration/utility/HostMock.kt | ✓ Read — 20 lines (infra) | GREEN rating-3 host, subsystems (3,3,3,3,3), TIERED | None |
| src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt | ✓ Read — 232 lines (infra) | rollers: winRoller→0, failRoller→3, hitRoller→5 | None |
| src/test/kotlin/com/shadowrun/matrix/integration/utility/ScenarioBuilder.kt | ✓ Read — 347 lines (infra) | jackOut asserts `dumpShock=true`; gracefulLogoff fail → JackOut | None |
| src/test/kotlin/com/shadowrun/matrix/network/AlertTransitionsTest.kt | ✓ Read — 82 lines | PASSIVE_ALERT +2 all 5 (4→6…8→10); ACTIVE unchanged; stacks | None |
| src/test/kotlin/com/shadowrun/matrix/network/NetworkTest.kt | ✓ Read — 247 lines | grid hierarchy; Host node/SAN structure | None |
| src/test/kotlin/com/shadowrun/matrix/operations/NullOperationModifierTest.kt | ✓ Read — 60 lines | <10s=0, 10-59s=1, 60-3599s=2, ≥3600s=4 | None |
| src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationTest.kt | ✓ Read — 34 lines | ANALYZE_HOST → CONTROL/ANALYZE; 29 operations | None |
| src/test/kotlin/com/shadowrun/matrix/operations/SystemOperationsTest.kt | ✓ Read — 540 lines | actionsPerTurn `reaction 9/RI2 → ceil(9/10)+2 = 3`; LocateFile=5/LocateSlave=3; download `ceil(200/100)=2` | None |
| src/test/kotlin/com/shadowrun/matrix/operations/SystemTestResolverTest.kt | ✓ Read — 183 lines | NullOp SV bonus +0/+2/+4; QueryPrecision +2/+1/0/-1/-2; TN floor 2 | None |
| src/test/kotlin/com/shadowrun/matrix/programs/ProgramTest.kt | ✓ Read — 29 lines | PersonaProgram `mpSize == rating²`; Analyze rating 3 → 27; ATTACK `ordinal+2` | None |
| src/test/kotlin/com/shadowrun/matrix/server/FakeWebSocketSession.kt | ✓ Read — 40 lines (infra) | no baked game constants | None |
| src/test/kotlin/com/shadowrun/matrix/server/SessionRegistryTest.kt | ✓ Read — 213 lines | 32-char name succeeds; 33-char → NAME_TOO_LONG | None |
| src/test/kotlin/com/shadowrun/matrix/server/TurnCoordinatorTest.kt | ✓ Read — 109 lines | NOT_YOUR_TURN when not active controller; NO_ACTION_PENDING | None |
| src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt | ✓ Read — 363 lines | NAME_ALREADY_TAKEN/ALREADY_REGISTERED/NOT_YOUR_TURN/BAD_REQUEST verbatim | None (ResultMessage successes presence under-asserted, not contradictory) |
| src/test/kotlin/com/shadowrun/matrix/server/dto/DtoMappingTest.kt | ✓ Read — 232 lines | IcProgram unanalyzed → `assertNull(rating/behavior/guardedNodeType)`; all 8 kinds | None |
| src/test/kotlin/com/shadowrun/matrix/utility/DiceRollerTest.kt | ✓ Read — 63 lines | exploding 6s (`6,3→9`); TN 1 throws (face [1,6], min TN 2) | None |

---

## Frontend TypeScript (frontend/src)

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| frontend/src/App.tsx | ✓ Read — 115 lines | `isActiveTurn = role === 'active_controller'`; JoinScreen when role null/observer | None |
| frontend/src/components/ActionsPanel.tsx | ✓ Read — 217 lines | default `precision: 'NORMAL'`; all 5 paramKind controls; `disabled = !isActiveTurn` | None |
| frontend/src/components/DeckerPanel.tsx | ✓ Read — 76 lines | renders name, isPinnedByBlackIc warning, phys/mental monitors, hackingPool, mcpRating, activeUtilities | None |
| frontend/src/components/EntitiesPanel.tsx | ✓ Read — 106 lines | filters to HostSubsystem/IcProgram/File/Device; IcProgram fields only when `analyzed === true` | None |
| frontend/src/components/LocationPanel.tsx | ✓ Read — 109 lines | prefix parse RTG/LTG/PLTG/Host; `locationIndex != null ? visibleObjects[idx] : find-by-name` | None (deferred #4) |
| frontend/src/components/NarrativePanel.tsx | ✓ Read — 64 lines | `[{deckerSuccesses}d vs {hostSuccesses}h]`; ERROR_LABELS covers all 8 codes; active-turn pulse | None |
| frontend/src/hooks/useWebSocket.ts | ✓ Read — 190 lines | reconnect 3000 initial → `Math.min(*2, 30000)`; events `slice(-19)`; `sendAction` guarded to active_controller | RT-1, UI-1 |
| frontend/src/main.tsx | ✓ Read — 10 lines | React 18 entry point | None |
| frontend/src/types/messages.ts | ✓ Read — 133 lines | all 7 type aliases + 8 ErrorCodes; ResultMessage successes non-optional number; reconnectToken optional | None |

---

## Completion Gate Checklist

- [x] Count match: 155 rows = 138 source (85 main + 44 test + 9 FE) + 17 design. Design 15 ✓ + 2 Skip:infra. Main 85 ✓. FE 9 ✓. Test 44 ✓ (Iteration 8 complete).
- [x] PRD coverage: all 3 PRDs (prd_core, prd_game, prd_ui) read in full this session.
- [x] Adversarial check: performed — see "Considered but NOT flagged" in discrepancies_without_prd.md (10 candidates retracted, incl. STR-1 after verifying Decker.kt:67).
- [x] Deferred currency: deferred.md #4 (locationIndex), #6 (DownloadHandle.destination), #2/#3 (SWAP_MEMORY/LOCATE_DECKER) all verified against current code; not flagged.
- [x] Root-cause consolidation: complete. 10 findings across main source (CM-1/2, NAV-1, CFG-1, HOST-1, RT-1, UI-1, NM-1/2/3); 0 findings across all 44 test files (every asserted constant/formula matches the spec). No cross-cutting root cause — the findings are independent; the only near-duplicate pair (CM-1/CM-2) shares the omitted-`personaOnlyCrashed` root cause in the Black Hammer/Killjoy resolvers.
