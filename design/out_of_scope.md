# Out of Scope

Features explicitly excluded from the current milestone. Unlike `todo.md` §4 (Deferred Features, which are deferred but may be done later), items here have been evaluated against the source book and are deliberately excluded pending a broader design decision or milestone change.

---

## 1. Grid security sheaf mechanics (D7C-3)

**What the source book requires (SR3 p. 211):**

RTGs, LTGs, and PLTGs have security sheafs with trigger steps identical to hosts. The rulebook uses "host/grid" throughout the Security Tally, Security Sheaves, and Trigger Steps sections:

> A security sheaf describes the security measures in place on a host **or grid** as well as how the host/grid reacts to intruders. … As a decker's security tally reaches each trigger step, the system activates one or more IC programs. Trigger steps also activate the various alert levels in a system. The security code of the **host/grid** determines the frequency of trigger steps in a system…

> When the tally reaches a level set by the gamemaster, it may trigger actions within the **host/grid**, ranging from the activation of black IC programs to nothing at all.

The HOST/GRID RESET section (SR3 p. 212) also applies equally to both: Blue resets in 2D6 min, Green/Orange/Red roll down at intervals, IC stays running until tally drops below its trigger step.

**What is currently in code:**

- `Grid` base class (`Grid.kt`) carries `securitySheaf: SecuritySheaf` and `alertStatus: AlertStatus` fields on every RTG, LTG, and PLTG.
- `SecuritySheaf` and `TriggerStep` data classes are fully defined.
- `GameContext.checkTriggers()` reads and evaluates only `host.securitySheaf`. Nothing evaluates grid sheafs.
- `GridLoader` parses no `security_sheaf` block from YAML. Only `HostLoader` loads sheaf data.
- No IC-spawn or alert-transition path exists for RTG/LTG/PLTG tally crossings.

**What is out of scope:**

- Loading `security_sheaf` entries from grid YAML files.
- Evaluating RTG/LTG/PLTG trigger steps when a decker's grid tally changes.
- Spawning IC programs triggered by grid tally crossings.
- Propagating `alertTransition` changes to grid `alertStatus` in `GameContext`.
- Host sheaf mechanics are **not** affected — they remain fully in scope and implemented.

**Why deferred:** Requires a `GameContext` architecture decision (mutable authoritative RTG/LTG/PLTG state analogous to the existing `host` field) before any of the above can be wired correctly. That decision is tracked in `todo.md` §3d.

---

## 2. Companion plug-pull with Black IC active (ICC-10)

**What the source book requires (SR3 / PRD ICC-10):**

> "If a companion at the jackpoint manually pulls the plug while Black IC is active, Black IC also gets one automatic final attack."

**What is currently in code:**

`combat.md`'s `resolveJackOutWithPin` models the decker's own Willpower test to jack out while pinned. The scenario where a third party physically severs the connection at the jackpoint is not designed anywhere.

**What is out of scope:**

Open questions that must be resolved before this can be implemented:
- Whether the decker's Willpower test is skipped (the companion acts unilaterally).
- Whether `resolveJackOutWithPin` is called or a new variant is needed.
- Who triggers the final-attack resolution — game engine, the caller, or a new method.

These belong in `combat.md` (Black IC Pin section) and `movement.md` (`jackOut` section).

**Why deferred:** The scenario requires design decisions about the call chain and Willpower test handling before any code can be written. Deferred pending a broader Black IC / jack-out design pass.

---

## 3. Security decker spawning (GC-2 / AL-02)

**What is in code and design:**

- `TriggerStep.securityDeckerCount: Int = 0` (`SecuritySheaf.kt`) — field is declared.
- `HostLoader` parses `security_decker_count` from YAML into that field (`HostLoader.kt`).
- `MitsuhamaPagoda.yaml` carries `security_decker_count: 1` on its Active Alert step.
- `AlertTransitions.kt` documents that the caller is responsible for spawning those NPC deckers — no spawn call is made.
- `design_core/ord.md` models the `Host → SecurityDecker Persona` (0:many, Active Alert) relationship.
- `design_core/operations.md` specifies `spawnSecurityDeckers(host, count, diceRoller)` and an AL-02 example row, but neither function nor call site exists.

**What is out of scope:**

The NPC decker AI (action selection, target priority, logon sequencing) is not designed. Without it, `securityDeckerCount` can never be consumed. No spawn path, no NPC controller, no `activeDeckers` list.

**Why deferred:** Requires a dedicated `npc_ai.md` design document covering how NPC deckers select their starting node, take turns, and handle passcodes before any code can be written.

---

## 4. `LOCATE_DECKER` operation — controller dispatch (MP-10)

**What is in code and design:**

- `locateDecker()` is fully implemented in `DeckerOperationsExtensions.kt` ([line 565](../src/main/kotlin/com/shadowrun/matrix/decker/DeckerOperationsExtensions.kt#L565)) and correct per the design.
- `LocateDeckerResult(decker, outcome, located, targetNotified)` is defined.
- Algorithm: Index Test → if passed, Sensor Test vs. `max(2, targetPersona.masking + sleazeRating)` → ≥ 1 success = located + target notified (MP-10).

**What is out of scope:**

- `LOCATE_DECKER` is excluded from `availableActions()` and never dispatched by `WebSocketDeckerController`.
- A dispatch case and target-selection mechanism (the caller must supply a `targetPersona`) are not wired.

**Why deferred:** Locate Decker is only meaningful once there is a second decker on the host to target — which requires the NPC security decker (§3 above) or a multi-player scenario, neither of which is designed yet.

---

## 5. `SWAP_MEMORY` operation — controller dispatch

**What is in code and design:**

- `swapUtility(toUnload, toLoad): LoadUtilityResult` is fully implemented in `DeckerMemoryExtensions.kt` and covered by unit and integration tests (CD-13).
- `SWAP_MEMORY` enum entry exists in `SystemOperation.kt` with `utility = null`.
- `design_core/cyberdeck_and_program_mechanics.md §3` specifies the algorithm (unload + load, one Simple Action).

**What is out of scope:**

- `SWAP_MEMORY` is excluded from `availableActions()` and never dispatched by `WebSocketDeckerController`.
- No target-selection mechanism exists for the caller to specify which utility to swap in/out.

**Why deferred:** The dispatch requires a design pass on how active-memory slots are presented to the player (which stored utility to load, what happens to running programs during the swap) before a controller action can be wired.

---

## 6. Utility upgrade and modification operations (CD-03)

**What the source book requires (SR3 p. 220, under "UTILITIES"):**

> Utility programs come in two formats, the original source code and copies. A decker must have the source code of a program to upgrade or modify the program. See Source and Object Code, p. 295.

SR3 p. 295 ("Source and Object Code") defines the upgrade and modification procedures that require a source-code copy; regular (object-code) copies may be run but never altered.

**What the PRD requires (CD-03):**

> A utility entry in the decker YAML may carry an optional `source_code: true` field (default: `false`). The application stores this flag on the Utility object. Upgrade and modification operations (out of scope for this milestone) are restricted to source-code copies; regular copies may be run but not altered.

**What is currently in code:**

- `Utility` carries `sourceCode: Boolean` (parsed from the YAML `source_code` field).
- The flag is stored and round-tripped; no operation reads it for any game-mechanical purpose.

**What is out of scope:**

- The upgrade operation (increases a utility's rating using its source-code copy).
- The modification operation (alters a utility's behaviour using its source-code copy).
- Any validation that blocks upgrade/modify on object-code copies at runtime.

**Why deferred:** SR3 p. 295 defines upgrade/modify procedures in the context of the Street Gear chapter (off-line decker maintenance), not the Matrix run itself. Implementing them correctly requires a design pass on the off-line decker-preparation workflow — cost, time, B/R skill tests — which is outside the scope of the current in-run Matrix simulator milestone.
