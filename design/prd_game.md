# Game

## Active Icons

The decker and the IC should have a common base class that exposes the following methods:

- initiative (Rolling for initiative)
- action (acting on the game)

In the case of an IC, the corresponding class implements both methods. The initative roll delegates to the corresponding initiative method and the action delegates to the corresponding action of the IC type in the CombatResolver class.
The decker also implements both methods and delegates to its initialive roll class, but the action method is empty for the time beeing. There will be a callback to the user in the future.

## Game Class

The game class hold all active icons and their corresponding initiative. When an icon is to act it calls the correspondent instance method.

There are two modes:

- outside of combat there are no active ICs yet. Each decker's action count per turn follows SO-01/SO-02: ⌈Persona Reaction ÷ 10⌉ + Response Increase (e.g. Reaction 5, RI 0 → 1 action; Reaction 9, RI 2 → 3 actions).
- in combat there are turns. At the start of each turn everyone is rolling for initiative. The icon with the highest initiative value starts (and is called). Afterwards, the initiative value is reduced by 10. As long as the initiative value is postive the icon can action. Assuming there are two icons (A and B) with initiatives of 15 and 8 respectively. Then A gets an action with 15, B gets an action with 8 and A gets an action again with 5. There are no further actions as the resulting initiative roll is non-positive. After the end of a turn, a new turn starts (rolling initiative again) until the combat is resolved.

## IC Actions

When an IC takes an action it uses its appropriate method of CombatResolver. The target is selected as follows:

- If the IC has a `guardedNode`, first look for an unauthorized decker in that node. If none, fall back to any unauthorized decker in the same host.
- If the IC has no `guardedNode`, look for any unauthorized decker in the host.
- If no unauthorized decker is found anywhere in the host, the IC takes no action.

If a target is found but is not in the IC's current node, a proactive IC moves to the target's node (returning `IcMoved` this action instead of attacking). A reactive IC does not move; it attacks regardless of which node the target is in.

## Available Actions

The `availableActions` list returned to the client must only include operations that have a complete server-side implementation. `SWAP_MEMORY` and `LOCATE_DECKER` are out of scope — see [out_of_scope.md §5](out_of_scope.md) and [§4](out_of_scope.md) respectively.

## Decker State — Locate Discovery and Address Gating

Locate operations (`LOCATE_FILE`, `LOCATE_SLAVE`, `LOCATE_ACCESS_NODE`) are **single System Tests**, not multi-turn interrogations — there is no accumulated-success state. Instead the `Decker` data class holds:

- `knownAddresses: Set<String>` (persisted) — full names of LTGs/PLTGs/hosts the decker may access. Seeded on a successful jack-in / logon and by selecting a located access node.
- `locatedFiles` / `locatedSlaves: Set<String>` (run-scoped) — host-qualified keys (`"<hostName>::<name>"`) for files/devices the decker has located.
- `pendingLocate: PendingLocate?` (transient) — the ≤5 candidate names from the most recent successful Locate, awaiting the decker's selection. Cleared when the decker logs off, jacks out, or is dumped.

`availableActions()` emits **at most one** `AccessLtg(targets)` and **one** `AccessHost(targets)`, where `targets` are the structurally-reachable LTGs/PLTGs/hosts filtered to those whose `name` is in `knownAddresses` (each emitted only when non-empty). Gating is **strict** — an address is required even for a directly-attached target. The old per-target `LogonToLtg` / `LogonToPltg` / `LogonToHost` actions are removed; `LogonToRtg` stays per-target and ungated (RTG backbone). Downstream host operations are gated too: `DOWNLOAD_DATA` / `EDIT_FILE` / `DECRYPT_FILE` appear only for files in `locatedFiles`, and `CONTROL_SLAVE` / `EDIT_SLAVE` / `MONITOR_SLAVE` only for devices in `locatedSlaves`; `LOCATE_FILE` / `LOCATE_SLAVE` themselves stay available so targets can be discovered.

## Decker State — IC Visibility

On host entry the decker sees **no IC** — resident host IC is not auto-detected on logon (PRD MP-11; the free Sensor Test of MP-01 fires only for icons entering the decker's area, and on logon the decker is the newcomer). IC becomes visible only once its specific instance is added to `detectedIcons`:

- **Locate IC** — on a successful System Test, auto-locates (no Sensor Test) every IC present on the host: the resident `host.icPrograms` **plus** the triggered `context.activeIc`.
- **Analyze Subsystem** — a successful test locates any Scramble IC guarding the targeted subsystem.
- **Spawn detection** (`runSpawnDetection`) — when a trigger step activates new IC, a Sensor Test (MP-07/MP-08) may detect it.
- **Coming under attack** — a proactive IC that attacks becomes visible (CC-13).

`visibleObjects` filters IC by **identity** (`IC.matchesIdentity`: type + name + rating + guarded node), not by name, and de-duplicates the union of the resident and active pools. Detecting a triggered Probe never reveals a distinct resident Probe of a different rating, and an identity-equal IC present in both pools renders exactly once (PRD MP-12).
