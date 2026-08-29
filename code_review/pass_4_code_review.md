# Code Review — Kotlin Codebase (Full Guidelines Pass)

## 🔴 Blockers

### [network/Grid.kt](src/main/kotlin/com/shadowrun/matrix/network/Grid.kt) — lines 28–39

**Guaranteed `StackOverflowError`: `LTG` ↔ `RTG` mutual data-class cycle.** `LTG` holds `parentRtg: RTG` while `RTG` holds `ltgs: List<LTG>`; the generated `equals()`, `hashCode()`, and `toString()` recurse into each other on any non-empty graph. Remove the `parentRtg` back-reference from `LTG` and look it up from the containing `Matrix`/`RTG` at call sites, or override the generated methods to exclude the circular field, or convert one side to a non-data class.

### [network/Grid.kt](src/main/kotlin/com/shadowrun/matrix/network/Grid.kt) — lines 41–51

**Guaranteed `StackOverflowError`: `PLTG` ↔ `LTG` mutual data-class cycle.** `PLTG` holds `parentLtg: LTG` while `LTG` holds `pltgs: List<PLTG>`; any use of `equals()`, `hashCode()`, or `toString()` on a populated LTG/PLTG graph will crash. Apply the same fix as for the LTG/RTG cycle: drop the parent back-reference or override the generated methods.

### [network/Host.kt](src/main/kotlin/com/shadowrun/matrix/network/Host.kt) — lines 11–29

**Guaranteed `StackOverflowError`: `Host` ↔ `DataFile` mutual data-class cycle.** `Host` holds `dataFiles: List<DataFile>` and `DataFile` holds `pointerToHost: Host?`; if the pointed-to host carries data files, the generated `equals()`/`hashCode()`/`toString()` recurse infinitely. Make the pointer a lightweight reference (e.g. a host name/id `String`) rather than a full `Host` object, or convert one side to a non-data class with hand-rolled equality.

---

## 🟠 Major

### [config/DeckCatalogLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogLoader.kt) — line 14

**Unsafe non-null cast on a nullable map lookup.** `data["decks"] as List<Map<String, Any>>` throws `NullPointerException` with no context if the `decks` key is absent in the YAML. Replace with `(data["decks"] as? List<Map<String, Any>>) ?: error("missing 'decks' key in deck catalog YAML")` to give an actionable message, consistent with error-handling elsewhere in this codebase.

### [decker/Cyberdeck.kt](src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt) — line 73

**Incomplete active-memory invariant in the `init` block.** The guard sums only `activeUtilities`, but `usedActiveMemoryMp` (line 37) sums both `activeUtilities` and `pendingUploads`. A `Cyberdeck` constructed or produced by `copy()` with both active utilities and pending uploads can silently have `freeActiveMemoryMp < 0` without triggering the `require`. The guard should mirror `usedActiveMemoryMp`: `require(activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize } <= activeMemoryMp)`.

### [decker/DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt) — line 411

**`dice.first()` in `resolvePointerChain` throws `NoSuchElementException` on an empty list.** `diceRoller.roll(1, 6).dice.first()` crashes if the roller returns zero dice (e.g. a stub or edge-case roller). Prefer `firstOrNull() ?: 1` or add an assertion that `roll(n, …)` always returns exactly `n` elements.

### [game/Game.kt](src/main/kotlin/com/shadowrun/matrix/game/Game.kt) — lines 18–20

**`runOutOfCombatTurn()` swallows `CancellationException`.** The `catch(e: Exception)` block in this suspend function catches `CancellationException` (a subclass of `IllegalStateException`), suppressing cooperative coroutine cancellation. Add `if (e is CancellationException) throw e` as the first statement in the catch block, or add a dedicated `catch (e: CancellationException) { throw e }` branch before the generic handler.

### [game/Game.kt](src/main/kotlin/com/shadowrun/matrix/game/Game.kt) — lines 33–34

**`runCombatTurn()` swallows `CancellationException`.** Identical issue to lines 18–20: the `catch(e: Exception)` in this suspend function prevents cancellation from propagating. Apply the same fix.

### [network/Grid.kt](src/main/kotlin/com/shadowrun/matrix/network/Grid.kt) — lines 16–26

**`RTG` self-referential data class causes `StackOverflowError` on bidirectional links.** `RTG.connectedRtgs: List<RTG>` means any mutual connection (A contains B, B contains A) causes infinite recursion in the generated `equals()`/`hashCode()`/`toString()`. Real network topologies routinely have bidirectional RTG links; use a name/id reference instead of a full object reference for peer RTGs.

### [network/Host.kt](src/main/kotlin/com/shadowrun/matrix/network/Host.kt) — line 31

**`Host.connectedHosts: List<Host>` causes `StackOverflowError` on any cycle or mutual link.** Cycles between hosts are common in tiered/mesh topologies and will trigger infinite recursion in the generated `equals()`/`hashCode()`/`toString()`. Use a name/id reference or convert to a non-data class for the connected-host graph.

### [operations/NullOperationModifier.kt](src/main/kotlin/com/shadowrun/matrix/operations/NullOperationModifier.kt) — line 29

**KDoc and formula for `totalBonusForDuration` describe contradictory behaviour.** The KDoc says "+1 per additional 12-hour window beyond the first" (first increment at 43200 s), but `(seconds - 3600) / 43200` fires the first non-zero increment at 46800 s (13 h), one full 12-hour block late. If the KDoc is authoritative, change the formula to `(seconds - 43200) / 43200 + 1`; if the inline comment is correct, update the KDoc. As written, callers relying on the KDoc observe off-by-one bonus increments.

### [server/MatrixServer.kt](src/main/kotlin/com/shadowrun/matrix/server/MatrixServer.kt) — line 57

**`catch (e: Exception)` inside the `webSocket` coroutine lambda swallows `CancellationException`.** Unlike `WebSocketDeckerController.conductTurn`, this catch block has no guard that re-throws `CancellationException`, so a mid-execution cancellation is suppressed, logged as a spurious "Frame dispatch error", and execution continues. Add an explicit `if (e is CancellationException) throw e` (or a dedicated `catch (e: CancellationException) { throw e }` branch) before the generic handler.

### [server/TurnCoordinator.kt](src/main/kotlin/com/shadowrun/matrix/server/TurnCoordinator.kt) — line 24

**`currentControllerUnsafe()` reads `activeController` without acquiring `TurnCoordinator.mutex`, and the field is not `@Volatile`.** Writes occur under `TurnCoordinator.mutex` in `setActive()` and `cancelIfActive()`; `SessionRegistry.broadcastWithRoles` reads the field while holding only `SessionRegistry.mutex`, establishing no happens-before relationship with the writes. On the JVM, `broadcastWithRoles` can observe a stale value and broadcast the wrong role to sessions. Mark `activeController` `@Volatile`, or add an accessor that acquires `TurnCoordinator.mutex`.

### [Main.kt](src/main/kotlin/com/shadowrun/matrix/Main.kt) — lines 46–48

**The game-loop `catch(e: Exception)` logs only `e.message` (no stack trace) and resumes the loop.** Every `NullPointerException`, `ClassCastException`, and `IllegalStateException` is silently swallowed; bugs that should crash and surface instead repeat every 500 ms. Use `logger.error(e) { … }` to capture the full stack trace, and consider catching only expected operational exceptions rather than the entire `Exception` hierarchy.

### [test/game/GameTest.kt](src/test/kotlin/com/shadowrun/matrix/game/GameTest.kt) — line 512

**Test `runOutOfCombatTurn calls action on each decker` is a no-op.** `trackingIcon` is defined but never passed to the `Game`; the game runs `decker1.action()` and `decker2.action()` on the unchanged deckers instead. `actionLog` stays empty and is never asserted; the final `assertEquals(2, ctx.deckers.size)` was true before the call and proves nothing about whether `runOutOfCombatTurn` executed. The test passes green even if `runOutOfCombatTurn` does nothing.

---

## 🟡 Minor

### [combat/CombatResolver.kt](src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) — line 291

**`resolveLethalBlackIc` (and identically `resolveNonLethalBlackIc` at line 339) hardcodes `attackerSuccesses = 1` in the returned `AttackResult.Hit`.** Neither function rolls an attack, so any caller passing `iconDamage` to `resolveTrackLock` compares against a bogus success count, silently producing a wrong `cycleTurns` calculation. Either document the field as meaningless and guard the call site, or thread a real success count through.

### [config/DeckerLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt) — line 61

**The active-utility partition is O(n²) and silently drops `active` flags for duplicate `UtilityType` entries.** The loop re-scans the full `utilData` list via `firstOrNull` for each utility; if two utilities share the same `UtilityType`, the second utility's `active: true/false` flag is ignored. Build a `Map<UtilityType, Boolean>` from `utilData` once before the partition to fix both issues.

### [config/GridLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt) — line 29

**`rtgs.associateBy { it.name }` silently drops duplicate RTG IDs.** If two RTG entries in `grid.yaml` share the same `id`, the second entry overwrites the first with no warning. Add `require(rtgs.size == rtgById.size) { "duplicate RTG IDs" }` after building the map.

### [config/GridLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt) — line 147

**`parseSubsystemRatings` casts a nullable `Any?` parameter directly to `Map<String, Int>` with no null guard.** If the `ratings` key is absent in the YAML, the cast throws `NullPointerException` instead of an informative error. Add `requireNotNull(value) { "ratings map is required" }` before the cast, mirroring the `error(…)` pattern used elsewhere in this file.

### [config/HostLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt) — line 57

**`nodes.groupBy { it.subsystemType }.mapValues { (_, v) -> v.first() }` silently discards duplicate `Node` entries with the same `SubsystemType`.** A host YAML with two nodes of the same type loses all but the first with no warning or error. Add a validation check or at minimum log a warning on discard.

### [config/HostLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/HostLoader.kt) — line 206

**`parseSubsystemRatings` is a verbatim copy of the `GridLoader` version and carries the same unsafe null cast.** Missing `ratings` in a host YAML crashes with NPE. Additionally, duplicating this function in both loaders is a DRY violation; it should be extracted to a shared utility, with the null guard added at the same time.

### [decker/DeckerNavigationExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt) — line 154

**`logonToPltg` passes `operation = SystemOperation.LOGON_TO_LTG` to `performLogon` instead of a PLTG-specific constant.** If `SystemTestResolver` or the operation table has PLTG-specific entries, the wrong dice-pool modifiers or utility lookups are applied. Change to `SystemOperation.LOGON_TO_PLTG` (or the appropriate enum constant), unless the rules explicitly treat PLTG logon identically to LTG logon — in which case add a comment explaining that equivalence.

### [decker/DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt) — line 196

**`locateFile`, `locateSlave`, and `locateAccessNode` persist `state.copy(accumulatedSuccesses = …)` instead of the `newState` returned by `resolveInterrogation`.** If `resolveInterrogation` ever updates any `InterrogationState` field beyond `accumulatedSuccesses`, those updates are silently dropped each turn. Store `newState` directly.

### [game/Game.kt](src/main/kotlin/com/shadowrun/matrix/game/Game.kt) — line 12

**`inCombat: Boolean` constructor parameter is declared `val` but is never read anywhere in the class body.** It is dead code that misleads readers into thinking it influences behaviour. Remove it.

### [game/Game.kt](src/main/kotlin/com/shadowrun/matrix/game/Game.kt) — line 31

**The `?: break` after `maxByOrNull` is unreachable dead code.** The enclosing `while` already guarantees `states.any { it.currentInitiative > 0 }`, so the filtered list is never empty and `maxByOrNull` always returns a value. Remove the `?: break` to avoid misleading readers into thinking a null result is possible.

### [ic/IC.kt](src/main/kotlin/com/shadowrun/matrix/ic/IC.kt) — line 96

**`Scramble.action` always returns `ActionResult.NoTarget` regardless of whether a target is present.** Every other IC subclass calls `findTarget` and acts on it; returning `NoTarget` unconditionally looks like an unimplemented stub. If `Scramble` is intentionally passive, document it and return a distinct result (e.g. `ActionResult.NoOp`) rather than reusing the "no target found" sentinel.

### [Main.kt](src/main/kotlin/com/shadowrun/matrix/Main.kt) — lines 21, 24, 27

**Three `!!` dereferences on `getResourceAsStream(…)` return values.** If a resource file is absent from the classpath, the code throws `NullPointerException` with no indication of which resource was missing. Replace each with `requireNotNull(classLoader.getResourceAsStream("decks.yaml")) { "Resource not found: decks.yaml" }` (and likewise for the other two) to give an actionable message.

### [network/AlertTransitions.kt](src/main/kotlin/com/shadowrun/matrix/network/AlertTransitions.kt) — lines 17–27

**`applyAlertTransition` adds +2 to every subsystem rating unconditionally on each `PASSIVE_ALERT` call with no idempotency guard.** Calling it a second time for the same host silently inflates every rating by another +2. Consider checking `host.alertStatus != AlertStatus.PASSIVE_ALERT` before applying the increment, or document clearly that callers must not invoke this twice for the same alert transition.

### [network/Matrix.kt](src/main/kotlin/com/shadowrun/matrix/network/Matrix.kt) — line 6

**`getHost()` only searches `LTG.hosts` directly; it never descends into `LTG.pltgs` or `PLTG.hosts`.** Hosts attached to a PLTG are unreachable through this API. If the design intentionally omits PLTGs, add a comment or a dedicated `getPltgHost()` overload; otherwise extend the search to include PLTG-attached hosts.

### [network/SecuritySheaf.kt](src/main/kotlin/com/shadowrun/matrix/network/SecuritySheaf.kt) — line 17

**`SecuritySheaf` accepts any `List<TriggerStep>` with no validation that `tallyThreshold` values are unique or ordered.** Duplicate thresholds yield ambiguous security responses (which step fires?), and an unsorted list makes threshold matching error-prone. An `init` block asserting uniqueness and ascending order would catch misconfigured sheaves at construction time.

### [operations/MonitoredOperationHandle.kt](src/main/kotlin/com/shadowrun/matrix/operations/MonitoredOperationHandle.kt) — line 12

**`val target: Any` erases all type information for the monitored operation's target.** Callers must cast unsafely to use the value, and the compiler cannot verify correctness. A sealed class (e.g. `MonitoredTarget`) covering the concrete variants would restore type safety and make exhaustive `when` branches possible.

### [operations/OperationResult.kt](src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt) — line 67

**`LocateResult.Located.target: Any` has the same type-erasure problem as `MonitoredOperationHandle.target`.** The three locate operations (Locate File, Locate Slave, Locate Access Node) return different concrete target types; encoding them in a sealed class would eliminate unchecked casts at every call site.

### [test/integration/WebSocketServerIntegrationTest.kt](src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt) — lines 77, 98, 109, 123, 124

**Multiple `!!` dereferences on nullable JSON lookups without a prior `assertNotNull` guard.** `obj["availableActions"]!!`, `state1["availableActions"]!!`, `state2["availableActions"]!!`, `it.jsonObject["ltgName"]!!`, and `jackpoint.connectsToLtg!!` all throw NPE on failure with no test-friendly message. Replace with `assertNotNull(…)` before use to get a meaningful assertion failure.

### [test/integration/WebSocketServerIntegrationTest.kt](src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt) — lines 106, 118

**`Thread.join()` without an alive-check after the call.** `Thread.join(millis)` returns silently when the timeout expires; if the coroutine-on-thread hangs, the test still passes and the thread leaks into subsequent tests. After each `join`, add `assertFalse(thread.isAlive)` to turn a silent leak into an explicit failure.

### [test/integration/utility/IntegrationTestBase.kt](src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt) — line 118

**`maxByOrNull { it.currentInitiative }!!` relies on a non-local reasoning invariant.** The enclosing `while` guarantees the list is non-empty, but this is not obvious at the call site. Prefer `requireNotNull(states.filter { … }.maxByOrNull { … }) { "initiative list unexpectedly empty" }` to make the invariant explicit.

### [test/integration/utility/IntegrationTestBase.kt](src/test/kotlin/com/shadowrun/matrix/integration/utility/IntegrationTestBase.kt) — lines 127–128, 188–189

**`currentDecker().persona!!` inside test helpers `runCombatTurn` and `runCombatTurnForPhysicalDamage` crashes with NPE if the decker has no persona.** Use `requireNotNull(currentDecker().persona) { "decker persona must not be null in runCombatTurn" }` to surface a readable message pointing to the specific helper.

### [test/integration/utility/ScenarioBuilder.kt](src/test/kotlin/com/shadowrun/matrix/integration/utility/ScenarioBuilder.kt) — lines 69, 231, 257, 269, 284, 302, 315

**Several step lambdas perform unchecked casts without a prior `require` or `assertIs`.** `(currentDecker().currentLocation as MatrixLocation.OnLTG)` and multiple `as MatrixLocation.OnHost` casts throw `ClassCastException` with no indication of what was expected vs. what was found. Add `require(currentDecker().currentLocation is MatrixLocation.OnXXX) { "Expected OnXXX but was ${currentDecker().currentLocation}" }` before each cast.

### [test/server/FakeWebSocketSession.kt](src/test/kotlin/com/shadowrun/matrix/server/FakeWebSocketSession.kt) — line 38

**`nextText()` calls `_outgoing.receive()` with no timeout.** If the system under test never sends the expected frame (e.g. due to a bug), every test that calls `nextText()` hangs indefinitely. Wrap `receive()` in `withTimeout` at this site, or require callers to use `withTimeoutOrNull`.

### [test/server/WebSocketServerTest.kt](src/test/kotlin/com/shadowrun/matrix/server/WebSocketServerTest.kt) — lines 138, 173, 275

**`Thread.join(timeoutMs)` returns silently when the timeout expires, masking hangs.** If the background coroutine hangs, the test still passes and the thread leaks into subsequent tests. After each `join`, add `assertFalse(thread.isAlive)` (or `assertEquals(Thread.State.TERMINATED, thread.state)`) to turn a silent leak into an explicit failure.

---

## 🔵 Nit

### [combat/CombatResolver.kt](src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) — line 145

**`decker.blackIcPin!!.pinningIc.rating` uses `!!` immediately after `require(decker.isPinnedByBlackIc)`.** The invariant is guaranteed by the `require`, but `!!` yields a `KotlinNullPointerException` with no context. Prefer `val pin = requireNotNull(decker.blackIcPin) { "resolveJackOutWithPin: blackIcPin is null" }` then use `pin.pinningIc.rating`.

### [combat/CombatResolver.kt](src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) — line 379

**`resolveTrackLock` embeds `requireNotNull(…).evasion` directly inside the `diceRoller.roll()` call, creating an extremely long line.** The null-safety argument is difficult to audit inline. Extract `val persona = requireNotNull(targetDecker.persona) { … }` before the roll call to make the guard visible and the line readable.

### [combat/CombatResolver.kt](src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt) — line 471

**`stage` is declared `internal` rather than `private`, exposing an implementation detail to the entire module.** If test coverage is the motivation, a dedicated unit test on `resolveAttack` with known inputs covers it without widening visibility. If `internal` is intentional, add a KDoc comment explaining why.

### [common/SharedTypes.kt](src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt) — line 24

**`isCrashed` and `isDestroyed` are identical computed properties (`damage >= maxBoxes`).** A future change to one may be missed on the other. One should delegate to the other: `val isCrashed: Boolean get() = isDestroyed`.

### [config/GridLoader.kt](src/main/kotlin/com/shadowrun/matrix/config/GridLoader.kt) — line 74

**Data-class graph inconsistency in `buildRtg` and `buildLtg`.** `finalRtg` is copied to include `fixedLtgs` (line 76), but each `LTG` in `fixedLtgs` has `parentRtg` pointing to the pre-copy `finalRtg` (the version with `ltgsWithPltgs`, not `fixedLtgs`). Similarly, PLTGs' `parentLtg` references a copy without `fixedPltgs`. Navigation through `parentRtg.ltgs` will produce inconsistent results.

### [decker/Decker.kt](src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt) — line 55

**Fully-qualified references to `com.shadowrun.matrix.common.PersonaAttributeType.MASKING`** (line 55) and equivalent inline references in `DeckerNavigationExtensions.kt` lines 253–265 add noise and are inconsistent with how these types are used in `Persona.kt`. Add the missing imports.

### [decker/DeckerMemoryExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt) — line 27

**`Math.ceil(…)` uses the Java `java.lang.Math` API.** Kotlin idiomatic style prefers `kotlin.math.ceil(…)` (or `ceil(…)` with an import), consistent with usage elsewhere in the module (e.g. `Cyberdeck.kt`, `Decker.kt`).

### [decker/DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt) — lines 45, 54, 64–65

**Double null-guard on `persona` in `noticeIcon` and `noticeTriggeredIc`.** `check(persona != null)` throws if null, making the immediately following `requireNotNull(persona)` unreachable. Remove the redundant `requireNotNull` and use the smart-cast produced by `check`.

### [decker/DeckerOperationsExtensions.kt](src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt) — line 81

**Unnecessary explicit cast in `analyzeHost`.** `(currentLocation as MatrixLocation.OnHost).host` is written after `currentLocation is MatrixLocation.OnHost` has already been confirmed in the same `require`. Since `currentLocation` is a `val` on a data class, Kotlin's smart-cast applies — the explicit cast can be removed.

### [game/GameContext.kt](src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt) — line 33

**`resetDeckers(decker: Decker)` is a misleading name.** The function clears the list and adds a single entry; it does not restore a multi-decker state. `replaceWithSingleDecker` or `resetToSingleDecker` would communicate the actual behaviour.

### [Main.kt](src/main/kotlin/com/shadowrun/matrix/Main.kt) — line 45

**`DiceRoller()` is instantiated fresh on every iteration of the infinite game loop.** If `DiceRoller` is stateless, a single instance constructed once before the loop avoids repeated allocation with no change in behaviour.

### [operations/BufferedMessage.kt](src/main/kotlin/com/shadowrun/matrix/operations/BufferedMessage.kt) — line 18

**`deliverAtEndOfTurn: Boolean = true` is a field whose only legal value is `true`.** It cannot encode meaningful state, it misleads readers into thinking late delivery is optional, and `copy(deliverAtEndOfTurn = false)` silently produces an invalid object. Remove the field and encode the invariant in the KDoc or class name alone.

### [server/SessionRegistry.kt](src/main/kotlin/com/shadowrun/matrix/server/SessionRegistry.kt) — line 168

**`future!!.complete(cmd)` uses `!!` to assert non-null.** The invariant holds because `claimAction` returns `(null, errorKey)` on failure and `(future, null)` on success, but this contract is implicit and invisible at the call site. Replace with `requireNotNull(future) { "claimAction returned null future with null errorKey — invariant violated" }.complete(cmd)` to make the contract explicit and provide a meaningful message on violation.

### [server/WebSocketDeckerController.kt](src/main/kotlin/com/shadowrun/matrix/server/WebSocketDeckerController.kt) — line 303

**`val p = cmd.params` is a dead assignment in `dispatchCommsOp`.** `p` is never referenced in the function body; the `MAKE_COMCALL` and `TAP_COMCALL` branches do not use `p` or `cmd.params`. Remove the unused variable to eliminate the compiler warning and the false implication that params influence comms operations.

### [test/combat/CombatResolverTest.kt](src/test/kotlin/com/shadowrun/matrix/combat/CombatResolverTest.kt) — lines 284, 336, 356, 368, 382, 416

**Redundant explicit casts after `assertIs<…>`.** After `assertIs<ManeuverResult.Success>(result)` and similarly after `assertIs<AttackResult.Hit>`, Kotlin's contract on `assertIs` smart-casts `result` to the target type, making subsequent explicit casts like `(result as ManeuverResult.Success)` redundant. Remove the explicit casts and use the smart-cast directly.

### [test/integration/WebSocketServerIntegrationTest.kt](src/test/kotlin/com/shadowrun/matrix/integration/WebSocketServerIntegrationTest.kt) — line 156

**Positional constructor arguments in `DeckerStateDto(name, "not jacked in", null, false, 0, 10, 0, 10, 6, 4, emptyList())`.** Any reordering or insertion of a field in `DeckerStateDto` will silently pass wrong values. Use named arguments.

### [test/integration/utility/DeckerMock.kt](src/test/kotlin/com/shadowrun/matrix/integration/utility/DeckerMock.kt) — line 113

**Parameter `decks` in `fun load(decker: InputStream, decks: InputStream)` is immediately shadowed by `val decks = DeckCatalogLoader.load(decks)`.** The parameter is consumed correctly before the shadow takes effect, but the shadowing is confusing and may mislead future editors. Rename the local variable to `deckCatalog` or similar.

---

## Summary Table

| Severity | Count | Packages Affected |
|---|---|---|
| 🔴 Blocker | 3 | network |
| 🟠 Major | 12 | config, decker, game, network, operations, server, test/game |
| 🟡 Minor | 24 | combat, config, decker, game, ic, network, operations, test/integration, test/server |
| 🔵 Nit | 17 | combat, common, config, decker, game, operations, server, test/combat, test/integration |

---

## Clean Packages

The following files were reviewed and produced no findings:

`DeckerDisconnectedException.kt`, `AvailableActionDto.kt`, `DeckerStateDto.kt`, `MatrixObjectDto.kt`, `Messages.kt`, `ActionResult.kt`, `ActiveIcon.kt`, `ActiveIconState.kt`, `DeckerExtensions.kt`, `AttackParticipant.kt`, `AttackResult.kt`, `BlackIcPinState.kt`, `Combat.kt`, `CombatInitiative.kt`, `CombatModifiers.kt`, `CripplerResult.kt`, `DefenderParticipant.kt`, `IcDamageResult.kt`, `IcSuppressionState.kt`, `JackOutPinResult.kt`, `ManeuverParticipant.kt`, `ManeuverResult.kt`, `SimsenseOverloadResult.kt`, `SlowResult.kt`, `TarBabyResult.kt`, `TrackState.kt`, `ActiveMemory.kt`, `Cyberterminal.kt`, `DownloadDestination.kt`, `MedicResult.kt`, `MovementResult.kt`, `Persona.kt`, `DataFile.kt`, `Jackpoint.kt`, `MatrixLocation.kt`, `Node.kt`, `RemoteDevice.kt`, `SAN.kt`, `AvailableAction.kt`, `DownloadHandle.kt`, `InterrogationState.kt`, `MatrixIcon.kt`, `MatrixObject.kt`, `PointerChain.kt`, `SystemOperation.kt`, `SystemTestOutcome.kt`, `SystemTestResolver.kt`, `DeckCatalogEntry.kt`, `GridInitializer.kt`, `PersonaProgram.kt`, `Program.kt`, `Utility.kt`, `Enums.kt`, `Accessory.kt`, `DiceRoller.kt`, `TurnCoordinatorTest.kt`, `SessionRegistryTest.kt`, `DtoMappingTest.kt`, `CombatTest.kt`, `GameContextTest.kt`, `DiceRollerTest.kt`, `GridMock.kt`, `HostMock.kt`, `DeckerTest.kt`, `DeckerOperationsTest.kt`
