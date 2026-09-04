# Iteration 7 Audit — Config Loaders (Kotlin)

Scope: `src/main/kotlin/com/shadowrun/matrix/config/` (7 files, glob-confirmed). Each read in full
from line 1 to last line in this session. Constructor-completeness (Rule 9) and post-fix surface
(Rule 10) verified against the domain types audited earlier: `Cyberdeck.kt`, `Decker.kt`, `Host.kt`,
`Grid.kt` (RTG/LTG/PLTG), `SecuritySheaf.kt` (TriggerStep/SecuritySheaf), `Node.kt`, `DataFile.kt`,
`SAN.kt`, `RemoteDevice.kt`, `IC.kt`, `PersonaProgram.kt`, `Utility.kt`, `Enums.kt`. Config resources
read for field mapping: `decks.yaml`, `grid.yaml`, `hosts/MitsuhamaPagoda.yaml`, `headcrash.yaml`.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `ConfigUtils.kt` | 9 | (1) `internal object ConfigUtils {` (2) `return value as Map<String, Int>` | Single helper `parseSubsystemRatings`; requireNotNull on null, unchecked cast to `Map<String,Int>`. No discrepancy. |
| `DeckCatalogEntry.kt` | 11 | (1) `data class DeckCatalogEntry(` (2) `val ioSpeedMpPerTurn: Int,` … `val costNuyen: Int` | 7 fields exactly match iter2_cyberdeck L53-61 (model, mpcp, hardening, activeMemoryMp, storageMemoryMp, ioSpeedMpPerTurn, costNuyen). No `responseIncrease` — correct by design (L64). No discrepancy. |
| `DeckCatalogLoader.kt` | 28 | (1) `val entries = (data["decks"] as? List<Map<String, Any>>) ?: error("missing 'decks' key in deck catalog YAML")` (2) `activeMemoryMp = data["active_memory"] as Int,` (3) `costNuyen = (data["cost_nuyen"] as? Int) ?: 0` | YAML keys `model/mpcp/hardening/active_memory/storage_memory/io_speed/cost_nuyen` match decks.yaml verbatim (CD-25). All 7 DeckCatalogEntry fields supplied. hardening & cost_nuyen default 0. No `type: cyberterminal` handling → see D7C-1. |
| `DeckerLoader.kt` | 106 | (1) `fun load(input: InputStream, catalog: List<DeckCatalogEntry> = emptyList()): Decker` (2) `mcpRating = (data["mpcp"] as? Int) ?: entry?.mpcp ?: error("mpcp required"),` (3) `PersonaProgram(PersonaAttributeType.SENSORS, data["sensor"]  ?: error("missing sensor"))` | Decker + Cyberdeck constructors fully supplied. Inline overrides catalog defaults (design step 3). MPCP/RI/persona-sum constraints delegated to `Cyberdeck.init` (verified present). `sensor`→SENSORS mapping correct. currentRating defaults to rating (design step 8). Cyberterminal never built → D7C-1. |
| `GridInitializer.kt` | 14 | (1) `.getResourceAsStream("grid.yaml")` (2) `return input.use { GridLoader.load(it) }` | Thin loader of classpath `grid.yaml`; delegates to GridLoader. No discrepancy. |
| `GridLoader.kt` | 157 | (1) `val rtgData = root["rtgs"] as List<Map<String, Any>>` (2) `val secRating = (data["security"] as? String)?.let { parseSecurityRating(it) } ?: inheritedSecRating` (3) `access  = map["access"]  ?: error("missing access rating"),` | RTG/LTG/PLTG constructors supplied; LTG inherits sec/ratings from parent RTG (design). PLTG requires explicit `security` → D7C-5. Grid securitySheaf always defaulted → D7C-3. `buildHost` never wires connectedHosts → D7C-2. |
| `HostLoader.kt` | 220 | (1) `val difficulty = IntrusionDifficulty.valueOf((data["intrusion_difficulty"] as? String ?: "AVERAGE").uppercase())` (2) `sizeMp = (data["size_mp"] as? Int) ?: 0` (3) `tallyThreshold = data["tally_threshold"] as Int,` … `securityDeckerCount = (data["security_decker_count"] as? Int) ?: 0` | Host + TriggerStep + IC constructors supplied correctly (IC param order rating/targetAttr/targetCategory/guardedNode verified against IC.kt). Sheaf trigger steps fully mapped. connectedHosts omitted → D7C-2; intrusion_difficulty/topology loader-defaulted → D7C-4; DataFile pointer fields not loadable → D7C-6; raw duplicate nodes retained → D7C-7. |

## Findings

**D7C-1 — Cyberterminal is never constructible from config (documented loader behavior unimplemented).**
`DeckerLoader.kt:73-85` always builds `Cyberdeck(...)` leaving `isCyberterminal` at its default `false`;
`buildCyberdeck` reads no `type` field, and `DeckCatalogLoader.buildEntry` (`DeckCatalogLoader.kt:19-27`)
has no `type` either. Verbatim (`DeckerLoader.kt:73`): `return Cyberdeck(` … with no `isCyberterminal`
argument. iter2_cyberdeck L68/L541: "Loader: `type: cyberterminal` in `decks.yaml` → instantiate
`Cyberterminal`, else `Cyberdeck`." Result: CT-01..CT-05 (MPCP≤4 cap, RI=0, −1 rating, black-IC/dump-shock
immunity) cannot be produced through the config path. The `Cyberterminal` factory exists but no loader calls it.

**D7C-2 — `Host.connectedHosts` never populated; TIERED / HOST_HOST topologies have no linked hosts.**
`HostLoader.buildFromMap` (`HostLoader.kt:85-99`) omits `connectedHosts` (defaults `emptyList()`), and
`GridLoader.buildHost` (`GridLoader.kt:129-137`) does no host-to-host wiring. Verbatim (`HostLoader.kt:98`):
`securitySheaf = securitySheaf` — the last constructor arg; no `connectedHosts =`. Yet `grid.yaml` declares
hosts with `topology: TIERED` and `topology: HOST_HOST` (e.g. "Aztechnology Inner Sanctum" HOST_HOST,
"Ares R&D Secure Archive" TIERED). Impact: `Decker.availableActions` OnHost branch
(`loc.host.connectedHosts.forEach { LogonToHost }`, Decker.kt:160) is always empty for loaded hosts, so
tiered/host-host navigation is impossible. Violates ord.md L27/L249 (Host→Host via SAN; tiered/host-host).

**D7C-3 — Grid SecuritySheaf / alert escalation is not loadable.**
`GridLoader` builds RTG (`GridLoader.kt:49-54`), LTG (`:90-96`) and PLTG (`:119-126`) never parsing any
`security_sheaf` key; each grid's `securitySheaf` falls to the domain default `SecuritySheaf()` (empty).
Verbatim (`GridLoader.kt:49`): `val placeholder = RTG(` … constructs with only name/region/securityRating/
subsystemRatings — no sheaf. ord.md L40-43 & L235-241: Grid→SecuritySheaf 1:1 (grids accumulate tally and
escalate alert). There is no YAML path to give a grid trigger steps, so grid-level tally escalation can
never fire. Schema gap (HostLoader has the parser; GridLoader does not).

**D7C-4 — `intrusion_difficulty` and `topology` silently defaulted for fields the domain marks required.**
`Host.kt:15` declares `intrusionDifficulty` and `topologyType` with no default (required constructor
params). `HostLoader.kt:50-55` supplies loader defaults when the YAML omits them: verbatim
`IntrusionDifficulty.valueOf((data["intrusion_difficulty"] as? String ?: "AVERAGE").uppercase())` and
`TopologyType.valueOf((data["topology"] as? String ?: "OPEN_ACCESS")...)`. A host config missing these keys
becomes AVERAGE / OPEN_ACCESS instead of erroring — a required field defaulted rather than validated
(per-file checklist "No required field defaulted incorrectly"). Low severity; no design-doc schema mandates
the fields be present, but the domain type treats them as required.

**D7C-5 — PLTG does not inherit security from its parent RTG; explicit `security` is mandatory.**
`GridLoader.buildPltg` (`GridLoader.kt:111-113`): `val secRating = parseSecurityRating(data["security"] as
String)` throws if absent, whereas `buildLtg` (`:87-88`) inherits from the parent when omitted. ord.md L19:
"PLTG … carries security flags from parent RTG." The loader neither inherits nor allows omission for PLTGs.
Low severity (grid.yaml always provides `security` on PLTGs), but inconsistent with the design's inheritance
rule and with the sibling LTG loader.

**D7C-6 — DataFile pointer / distributed-database fields cannot be loaded.**
`HostLoader.buildDataFile` (`HostLoader.kt:127-132`) sets only `name`, `isScrambleProtected`, `sizeMp`;
`pointerToHost` and `pointerTargetFile` default `null`. ord.md L56-62 documents pointer files
(`DataFile.isPointer`, `pointerToHost`, chained `pointerTargetFile`) as part of the model. No YAML path
produces a pointer file, so distributed-database scenarios are not configurable. Gap; may be intentionally
deferred — flag for verification against `deferred.md`.

**D7C-7 — Duplicate host subsystem nodes are passed through to `Host`, violating the 1:5 rule.**
`HostLoader.buildFromMap` dedups only into `nodesByType` for IC guarded-node lookup, warning on duplicates
(`HostLoader.kt:60-63`), but the *raw* `nodes` list is what is handed to the `Host` constructor
(`HostLoader.kt:93`: `nodes = nodes`). `Host.init` (Host.kt:33-38) only checks set-coverage, so duplicate
`type:` entries survive. ord.md L249: "Host→Node 1:5 (exactly one per subsystem type)." Low severity; the
warning fires but the duplicate is not dropped from the constructed host.

## Zero-finding files (Rule 5)
- `ConfigUtils.kt` — No discrepancies found.
- `DeckCatalogEntry.kt` — No discrepancies found.
- `GridInitializer.kt` — No discrepancies found.
- `DeckCatalogLoader.kt` — No discrepancies (D7C-1 is charged against the DeckerLoader/Cyberterminal path, not the catalog schema, which matches decks.yaml verbatim).

## Constructor-completeness cross-check (Rule 9/10)
- Decker(name,intelligence,body,willpower,reaction,computerSkill,cyberdeck) — all 7 required supplied; rest defaulted per design. ✓
- Cyberdeck(name,mcpRating,hardening,activeMemoryMp,storageMemoryMp,ioSpeedMpPerTurn,responseIncrease,costNuyen,personaPrograms,activeUtilities,storedUtilities) — all supplied; MPCP/RI/persona constraints enforced in init. `isCyberterminal` NOT set → D7C-1. ✓/⚠
- Host(...) — all except `alertStatus`(default), `securityTally`(default), `connectedHosts`(default, wrongly never wired → D7C-2). ⚠
- RTG/LTG/PLTG — identity/security/ratings/children supplied; `securitySheaf` defaulted (→ D7C-3). ⚠
- TriggerStep(tallyThreshold,description,activatedIc,alertTransition,securityDeckerCount) — all supplied. ✓
- IC subclasses (Killer/Probe/Scramble/Blaster/Sparky/Lethal/NonLethal + TarBaby/TarPit targetCategory + Crippler/Ripper targetAttribute) — param order matches IC.kt exactly. ✓
- PersonaProgram(attributeType,rating), Utility(type,rating,attackDamageLevel,currentRating,sourceCode), SAN, DataFile, RemoteDevice, Node — all supplied correctly. ✓
