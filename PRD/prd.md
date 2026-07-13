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

### Grid

The configuration file `grid.yaml` should contain data following these guidelines:

- All north american RTGs from the rules table are seeded. Their System Ratings (Security Code/Value + ACIFS) are taken verbatim from the rules.
- LTG ratings inherit from the parent RTG. Each RTG has 2–4 LTGs covering major regions (e.g. Seattle, Chicago, Los Angeles). LTG addressing follows the `[RTG]-[REGION]-[4-digit number]` convention (e.g. `UCAS-SEA-2206`).
- Each LTG contains at least one named host from the Shadowrun world. Hosts carry their own System Rating independent of the LTG.
- PLTGs (private grids) are separate from public LTGs. They are owned by megacorps or governments, may use Orange/Red security codes, and security tally accrued on the public RTG carries over on entry.

### Integration Tests

- A decker logs on to an LTG, switches to the RTG, moves to a different RTG and one of the LTGs. There, he logs on to a host. Afterwards, he logs of.
