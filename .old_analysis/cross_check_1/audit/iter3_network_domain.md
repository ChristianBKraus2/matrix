# Iteration 3 Audit — src/main/kotlin/com/shadowrun/matrix/network/*.kt

Domain-model conformance audit (Kotlin). Every file read in full via `Read` from line 1 to last
line in this session. Against: `iter2_ord.md` object model, `iter2_a.md` (creation.md YAML distill),
`spec_baseline.md` (§Alert, §Movement), and design.md L114 (`Node`).

## Coverage table

| File | Lines | Verbatim excerpt(s) | Notes |
|---|---|---|---|
| `AlertTransitions.kt` | 91 | L21-22: `fun applyAlertTransition(host: Host, newAlertStatus: AlertStatus): Host = when (newAlertStatus) {` / `AlertStatus.PASSIVE_ALERT -> host.copy(` … L24-28 add `+ 2` to access/control/index/files/slave. L41: `fun applyAlertTransition(grid: Grid, newAlertStatus: AlertStatus): Grid` (grid overload). L77-79: `fun checkGridTriggers(grid: Grid, oldTally: Int, newTally: Int)` filters `it.tallyThreshold in (oldTally + 1)..newTally`. | AL-01 Passive → all 5 subsystems +2, enum values `PASSIVE_ALERT`/`ACTIVE_ALERT`/`NO_ALERT` correct. Both Host and Grid (RTG/LTG/PLTG) overloads present (Rule 10 — no host-only partial fix). Conforms. |
| `DataFile.kt` | 26 | L3-5: `data class DataFile(` / `val name: String,` / `val isScrambleProtected: Boolean = false,` L11: `val sizeMp: Int = 0` L13: `val isPointer: Boolean get() = pointerToHost != null` L22: `return name == other.name && isScrambleProtected == other.isScrambleProtected && sizeMp == other.sizeMp` | Equality by (name, isScrambleProtected, sizeMp); pointer fields excluded — matches ord.md L66 exactly. See D3N-2. |
| `Grid.kt` | 63 | L7: `sealed class Grid(` L16: `data class RTG(` … L27: `override fun equals(other: Any?) = other is RTG && name == other.name` L32: `data class LTG(` (`val parentRtg: RTG`) L49: `data class PLTG(` (`val owner: String,` / `val parentLtg: LTG,`) | Subtypes RTG/LTG/PLTG present; equality by `name` alone per subtype (ord.md L66) — avoids recursion through `ltgs`/`hosts`/`connectedRtgs`. `securityRating: SecurityRating` bundles SecurityCode+Value (value object). Conforms. |
| `Host.kt` | 43 | L11-16: `data class Host(` / `val securityRating: SecurityRating,` / `val subsystemRatings: SubsystemRatings,` / `val intrusionDifficulty: IntrusionDifficulty,` / `val topologyType: TopologyType,` L26: `val nodes: List<Node> = SubsystemType.entries.map { Node(it) },` L34-37: `require(coveredTypes == SubsystemType.entries.toSet())` | All required fields present: security, ratings, intrusion_difficulty, topology, offline, security_sheaf, sans, nodes, ic_programs, data_files, remote_devices, connectedHosts. Node default = one per subsystem type, empty descriptions (creation.md L100). See D3N-1. |
| `Jackpoint.kt` | 15 | L5-8: `data class Jackpoint(` / `val type: JackpointType,` / `val connectsToLtg: LTG? = null,` / `val connectsToHost: Host? = null` L11: `require((connectsToLtg == null) != (connectsToHost == null))` | Connects to exactly one of LTG or Host (ord.md L45-49). `type: JackpointType` — enum lives in `common/` (out of scope). See D3N-4 (ord DOC-5). |
| `Matrix.kt` | 11 | L3: `class Matrix(val rtgs: List<RTG> = emptyList()) {` L6-9: `fun getHost(...)` falls back to `ltg.pltgs.flatMap { it.hosts }`. | Root of engine (ord.md L5). Navigates RTG→LTG→Host/PLTG-host. Conforms. |
| `MatrixLocation.kt` | 8 | L3-7: `sealed class MatrixLocation {` / `data class OnLTG(val ltg: LTG)` / `OnRTG` / `OnPLTG` / `OnHost` | Sealed location type covers all 4 grid/host targets. No spec field list; conforms. |
| `Node.kt` | 5 | L5: `data class Node(val subsystemType: SubsystemType, val description: String = "")` | Exact match to design.md L114: field `subsystemType` (not `type`), `description` default `""`. YAML key `type`→domain `subsystemType` mapping is a loader concern (iter2_a DOC-2). Conforms. |
| `RemoteDevice.kt` | 3 | L3: `data class RemoteDevice(val name: String, val systemAddress: String)` | Matches ord.md ERD L380-383 (Name, SystemAddress). Device-kind free-form label omitted (ord.md L50-54 says free-form, not typed) — benign. Conforms. |
| `SAN.kt` | 3 | L3: `data class SAN(val name: String, val isScrambleProtected: Boolean = false)` | Matches creation.md L128-130 (name, scramble_protected). Conforms. |
| `SecuritySheaf.kt` | 27 | L6-7: `data class TriggerStep(` / `val tallyThreshold: Int,` L14: `val securityDeckerCount: Int = 0` L20/L23: `require(thresholds.size == thresholds.distinct().size)` / `require(thresholds == thresholds.sorted())` | TriggerStep fields tallyThreshold+securityDeckerCount match ERD L346-349; sheaf enforces unique+ascending thresholds. See D3N-3. |

Total: 11 files, 295 lines.

## Findings

### D3N-1 — Host allows duplicate nodes of the same subsystem type (ord.md says exactly one per type)
`Host.kt` L34-37:
```
val coveredTypes = nodes.map { it.subsystemType }.toSet()
require(coveredTypes == SubsystemType.entries.toSet()) {
    "Host must have at least one node per subsystem type"
}
```
**Spec:** ord.md L249 — `Host→Node 1:5 (exactly one per subsystem type)`. **Code:** the `require`
checks only that the *set* of covered types equals all five; a config supplying two `ACCESS` nodes
passes. **Severity: low** (the default `SubsystemType.entries.map { Node(it) }` produces exactly one
each; only a hand-authored config with duplicates would violate the 1:1). No PRD text tightens this
to an exact-count invariant. Documented, no fix applied.

### D3N-2 — ord.md DataFile ERD/prose field names are stale; code is correct (resolves ord DOC-2/3/4)
`DataFile.kt` L3-11 declares `name`, `isScrambleProtected`, `pointerToHost`, `pointerTargetFile`,
`sizeMp`; L22 equality uses `name, isScrambleProtected, sizeMp`.
- **ord DOC-2** (prose `pointerToHost` vs ERD `PointerTargetHost`): code uses **`pointerToHost`** →
  ERD name is doc-stale, code matches prose L61. Doc bug, not code.
- **ord DOC-3** (`sizeMp` used in equality but never declared as a field): code **declares
  `sizeMp: Int = 0`** (L11) and creation.md L139-142 has YAML `size_mp` → prose/ERD field list is
  doc-stale. Real code has the field. Doc bug, not code.
- **ord DOC-4** (`ScrambleProtected` vs `isScrambleProtected` casing): code uses
  **`isScrambleProtected`** (also SAN.kt L3), matching Implementation-Notes L66 Kotlin convention →
  ERD/prose casing doc-stale. Doc bug, not code.
All three resolve as design-doc staleness; the DataFile domain type conforms to the corrected spec.

### D3N-3 — TriggerStep.alertTransition vs ERD `NewAlertStatus` naming (doc-stale)
`SecuritySheaf.kt` L12: `val alertTransition: AlertStatus? = null`. ord.md ERD L350-352 declares an
`AlertTransition` class with field `NewAlertStatus`. Code collapses this into an `AlertStatus?` field
named `alertTransition` on `TriggerStep` (semantically equivalent; creation.md L144-161 uses YAML key
`alert_transition`). **Doc-stale ERD naming, code conforms.** Documented, no fix.

### D3N-4 — Jackpoint legal-access/illegal-access variants (ord DOC-5) unresolvable in this iteration
ord DOC-5 flags that Jackpoint Type variants `legal-access`/`illegal-access` have no
connection mapping. `Jackpoint.kt` models only `connectsToLtg`/`connectsToHost` and defers the
variant set to `JackpointType` (enum in `common/`, not in this iteration's scope). The Jackpoint
domain type itself conforms (exactly-one-target invariant, ord.md L45-49); the enum-variant question
is deferred to the `common/` audit. No code bug here.

### Explicit resolution of remaining ord.md candidate findings
- **ord DOC-1** (PersonaAttributeType `Sensors` plural) — in `programs/` (PersonaProgram), **out of
  scope** for iteration 3 network domain. Not present in any assigned file.
- **ord DOC-6** (PersonaProgram missing Multiplier) — `programs/`, **out of scope**.
- **ord DOC-7** (Utility.Category vs TargetCategory ordering) — `programs/`/`ic/`, **out of scope**.

### Summary
No true code bugs in the network domain layer. Every genuine mismatch flagged by ord.md against the
DataFile / TriggerStep types (ORD DOC-2/3/4, and the ERD alert-transition naming) resolves as
**design-doc staleness — the Kotlin code is correct**. The one code-side observation (D3N-1, Host
node-count not exactly-one-per-type) is low severity and unbacked by any PRD count invariant. Node
correctly uses `subsystemType`+`description=""` (design.md L114); DataFile equality correctly excludes
pointer fields (ord.md L66); AlertTransitions correctly implements AL-01 (+2 to all five subsystems)
for both Host and Grid.
