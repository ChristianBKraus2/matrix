# Decker Controller Interface

This document describes the three-method interface that a **controller** — an AI agent, user interface, or game-master tool — uses to observe and drive a `Decker` through the Shadowrun Matrix.

A controller never needs to read `Decker.kt` source. It queries what the decker can see (`visibleObjects`), queries what the decker can do (`availableActions`), picks one action, and calls the corresponding `Decker` method. The game engine takes care of initiative and timing by calling `action()` — the controller never calls `action()` directly.

---

## 1. The interaction loop

```
while (decker.persona != null) {
    val objects  = decker.visibleObjects()    // what can be seen
    val actions  = decker.availableActions()  // what can be done

    val chosen   = controller.decide(objects, actions, decker)

    val result   = invoke(decker, chosen, diceRoller)   // call the matching Decker method
    decker       = result.decker                        // replace with updated copy
}
```

**Immutability.** `Decker` is a Kotlin `data class` — all operations return a new copy. After every call you must store the `decker` field from the result and use it for subsequent calls. Discarding it means losing tally updates, utility state changes, and damage.

---

## 2. Decker state the controller reads

| Field | Type | Meaning |
|---|---|---|
| `persona` | `Persona?` | `null` when not jacked in. Non-null while in the Matrix. |
| `currentLocation` | `MatrixLocation?` | Sealed type; see below. `null` before first jack-in. |
| `isPinnedByBlackIc` | `Boolean` | `JackOut` will throw when this is `true`. |
| `actionsPerTurn` | `Int` | Number of actions available per combat turn (requires jacked-in persona). |
| `cyberdeck.activeUtilities` | `List<Utility>` | Utilities currently effective. Each has `type: UtilityType` and `currentRating: Int`. |
| `persona?.currentNode` | `Node?` | Which subsystem node the persona is in (relevant for node-guarded IC). |

### MatrixLocation subtypes

| Subtype | Meaning | Key fields |
|---|---|---|
| `OnRTG(rtg)` | On a Regional Telecommunications Grid | `rtg.name`, `rtg.ltgs`, `rtg.connectedRtgs` |
| `OnLTG(ltg)` | On a Local Telecommunications Grid | `ltg.name`, `ltg.parentRtg`, `ltg.hosts`, `ltg.pltgs` |
| `OnPLTG(pltg)` | On a Private LTG | `pltg.name`, `pltg.owner`, `pltg.parentLtg`, `pltg.hosts` |
| `OnHost(host)` | Inside a host system | `host.name`, `host.nodes`, `host.icPrograms`, `host.dataFiles`, `host.remoteDevices` |

---

## 3. `visibleObjects(): List<MatrixObject>`

Returns everything the decker can perceive from their current position. **Returns an empty list when not jacked in.**

Each entry is a variant of the `MatrixObject` sealed class:

### MatrixObject variants

| Variant | Wrapped type | Key readable fields |
|---|---|---|
| `MatrixObject.GridNode(rtg)` | `RTG` | `name: String`, `region: String`, `ltgs: List<LTG>`, `connectedRtgs: List<RTG>`, `alertStatus: AlertStatus`, `securityTally: Int` |
| `MatrixObject.LocalGrid(ltg)` | `LTG` | `name: String`, `parentRtg: RTG`, `hosts: List<Host>`, `pltgs: List<PLTG>`, `alertStatus: AlertStatus`, `securityTally: Int` |
| `MatrixObject.PrivateGrid(pltg)` | `PLTG` | `name: String`, `owner: String`, `parentLtg: LTG`, `hosts: List<Host>`, `alertStatus: AlertStatus` |
| `MatrixObject.HostNode(host)` | `Host` | `name: String`, `topologyType: TopologyType`, `offline: Boolean`, `alertStatus: AlertStatus`, `securityRating: SecurityRating`, `securityTally: Int` |
| `MatrixObject.HostSubsystem(node)` | `Node` | `subsystemType: SubsystemType` (ACCESS / CONTROL / INDEX / FILES / SLAVE), `description: String` |
| `MatrixObject.IcProgram(ic)` | `IC` | `name: String`, `rating: Int`, `behavior: IcBehavior` (PROACTIVE / REACTIVE), `guardedNode: Node?` |
| `MatrixObject.File(file)` | `DataFile` | `name: String`, `isScrambleProtected: Boolean`, `isPointer: Boolean`, `sizeMp: Int` |
| `MatrixObject.Device(device)` | `RemoteDevice` | `name: String`, `systemAddress: String` |

### What is visible by location

| Location | Visible objects |
|---|---|
| Not jacked in | *(empty)* |
| `OnRTG` | Own RTG (`GridNode`), each connected RTG (`GridNode`), each child LTG (`LocalGrid`) |
| `OnLTG` | Own LTG (`LocalGrid`), parent RTG (`GridNode`), each PLTG (`PrivateGrid`), each host (`HostNode`) |
| `OnPLTG` | Own PLTG (`PrivateGrid`), parent LTG (`LocalGrid`), each host (`HostNode`) |
| `OnHost` | Own host (`HostNode`), all subsystem nodes (`HostSubsystem` ×5), all IC programs (`IcProgram`), all data files (`File`), all remote devices (`Device`), each connected host (`HostNode`) |

---

## 4. `availableActions(): List<AvailableAction>`

Returns every action the decker can attempt from their current position. **Returns an empty list when not jacked in.**

Every entry has `actionType: ActionType` — one of `FREE`, `SIMPLE`, or `COMPLEX`.

> **Availability is positional only.** The list does not check whether the required utility is loaded. If an operation needs a utility (see the SystemOperation table below) and none is loaded, the roll will still proceed — it simply won't benefit from the utility modifier. It is the controller's responsibility to check `cyberdeck.activeUtilities` before committing to an action.

### Navigation actions

| Variant | Carried data | When it appears |
|---|---|---|
| `LogonToRtg(rtg, COMPLEX)` | Target `RTG` | On `OnLTG` (one entry: parent RTG); on `OnRTG` (one per connected RTG) |
| `LogonToLtg(ltg, COMPLEX)` | Target `LTG` | On `OnRTG` (one per child LTG); on `OnPLTG` (one: parent LTG) |
| `LogonToPltg(pltg, COMPLEX)` | Target `PLTG` | On `OnLTG` (one per child PLTG) |
| `LogonToHost(host, COMPLEX)` | Target `Host` | On `OnLTG`/`OnPLTG` (one per child host); on `OnHost` (one per connected host) |
| `GracefulLogoff(COMPLEX)` | — | Always when jacked in |
| `JackOut(FREE)` | — | Always when jacked in (still appears when pinned by Black IC; the call will throw — check `isPinnedByBlackIc` first) |

### System operation actions (all `Operation(operation, target?, actionType)`)

On grids (`OnRTG`, `OnLTG`, `OnPLTG`) the following operations are available with `target = null`:

| SystemOperation | Action type | Required utility |
|---|---|---|
| `NULL_OPERATION` | COMPLEX | DECEPTION |
| `ANALYZE_SECURITY` | SIMPLE | ANALYZE |
| `RELOCATE_ICON` | SIMPLE | RELOCATE |
| `LOCATE_IC` | COMPLEX | ANALYZE |
| `ANALYZE_IC` | FREE | ANALYZE | *(one per visible IC — grids currently carry no IC)* |

On a host (`OnHost`) the following operations are available:

| SystemOperation | Action type | Target in `Operation.target` | Required utility |
|---|---|---|---|
| `ANALYZE_HOST` | COMPLEX | `null` | ANALYZE |
| `ANALYZE_SECURITY` | SIMPLE | `null` | ANALYZE |
| `ANALYZE_SUBSYSTEM` | SIMPLE | `HostSubsystem` (one per node) | ANALYZE |
| `ANALYZE_IC` | FREE | `IcProgram` (one per IC on host) | ANALYZE |
| `ANALYZE_ICON` | FREE | `IcProgram` (one per IC on host) | ANALYZE |
| `LOCATE_FILE` | COMPLEX | `null` | BROWSE |
| `LOCATE_SLAVE` | COMPLEX | `null` | BROWSE |
| `LOCATE_ACCESS_NODE` | COMPLEX | `null` | BROWSE |
| `LOCATE_DECKER` | COMPLEX | `null` | SCANNER |
| `LOCATE_IC` | COMPLEX | `null` | ANALYZE |
| `DECRYPT_ACCESS` | SIMPLE | `null` | DECRYPT |
| `DECRYPT_FILE` | SIMPLE | `File` (only scramble-protected files) | DECRYPT |
| `DECRYPT_SLAVE` | SIMPLE | `null` | DECRYPT |
| `DOWNLOAD_DATA` | SIMPLE | `File` (one per file) | READ_WRITE |
| `EDIT_FILE` | SIMPLE | `File` (one per file) | READ_WRITE |
| `UPLOAD_DATA` | SIMPLE | `null` | READ_WRITE |
| `CONTROL_SLAVE` | COMPLEX | `Device` (one per device) | SPOOF |
| `EDIT_SLAVE` | COMPLEX | `Device` (one per device) | SPOOF |
| `MONITOR_SLAVE` | SIMPLE | `Device` (one per device) | SPOOF |
| `MAKE_COMCALL` | COMPLEX | `null` | COMMLINK |
| `TAP_COMCALL` | COMPLEX | `null` | COMMLINK |
| `NULL_OPERATION` | COMPLEX | `null` | DECEPTION |
| `RELOCATE_ICON` | SIMPLE | `null` | RELOCATE |
| `SWAP_MEMORY` | SIMPLE | `null` | *(none)* |

---

## 5. Executing an action — calling Decker methods

Once the controller has chosen an `AvailableAction`, it calls the corresponding `Decker` method. Obtain the current host from `(decker.currentLocation as MatrixLocation.OnHost).host` when needed.

### Navigation

| AvailableAction variant | Decker method | Return type |
|---|---|---|
| `LogonToRtg(rtg)` | `decker.logonToRtg(rtg, diceRoller)` | `LogonResult` |
| `LogonToLtg(ltg)` | `decker.logonToLtg(ltg, diceRoller)` | `LogonResult` |
| `LogonToPltg(pltg)` | `decker.logonToPltg(pltg, diceRoller)` | `LogonResult` |
| `LogonToHost(host)` | `decker.logonToHost(host, diceRoller)` | `LogonResult` |
| `GracefulLogoff` | `decker.gracefulLogoff(diceRoller)` | `LogoffResult` |
| `JackOut` | `decker.jackOut()` | `LogoffResult` |

### Operations

| Operation value | Decker method | Return type |
|---|---|---|
| `ANALYZE_HOST` | `decker.analyzeHost(host, requestedItems, diceRoller)` | `AnalyzeHostResult` |
| `ANALYZE_IC` | `decker.analyzeIc(ic, host, diceRoller)` | `OperationResult` |
| `ANALYZE_ICON` | `decker.analyzeIcon(icon, host, diceRoller)` | `OperationResult` |
| `ANALYZE_SECURITY` | `decker.analyzeSecurity(host, diceRoller)` | `AnalyzeSecurityResult` |
| `ANALYZE_SUBSYSTEM` | `decker.analyzeSubsystem(host, subsystem, diceRoller)` | `OperationResult` |
| `CONTROL_SLAVE` | `decker.controlSlave(device, host, diceRoller)` | `Pair<OperationResult, MonitoredOperationHandle?>` |
| `DECRYPT_ACCESS` | `decker.decryptAccess(host, diceRoller)` | `OperationResult` |
| `DECRYPT_FILE` | `decker.decryptFile(file, host, diceRoller)` | `OperationResult` |
| `DECRYPT_SLAVE` | `decker.decryptSlave(host, diceRoller)` | `OperationResult` |
| `DOWNLOAD_DATA` | `decker.downloadData(file, host, diceRoller)` | `Pair<OperationResult, DownloadHandle?>` |
| `EDIT_FILE` | `decker.editFile(file, host, newContent, diceRoller)` | `EditFileResult` |
| `EDIT_SLAVE` | `decker.editSlave(device, host, diceRoller)` | `Pair<OperationResult, MonitoredOperationHandle?>` |
| `LOCATE_ACCESS_NODE` | `decker.locateAccessNode(host, state, precision, diceRoller)` | `Pair<OperationResult, LocateResult>` |
| `LOCATE_DECKER` | `decker.locateDecker(host, targetPersona, diceRoller, targetSleazeRating)` | `LocateDeckerResult` |
| `LOCATE_FILE` | `decker.locateFile(host, state, precision, diceRoller)` | `Pair<OperationResult, LocateResult>` |
| `LOCATE_IC` | `decker.locateIc(host, diceRoller)` | `OperationResult` |
| `LOCATE_SLAVE` | `decker.locateSlave(host, state, precision, diceRoller)` | `Pair<OperationResult, LocateResult>` |
| `MAKE_COMCALL` | `decker.makeComcall(host, diceRoller, hasValidPasscode)` | `Pair<OperationResult, MonitoredOperationHandle?>` |
| `MONITOR_SLAVE` | `decker.monitorSlave(device, host, diceRoller)` | `Pair<OperationResult, MonitoredOperationHandle?>` |
| `NULL_OPERATION` | `decker.nullOperation(host, inactivitySeconds, diceRoller)` | `OperationResult` |
| `RELOCATE_ICON` | `decker.relocateIcon(opponentSensor, trackerMcpRating, diceRoller)` | `OperationResult` |
| `SWAP_MEMORY` | `decker.swapUtility(toUnload, toLoad)` | `LoadUtilityResult` |
| `TAP_COMCALL` | `decker.tapComcall(host, scannerDeviceRating, diceRoller)` | `Pair<OperationResult, MonitoredOperationHandle?>` |
| `UPLOAD_DATA` | `decker.uploadData(host, diceRoller)` | `OperationResult` |

**Extracting the target from `Operation`.** When `Operation.target` is non-null, cast it to the expected `MatrixObject` subtype and unwrap:

```kotlin
when (val action = chosen as AvailableAction.Operation) {
    SystemOperation.ANALYZE_IC -> {
        val ic = (action.target as MatrixObject.IcProgram).ic
        val result = decker.analyzeIc(ic, host, diceRoller)
    }
    SystemOperation.DOWNLOAD_DATA -> {
        val file = (action.target as MatrixObject.File).file
        val (opResult, handle) = decker.downloadData(file, host, diceRoller)
    }
    // …
}
```

---

## 6. Result types

Every result carries an updated `decker: Decker`. **Always replace the stored decker with this value**, even on failure.

### LogonResult

```
LogonResult.Success(decker, location)
```
The System Test succeeded. `location` is the new `MatrixLocation`; it is also stored in `decker.currentLocation`.

```
LogonResult.Failure(decker, location?)
```
The System Test failed. The decker remains at the previous location. `location` is `null` when the initial jack-in fails (persona was never created).

### LogoffResult

```
LogoffResult.GracefulSuccess(decker)
```
Clean disconnect. `decker.persona` and `decker.currentLocation` are both `null`. No dump shock.

```
LogoffResult.JackOut(decker, dumpShock)
```
Forced disconnect (voluntary jack-out, or graceful logoff fallback). `decker.persona` and `decker.currentLocation` are `null`. If `dumpShock = true`, apply physical damage to the decker's body (handled outside this library).

### OperationResult

```
OperationResult.Success(decker, outcome)
OperationResult.Failure(decker, outcome)
```
`outcome.hostSuccesses` were added to the security tally of the current location and are reflected in the updated `decker`. Read `outcome.deckerSuccesses` to know how many successes the decker rolled.

### AnalyzeHostResult

```
AnalyzeHostResult(decker, outcome, revealedSecurityRating?, revealedSubsystemRatings)
```
`revealedSecurityRating` is non-null if the decker spent a net success on it (or net ≥ 7). `revealedSubsystemRatings` maps each revealed `SubsystemType` to its integer rating.

### AnalyzeSecurityResult

```
AnalyzeSecurityResult(decker, outcome, securityRating, currentTally, alertStatus)
```
Always reveals the current security state regardless of success/failure; the host is expected to know the decker asked.

### EditFileResult

```
EditFileResult(decker, outcome, authenticationSuccesses?)
```
`authenticationSuccesses` is `null` when authentication was not attempted. A non-zero value means the decker succeeded in writing a header that makes the edit look legitimate.

### LocateResult (for LOCATE_FILE, LOCATE_SLAVE, LOCATE_ACCESS_NODE)

These operations are **interrogation** operations that accumulate successes across multiple turns. Pass the previous `InterrogationState` on each call.

```
LocateResult.Ongoing(accumulatedSuccesses)   // keep trying
LocateResult.Located(target, accumulatedSuccesses)   // found; cast target to the appropriate type
LocateResult.NotFound   // host confirmed the resource does not exist
```

### LocateDeckerResult

```
LocateDeckerResult(decker, outcome, located, targetNotified)
```
When `located = true`, `targetNotified` is also `true` — the target decker learns they were traced (but not by whom).

### DownloadHandle

```
Pair<OperationResult, DownloadHandle?>
```
`DownloadHandle` is non-null on success. It tracks `turnsRemaining` — the game engine decrements this each combat turn. When it reaches 0, the file is fully transferred. Aborting early yields a corrupted copy.

### MonitoredOperationHandle

```
Pair<OperationResult, MonitoredOperationHandle?>
```
`MonitoredOperationHandle` is non-null on success. Monitored operations (CONTROL_SLAVE, EDIT_SLAVE, MONITOR_SLAVE, MAKE_COMCALL, TAP_COMCALL) require a Free Action each Initiative Pass to maintain:

```kotlin
handle = decker.maintainMonitoredOperation(handle)   // call every pass
// or
handle = decker.abortMonitoredOperation(handle)       // cancel early
```

If `maintainMonitoredOperation` is not called, the handle's `active` flag becomes `false` and the operation ends.

---

## 7. The `action()` method and game engine integration

`Decker` implements `ActiveIcon`, which has one method:

```kotlin
fun action(context: GameContext, diceRoller: DiceRoller): ActionResult
```

The game engine calls this method each time the decker's initiative comes up. `Decker.action()` always returns `ActionResult.DeckerAction` — it is a marker only. **The controller does not call `action()` directly.**

The controller hooks into the game loop by implementing `ActiveIcon` and queuing up `Decker` method calls inside the `action()` body. The pattern used in the integration tests is `ScriptedDeckerIcon`: a list of step lambdas is built ahead of time, and each `action()` invocation executes the next step. The controller can do the same, or can use a reactive approach (query `visibleObjects`/`availableActions` inside `action()` and decide on the spot).

```kotlin
// Reactive controller pattern
class MyController(var decker: Decker) : ActiveIcon {
    override fun action(context: GameContext, diceRoller: DiceRoller): ActionResult {
        val actions = decker.availableActions()
        val chosen  = decide(decker.visibleObjects(), actions)
        val result  = invoke(decker, chosen, context, diceRoller)
        decker      = result.decker
        context.updateDecker(/* old */ context.deckers.first(), decker)
        return ActionResult.DeckerAction
    }
}
```

After calling a Decker method, call `context.updateDecker(oldDecker, newDecker)` so the game engine's `GameContext` reflects the updated state.

---

## 8. Worked example

```kotlin
// 1. Jack in — not driven by visibleObjects/availableActions (decker not yet in Matrix)
var result = decker.jackInToLtg(targetLtg, diceRoller)
decker = (result as LogonResult.Success).decker

// 2. On the LTG — find a host to log into
val hostEntry = decker.visibleObjects()
    .filterIsInstance<MatrixObject.HostNode>()
    .first { !it.host.offline }

// 3. Check that logging on is an available action (it always is for a visible host)
val logonAction = decker.availableActions()
    .filterIsInstance<AvailableAction.LogonToHost>()
    .first { it.host.name == hostEntry.host.name }

// 4. Log on
val logonResult = decker.logonToHost(logonAction.host, diceRoller)
decker = (logonResult as LogonResult.Success).decker

// 5. Inside the host — look for IC
val ic = decker.visibleObjects()
    .filterIsInstance<MatrixObject.IcProgram>()
    .firstOrNull()

// 6. If IC present, analyze it
if (ic != null) {
    val analyzeAction = decker.availableActions()
        .filterIsInstance<AvailableAction.Operation>()
        .first { it.operation == SystemOperation.ANALYZE_IC && it.target == ic }

    val host = (decker.currentLocation as MatrixLocation.OnHost).host
    val opResult = decker.analyzeIc(ic.ic, host, diceRoller)
    decker = opResult.decker   // always update
}

// 7. Leave gracefully
val logoffResult = decker.gracefulLogoff(diceRoller)
decker = when (logoffResult) {
    is LogoffResult.GracefulSuccess -> logoffResult.decker
    is LogoffResult.JackOut         -> { applyDumpShock(logoffResult.decker); logoffResult.decker }
}
```
