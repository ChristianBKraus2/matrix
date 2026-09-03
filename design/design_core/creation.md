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

**RTG-level PLTG replication:** PLTGs declared under an RTG in `grid.yaml` (rather than under a specific LTG) are replicated to **all** child LTGs of that RTG at load time. This means every LTG belonging to the RTG exposes those PLTGs as reachable destinations. PLTGs declared directly under an LTG are attached only to that LTG and are not propagated upward.

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

`grid.yaml` defines the grid hierarchy — RTGs, LTGs, and PLTGs with their ratings. Each LTG lists its hosts under a `hosts:` key using one of two formats:

- **External config:** `{ name, config: hosts/filename.yaml }` — full host data lives in a separate file.
- **Inline:** all host fields declared directly under the host entry in `grid.yaml`.

Both formats can be mixed within the same LTG. The host's LTG is determined by its position in `grid.yaml`; host config files carry no `ltg:` back-reference field.

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
          - name: Mitsuhama Pagoda
            config: hosts/MitsuhamaPagoda.yaml       # external config file
          - name: Lone Star GridSec Seattle           # inline
            security: Orange-7
            ratings: { access: 9, control: 9, index: 8, files: 8, slave: 8 }
            intrusion_difficulty: HARD
            topology: OPEN_ACCESS
          - name: Renraku Public Relations
            security: Green-5
            ratings: { access: 7, control: 8, index: 7, files: 7, slave: 7 }
            intrusion_difficulty: EASY
            topology: OPEN_ACCESS
      - id: UCAS-CHI
        region: Chicago
        hosts:
          - name: Ares Macrotechnology Chicago Branch
            security: Orange-5
            ratings: { access: 8, control: 8, index: 7, files: 7, slave: 7 }
            intrusion_difficulty: AVERAGE
            topology: OPEN_ACCESS
    pltgs:
      - id: UCAS-PLTG-ARES
        owner: Ares Macrotechnology
        security: Orange-6
        ratings: { access: 9, control: 9, index: 8, files: 8, slave: 8 }

  # Additional RTGs follow the same structure — ratings, ltgs (inline or external-config hosts), optional pltgs.
  # See grid.yaml for the complete current set.
```

---

## Host YAML Structure

Hosts with complex configurations (nodes, IC programs, data files, etc.) are defined in separate files under `src/main/resources/hosts/`. Simpler hosts can be defined inline in `grid.yaml`. The host's LTG association is determined by its position in `grid.yaml`; host config files carry no `ltg:` back-reference field.

Top-level fields in a host file:

| Field | Required | Description |
|---|---|---|
| `name` | yes | Host display name |
| `security` | yes | `SecurityCode-SecurityValue` (e.g. `Orange-6`) |
| `ratings` | yes | `{ access, control, index, files, slave }` |
| `intrusion_difficulty` | yes | `EASY`, `AVERAGE`, or `HARD` |
| `topology` | yes | `OPEN_ACCESS`, `TIERED`, `HOST_HOST`, or `PRIVATE_GRID` |
| `nodes` | no | List of `{ type, description }` overrides; defaults to all five subsystem types with empty descriptions if omitted |
| `sans` | no | List of SAN objects |
| `ic_programs` | no | List of IC program objects |
| `data_files` | no | List of data file objects |
| `remote_devices` | no | List of remote device objects |
| `security_sheaf` | no | Trigger step configuration |
| `offline` | no | `true` for physically isolated hosts |

```yaml
# src/main/resources/hosts/MitsuhamaPagoda.yaml
name: Mitsuhama Pagoda
security: Orange-6
ratings: { access: 8, control: 8, index: 8, files: 8, slave: 8 }
intrusion_difficulty: AVERAGE
topology: OPEN_ACCESS

nodes:
  - type: ACCESS
    description: Corporate access control system
  - type: CONTROL
    description: Facility management and automation
  - type: INDEX
    description: Employee and resource directory
  - type: FILES
    description: Corporate document archive
  - type: SLAVE
    description: Physical security and sensor network

sans:
  - name: Main SAN
    scramble_protected: false

ic_programs:
  - type: Probe
    rating: 5
  - type: Killer
    rating: 6
    guarded_node: FILES

data_files:
  - name: Personnel Records
    scramble_protected: true
    size_mp: 10

security_sheaf:
  trigger_steps:
    - tally_threshold: 5
      description: Probe IC deployed to locate intruder
      activated_ic:
        - type: Probe
          rating: 5
    - tally_threshold: 10
      description: Passive alert triggered; Killer IC deployed
      alert_transition: PASSIVE_ALERT
      activated_ic:
        - type: Killer
          rating: 6
    - tally_threshold: 20
      description: Active alert; security decker dispatched
      alert_transition: ACTIVE_ALERT
      security_decker_count: 1
```

The `alert_transition` values must match the `AlertStatus` enum exactly: `PASSIVE_ALERT` or `ACTIVE_ALERT`. The `topology` value must match the `TopologyType` enum: `OPEN_ACCESS`, `TIERED`, `HOST_HOST`, or `PRIVATE_GRID`.

Offline hosts carry an additional `offline: true` flag (see Offline Hosts section below).

### Initialization Sequence

1. Parse `grid.yaml`; instantiate all RTG, LTG, and PLTG objects.
2. For each host entry under a LTG, either load from the referenced `config:` file or instantiate inline.
3. Register the host under its parent LTG.

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

> **Superseded.** The canonical 10-step sequence is in `design_core/cyberdeck_and_program_mechanics.md` — see the *Updated Decker Initialization Sequence* section there. The 7-step sequence below is retained for historical reference only; follow the 10-step sequence for all implementation work.

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
