# Matrix of Shadowrun

## Overview

We implement an object oriented model and application for the Matrix of Shadowrun 3. The object model can be found in [ord.md](ord.md).

This document focuses on the various use cases.

## Use Cases

### Movement

#### Jacking In (Initial Entry)

- M-01: A decker using a telecom or illegal-telecom jackpoint may only perform **Logon to LTG** as the first operation; the persona appears on the LTG connected to that jackpoint.
- M-02: A decker using a workstation, console, or remote-device jackpoint may only perform **Logon to Host** as the first operation, and only to the host that controls that device; the persona appears inside that host.
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

- **Alert status** — starts at No Alert. Passive Alert (typically at the third or fourth trigger step) raises all Subsystem Ratings by +2. Active Alert (one or two trigger steps later) may also spawn corporate or law-enforcement security deckers.
- **Reset timing** — determined by security code. Blue hosts reset fully in 2D6 minutes. Green/Orange/Red hosts begin resetting after 3D6 minutes if no alert was triggered; if an alert fired, reduce the security tally by 1D6 every 5/10/15 minutes (Green/Orange/Red respectively). IC programs left running when the decker logged off remain active until the tally drops below the trigger step that activated them.

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

The following persona values are **calculated by the application** and must not be stored in the config file:

- **Hacking Pool** = floor((Intelligence + MPCP) ÷ 3).
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
  | Deception | Logon to Host, Logon to LTG, Logon to RTG, Graceful Logoff |
  | Decrypt | Decrypt Access, Decrypt File, Decrypt Slave |
  | Read/Write | Download Data, Edit File, Upload Data |
  | Relocate | Relocate Icon |
  | Scanner | Locate Decker |
  | Spoof | Control Slave, Edit Slave, Monitor Slave |

- CD-16: Add **Relocate Icon** to the set of named system operations: Simple Action, Control Test, Standard category. The Relocate utility reduces its TN per CD-14.

### Passive Program Behavior

- CD-17: Sleaze is a passive program. Once fully uploaded into active memory (not pending), it contributes automatically to the Detection Factor for every subsequent System Test during that run. No explicit activation is required.
- CD-18: The Detection Factor is recalculated at the moment each System Test is resolved, not cached at jack-in. If a Sleaze utility is fully active: DF = ⌈(Masking + Sleaze.currentRating) ÷ 2⌉; otherwise DF = ⌈Masking ÷ 2⌉. Loading or unloading Sleaze mid-run changes the Detection Factor for all tests resolved after that action.

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
