# Architecture Review — game_logic

## Summary

The game logic layer is cleanly decomposed into small, well-named packages, and the use of immutable data classes with Kotlin extension files keeps most individual files readable. However, two structural problems cut across the whole codebase. First, the `ActiveIcon` interface lives in the `game` (orchestration) package but is implemented by `Decker` and every `IC` subclass, forcing both domain packages to import the orchestration layer — a classic layer inversion. Second, as a direct consequence, `IC` subclasses and `Decker` reach back into `GameContext` to mutate shared game state, collapsing the boundary between domain logic and game-loop orchestration. Alongside these, `GameContext` has accumulated several distinct responsibilities (collection management, tally tracking, security-sheaf trigger evaluation, and cascading host-reference repair), and `DeckerOperationsExtensions.kt` at ~527 lines bundles fifteen unrelated operation categories into one file without a unifying concern beyond "things a Decker can do".

## Findings

### [HIGH] Domain packages depend upward on the orchestration layer via ActiveIcon
**File:** src/main/kotlin/com/shadowrun/matrix/game/ActiveIcon.kt:1  
**Issue:** `ActiveIcon` is declared in the `game` package, but `Decker` (package `decker`) and every `IC` subclass (package `ic`) implement it. This forces both domain packages to import `game.ActionResult`, `game.ActiveIcon`, and `game.GameContext`. The dependency arrow runs from domain up to orchestration — the reverse of a healthy layered architecture. Any change to `GameContext` or `ActionResult` can force recompilation of all domain entities.  
**Recommendation:** Move `ActiveIcon` (and `ActionResult`) to a neutral shared package such as `common` or a new `engine` package. The `game` package should depend on `decker` and `ic`, not the other way around.

### [HIGH] IC subclasses mutate GameContext directly — domain objects acting as game controllers
**File:** src/main/kotlin/com/shadowrun/matrix/ic/IC.kt:59  
**Issue:** Every concrete IC subclass (`Killer`, `Crippler`, `Probe`, `Blaster`, `Ripper`, `Sparky`, `TarBaby`, `TarPit`, `LethalBlackIC`, `NonLethalBlackIC`) calls `context.updateDecker()` and, in the case of `Probe`, `context.addToSecurityTally()` directly inside their `action()` implementations. Domain objects in the `ic` package are therefore writing to mutable game state, coupling IC type definitions to the GameContext API. The `ic` package also imports `game.asDefenderParticipant` — an extension that itself queries `MatrixLocation` — creating a three-layer coupling chain (`ic` → `game` → `decker` navigation internals).  
**Recommendation:** `action()` should return a value object describing the outcome (damage dealt, tally change, decker mutation) rather than writing to `GameContext` itself. The `Game` or `GameContext` layer should apply those outcomes, keeping IC types as pure rule objects.

### [MEDIUM] Decker entity embeds topology-traversal and action-catalog logic (SRP violation)
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:78  
**Issue:** `Decker` is a domain value object, but `visibleObjects()` (line 78) and `availableActions()` (line 114) contain substantial business logic: they traverse the full `MatrixLocation` sealed hierarchy, enumerate host subsystems, IC programs, data files, and remote devices, and build an action catalog referencing every `AvailableAction` subtype and `SystemOperation` variant. The private helpers `addHostSystemActions()` and `addGridSystemActions()` further expand this into ~50 lines of embedded action-catalog policy. These concerns belong in a query/service layer, not in the entity class.  
**Recommendation:** Extract `visibleObjects()` and `availableActions()` into a stateless `DeckerQueryService` or `MatrixPerceptionService` that takes a `Decker` and its location as inputs. `Decker` should carry only attributes and state; policy about what it can see or do should live elsewhere.

### [MEDIUM] GameContext accumulates multiple unrelated responsibilities
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:62  
**Issue:** `GameContext` is responsible for: (1) acting as a mutable collection registry for deckers and active IC, (2) tracking the canonical host reference and propagating host-object replacements into every decker's location field (`updateHost`, lines 51–60), (3) evaluating security-sheaf trigger steps and spawning IC / transitioning alert status (`checkTriggers`, lines 62–72), and (4) coordinating the multi-step "apply decker operation result" flow that reconciles tally state between the decker's local host copy and the canonical host (`applyDeckerOperationResult`, lines 74–83). Each of these is a separable concern.  
**Recommendation:** Extract trigger evaluation into a `SecuritySheafEvaluator`, and the host-reference consistency logic into a dedicated `HostStateManager` or handle it in `Game`. `GameContext` should be a thin container (registry + lookup) only.

### [MEDIUM] DeckerOperationsExtensions.kt is a 527-line catch-all file with no unifying responsibility
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:1  
**Issue:** The file contains fifteen distinct operation categories: matrix perception (`noticeIcon`, `noticeTriggeredIc`), five analyze variants, three decrypt variants, three interrogation/locate variants, four file/slave/comcall operations, utility management (`invokeMediac`), pointer-chain resolution, relocate icon, scramble destruct, and buffered messages. The only shared trait is that they all extend `Decker`. This makes the file a dumping ground and obscures conceptual groupings.  
**Recommendation:** Split into focused files along the operation-category lines already used in `SystemOperation.kt`: `DeckerPerceptionExtensions`, `DeckerAnalyzeExtensions`, `DeckerFileExtensions`, `DeckerSlaveExtensions`, etc. The existing `DeckerNavigationExtensions` and `DeckerMemoryExtensions` files demonstrate that this split is already partially done and works well.

### [MEDIUM] controlSlave manually duplicates SystemTestResolver logic instead of calling it
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt:303  
**Issue:** `controlSlave` constructs its own `deckerResult`/`hostResult` dice rolls and hand-builds a `SystemTestOutcome` (lines 303–307), bypassing `SystemTestResolver.resolve()`. All other operation functions delegate to the resolver. This means `controlSlave` silently skips the cyberterminal utility-rating reduction (`effectiveRating` logic in `SystemTestResolver`) and diverges from the shared resolution contract. If the resolver's behaviour is ever changed, `controlSlave` will not benefit.  
**Recommendation:** Refactor `controlSlave` to call `SystemTestResolver.resolve()` with `SystemOperation.CONTROL_SLAVE`. If the Spoof modifier needs a custom TN calculation, add a `customAccessRating` override parameter to `resolve()` rather than bypassing it entirely.

### [LOW] LocateResult.Located uses untyped Any, losing type safety across callers
**File:** src/main/kotlin/com/shadowrun/matrix/operations/OperationResult.kt:67  
**Issue:** `LocateResult.Located(val target: Any, ...)` erases the concrete type of the located object. Callers (`locateFile` returns a `DataFile`, `locateSlave` returns a `RemoteDevice`, `locateAccessNode` returns a `String`) must perform unchecked casts. Incorrect casts fail at runtime with no compile-time protection.  
**Recommendation:** Make `LocateResult` generic — `LocateResult<T>` with `Located<T>(val target: T, ...)` — or introduce separate sealed subtypes per operation (e.g., `FileLocateResult`, `SlaveLocateResult`). Either approach restores type safety without changing the call sites' logic.

### [LOW] Alert ordering logic is split between AlertStatus enum and GameContext
**File:** src/main/kotlin/com/shadowrun/matrix/game/GameContext.kt:68  
**Issue:** `checkTriggers` uses `transition.ordinal > host.alertStatus.ordinal` to enforce that alert levels can only increase. This ordering policy is encoded as raw ordinal comparison in `GameContext` rather than in `AlertStatus` itself. Any reordering of the enum entries would silently break this comparison with no compile error.  
**Recommendation:** Add a method `AlertStatus.canTransitionTo(next: AlertStatus): Boolean = next.ordinal > this.ordinal` (or `isEscalation`) on the enum and call it from `checkTriggers`. This centralises the ordering contract in the type.

### [LOW] Decker.withUpdatedTally embeds network-layer knowledge inside the domain entity
**File:** src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt:202  
**Issue:** `withUpdatedTally` pattern-matches over all four `MatrixLocation` variants and individually reconstructs the correct location subtype with the updated tally field. This makes the `Decker` entity aware of the internal structure of every grid and host type. If a new `MatrixLocation` variant is added, this method (and the similar `withAttribute`-style updates) must also be updated.  
**Recommendation:** Add a `withUpdatedTally(delta: Int): MatrixLocation` method to the `MatrixLocation` sealed class so tally mutation is encapsulated in the network layer. `Decker.withUpdatedTally` then delegates: `copy(currentLocation = currentLocation?.withUpdatedTally(delta))`.

### [INFO] logonToRtg initialises security tally as delta only, not additive — inconsistency with all other logon functions
**File:** src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt:104  
**Issue:** `logonToRtg` builds the new location as `rtg.copy(securityTally = hostTallyDelta)` — setting the tally to the raw host-test successes. Every other logon function adds the delta to the existing tally: e.g., `jackInToLtg` uses `ltg.securityTally + updatedTally`. Whether this is intentional (RTG always starts fresh) or a latent bug is not clear from the code, but the inconsistency is a maintenance risk.  
**Recommendation:** Document the intent explicitly with a comment, or align the formula with the other logon functions if the difference was unintentional. At minimum, rename the lambda parameter from `hostTallyDelta` to `initialTally` if zero-based initialisation is intentional.

### [INFO] Game.runCombatTurn initiative list can diverge from live context state mid-turn
**File:** src/main/kotlin/com/shadowrun/matrix/game/Game.kt:21  
**Issue:** `runCombatTurn` builds `states` once from `context.deckers` and `context.activeIc` at the top of the method, then iterates. If an IC's `action()` calls `context.removeIc()` (e.g., a crashed IC), or a decker is removed, the snapshot `states` list still contains the stale entry and will attempt to invoke `action()` on it in a later initiative pass. The returned `ActionResult` is discarded with no cross-check against the live context.  
**Recommendation:** Filter `states` against `context.activeIc` and `context.deckers` at the start of each initiative pass, or have IC actions signal "I am destroyed" via the return value so the initiative loop can drop them.

## No Issues Found In

- `src/main/kotlin/com/shadowrun/matrix/common/Enums.kt` — clean flat enum definitions with no logic leakage.
- `src/main/kotlin/com/shadowrun/matrix/common/SharedTypes.kt` — `SecurityRating`, `SubsystemRatings`, `ConditionMonitor` are well-scoped value types with cohesive helpers.
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt` — operation metadata enum is a clean data table with no behavioural code.
- `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt` — well-bounded stateless object; single responsibility, clear interface.
- `src/main/kotlin/com/shadowrun/matrix/operations/AvailableAction.kt` — thin sealed class, no coupling concerns.
- `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt` — constraint validation in `init` is appropriate and self-contained.
- `src/main/kotlin/com/shadowrun/matrix/decker/Persona.kt` — clean value type.
- `src/main/kotlin/com/shadowrun/matrix/decker/DeckerNavigationExtensions.kt` — well-structured, single-topic file (navigation/logon/logoff).
- `src/main/kotlin/com/shadowrun/matrix/decker/DeckerMemoryExtensions.kt` — focused utility-management concern.
- `src/main/kotlin/com/shadowrun/matrix/network/Host.kt`, `Grid.kt`, `Node.kt`, `SecuritySheaf.kt` — network model types are clean value objects with no game-logic coupling.
- `src/main/kotlin/com/shadowrun/matrix/programs/Program.kt` — minimal base class, appropriate.
- `src/main/kotlin/com/shadowrun/matrix/utility/DiceRoller.kt` — single responsibility, no domain coupling.
- `src/main/kotlin/com/shadowrun/matrix/game/Game.kt` — small and focused on turn sequencing (modulo the INFO finding above).
- `src/main/kotlin/com/shadowrun/matrix/game/ActiveIconState.kt`, `ActionResult.kt` — appropriately thin data types.
- `src/main/kotlin/com/shadowrun/matrix/combat/CombatResolver.kt` — stateless resolver with clear SRP; all methods take explicit inputs and return values without side effects.
