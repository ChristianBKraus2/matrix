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
