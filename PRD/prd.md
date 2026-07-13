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

### Non functional Requirements

- 
