# Iteration 2 (design docs) — Audit batch A

Scope: `design/design.md`, `design/start.md`, `design/design_core/creation.md`,
`design/design_core/missing.md`. Every file read in full via `Read` starting at line 1.

## Coverage table

| File | Lines | Verbatim excerpts (copied tokens proving full coverage) | Notes/findings |
|---|---|---|---|
| `design/design.md` | 140 | L8 (opening): `` - [Urility(Utility)] `` · L48 (middle): `` - ActiveIcon (--> Game) `` · L114 (closing): `` - Node (`data class Node(val subsystemType: SubsystemType, val description: String = "")`) `` | Pure index / table-of-contents outline of the domain layers. Typo `Urility` (L8). Only concrete typed spec is the inline `Node` data class (L114). Cross-doc note vs creation.md YAML key `type`. See DOC-1, DOC-2. |
| `design/start.md` | 35 | L10 (opening): `` The WebSocket endpoint is at `ws://localhost:8080/decker/ws`. `` · L33 (closing): `` Only one decker session is supported at a time (HeadCrash loaded from `headcrash.yaml`) `` | Operational runbook (start server, UI steps, dev mode). Backend on :8080, WS at `/decker/ws`, Vite dev on :5173. Filename `headcrash.yaml` vs creation.md `<decker_name>.yaml` naming. See DOC-3. |
| `design/design_core/creation.md` | 319 | L48 (opening third): `` ratings: { access: 6, control: 8, index: 6, files: 6, slave: 6 } `` · L163 (middle third): `` The `alert_transition` values must match the `AlertStatus` enum exactly: `PASSIVE_ALERT` or `ACTIVE_ALERT`. `` · L298 (closing third): `` > **Superseded.** The canonical 10-step sequence is in `design_core/cyberdeck_and_program_mechanics.md` `` | Grid + decker YAML spec. Rich checkable content: validation rules, calculated-field formulas, enum sets, random-gen tables. Explicit "Superseded" staleness marker on the 7-step decker init sequence (L296-307). See DOC-4. All worked-example arithmetic verified correct. |
| `design/design_core/missing.md` | 143 | L7 (opening third): `` ## 1. Host Rating Random Generation Table (rules p. 205) ✓ resolved `` · L64 (middle third): `` The rules specify: *"IC programs re-detect evading icons in a number of Combat Turns equal to the net successes of the icon's Evasion Test..."* `` · L128 (closing third): `` PRD ICC-10: *"If a companion at the jackpoint manually pulls the plug while Black IC is active, Black IC also gets one automatic final attack."* `` | Backlog of SR3 rules not yet reflected in design docs. Items #1-7, #9-14 marked `✓ resolved`; items **#8** (L63) and **#15** (L126) are NOT resolved. Item numbering is out of order (#15 at L126 precedes #14 at L136). See DOC-5, DOC-6. |

## Distilled spec additions

Checkable facts suitable for merging into `spec_baseline.md` (design-doc line refs).

### Node domain type (design.md L114)
- `data class Node(val subsystemType: SubsystemType, val description: String = "")` — field `subsystemType` (not `type`, not `subsystem`), `description` defaults to `""`.

### Domain layer / type inventory (design.md L41-140)
- Common: enums `SecurityRating`, `SubsystemRating`, `ConditionMonitor`; `ActiveIcon` base class (L43-49).
- Utility: `DiceRoller` (L51-52).
- Decker `: ActiveIcon`; things `Cyberdeck`, `Cyberterminal`; result types `ActiveMemory` (with `PendingUpload`, `LoadUtilityResult`), `DownloadDestination`, `MedicResult` (L54-69).
- IC `: ActiveIcon` (L71-72).
- Operations: `MatrixIcon`, `SystemOperation`, `SystemTestResolver`; virtual `BufferedMessage`/`LinkedObserver`, `DownloadHandle`, `InterrogationState`, `MatrixIcon`/`SensorTestResult`/`IcDetectionResult`, `MonitoredOperationHandle`, `NullOperationModifier`, `OperationResult`/`HostInfoItem`, `PointerChain`, `SystemTestOutcome` (L74-97).
- Programs: `PersonaProgram : Program`, `Utility[UtilityType] : Program` (L98-101).
- Accessory: `Accessory` (L103-105).
- Network: `Matrix`, `RTG : Grid`, `(P)LTG : Grid`; `Host`, `Node`, `SAN`, `IC`, `DataFile`, `RemoteDevice`; virtual `Jackpoint`, `MatrixLocation`, `TriggerStep`, `SecuritySheaf`; helper `applyAlertTransition` (L107-128).
- Config: `DeckCatalogEntry`, `DeckCatalogLoader`, `DeckerLoader`, `GridInitializer`/`GridLoader` (L129-140).
- Logic methods named: `ConditionMonitor.applyDamage`, `AlertTransition.applyAlertTransition`, `SystemTestResolver` (called by Decker), `CombatTest` (called by IC or Decker), `ActiveIcon.action` (L21-38).

### Runbook / wire endpoints (start.md)
- Backend HTTP: `http://localhost:8080`; WebSocket endpoint: `ws://localhost:8080/decker/ws` (L9-10).
- Vite dev server: `http://localhost:5173`, proxies `/decker/ws` to `localhost:8080` (L28).
- Single decker session only; active decker registered on Join, named "HeadCrash" (L16, L33).
- Logging silent by default; `logback-classic` test-only (L34).

### Grid YAML structure (creation.md L36-83)
- Top: `rtgs:` list. RTG fields: `id`, `name`, `security` (`SecurityCode-SecurityValue`), `ratings: { access, control, index, files, slave }`, `ltgs:`, optional `pltgs:`.
- LTG fields: `id`, `region`, optional `ratings` (inherit parent RTG if omitted), `hosts:`.
- Host entry two formats: external `{ name, config: hosts/filename.yaml }` or inline (all fields under entry). Mixable in one LTG (L38-42).
- PLTG fields: `id`, `owner`, `security`, `ratings`.
- RTG-level PLTGs replicate to ALL child LTGs; LTG-level PLTGs attach only to that LTG, not propagated upward (L17).
- Host config files carry NO `ltg:` back-reference; LTG determined by position (L42, L89).

### Rating format (creation.md L22-32)
- ACIFS shorthand: `SecurityCode-SecurityValue / Access / Control / Index / Files / Slave`, e.g. `Orange-6/8/8/8/8/8`.
- Security codes: `Blue`, `Green`, `Orange`, `Red`.

### Host YAML fields (creation.md L91-106)
- Required: `name`, `security`, `ratings`, `intrusion_difficulty`, `topology`.
- `intrusion_difficulty` ∈ { `EASY`, `AVERAGE`, `HARD` } (L98).
- `topology` (must match `TopologyType` enum) ∈ { `OPEN_ACCESS`, `TIERED`, `HOST_HOST`, `PRIVATE_GRID` } (L99, L163).
- Optional: `nodes` (list of `{ type, description }`; defaults to all five subsystem types with empty descriptions if omitted — L100), `sans`, `ic_programs`, `data_files`, `remote_devices`, `security_sheaf`, `offline`.
- `alert_transition` (in security_sheaf trigger steps) must match `AlertStatus` enum: `PASSIVE_ALERT` or `ACTIVE_ALERT` (L163).
- Node YAML key is `type` (L100, L117-126) — note vs domain field `subsystemType` (design.md L114).
- SAN object fields: `name`, `scramble_protected` (L128-130).
- IC program fields: `type`, `rating`, optional `guarded_node` (L132-137).
- Data file fields: `name`, `scramble_protected`, `size_mp` (L139-142).
- Security sheaf trigger step: `tally_threshold`, `description`, optional `alert_transition`, `activated_ic` (list of `{ type, rating }`), optional `security_decker_count` (L144-161).
- Offline hosts: `offline: true` (L106, L165, L209-216).

### Host rating random generation (creation.md L175-185, rules p.205)
- Easy: Security Value `1D3 + 3`, Subsystem `1D3 + 7`.
- Average: Security Value `1D3 + 6`, Subsystem `2D3 + 9`.
- Hard: Security Value `2D3 + 6`, Subsystem `1D6 + 12`.
- Each subsystem rolled independently; design-time tool only (static YAML output), not runtime.

### Security sheaf random generation (creation.md L189-203, rules p.211)
- Roll `1D6 ÷ 2` (round down, min 1); add Security Code modifier: Blue +4, Green +3, Orange +2, Red +1; add cumulatively to previous threshold. Static config, not rolled at runtime.

### Decker validation rules on load (creation.md L226-234)
- Each persona program rating ≤ MPCP.
- Sum of all four persona program ratings ≤ MPCP × 3.
- Response Increase ≤ min(3, floor(MPCP ÷ 4)).
- Total Mp of all utilities ≤ Storage Memory.
- Total Mp of active-memory utilities ≤ Active Memory (checked at runtime, not parse time).

### Decker calculated fields (creation.md L238-249) — must NOT appear in YAML
- Hacking Pool = `floor((Intelligence + MPCP) ÷ 3)`.
- Detection Factor = `ceil((Masking + Sleaze rating) ÷ 2)`; or `ceil(Masking ÷ 2)` if no Sleaze loaded.
- Persona Reaction = `base Reaction + (Response Increase × 2)`.
- Persona Bod/Evasion/Masking/Sensor = read directly from the four persona program ratings.
- Program Mp size = `Rating² × Multiplier`.

### Utility multipliers (creation.md L274-286)
- Deception (operational) ×2; Sleaze (special) ×3; Analyze (operational) ×3; Attack (offensive) ×4 for Serious damage level; Armor (defensive) ×3.
- Attack utility carries a `damage_level` field (e.g. `Serious`) that governs its multiplier (L281-283).

### Decker YAML fields (creation.md L254-292)
- Top: `name`, `intelligence`, `body`, `willpower`, `reaction`, `computer_skill`, `cyberdeck`.
- `cyberdeck`: `model` (resolved vs `decks.yaml` catalog, overrides catalog defaults), `mpcp`, `hardening`, `active_memory` (Mp), `storage_memory` (Mp), `io_speed` (Mp/Combat Turn), `response_increase`, `persona_programs: { bod, evasion, masking, sensor }`, `utilities` (list of `{ type, rating, [damage_level] }`).

### LTG address format (creation.md L310-318)
- Form `UCAS-SEA-2206` (RTG-region-node). LTG `id` is the base address; node numbers allocated at runtime or enumerated under a `nodes` list.

### Decker init sequence (creation.md L296-307) — SUPERSEDED
- The 7-step sequence is explicitly retained for historical reference only; the canonical **10-step** sequence lives in `design_core/cyberdeck_and_program_mechanics.md` (*Updated Decker Initialization Sequence*). Any code conformance must target the 10-step version.

## Candidate findings

- **DOC-1 — design.md TOC typo "Urility"** (design.md L8): `` - [Urility(Utility)] `` — misspelling of "Utility"; harmless but a staleness/quality marker in the index.

- **DOC-2 — Node YAML key vs domain field name mismatch (cross-doc)** (design.md L114 vs creation.md L100, L117): design.md mandates `data class Node(val subsystemType: SubsystemType, val description: String = "")`, i.e. field `subsystemType`; creation.md's host YAML declares nodes with key `type`: `` - type: ACCESS `` / `nodes` "List of `{ type, description }`". The loader must map YAML `type` → domain `subsystemType`. Flag to confirm the loader alias exists and that no doc/code expects a `type` field on the domain object.

- **DOC-3 — Decker config filename casing inconsistency (cross-doc)** (start.md L33 vs creation.md L222, L255): start.md says "HeadCrash loaded from `headcrash.yaml`" (lowercase), while creation.md reads `<decker_name>.yaml` with `name: HeadCrash`. Whether the resource file is `headcrash.yaml` or `HeadCrash.yaml` is case-sensitive on non-Windows loaders; docs disagree on the derivation from the decker name. Verify against the actual resource file and loader.

- **DOC-4 — Superseded 7-step decker init sequence retained in creation.md** (creation.md L296-307): `` > **Superseded.** The canonical 10-step sequence is in `design_core/cyberdeck_and_program_mechanics.md` ``. Explicit staleness marker; the 7-step list is kept "for historical reference only." Conformance checks must use the 10-step sequence; ensure downstream audit does not check code against the stale 7-step list.

- **DOC-5 — Unresolved "missing rule" backlog items #8 and #15** (missing.md L63-69, L126-133): every other item is tagged `✓ resolved`, but item **#8 "Evade Detection — IC Re-Detection Timing" (rules p.224-225)** and item **#15 "ICC-10 — Companion Plug-Pull While Black IC is Active"** (PRD ICC-10) carry no resolution marker. These are open design gaps: #8 says combat.md `ManeuverResult.Success(netSuccesses)` lacks the re-detection countdown / tally-shortening mechanic; #15 says `resolveJackOutWithPin` does not model a third-party plug-pull final attack. Track as still-missing design coverage.

- **DOC-6 — missing.md item numbering out of order** (missing.md L126 vs L136): `## 15. ICC-10 …` (L126) appears before `## 14. Legitimate Passcode …` (L136). Numbering/ordering anomaly indicating the list was edited without resequencing; minor doc-hygiene, but paired with DOC-5 it flags the two unresolved items sit at the end out of sequence.
