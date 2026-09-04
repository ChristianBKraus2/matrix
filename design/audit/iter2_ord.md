# Iteration 2 Audit — design/design_core/ord.md

| File | Lines | Verbatim excerpts | Notes/findings |
|---|---|---|---|
| `design/design_core/ord.md` | 612 | **(1) L66:** `Equality is based on the name field alone for RTG, LTG, PLTG, and Host. For DataFile, equality is based on name, isScrambleProtected, and sizeMp (pointerToHost and pointerTargetFile are excluded to prevent recursive equality).` **(2) L254:** `**Host → AlertStatus** (1:1) — The host tracks its current alert state (No Alert → Passive Alert → Active Alert); Passive Alert raises all Subsystem Ratings by 2.` **(3) L450-452:** `class PersonaProgram {` / `+attributeType PersonaAttributeType` / `}` | Domain object-model reference. Object catalogue + relationships + two mermaid ERDs. 7 candidate findings — enum-name and field-name inconsistencies between prose field lists, Implementation Notes, and the ERD. |

## Distilled spec additions

### Network infrastructure (L3-66)
- **Matrix**: root of engine (L5).
- **Grid** (abstract base; subtypes RTG, LTG, PLTG) (L7). Fields: Security Code = `Blue | Green | Orange | Red` (L9); Security Value = integer 4–12+ (L10); Subsystem Ratings = `Access, Control, Index, Files, Slave` (L11); Security Tally (L12); Alert Status = `No Alert | Passive Alert | Active Alert` (L13).
- **RTG**: largest grid, covers countries/regions (L15).
- **LTG**: covers cities; references parent RTG; ratings normally equal parent RTG (L17).
- **PLTG**: private/corporate, closed, dedicated fiber, carries security flags from parent RTG (L19).
- **Host** (L21): Security Code + Value; Subsystem Ratings (Access, Control, Index, Files, Slave); Intrusion Difficulty = `Easy | Average | Hard` (L26); Topology Type = `Open Access | Tiered | Host-Host | Private Grid` (L27); Security Sheaf (ordered TriggerSteps); Alert Status; Security Tally; Reset timing varies by Security Code (L22-30).
- **SAN** (System Access Node): entry-point icon; may be protected by Scramble IC (L32-34).
- **Node**: subsystem within host; Subsystem type = `Access | Control | Index | Files | Slave` (L36-38).
- **SecuritySheaf**: ordered list of TriggerSteps; if one tally accumulation reaches ≥ two trigger steps at once, all fire simultaneously (L40-43).
- **Jackpoint**: Type = `legal-access | illegal-access | workstation | console | remote-device | telecom | illegal junction-box`; connects to an LTG or directly to a Host (L45-49).
- **RemoteDevice**: Name; SystemAddress: string (unique within Slave subsystem); device-kind labels are free-form strings, not a typed enum (L50-54).
- **DataFile** (L56-62): Name; ScrambleProtected: bool; IsPointer: bool; pointerToHost: Host? (non-null when isPointer=true); pointerTargetFile: DataFile? (may chain).
- **Implementation Notes (L64-66):** RTG/LTG/PLTG/Host/DataFile are Kotlin data classes with overridden equals/hashCode/toString to prevent recursion. Equality by `name` alone for RTG/LTG/PLTG/Host; DataFile equality by `name, isScrambleProtected, sizeMp`. `copy()` unaffected.

### Decker / Cyberdeck / Persona (L70-109)
- **Decker**: Intelligence, Body, Willpower, Reaction, Computer Skill (optional Decking spec); Hacking Pool = `floor((Intelligence + MPCP) ÷ 3)`, addable to any Matrix test except Body/Willpower resisting gray/black IC physical damage (L79); Physical Condition Monitor (10 boxes); Mental Condition Monitor (10 boxes); Suppressed IC list — each entry reduces Detection Factor by 1 (L82).
- **Cyberdeck**: MPCP Rating (caps persona program ratings; max total persona ratings = MPCP × 3); Hardening; Active Memory (Mp); Storage Memory (Mp); I/O Speed (Mp per Combat Turn); Response Increase (0–3 points, max = ⌊MPCP ÷ 4⌋, each = +2 Reaction and +1D6 Initiative, L91); Detection Factor = `(Masking + Sleaze) ÷ 2 rounded up; or Masking ÷ 2 if no Sleaze running` (L92); Cost (nuyen).
- **Cyberterminal ("Tortoise")** (L95-99): NOT a Cyberdeck subclass; factory function returning a `Cyberdeck` (data class ⇒ final). Max MPCP 4; no Response Increase; all programs run at −1 Rating; user unharmed by Black IC or Dump Shock.
- **Persona** (L101-108): Bod, Evasion, Masking, Sensor (each driven by corresponding persona program); Condition Monitor (10 boxes); Status = `Legitimate | Intruding`.

### Programs (L114-131)
- **Program** (abstract): Rating; Mp size = `Rating² × Multiplier` (L116).
- **PersonaProgram** (extends Program): attributeType: PersonaAttributeType = `Bod | Evasion | Masking | Sensors` (L121); Constraint Rating ≤ MPCP, sum of four ≤ MPCP × 3 (L122).
- **Utility** (extends Program): Category = `Operational | Special | Offensive | Defensive` (L127); Multiplier. Operational (TN reducers): Analyze ×3, Browse ×1, Commlink ×1, Deception ×2, Decrypt ×1, Read/Write ×2, Relocate ×2, Scanner ×3, Spoof ×3 (L128). Special: Sleaze ×3, Track ×8 (L129). Offensive: Attack ×2/3/4/5 by damage level, Black Hammer ×20, Killjoy ×10, Slow ×4 (L130). Defensive: Armor ×3, Cloak ×3, Lock-On ×3, Medic ×4 (L131).

### IC (L135-179)
- **IC** (abstract): color via sealed hierarchy (WhiteIC/GrayIC/BlackIC), NOT an explicit field (L137); Rating; Behavior = `Proactive | Reactive`; Initiative = `NxD6 + IC Rating`, N = 1/2/3/4 for Blue/Green/Orange/Red hosts (L141).
- **WhiteIC** (attacks persona icon only): Crippler (TargetAttribute = Bod/Acid, Evasion/Binder, Sensor/Jammer, Masking/Marker, L147); Killer; Probe (reactive, non-combat, raises tally on detect); Scramble (reactive, placement, destroys data); TarBaby (reactive, TargetCategory = `Operational | Offensive | Defensive | Special`, L157).
- **GrayIC** (attacks deck/utilities, permanent): Blaster; Ripper (TargetAttribute = Bod/Acid-Rip, Evasion/Bind-Rip, Sensor/Jam-Rip, Masking/Mark-Rip, L165); Sparky; TarPit (reactive, TargetCategory = `Operational | Offensive | Defensive | Special`, L171).
- **BlackIC** (ASIST biofeedback): LethalBlackIC (Physical, resisted by Body + Hardening); NonLethalBlackIC (Stun/Mental, resisted by Willpower + Hardening) (L175-177).
- Note (L179): `Black Hammer` and `Killjoy` are Offensive Utilities, NOT IC.

### System Operations (L185-193)
- **SystemOperation**: Name; System Test type = `Access | Control | Index | Files | Slave`; Associated Utility (optional TN reducer); Action type = `Free | Simple | Complex`; Category = `Standard | Interrogation | Ongoing | Monitored` (L187-191).
- **27 named operations** (L193): Analyze Host, Analyze IC, Analyze Icon, Analyze Security, Analyze Subsystem, Control Slave, Decrypt Access, Decrypt File, Decrypt Slave, Download Data, Edit File, Edit Slave, Graceful Logoff, Locate Access Node, Locate Decker, Locate File, Locate IC, Locate Slave, Logon to Host, Logon to LTG, Logon to RTG, Make Comcall, Monitor Slave, Null Operation, Relocate Icon, Tap Comcall, Upload Data. (Verified: list count = 27, matches stated total.)

### Combat (L199-219)
- **CombatTurn**: 3-second round, Initiative order (L199). Decker Initiative = Reaction + 1D6 (+ Response Increase); IC = NxD6 + IC Rating (L204-205).
- **CombatManeuver** (Simple Action): Types = `Evade Detection, Parry Attack, Position Attack`; resolved as opposed Evasion vs. Sensor test (L207-210).
- **DamageLevel** = `Light | Moderate | Serious | Deadly` (L211).
- **ConditionMonitor**: 10 boxes (L213).
- **DumpShock**: Stun on involuntary jack-out/persona crash; Power = host Security Value; Level by Security Code (L215-217).
- **SimsenseOverload**: Stun to physical body from White/Gray IC hits; resisted by Willpower (L219).

### Accessories (L225-227)
- **Accessory** Types: Off-line storage, Vid-screen display, Hitcher jack (observers cannot be harmed by IC).

### Key relationships (L231-321)
- Matrix→RTG 1:many; RTG→LTG 1:many (LTG ratings = parent RTG; switching LTGs within same RTG retains SecurityTally, L236); RTG↔RTG many:many (crossing RTG resets SecurityTally, L237); LTG→PLTG 0:many entry points; PLTG→Host 1:many; PLTG inherits SecurityTally from RTG; Grid→SecuritySheaf 1:1 (L235-241).
- Host→Grid via SAN many:1 (open access); Host→Host via SAN (tiered many:many; host-host many:many); Host→SAN 1:1..many; Host→Node **1:5** (exactly one per subsystem type, L249); Host→SecuritySheaf 1:1; Host→IC 1:many (Security Value = IC dice pool); Host→DataFile 1:many; Host→RemoteDevice 1:many; Host→AlertStatus 1:1 (Passive Alert raises all Subsystem Ratings by 2, L254).
- SecurityTally per (Decker × Host/Grid); persists through run; resets by SecurityCode (Blue fastest, Red slowest); crashing IC adds IC Rating to tally unless suppressed (CC-22); entering mid-reset host starts at reduced value not 0 (L276).
- Decker→Cyberdeck 1:1; Decker→Jackpoint 1:1; Cyberdeck→Persona 1:1; Cyberdeck→PersonaProgram 1:4 exactly (Bod/Evasion/Masking/Sensors, each ≤ MPCP, sum ≤ MPCP×3, L285); Cyberdeck→Utility (Active + Storage capacity-limited); Persona→ConditionMonitor 1:1.
- SecurityDecker (NPC): under Active Alert a TriggerStep may spawn security deckers (L294).

### ERD-declared field names (L327-535)
- **Grid / Host** fields: `SecurityCode, SecurityValue int, AccessRating int, ControlRating int, IndexRating int, FilesRating int, SlaveRating int, SecurityTally int, AlertStatus` (+Host: `IntrusionDifficulty, TopologyType`) (L331-368).
- **TriggerStep**: `TallyThreshold int, SecurityDeckerCount int` (L346-349).
- **AlertTransition**: `NewAlertStatus` (L350-352).
- **DataFile** (ERD): `Name string, ScrambleProtected bool, IsPointer bool, PointerTargetHost Host, PointerTargetFile DataFile` (L373-379).
- **RemoteDevice** (ERD): `Name string, SystemAddress string` (L380-383).
- **Decker** (ERD): `Intelligence, Body, Willpower, Reaction, ComputerSkill, HackingPool` (all int) (L422-429).
- **Cyberdeck** (ERD): `MPCP, Hardening, ActiveMemory, StorageMemory, IOSpeed, ResponseIncrease` (all int) (L430-437).
- **Persona** (ERD): `Bod int, Evasion int, Masking int, Sensor int, Status` (L439-445).
- **Program** (ERD): `Rating int, MpSize int` (L446-449).
- **ConditionMonitor** (ERD): `damage int` (L416-418).
- Inheritance edges (L468-488) and cardinalities (L490-534) mirror the prose relationships.

## Candidate findings

**DOC-1 — PersonaAttributeType enum variant `Sensors` (plural) contradicts singular `Sensor` used everywhere else.**
- L121: `attributeType: PersonaAttributeType (Bod | Evasion | Masking | Sensors)` and L285: `The four PersonaPrograms (Bod, Evasion, Masking, Sensors)`.
- Contradicts Persona field L107 (`Sensor (driven by Sensor persona program)`), Crippler L147 (`Sensor (Jammer variant)`), Ripper L165 (`Sensor (Jam-Rip variant)`), CombatManeuver L210 (`opposed Evasion vs. Sensor test`), L290 (`Sensor ratings are read directly`), and ERD L443 (`+Sensor int`). Only the PersonaAttributeType enum spells it `Sensors`. Later Kotlin comparison needs one canonical spelling.

**DOC-2 — DataFile pointer-to-host field name differs between prose and ERD.**
- Prose L61: `pointerToHost: Host? — the host where the actual data resides`.
- ERD L377: `+PointerTargetHost Host`. Two different field names for the same concept (`pointerToHost` vs `PointerTargetHost`). (The sibling field `pointerTargetFile`/`PointerTargetFile` is consistent, L62/L378.)

**DOC-3 — DataFile field `sizeMp` used in equality spec but never declared as a field.**
- Implementation Notes L66: `For DataFile, equality is based on name, isScrambleProtected, and sizeMp`.
- The DataFile field list (L56-62: Name, ScrambleProtected, IsPointer, pointerToHost, pointerTargetFile) and the ERD DataFile class (L373-379) contain no `sizeMp` field. The equality contract references a field the type model does not define — staleness/omission.

**DOC-4 — DataFile scramble-protection field name casing inconsistent.**
- L59: `ScrambleProtected: bool`; ERD L376: `+ScrambleProtected bool`; but Implementation Notes L66: `isScrambleProtected`. Three-way the prose/ERD use `ScrambleProtected` while the equality note uses `isScrambleProtected` (Kotlin boolean-property convention). Ambiguous which the Kotlin field is named.

**DOC-5 — Jackpoint Type variants `legal-access` and `illegal-access` have no connection mapping.**
- L47 declares Type = `legal-access | illegal-access | workstation | console | remote-device | telecom | illegal junction-box`.
- Relationships map only `telecom`/`illegal-junction-box` → LTG (L282) and `workstation`/`console`/`remote-device` → Host (L283). `legal-access` and `illegal-access` are never connected to a grid or host — either dead enum variants or a missing relationship.

**DOC-6 — PersonaProgram has no Multiplier, yet Program.MpSize = Rating² × Multiplier depends on one.**
- Program (abstract) L116: `Mp size = Rating² × Multiplier`. Only Utility declares `Multiplier` (L125, and multiplier tables L128-131). PersonaProgram (L119-122) lists only `attributeType` and constraints — no multiplier — so its Mp size is undefined. Either PersonaProgram needs a multiplier value or the base formula does not apply to it.

**DOC-7 — Utility.Category variant order differs from TarBaby/TarPit TargetCategory (set matches, order does not).**
- Utility Category L127: `Operational | Special | Offensive | Defensive`.
- TarBaby TargetCategory L157 and TarPit L171: `Operational | Offensive | Defensive | Special`. Same four members, reordered. Low severity (enum member set is identical) but flagged for canonical-ordering consistency when compared against Kotlin enum declaration order.
