# Matrix of Shadowrun

## Overview

We implement an object oriented model and application for the Matrix of Shadowrun 3. The object model can be found in [ord.md](ord.md).

This document focuses on the various use cases.

## Use Cases

### Movement

#### Jacking In (Initial Entry)

- M-01: A decker using a telecom or illegal-telecom jackpoint may only perform **Logon to LTG** as the first operation; the persona appears on the LTG connected to that jackpoint.
- M-02: A decker using a workstation, console, or remote-device jackpoint may only perform **Logon to Host** as the first operation, and only to the host that controls that device; the persona appears inside that host at a location determined by jackpoint type:
  - Workstation jackpoint → persona appears at the I/O port node (Access subsystem).
  - Remote-device jackpoint → persona appears at the slave controller (Slave node).
  - Console jackpoint → persona appears at the CPU node (Control node).
- M-03: A decker using an illegal junction-box jackpoint may perform either **Logon to LTG** or **Logon to Host** as the first operation, depending on where the fiber-optic trunk is connected.
- M-04: A logon attempt requires a System Test (Computer Skill vs. Access Rating in a Success Contest); the host/grid simultaneously makes a Security Test (Security Value dice vs. the decker's Detection Factor). The decker succeeds when achieving at least as many successes as the host/grid.
- M-05: Each System Test result (host/grid successes) is added to the decker's **security tally** on that system, regardless of who won the contest.

#### Moving Between Grids

- M-06: From an **LTG**, a decker may:
  - Move to the parent RTG via **Logon to RTG**.
  - Access any host attached to the LTG (whose address is known) via **Logon to Host**.
  - Access a PLTG attached to the LTG (if its address is known) via **Logon to LTG**.
- M-07: From an **RTG**, a decker may:
  - Move to another RTG (long-distance routing) via **Logon to RTG**.
  - Enter any LTG attached to the RTG via **Logon to LTG**.
  - Perform a **Locate Access Node** operation to discover LTG codes and host addresses.
- M-08: From a **PLTG**, a decker may perform any system operation available on public RTGs and LTGs.
- M-08a: **ANALYZE_IC** is only available inside a host. IC programs are host-resident objects; this operation is not available from RTG, LTG, or PLTG contexts.

#### Security Tally Persistence Rules

- M-09: Switching between LTGs within the same RTG does **not** reset or separate the security tally; the single tally covers all LTGs of an RTG and the RTG itself.
- M-10: Moving to a **different RTG** starts a fresh security tally on that RTG; the old RTG's tally is left behind.
- M-11: When a decker moves from a public RTG/LTG onto a **PLTG**, the accumulated RTG security tally carries over to the PLTG ("security flags"). IC on the PLTG may trigger immediately on entry if the inherited tally reaches a trigger step.
- M-12: A failed Logon to LTG leaves the security tally in place on that LTG for a memory window (typically 1D3 × 5 minutes for public LTGs). Switching to a different jackpoint before retrying causes the LTG to start a fresh tally for the decker.

#### Moving Between Hosts

- M-13: In a **tiered-access** topology, a decker must pass through the first-tier host to reach any second-tier host. Moving from one second-tier host to another requires re-entering the first-tier host.
- M-14: In a **host-host access** topology, a decker must traverse the chain of linked hosts in order (e.g., B → C → D → E) to reach a deeper host; no shortcutting is possible.
- M-15: In a **private-grid (PLTG)** topology, once a decker is on the PLTG they may access any host connected to that grid directly.

#### Logging Off

- M-16: A decker may perform a **Graceful Logoff** (Complex Action, Access Test) at any time to disconnect cleanly. On success, all traces of the decker are cleared from the system's security and memory. Dump shock does **not** occur.
- M-17: A decker may **Jack Out** (Free Action) at any time unless currently being attacked by Black IC. Jacking out without a prior successful Graceful Logoff causes **dump shock**.
- M-18: A decker who is involuntarily disconnected (persona crashed, deck disabled, comlink severed) is **dumped** and also suffers dump shock.

### Data Creation

The initial data is stored in different configuration files in yaml format. The application should read this file and instantiate the corresponding entities.

#### Grid

The configuration file `grid.yaml` should contain data following these guidelines:

- All north american RTGs from the rules table are seeded. Their System Ratings (Security Code/Value + ACIFS) are taken verbatim from the rules.
- LTG ratings inherit from the parent RTG. Each RTG has 2–4 LTGs covering major regions (e.g. Seattle, Chicago, Los Angeles). LTG addressing follows the `[RTG]-[REGION]-[4-digit number]` convention (e.g. `UCAS-SEA-2206`).
- Each LTG contains at least one named host from the Shadowrun world. Hosts carry their own System Rating independent of the LTG.
- PLTGs (private grids) are separate from public LTGs. They are owned by megacorps or governments, may use Orange/Red security codes, and security tally accrued on the public RTG carries over on entry.

#### Host

There is a configuration file per host: ```<host_name>.yaml```. Every public host must reference an existing LTG (see above).

Each host configuration must include:

- **Topology type** — one of: `open-access` (connected directly to a grid, reachable by any decker on that LTG); `tiered` (only the first-tier host is on the grid; second-tier hosts require passing through it, and moving between second-tier hosts requires re-entering the first-tier host); `host-host` (hosts linked in a chain, e.g., B → C → D → E; a decker must traverse the chain in order with no shortcuts); `private-grid` (host lives behind a PLTG; once on the PLTG any connected host is directly accessible).
- **Offline flag** — `offline: true` marks a host that is physically isolated from the Matrix. Such a host cannot be reached remotely; a decker must find a jackpoint at the physical facility. Example from the rules: accessing the Saeder-Krupp research vault requires physically penetrating the facility.
- **Security sheaf** — an ordered list of trigger steps. Each trigger step is a security-tally threshold; when the decker's tally reaches or exceeds it, the system activates IC programs and/or transitions alert status. A host config may declare explicit trigger steps or rely on generated defaults based on its security code. Example sheaf from the rules:

  | Trigger Step | Event |
  | --- | --- |
  | 3 | Probe-5 |
  | 7 | Probe-7 |
  | 10 | Killer-8, Passive Alert |
  | 13 | Killer-10, Active Alert |

  Trigger step spacing by security code: Red = 2–4, Orange = 3–5, Green = 4–6, Blue = 5–7 (roll 1D6 ÷ 2 + modifier; each result adds to the previous step).

  **Multiple trigger steps:** If a single accumulation of security tally points causes the tally to reach or exceed two or more trigger steps at once, all events for every triggered step activate simultaneously.

- **Alert status** — starts at No Alert. Passive Alert (typically at the third or fourth trigger step) raises all Subsystem Ratings by +2. Active Alert (one or two trigger steps later) may also spawn corporate or law-enforcement security deckers.
- **Reset timing** — determined by security code. Blue hosts reset fully in 2D6 minutes. Green/Orange/Red hosts begin resetting after 3D6 minutes if no alert was triggered; if an alert fired, reduce the security tally by 1D6 every 5/10/15 minutes (Green/Orange/Red respectively). IC programs left running when the decker logged off remain active until the tally drops below the trigger step that activated them. If a new decker illegally logs on to the host before it finishes resetting, that decker's security tally begins at the level the tally had dropped to at the moment of intrusion.

Provide some examples in the Seattle LTG.

#### Decker

There is one configuration file per decker: `<decker_name>.yaml`. It contains all values of:

- **Decker** (physical character stats): Intelligence, Body, Willpower, Reaction, Computer Skill (with optional Decking specialization).
- **Cyberdeck**:
  - MPCP Rating — master OS; no single persona program may exceed MPCP; sum of all four persona programs ≤ MPCP × 3.
  - Hardening — reduces Power of Black IC damage; raises Gray IC Attack Test target numbers.
  - Active Memory (Mp) — limits total Mp of simultaneously running utilities.
  - Storage Memory (Mp) — must hold all utilities (active or not) plus any downloaded data.
  - I/O Speed (Mp per Combat Turn) — upload/download rate.
  - Response Increase (0–3 points; max = floor(MPCP ÷ 4)) — each point adds +2 Reaction and +1D6 Initiative to the persona.
- **Persona programs** (exactly 4, each with a numeric rating): Bod, Evasion, Masking, Sensors. Constraints: each rating ≤ MPCP; sum of all four ≤ MPCP × 3. Example from the rules: a Renraku Kraftwerk-8 with programs distributed equally yields MPCP-8/6/6/6/6; raising Bod to 8 and reducing Evasion and Sensor by 1 each gives MPCP-8/8/5/6/5.
- **Utilities** — each with a type and a rating. Program Mp size = Rating² × Multiplier. Total Mp of all utilities must fit within Storage Memory. Utility types by category:
  - *Operational* (reduce System Test target numbers): Analyze (×3), Browse (×1), Commlink (×1), Deception (×2), Decrypt (×1), Read/Write (×2), Relocate (×2), Scanner (×3), Spoof (×3).
  - *Special*: Sleaze (×3), Track (×8).
  - *Offensive*: Attack at Light/Medium/Serious/Deadly damage level (×2/3/4/5), Black Hammer (×20), Killjoy (×10), Slow (×4).
  - *Defensive*: Armor (×3), Cloak (×3), Lock-On (×3), Medic (×4).

  **Medic mechanics:** To use Medic, spend a Complex Action and roll Medic Rating dice. Target number by current icon damage: Light → TN 4; Moderate → TN 5; Serious → TN 6. Each success repairs 1 box on the icon's Condition Monitor. The Medic utility's `currentRating` decreases by 1 each time it is invoked, whether the attempt succeeds or not (see CD-20). The utility can be reloaded from storage via Swap Memory to restore its full rating.

The following persona values are **calculated by the application** and must not be stored in the config file:

- **Hacking Pool** = floor((Intelligence + MPCP) ÷ 3). Hacking Pool dice may be added to any test made in the Matrix — System Tests, Attack or Defense tests, maneuvers, or Attribute Tests. **Exception:** Hacking Pool dice may **not** be used in Body or Willpower Tests made to resist damage from gray or black IC attacking the decker's physical body. Only Karma Pool dice, cyberdeck-connected enhancements, or magic boosts to the decker's Body or Willpower apply in those cases.
- **Detection Factor** = ceil((Masking + Sleaze rating) ÷ 2); if no Sleaze program is loaded, Detection Factor = Masking ÷ 2. Example from the rules: HeadCrash (Computer-6, MPCP-8/6/6/6/6, Sleaze-5) has Detection Factor = ceil((6 + 5) ÷ 2) = 6.
- **Persona Reaction** = base Reaction + (Response Increase × 2).
- **Persona attributes** (Bod, Evasion, Masking, Sensor) are read directly from the four persona program ratings.

### Integration Tests

- A decker logs on to an LTG, switches to the RTG, moves to a different RTG and one of the LTGs. There, he logs on to a host. Afterwards, he logs of.
- A decker logs on to an LTG, tries to logon to an host, but fails.

#### Additional Information

- It should be possible to control, which test fails ad which test succeeds.

## Cyberdeck and Program Mechanics

### Validation Rules

- CD-01: Every utility's rating must not exceed the deck's MPCP Rating. The application rejects any configuration where a utility rating > MPCP, producing a descriptive load error naming the offending utility. This constraint mirrors the existing persona-program cap.
- CD-02: Response Increase obeys two simultaneous constraints: (a) ≤ floor(MPCP ÷ 4) and (b) ≤ 3 absolute hard cap. The following persona attributes are **calculated by the application** and must not appear in the decker YAML (extending the existing calculated-values list):

  | Field | Formula |
  | --- | --- |
  | Persona Reaction | base Reaction + (Response Increase × 2) |
  | Initiative dice | Persona Reaction + (1 + Response Increase) D6 |

  Example: Reaction 5, Response Increase 2 → Persona Reaction 9, initiative = 9 + 3D6.

- CD-03: A utility entry in the decker YAML may carry an optional `source_code: true` field (default: `false`). The application stores this flag on the Utility object. Upgrade and modification operations (out of scope for this milestone) are restricted to source-code copies; regular copies may be run but not altered.

### Cyberdeck Initialization

- CD-04: The four persona programs (Bod, Evasion, Masking, Sensors) are firmware-resident. They are active from the moment the decker jacks in, require no upload countdown, and do **not** consume Active Memory. No persona-program entry appears in the runtime active-utilities list.
- CD-05: A utility entry in the decker YAML may carry `active: true` to mark it as pre-loaded at the start of a run. The loader validates that the total Mp of all `active: true` utilities fits within Active Memory; violation is a configuration error. Pre-loaded utilities are immediately usable at jack-in with no upload delay.
- CD-06: The initialization sequence loads each `active: true` utility into active memory with `currentRating = storedRating` and `turnsRemaining = 0` (fully uploaded).

### Active Memory Management

- CD-07: **Load Utility** is a Simple Action; no System Test is required. Preconditions: the utility is in storage memory, not already loaded in active memory, and remaining active memory capacity ≥ utility Mp size. On success the utility enters the pending-upload state (see CD-10).
- CD-08: If remaining active memory < utility Mp size, the Load Utility action is rejected before any action economy is spent. The application reports the shortfall in Mp. The decker must unload one or more utilities before retrying.
- CD-09: **Unload Utility** is a Free Action; no System Test is required. The utility reverts to stored state, retaining its `currentRating`. Active memory is freed immediately.
- CD-10: **Upload Time.** After entering the pending-upload state a utility is not yet usable. Upload time (in whole Combat Turns, rounded up) = ⌈utility Mp size ÷ cyberdeck I/O speed⌉. The utility becomes fully active only after that many Combat Turns have elapsed.
- CD-11: **Upload Progress Tracking.** The application tracks each in-flight upload as `{utility, turnsRemaining}`, initialized per CD-10. At the start of each Combat Turn all `turnsRemaining` counters decrement by 1. When a counter reaches 0 the utility transitions from pending to active. The Mp of a pending utility counts against Active Memory from the moment the load action is accepted.
- CD-12: A utility in the pending-upload state provides **no game-mechanical effect**: it does not reduce target numbers, does not contribute to Detection Factor, and is not considered "loaded in active memory" for any rule. It is visible in the active-memory list as uploading.
- CD-13: **Swap Memory** sequence: Unload (Free Action, absorbed into the swap) → Load (Simple Action, this is the action cost). The application frees the old utility's capacity before validating the new load.

### Operational Utility Effects on System Tests

- CD-14: **General rule.** When a System Test is resolved for a named operation, if the associated utility (per CD-15) is fully active in active memory at that moment, subtract its `currentRating` from the base target number. The target number floor is **2**; no reduction may bring the effective target number below 2.
- CD-15: **Utility-to-operation mapping.** The table below is the authoritative mapping. Each operation has at most one associated utility type; only that utility type reduces the TN.

  | Utility | Operations whose TN is reduced |
  | --- | --- |
  | Analyze | Analyze Host, Analyze IC, Analyze Icon, Analyze Security, Analyze Subsystem, Locate IC |
  | Browse | Locate Access Node, Locate File, Locate Slave |
  | Commlink | Make Comcall, Tap Comcall |
  | Deception | Logon to Host, Logon to LTG, Logon to RTG, Graceful Logoff, Null Operation |
  | Decrypt | Decrypt Access, Decrypt File, Decrypt Slave |
  | Read/Write | Download Data, Edit File, Upload Data |
  | Relocate | Relocate Icon |
  | Scanner | Locate Decker |
  | Spoof | Control Slave, Edit Slave, Monitor Slave |

- CD-16: Add **Relocate Icon** to the set of named system operations: Simple Action, Control Test, Standard category. The Relocate utility reduces its TN per CD-14.

### Passive Program Behavior

- CD-17: Sleaze is a passive program. Once fully uploaded into active memory (not pending), it contributes automatically to the Detection Factor for every subsequent System Test during that run. No explicit activation is required.
- CD-18: The Detection Factor is recalculated at the moment each System Test is resolved, not cached at jack-in. If a Sleaze utility is fully active: DF = ⌈(Masking + Sleaze.currentRating) ÷ 2⌉; otherwise DF = ⌈Masking ÷ 2⌉. Loading or unloading Sleaze mid-run changes the Detection Factor for all tests resolved after that action.
- CD-18a: `effectiveDetectionFactor` — the Detection Factor used by the host in System Tests — equals `max(2, detectionFactor - suppressionDfPenalty)`. The floor of 2 is the standard SR3 target number minimum.

### Utility Degradation

- CD-19: **Armor degradation.** Each time an Armor utility fails to fully absorb incoming damage — meaning damage bleeds through to the persona's condition monitor — the Armor utility's `currentRating` decreases by 1. The stored copy retains its original rating; only the in-memory instance degrades.
- CD-20: **Medic degradation.** Each time the Medic utility is invoked, its `currentRating` decreases by 1, regardless of whether the repair attempt succeeded or failed.
- CD-21: **Current vs. stored rating.** Every active utility instance carries two distinct values: `storedRating` (immutable at runtime, from the YAML) and `currentRating` (starts equal; decrements on degradation per CD-19/CD-20). All game-mechanical effects use `currentRating`.
- CD-22: **Zero-rating auto-unload.** When a utility's `currentRating` reaches 0 it immediately ceases to provide any effect. The application auto-unloads it from active memory, marks it depleted in the storage inventory, and logs the event. A depleted utility cannot be re-loaded.
- CD-23: **Restoring a degraded utility.** To restore a depleted or degraded utility, the decker loads a fresh instance from the stored copy via the normal Load Utility procedure (CD-07/CD-10); the fresh instance starts with `currentRating = storedRating`. If the only copy is already in active memory in a degraded state, no fresh instance is available and the utility is lost for the remainder of the run.

### Cyberdeck Catalog (Data Seeding)

- CD-24: A `decks.yaml` configuration file is loaded at application startup, before any decker YAML is parsed. Each entry specifies: `model` (unique name string), `mpcp`, `hardening`, `active_memory` (Mp), `storage_memory` (Mp), `io_speed` (Mp per Combat Turn), and `cost_nuyen`. Response Increase is a decker-configuration value and must **not** appear in `decks.yaml`.
- CD-25: The following deck models must be seeded in `decks.yaml` with hardware values taken verbatim from the SR3 equipment tables:

  | Model | MPCP | Hardening | Active Mem (Mp) | Storage Mem (Mp) | I/O (Mp/Turn) | Cost (¥) |
  | --- | --- | --- | --- | --- | --- | --- |
  | Allegiance Sigma | 3 | 1 | 200 | 500 | 100 | 14,000 |
  | Sony CTY-360-D | 5 | 3 | 300 | 600 | 200 | 70,000 |
  | Novatech Hyperdeck-6 | 6 | 4 | 500 | 1,000 | 240 | 125,000 |
  | CMT Avatar | 7 | 4 | 700 | 1,400 | 300 | 250,000 |
  | Renraku Kraftwerk-8 | 8 | 4 | 1,000 | 2,000 | 360 | 400,000 |
  | Transys Highlander | 9 | 4 | 1,500 | 2,500 | 400 | 600,000 |
  | Novatech Slimcase-10 | 10 | 5 | 2,000 | 2,500 | 480 | 960,000 |
  | Fairlight Excalibur | 12 | 6 | 3,000 | 5,000 | 600 | 1,500,000 |

- CD-26: When a decker YAML specifies `model: <name>` and the named model exists in `decks.yaml`, the loader uses the catalog's hardware values as defaults for all deck hardware fields. Any field explicitly set in the decker YAML overrides the corresponding catalog value. If the model name is not found, the loader emits a warning and uses all inline values.

### Non functional Requirements

- Every public method of the decker, i.e. every action that is triggered from the user, should be logged at the start (describing the intention) and at the end (describing the outcome incl. success or failure)
- Every Test (set of dice rolls of both parties) should be logged

## Cyberterminals

Cyberterminals are legal Matrix access devices used by ordinary corporate workers. Deckers call them "tortoises" for their lack of speed and finesse.

- CT-01: A cyberterminal functions like a cyberdeck but has an MPCP cap of **4**; no cyberterminal may have an MPCP higher than 4.
- CT-02: Cyberterminals **cannot** be fitted with Response Increase.
- CT-03: All utility program ratings run on a cyberterminal are **reduced by 1** to reflect the lack of fine control.
- CT-04: Cyberterminal users **cannot be hurt by black IC or dump shock**. They are protected from biofeedback side-effects the same way hitcher-jack users are.
- CT-05: A cyberterminal costs approximately **10% of the price of an equivalent cyberdeck**.

## Cyberdeck Accessories

- ACC-01: **Off-line storage** — external storage that expands the deck's effective storage capacity beyond the on-board Storage Memory.
- ACC-02: **Vid-screen** — allows bystanders to observe the decker's Matrix activity from the outside ("shoulder-surf") without being jacked in.
- ACC-03: **Hitcher jacks** (electrode net or datajack feed) — allow a second person to jack in and experience the decker's icon view directly. Hitchers:
  - Cannot manipulate or affect the decker's persona in any way; they are purely observers.
  - Are protected from black IC biofeedback in the same way as cyberterminal users.
  - Cannot control the decker's movements or perspective.

## Matrix Perception

### Noticing New Icons

- MP-01: Whenever a new icon (decker, IC, or other program) **enters the area currently occupied by the decker**, the decker may make a **free Sensor Test** (no utilities allowed) to become aware of the new icon.
- MP-02: Target number for the Sensor Test:
  - If the icon is a **decker**: target = that decker's Masking Rating + Sleaze utility rating (if any).
  - If the icon is **IC or another program**: target = the icon's rating.
- MP-03: Success thresholds:
  - **1 success** — the decker is aware of the icon's presence (location known; type unknown unless further analysis is performed).
  - **2 successes** — (IC/program only) the decker also learns the **type** of IC/program.
  - **3 successes** — (IC/program only) the decker also learns the **rating**.
- MP-04: Once located, an icon remains "visible" unless it performs a combat maneuver to escape detection.
- MP-05: If the Sensor Test fails, the decker is **unaware** of the icon's presence until the icon chooses to reveal itself or attacks the decker.
- MP-06: If a decker **suspects** the presence of another icon, she may perform a **Locate Decker** or **Locate IC** operation to verify that suspicion.
- MP-09: Friendly deckers who wish to make their presences known to each other may do so automatically, without requiring a Sensor Test.
- MP-10: When a Locate Decker operation succeeds, the targeted decker is **automatically informed** that their location has been traced. The target does not learn who performed the operation or where the attacker is.

### Noticing Triggered Reactive IC

- MP-07: Reactive IC does not reveal itself by attacking. Whenever a decker triggers reactive IC, the GM secretly makes a **Sensor Test** (using the decker's Sensor Rating) against a target number equal to the IC's Rating:
  - **1 success** — the decker is informed that her actions triggered IC (but not which type or rating).
  - **2 successes** — the decker also learns the **type** of IC triggered.
  - **3+ successes** — the decker also learns the IC's **rating and location**.
- MP-08: This Sensor Test is made only once, at the moment the IC becomes active.

## System Operation Mechanics

### Non-Combat Actions Per Turn

- SO-01: Outside of cybercombat, deckers do **not** roll for Initiative. Instead, divide the decker's **Persona Reaction** (augmented by Response Increase) by 10, rounding up. The result is the number of actions the decker may perform during each 3-second game turn.
- SO-02: Add **+1 action** for every Initiative die the decker receives in the Matrix beyond the base 1D6 (i.e., each point of Response Increase adds +1 action, since each point grants +1D6).

  Example: Reaction 5, Response Increase 2 → Persona Reaction 9 → ⌈9 ÷ 10⌉ = 1 base action + 2 extra actions = 3 actions per turn.

### Distributed Databases

- SO-03: Data on a host may be stored only as a **pointer** to a file on another connected host. When a decker accesses such a pointer, she obtains only the address of where the actual data resides and must navigate to that host to access the real file.
- SO-04: Roll **1D6** to determine the number of pointer-chain links in a given chain of files. The decker must follow each link through successive hosts, performing the appropriate logon and locate operations at each step.

### Operation Categories

Every system operation belongs to one of three categories:

#### Interrogation Operations

- SO-05: Interrogation operations involve a "dialogue" with the system to locate specific data. The decker may need to repeat the operation more than once.
- SO-06: Keep a running total of the decker's **net successes** across all attempts at the same interrogation. When the total reaches **5 or more**, the decker has located the objective. The GM may independently assign a different success threshold or reveal data incrementally at specific totals.
- SO-07: **Query precision modifiers** to the target number:
  - Vague or general query: **+1** TN modifier.
  - Extremely vague query: **+2** TN modifier.
  - Well-phrased, insightful, or very relevant query: **–1** or **–2** TN modifier.
- SO-08: If the host **does not contain** the queried information, the GM reveals this after the decker achieves **3 or more successes**.
- SO-09: A successful interrogation may yield only a pointer to a file on another host (see Distributed Databases SO-03/SO-04).

The following operations are interrogation operations: **Locate Access Node**, **Locate File**, **Locate Slave**.

#### Ongoing Operations

- SO-10: Ongoing operations (uploads, downloads, Swap Memory) begin with a successful System Test and then run automatically without further direction from the decker.
- SO-11: Time is measured in seconds; divide by 3 (round up) to convert to Combat Turns.
- SO-12: If an ongoing operation is terminated before completion, the partial data transfer produces a **corrupted, worthless file copy** (unless the GM rules the partial data is usable for story purposes).

The following are ongoing operations: **Download Data**, **Swap Memory**, **Upload Data**.

#### Monitored Operations

- SO-13: After the initial System Test to start a monitored operation, the decker must spend a **Free Action each Initiative Pass** to maintain it. Missing even one Free Action causes the operation to **abort**; the decker must repeat the System Test to restart.
- SO-14: Aborting a monitored operation may have **irreversible real-world consequences** (e.g., aborting an Edit Slave that was hiding the team from security cameras).

The following are monitored operations: **Control Slave**, **Edit Slave**, **Make Comcall**, **Monitor Slave**, **Tap Comcall**.

### Individual System Operations

Each operation entry: **Test** (subsystem), **Utility** (reduces TN), **Action type**, and description.

| Operation | Test | Utility | Action | Notes |
| --- | --- | --- | --- | --- |
| Analyze Host | Control | Analyze | Complex | Each net success reveals one piece of info. **Decker chooses** which piece each success reveals: Security Rating or any one subsystem rating not yet revealed. 7+ successes reveals all. Decker must be on the host. |
| Analyze IC | Control | Analyze | Free | Identifies type and rating of a located IC program, plus any options/defenses. |
| Analyze Icon | Control | Analyze | Free | Scans any icon; identifies general type. Decker may subtract Sensor Rating + Analyze rating from TN, but TN may not drop below 2. |
| Analyze Security | Control | Analyze | Simple | Returns current Security Rating, decker's current security tally (including points from this test), and alert status. |
| Analyze Subsystem | Targeted Subsystem | Analyze | Simple | Identifies anomalies in a subsystem, such as scramble IC or other defenses. |
| Control Slave | Slave | Spoof | Complex | Takes control of a remote device. For manufacturing/scientific processes, use average of Computer Skill + applicable B/R or Knowledge Skill. Monitored operation. |
| Decrypt Access | Access | Decrypt | Simple | Defeats scramble IC on a SAN; required before Logon to Host on a scrambled SAN. |
| Decrypt File | Files | Decrypt | Simple | Defeats scramble IC on a file; required before downloading a scrambled file. |
| Decrypt Slave | Slave | Decrypt | Simple | Defeats scramble IC on a Slave subsystem; required before Slave Tests on a scrambled subsystem. |
| Download Data | Files | Read/Write | Simple | Copies file from host to deck at I/O speed (Mp/Combat Turn). Ongoing operation. Incomplete download = corrupted file. |
| Edit File | Files | Read/Write | Simple | Creates, changes, or erases a datafile. Small changes (≈1 line) may be made directly. Larger changes require prior offline preparation and upload. After editing, decker may make a Control Test (TN reduced by Read/Write rating) to authenticate headers; failure risks host detection via Masking(Files) Test (successes = hours before the host notices the tampering). A subsequent Files Test by another party can detect tampering; if headers were authenticated, the checker must exceed the original tamperer's authentication successes to detect signs of tampering. |
| Edit Slave | Slave | Spoof | Complex | Modifies data sent to/from a remote device (e.g., fake camera feeds). Monitored operation. |
| Graceful Logoff | Access | Deception | Complex | Disconnects cleanly; no dump shock. On success, clears all traces from host security/memory. Track utility in location cycle adds its rating to TN. |
| Invoke Medic | Control | Medic | Complex | Repairs the decker's icon Condition Monitor. Not a System Test — no host roll. Roll Medic Rating dice; TN by current icon damage: Light → 4, Moderate → 5, Serious → 6. Each success repairs 1 box. Medic `currentRating` decreases by 1 per invocation regardless of outcome. Requires Medic utility in active memory. |
| Locate Access Node | Index | Browse | Complex | Finds LTG codes, host addresses, and commcodes for regular telecom calls (directory assistance). Interrogation operation. TN modifier: vague query +1, specific −1. Once a decker has located an LTG code or host address, she need not repeat this operation in future (unless the owner changes the address). |
| Locate Decker | Index | Scanner | Complex | Two-step: System Test then open-ended Sensor Test. Locates deckers whose Masking ≤ Sensor Test result (add target's Sleaze to their Masking). Sensor TN minimum is 2. |
| Locate File | Index | Browse | Complex | Finds specific datafiles. Interrogation operation. Decker must have a specific search goal. |
| Locate IC | Index | Analyze | Complex | Like Locate Decker but for IC; auto-locates on System Test success (no Sensor Test needed). |
| Locate Slave | Index | Browse | Complex | Like Locate File but for remote devices. Requires only **3 successes** (not 5) to locate a slave. Interrogation operation. |
| Logon to Host | Access | Deception | Complex | Standard System Test. Decker learns Access Rating on first attempt. Security tally starts accumulating on this test. |
| Logon to LTG | Access | Deception | Complex | System Test vs. LTG Access Rating. Failed attempt leaves tally on LTG for ~1D3×5 minutes; switching jackpoints starts fresh tally. |
| Logon to RTG | Access | Deception | Complex | System Test vs. RTG Access Rating. Required to move between LTGs or between RTGs. |
| Make Comcall | Files | Commlink | Complex | Places commcode calls; links multiple RTG calls into a secure conference. Each call linked requires a separate System Test. Licensed (corporate) deckers with a valid RTG passcode may skip all System Tests. The decker can detect taps or tracers on commlines with an Opposed Sensor vs. Device Rating Test; may neutralize them with Opposed Evasion vs. Device Rating. Dumping a participant or jumping into a tapped comcall each require a Files Test. Monitored operation. |
| Monitor Slave | Slave | Spoof | Simple | Reads data from a remote device (audio, video, sensor readouts). Monitored operation. |
| Null Operation | Control | Deception | Complex | Required when decker is inactive. GM applies TN modifier to host's Security Value: <10 s base; 10 s–1 min +1; 1–60 min +2; 1–12 hr +4; +1 per additional 12 hr. |
| Relocate Icon | Control | Relocate | Simple | Used to evade a Track utility. Decker makes Computer Test (TN = opponent's Sensor − Relocate rating); tracker makes MPCP Test vs. Relocate rating. Relocating decker wins → track fails completely. |
| Swap Memory | None | None | Simple | Loads a utility from storage into active memory. Free Action to unload first if needed. Upload countdown begins immediately (see CD-10). No System Test. |
| Tap Comcall | Special | Commlink | Complex | Locates active commcodes (Index Test), traces calls (Control Test), taps and records (Files Test). Scrambled lines require Opposed Computer vs. Device Rating decrypt test. **Dataline scanner mechanics:** if the target phone has one or more dataline scanners (Rating 1–10), the decker must make an Opposed Computer vs. scanner Device Rating test (Commlink reduces TN). When multiple scanners are present, use only the highest rating. The decker needs at least 1 success on this test; failure means the scanner detects the tap. These Tap Comcall tests do **not** affect the RTG security tally. Once a commcode has been tapped, the decker does not need a new Index Test to detect future activity on that same commcode; new trace and tap tests are still required for each subsequent call. Monitored operation. |
| Upload Data | Files | Read/Write | Simple | Transmits data from deck storage to the Matrix at I/O speed. Does not consume active memory. For modifying existing host files, an Edit File operation is required afterward. **Cannot** be used to upload utility programs — use Swap Memory for that. Ongoing operation. |

## Cybercombat

### Combat Sequence and Timing

- CC-01: Combat turns are 3 seconds. Within an Initiative Pass, resolve actions in order: astral → Matrix → physical.
- CC-02: Reactive IC programs that perform tasks at the end of a Combat Turn act **after** all deckers have performed their allotted actions for that turn.
- CC-03: A Delayed Action waiting for a physical-world event resolves alongside physical actions, even if the decker had a higher Initiative slot.
- CC-04: Direct meatworld communication (voice, datascreen) during a Combat Turn pins the decker's actions to the physical segment. Hitcher electrodes and intra-Matrix comms are exempt.

### Initiative

- CC-05: Decker Initiative = Persona Reaction + 1D6 + (Response Increase × 1D6). Physical enhancements (wired reflexes, etc.) do **not** affect Matrix Initiative.
- CC-06: Direct meatworld communication reduces a decker's Initiative by –1D6 until the link is dropped (hitcher exempt).
- CC-07: IC Initiative by host Security Code: Blue = 1D6 + IC Rating; Green = 2D6 + IC Rating; Orange = 3D6 + IC Rating; Red = 4D6 + IC Rating.
- CC-08: IC triggered mid-turn loses 10 Initiative per completed Initiative Pass. It acts on its next Initiative Pass.

### Actions per Combat Phase

- CC-09: Per Combat Phase: one Free Action AND either two Simple Actions OR one Complex Action.
- CC-10: **Free Actions** in combat: Analyze IC, Analyze Icon, Delay Action, Jack Out (if not pinned by Black IC), Speak a Word or buffer a message (up to 100 words, delivered to a hitcher/datascreen-linked character at end of Combat Turn), Terminate Download/Upload, Unload Program, Unsuppress IC.
- CC-11: **Simple Actions** in combat: Attack, Combat Maneuver, and all Simple system operations.
- CC-12: **Complex Actions** in combat: Jack Out while pinned by Black IC (Willpower test required), and all Complex system operations.

### Initiating Combat

- CC-13: A decker may attack any visible or located icon. Any icon that attacks automatically becomes visible unless it immediately succeeds at an Evade Detection maneuver.
- CC-14: Proactive IC initiates combat with any decker whose security tally triggers it; it continues attacking until the decker logs off or evades detection.

### Combat Maneuvers

- CC-15: All combat maneuvers are Simple Actions. Opposed Test: maneuvering icon's Evasion vs. opposing icon's Sensor Rating. IC substitutes the host's Security Value for both Evasion and Sensor.
- CC-16: Cloak reduces the TN for the maneuvering icon by its rating; Lock-On reduces the TN for the opposing icon by its rating. Hacking Pool may be added to these tests.
- CC-17: Net successes = maneuvering icon's successes minus opposing icon's successes. Equal or more opposing successes = maneuver fails.
- CC-18: **Evade Detection** — on success, the opposing icon loses track of the evading icon for a number of Combat Turns equal to net successes. This evasion period is shortened by 1 turn for each security tally point added while evading. IC reappears ready for Initiative at the end of the last evasion turn. The decker must use Locate IC / Locate Decker to re-detect.
- CC-19: **Parry Attack** — on success, all attacks against the maneuvering icon have their TN raised by net successes until the opposing icon's next attack. The bonus is retained if the opposing icon performs a Position Attack, but is lost if either icon performs a successful Evade Detection.
- CC-20: **Position Attack** — on success, the maneuvering icon may reduce the TN of its next attack by net successes OR increase the Power of its next attack by net successes. Bonus lasts until the next attack. If the opposing icon wins the contest, it receives the bonus instead.

### Crashing IC

- CC-21: When a decker crashes (destroys) an IC program in cybercombat, the rating of the crashed IC is immediately added to the decker's security tally. This reflects the system detecting that one of its defenses was eliminated.
- CC-22: **IC Suppression.** A decker may suppress a crashed IC program to avoid the security tally increase. The decker must declare suppression at the moment the IC is crashed. Rules:
  - Suppressing prevents the tally increase that would otherwise occur from crashing the IC.
  - For each IC program being suppressed, the decker's Detection Factor is reduced by 1. This reduction lasts as long as the decker remains in the system.
  - A decker may unsuppress (release) a suppressed IC at any time as a Free Action (CC-09). Unsuppressing restores 1 point of Detection Factor but immediately raises the security tally by the crashed IC's rating.
  - A decker cannot suppress IC in a system they have already left.

### Resolving Attacks

- CC-23: Attack is a Simple Action. The attacker makes an Attack Test using the offensive utility's rating (Hacking Pool may be added). IC programs attack using the host's Security Value as the dice pool (not the IC rating); the IC rating serves as the weapon (Power/Damage Level).
- CC-24: Target number for the Attack Test by target status and host Security Code:

  | Security Code | vs. Intruding icon | vs. Legitimate icon |
  | --- | --- | --- |
  | Blue | 6 | 3 |
  | Green | 5 | 4 |
  | Orange | 4 | 5 |
  | Red | 3 | 6 |

  An icon is **Legitimate** if it logged on with a valid passcode; all others are **Intruding**.

- CC-25: A decker who exploits Legitimate status in combat against the host's own security programs has that passcode devalidated at logoff/jackout (cover blown). Using the passcode against intruding deckers does not blow cover.
- CC-26: Record the number of attack successes — they determine Damage Level staging and trigger special per-IC effects.

### Icon Damage

- CC-27: Damage Code — Power = program/IC rating. Damage Level for IC by host Security Code: Blue/Green = Moderate; Orange/Red = Serious. Attack utility Damage Level is fixed at creation (Light / Moderate / Serious / Deadly).
- CC-28: **Damage Resistance Test**: target icon rolls Bod Rating dice vs. TN = Power. For IC taking damage, the GM rolls host Security Value dice. The Armor utility reduces Power before this test.
- CC-29: **Staging** — for every 2 net attacker successes stage the Damage Level up by 1; for every 2 net defender successes stage it down by 1.
- CC-30: **Condition Monitor** — icons use a single 10-box physical damage track (no Stun track). Standard SR3 TN modifiers apply for filled boxes. When all 10 boxes are filled the icon crashes; if the icon is a persona the decker is dumped.

### Simsense Overload

- CC-31: When a decker's icon takes damage from **white or gray IC**, the decker makes a Willpower Test to avoid 1 box of Stun damage to the Mental Condition Monitor. TNs: Light damage → TN 2; Moderate → TN 3; Serious → TN 5. Deadly damage auto-crashes the icon and triggers dump shock (no Willpower test for overload). Simsense overload does **not** apply to Black IC damage.

### Dump Shock Resolution

- CC-32: When dump shock occurs (M-17 / M-18), the decker makes a Body Damage Resistance Test vs. TN = Security Value of the last active host/grid. Damage Level by Security Code: Blue = Light; Green = Moderate; Orange = Serious; Red = Deadly. This is Stun damage applied to the Mental Condition Monitor.

### Track Utility in Combat

- CC-33: After each successful Attack Test against a target decker, the target makes an Evasion (Track Rating) Test. If the target achieves fewer successes than the attacker, the Track utility locks onto the target's datatrail. Location cycle duration (in full Combat Turns) = 10 ÷ net attacker successes (round up). The target may escape by logging off or jacking out; Graceful Logoff TN is raised by the Track Rating while the location cycle is running. Crashing the attacker's persona stops all its running programs, including Track.

## Alert State Effects

- AL-01: When the host transitions to **Passive Alert** (via an AlertTransition on a TriggerStep), all five subsystem ratings (Access, Control, Index, Files, Slave) are immediately raised by +2. This increase applies to all subsequent System Tests for the remainder of the decker's current session on that host. The increase is **not** reversed if the security tally later drops below the Passive Alert trigger step.
- AL-02: When the host transitions to **Active Alert**, any TriggerStep at or above the Active Alert threshold may specify one or more **security decker NPCs** to spawn. Each security decker NPC persona enters the host and operates as a hostile proactive combatant — it rolls for Initiative and attacks the intruding persona using standard cybercombat rules.

## Intrusion Countermeasures

- ICC-01: **Crippler** (proactive) — targets one persona attribute (subtype determines which: Acid → Bod, Binder → Evasion, Jammer → Sensor, Marker → Masking). The host makes an Attack Test (Security Value dice vs. Detection Factor); the decker defends with the targeted attribute vs. TN = IC Rating. Every 2 net IC successes reduces the targeted attribute by 1. The attribute cannot be reduced below 1. Neither Armor nor Hardening protect against Cripplers.

- ICC-02: **Killer** (proactive) — Power = IC Rating; Damage Level by host Security Code (Blue/Green = Moderate; Orange/Red = Serious). Follows standard Icon Damage rules (CC-24–CC-26). Armor reduces Power. Filling the persona's Condition Monitor dumps the decker.

- ICC-03: **Probe** (reactive) — every time the decker performs a System Test, the GM makes a Probe Test (IC Rating dice) vs. the decker's Detection Factor. Every success is added to the security tally immediately.

- ICC-04: **Scramble** (reactive) — guards a specified element of the Access, Files, or Slave subsystem. The protected element cannot be accessed until defeated via the appropriate Decrypt operation. If the decker fails a Decrypt attempt, the GM makes a Scramble Test (IC Rating dice) vs. TN = decker's Computer Skill (floor 2). If the Scramble Test succeeds, the protected data is destroyed; if it fails, the destruct code is suppressed. Scramble IC can be crashed with an attack utility but doing so raises the security tally unless the IC is suppressed afterward.

- ICC-05: **Tar Baby** (reactive) — pre-programmed to target one utility category (operational / offensive / defensive / special). Passive utilities (Armor, Sleaze) are not trigger targets. When the decker uses any trigger-category utility: Opposed Test — Tar Baby Test (TN = utility rating) vs. Utility Test (TN = Tar Baby rating). If Tar Baby wins: both programs crash; security tally is **not** raised. If the utility wins: the GM secretly checks whether the decker notices the IC (Sensor Test per MP-07/MP-08).

### Gray IC

- ICC-06: **Blaster** (proactive) — attacks like Killer IC; Armor reduces Power. If Blaster dumps the decker (crashes the persona), the GM makes a Blaster Test (IC Rating dice) vs. TN = deck's MPCP Rating; Hardening increases TN. Every 2 successes permanently reduces the MPCP Rating by 1. The decker must reduce persona programs if their total would exceed the new MPCP × 3 cap.

- ICC-07: **Ripper** (proactive) — gray version of Crippler (ICC-01); attacks one persona attribute via the same mechanics. Subtypes: Acid-Rip → Bod, Bind-Rip → Evasion, Jam-Rip → Sensor, Mark-Rip → Masking. If the attribute is reduced to 0: GM makes a Ripper Test (IC Rating dice) vs. TN = deck's MPCP Rating; Hardening increases TN. Every 2 successes permanently reduces MPCP Rating by 1.

- ICC-08: **Sparky** (proactive) — attacks like Killer IC. If Sparky crashes the persona: GM makes a Sparky Test (IC Rating dice) vs. TN = MPCP Rating + 2; Hardening increases TN. Every 2 successes permanently reduces MPCP Rating by 1. Additionally, Sparky causes (IC Rating)M physical damage to the decker's meatbody; stage the Damage Level up by 1 for every 2 successes on the Sparky Test. The decker resists this with Body; Hardening reduces the Power.

- ICC-09: **Tar Pit** (reactive) — attacks like Tar Baby (ICC-05). If Tar Pit wins and crashes the utility, the GM makes a Tar Pit Test (IC Rating dice) vs. TN = MPCP Rating; Hardening increases TN. If no successes: same effect as Tar Baby (decker may reload from storage). If any successes: all copies of that utility in active memory **and** storage memory are corrupted. The decker cannot reload the utility until jacking out and obtaining a clean copy from an external source.

### Black IC

- ICC-10: **Black IC — Pin Mechanic** — from the moment Black IC scores its first successful hit (even with zero net damage), the ASIST interface begins to be subverted. Before the first hit: Jack Out is a Free Action. After any successful Black IC hit: the decker must spend a Complex Action and succeed at a Willpower (Black IC Rating) Test to jack out. If the test succeeds, the decker may jack out, but Black IC makes one final cybercombat attack before the connection drops. If a companion at the jackpoint manually pulls the plug while Black IC is active, Black IC also gets one automatic final attack.

- ICC-11: **Lethal Black IC** (proactive) — attacks like Killer IC. Damage Code: (IC Rating)M for Blue/Green hosts; (IC Rating)S for Orange/Red hosts. Each successful hit requires **two separate Damage Resistance Tests**:
  - *Decker's body*: Body dice vs. TN = Power (Hardening reduces Power; Hacking Pool may **not** be used; Karma Pool may be used). The Armor utility does **not** protect the decker's meatbody — it only reduces Power for the icon's damage track.
  - *Icon*: icon's Bod dice vs. TN = Power (Armor protects normally).

  If the icon crashes before the decker dies: the decker cannot fight back; the IC's effective rating increases by 2 for all subsequent tests against the decker.

  If the decker dies: the connection drops; Black IC immediately makes one final attack against the MPCP at **double its rating** (resolved as Blaster IC, ICC-06). If the MPCP is destroyed (Rating → 0), all data downloaded during the run and all data in connected storage memory is deleted.

  Any Deadly wound may produce permanent neurological aftereffects per standard SR3 Deadly-wound rules.

- ICC-12: **Non-Lethal Black IC** (proactive) — identical to ICC-11 with these differences: inflicts Mental damage (not Physical); decker resists with Willpower Tests. If rendered unconscious, the Matrix connection automatically breaks. The final MPCP attack and data deletion still occur. Mental damage can overflow into the Physical Condition Monitor.

### Black Hammer and Killjoy Utilities

- ICC-13: **Black Hammer** — functions identically to Lethal Black IC (ICC-11), with one exception: it does **not** make a final attack against the MPCP on decker death (no blaster-like capability). Hardening reduces the Power of damage. Cyberterminal users and hitchers are immune (CT-04, ACC-03).

- ICC-14: **Killjoy** — functions identically to Non-Lethal Black IC (ICC-12), with the same MPCP exception as ICC-13. Hardening reduces the Power of damage. Cyberterminal users and hitchers are immune (CT-04, ACC-03).

### Slow Utility vs. IC

- ICC-15: **Slow** — when a decker attacks a **proactive** IC with the Slow utility: Opposed Test — host's Security Value vs. Slow Rating. If IC wins: no effect. If Slow wins: IC loses 1 action per 2 net successes. An IC with no actions remaining hangs (goes inert for that turn). While inert the IC does not add to the security tally. Suppressing the inert IC costs 1 Detection Factor point (see Suppressing IC). If the IC is not suppressed at the start of the next Combat Turn, the GM rolls normal Initiative for it and it resumes immediately. **Reactive IC is not vulnerable to Slow.**
