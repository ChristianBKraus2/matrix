# Grid Creation Design

## Overview

On startup the application reads `grid.yaml` and instantiates the full grid hierarchy: RTGs → LTGs → Hosts (and optionally PLTGs). This document defines the structure of that file and the data it should contain.

---

## Initialization Sequence

1. Parse `grid.yaml`.
2. Instantiate all RTG objects with their System Ratings.
3. For each RTG, instantiate its LTGs. LTG ratings default to the parent RTG's ratings unless overridden.
4. For each LTG, instantiate its Hosts with their own System Ratings.
5. For each RTG (or LTG), instantiate any PLTGs and connect them.

---

## Rating Format

All System Ratings follow the ACIFS shorthand from the rules:

```
SecurityCode-SecurityValue / Access / Control / Index / Files / Slave
```

Example: `Orange-6/8/8/8/8/8`

Security codes: `Blue` (minimal), `Green` (average), `Orange` (significant), `Red` (high/lethal).

---

## YAML Structure

`grid.yaml` defines the grid hierarchy only — RTGs, LTGs, and PLTGs with their ratings. Each LTG entry lists the **filenames** of its host configuration files; the full host data lives in those separate files (see Host YAML Structure below).

```yaml
rtgs:
  - id: UCAS                        # RTG identifier used in LTG addresses
    name: UCAS Regional Grid
    security: Green-4
    ratings: { access: 6, control: 8, index: 6, files: 6, slave: 6 }
    ltgs:
      - id: UCAS-SEA
        region: Seattle
        # ratings omitted → inherited from parent RTG
        hosts:
          - mitsuhama_pagoda.yaml
          - lone_star_gridsec_seattle.yaml
          - renraku_public_relations.yaml
      - id: UCAS-CHI
        region: Chicago
        hosts:
          - ares_macrotechnology_chicago.yaml
      - id: UCAS-NYC
        region: New York City
        hosts:
          - fuchi_industrial_electronics_east_coast.yaml
      - id: UCAS-BOS
        region: Boston
        hosts:
          - mitt_academic_network.yaml
    pltgs:
      - id: UCAS-PLTG-ARES
        owner: Ares Macrotechnology
        security: Orange-6
        ratings: { access: 9, control: 9, index: 8, files: 8, slave: 8 }

  - id: CAS
    name: Confederate American States Grid
    security: Green-3
    ratings: { access: 6, control: 8, index: 7, files: 8, slave: 8 }
    ltgs:
      - id: CAS-ATL
        region: Atlanta
        hosts: [cas_government_archives.yaml]
      - id: CAS-DAL
        region: Dallas
        hosts: [lone_star_corporate_hq.yaml]
      - id: CAS-MIA
        region: Miami
        hosts: [caribbean_trade_exchange.yaml]

  - id: CFS
    name: California Free State Grid
    security: Green-4
    ratings: { access: 6, control: 8, index: 6, files: 6, slave: 7 }
    ltgs:
      - id: CFS-LAX
        region: Los Angeles
        hosts: [horizon_entertainment_archive.yaml]
      - id: CFS-SFO
        region: San Francisco
        hosts: [shiawase_envirotech_west_coast.yaml]

  - id: AZT
    name: Aztlan Grid
    security: Orange-3
    ratings: { access: 8, control: 8, index: 6, files: 7, slave: 7 }
    ltgs:
      - id: AZT-MEX
        region: Mexico City
        hosts:
          - aztechnology_archive.yaml
          - aztlan_ministry_of_information.yaml
      - id: AZT-GDL
        region: Guadalajara
        hosts: [aztechnology_regional_office.yaml]
    pltgs:
      - id: AZT-PLTG-AZTECHNOLOGY
        owner: Aztechnology
        note: Only corporate PLTG that operates freely within Aztlan
        security: Red-5
        ratings: { access: 10, control: 12, index: 10, files: 9, slave: 9 }

  - id: SS
    name: Salish-Shidhe Grid
    security: Green-3
    ratings: { access: 6, control: 8, index: 7, files: 6, slave: 6 }
    ltgs:
      - id: SS-PDX
        region: Portland
        hosts: [salish_shidhe_council_datastore.yaml]
      - id: SS-VAN
        region: Vancouver
        hosts: [wuxing_pacific_rim_office.yaml]

  - id: SIO
    name: Sioux Nation Grid
    security: Orange-3
    ratings: { access: 7, control: 8, index: 8, files: 7, slave: 7 }
    ltgs:
      - id: SIO-RAP
        region: Rapid City
        hosts: [sioux_military_intelligence.yaml]

  - id: PUE
    name: Pueblo Corporate Council Grid
    security: Orange-4
    ratings: { access: 8, control: 8, index: 8, files: 8, slave: 8 }
    ltgs:
      - id: PUE-DEN
        region: Denver
        hosts: [pueblo_corporate_data_exchange.yaml]

  - id: QUE
    name: Québec Grid
    security: Green-2
    ratings: { access: 6, control: 8, index: 8, files: 7, slave: 7 }
    ltgs:
      - id: QUE-MTL
        region: Montreal
        hosts: [quebec_provincial_archives.yaml]
      - id: QUE-QBC
        region: Quebec City
        hosts: [universite_laval_research.yaml]

  - id: TT
    name: Tir Tairngire Grid
    security: Orange-5
    ratings: { access: 7, control: 8, index: 8, files: 7, slave: 7 }
    ltgs:
      - id: TT-PDX
        region: Portland Border Zone
        hosts: [tir_tairngire_embassy.yaml]

  - id: TSI
    name: Tsimshian Grid
    security: Orange-5
    ratings: { access: 8, control: 8, index: 8, files: 8, slave: 8 }
    ltgs:
      - id: TSI-PRI
        region: Prince Rupert
        hosts: [tsimshian_council_datastore.yaml]

  # Remaining RTGs (Caribbean League sub-nations, NAN nations) follow the same pattern.
  # Ratings taken verbatim from the North American RTG System Ratings table (rules p. 203).
```

---

## Host YAML Structure

Each host lives in its own file `<host_name>.yaml` under `src/main/resources/hosts/`. The file must declare which LTG it belongs to so the loader can register it in the grid hierarchy.

```yaml
# src/main/resources/hosts/mitsuhama_pagoda.yaml
name: Mitsuhama Pagoda
ltg: UCAS-SEA                        # must reference a valid LTG id in grid.yaml
security: Orange-6
ratings: { access: 8, control: 8, index: 8, files: 8, slave: 8 }
sculpt: medieval_japanese
topology: open-access
security_sheaf:
  trigger_steps:
    - tally_threshold: 3
      description: Initial probe deployed
      activated_ic:
        - {type: probe, rating: 5}
    - tally_threshold: 7
      description: Second probe deployed
      activated_ic:
        - {type: probe, rating: 7}
    - tally_threshold: 10
      description: Passive alert; Killer IC deployed
      activated_ic:
        - {type: killer, rating: 8}
      alert_transition: passive
    - tally_threshold: 13
      description: Active alert; reinforcements called
      activated_ic:
        - {type: killer, rating: 10}
      alert_transition: active
      security_decker_count: 1
```

```yaml
# src/main/resources/hosts/lone_star_gridsec_seattle.yaml
name: Lone Star GridSec Seattle
ltg: UCAS-SEA
security: Orange-7
ratings: { access: 9, control: 9, index: 8, files: 8, slave: 8 }
topology: open-access
security_sheaf:
  trigger_steps:
    - tally_threshold: 3
      description: Initial probe deployed
      activated_ic:
        - {type: probe, rating: 6}
    - tally_threshold: 6
      description: Killer IC deployed
      activated_ic:
        - {type: killer, rating: 7}
    - tally_threshold: 10
      description: Passive alert; heavy Killer IC
      activated_ic:
        - {type: killer, rating: 9}
      alert_transition: passive
    - tally_threshold: 13
      description: Active alert; GridSec deckers dispatched
      activated_ic:
        - {type: killer, rating: 10}
      alert_transition: active
      security_decker_count: 2
```

```yaml
# src/main/resources/hosts/renraku_public_relations.yaml
name: Renraku Public Relations
ltg: UCAS-SEA
security: Green-5
ratings: { access: 7, control: 8, index: 7, files: 7, slave: 7 }
topology: open-access
security_sheaf:
  trigger_steps:
    - tally_threshold: 4
      description: Probe IC deployed
      activated_ic:
        - {type: probe, rating: 4}
    - tally_threshold: 8
      description: Second probe deployed
      activated_ic:
        - {type: probe, rating: 6}
    - tally_threshold: 12
      description: Passive alert triggered
      alert_transition: passive
```

Offline hosts carry an additional `offline: true` flag (see Offline Hosts section below). The `ltg` field is still required so the loader can resolve the host's address space, but the host cannot be reached remotely.

### Initialization Sequence (updated)

1. Parse `grid.yaml`; instantiate all RTG, LTG, and PLTG objects.
2. For each LTG entry, load each filename listed under `hosts` from `src/main/resources/hosts/`.
3. Validate the `ltg:` field in each host file references an existing LTG id. Emit a load error if not found.
4. Register the host under its LTG.

---

## Host Rating Random Generation

When a GM wants to generate a host procedurally rather than specifying ratings by hand, use the following table (rules p. 205):

| Intrusion Difficulty | Security Value | Subsystem Ratings (Access/Control/Index/Files/Slave) |
|---|---|---|
| Easy | 1D3 + 3 | 1D3 + 7 |
| Average | 1D3 + 6 | 2D3 + 9 |
| Hard | 2D3 + 6 | 1D6 + 12 |

Each subsystem is rolled independently. The resulting ratings are placed directly into the YAML as explicit values — the random generation is a design-time tool, not a runtime mechanic.

---

## Security Sheaf Random Generation

Trigger step intervals are also generated randomly when not specified by hand (rules p. 211):

1. Roll 1D6 ÷ 2 (round down, minimum 1).
2. Add a Security Code modifier:
   - **Blue:** +4 (result range 5–7)
   - **Green:** +3 (result range 4–6)
   - **Orange:** +2 (result range 3–5)
   - **Red:** +1 (result range 2–4)
3. Add the result cumulatively to the previous trigger step threshold.

Repeat for each desired trigger step. For tighter security (more trigger steps at lower tally values) use the minimum of the range; for looser security use the maximum.

These threshold values are written directly into the `security_sheaf` YAML once generated — they are static configuration, not rolled at runtime.

---

## Offline Hosts

Hosts that are physically isolated (no jackpoint reachable from the Matrix) are flagged `offline: true`. A decker cannot access them remotely; physical access to the facility is required.

```yaml
- name: Saeder-Krupp Research Vault
  security: Red-8
  ratings: { access: 12, control: 12, index: 10, files: 9, slave: 9 }
  offline: true
```

---

## Decker Configuration

On startup the application reads each `<decker_name>.yaml` and instantiates the decker, their cyberdeck, persona programs, and loaded utilities.

---

### Validation Rules

The application must enforce these constraints on load:

- Each persona program rating ≤ MPCP.
- Sum of all four persona program ratings ≤ MPCP × 3.
- Response Increase ≤ min(3, floor(MPCP ÷ 4)).
- Total Mp of all utilities ≤ Storage Memory.
- Total Mp of utilities loaded into active memory ≤ Active Memory (checked at runtime, not at parse time).

---

### Calculated Fields

These values are derived by the application; they must **not** appear in the YAML:

| Field | Formula |
| --- | --- |
| Hacking Pool | floor((Intelligence + MPCP) ÷ 3) |
| Detection Factor | ceil((Masking + Sleaze rating) ÷ 2); or ⌈Masking ÷ 2⌉ if no Sleaze loaded |
| Persona Reaction | base Reaction + (Response Increase × 2) |
| Persona Bod/Evasion/Masking/Sensor | read directly from the four persona program ratings |
| Program Mp size | Rating² × Multiplier |

---

### Decker YAML Structure

```yaml
name: HeadCrash
intelligence: 6
body: 4
willpower: 5
reaction: 5
computer_skill: 6
cyberdeck:
  model: Renraku Kraftwerk-8    # resolved against decks.yaml catalog; fields below override catalog defaults
  mpcp: 8
  hardening: 4
  active_memory: 1000           # Mp
  storage_memory: 2000          # Mp
  io_speed: 360                 # Mp per Combat Turn
  response_increase: 2          # max = min(3, floor(mpcp / 4)) = 2
  persona_programs:             # each ≤ mpcp; sum ≤ mpcp × 3 = 24
    bod: 6
    evasion: 6
    masking: 6
    sensor: 6
  utilities:
    - type: Deception            # operational; multiplier 2
      rating: 4                 # Mp = 4² × 2 = 32
    - type: Sleaze               # special; multiplier 3
      rating: 5                 # Mp = 5² × 3 = 75
    - type: Analyze              # operational; multiplier 3
      rating: 4                 # Mp = 4² × 3 = 48
    - type: Attack               # offensive; multiplier 4 (Serious)
      damage_level: Serious
      rating: 6                 # Mp = 6² × 4 = 144
    - type: Armor                # defensive; multiplier 3
      rating: 5                 # Mp = 5² × 3 = 75
# Total utility storage: 32 + 75 + 48 + 144 + 75 = 374 Mp (fits in 2000 Mp)
#
# Calculated by application:
#   hacking_pool    = floor((6 + 8) / 3) = 4
#   detection_factor = ceil((6 + 5) / 2) = 6
#   persona.reaction = 5 + (2 × 2) = 9
```

---

### Decker Initialization Sequence

1. Parse `<decker_name>.yaml`.
2. Instantiate the `Decker` with physical stats.
3. Instantiate the `Cyberdeck` with hardware values.
4. Instantiate the four `PersonaPrograms`; validate against MPCP constraints.
5. Validate Response Increase against MPCP cap.
6. Instantiate each `Utility`; calculate Mp sizes; validate total against Storage Memory.
7. Derive and attach the `Persona` (Bod/Evasion/Masking/Sensor from persona programs; Reaction from base + Response Increase; Hacking Pool and Detection Factor computed lazily or eagerly).

---

## LTG Address Format

```
UCAS-SEA-2206
UCAS-SEA-4206
CFS-LAX-1101
```

Individual LTG entries in the YAML carry an `id` (e.g. `UCAS-SEA`) which is the base address. Specific node numbers within that LTG are allocated at runtime or can be enumerated under a `nodes` list if deterministic addressing is required.
