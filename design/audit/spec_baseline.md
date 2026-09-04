# Spec Baseline — Matrix of Shadowrun

Distilled comparison baseline (align.md Iteration 0). **Supplements, never replaces** reading the
PRDs/design docs in full each session. Source of truth: `prd_core.md`, `prd_game.md`, `prd_ui.md`,
`protocol.md`. Line refs are into those files.

## Calculated persona/deck values (prd_core §Decker, CD-02)
- Hacking Pool = ⌊(Intelligence + MPCP) ÷ 3⌋. May be added to Matrix tests (optional — see GL-1).
- Detection Factor = ⌈(Masking + Sleaze) ÷ 2⌉; no Sleaze → ⌈Masking ÷ 2⌉ (L119, CD-18).
- effectiveDetectionFactor = max(2, DF − suppressionDfPenalty) (CD-18a).
- Persona Reaction = base Reaction + Response Increase×2 (L120).
- Initiative dice = Persona Reaction + (1 + Response Increase)D6 (CD-02, CC-05).
- Non-combat actions/turn = ⌈Persona Reaction ÷ 10⌉ + Response Increase (SO-01/02, prd_game L19).
- Response Increase ≤ min(3, ⌊MPCP÷4⌋); +2 Reaction & +1D6 init per point (L106, CD-02).

## Cyberdeck constraints
- Persona program rating ≤ MPCP; Σ(4 programs) ≤ MPCP×3 (L102, L107).
- Utility rating ≤ MPCP (CD-01). Utility Mp = Rating² × Multiplier (L108).
- Active mem limits running utilities; Σ active `active:true` ≤ Active Memory (CD-05).
- Upload turns = ⌈Mp ÷ ioSpeed⌉ (CD-10). Pending upload = no effect (CD-12).
- Cyberterminal: MPCP ≤ 4, no Response Increase, all utility ratings −1, immune to black IC/dump shock, ~10% cost (CT-01..05).

## System Test (SystemTestResolver)
- Decker rolls (Computer Skill [+ Hacking Pool]) dice vs TN = max(2, accessRating − utilityRating) (CD-14).
- Host rolls Security Value dice vs decker's effectiveDetectionFactor.
- Decker wins iff deckerSuccesses ≥ hostSuccesses (ties → decker) (M-04).
- Host successes ALWAYS added to security tally, regardless of winner (M-05).
- Utility→operation TN-reduction map: CD-15 table (Analyze/Browse/Commlink/Deception/Decrypt/Read-Write/Relocate/Scanner/Spoof). TN floor 2.

## Utility multipliers (Mp = Rating²×mult)
Analyze×3 Browse×1 Commlink×1 Deception×2 Decrypt×1 Read/Write×2 Relocate×2 Scanner×3 Spoof×3;
Sleaze×3 Track×8; Attack L/M/S/D ×2/3/4/5, Black Hammer×20, Killjoy×10, Slow×4; Armor×3 Cloak×3 Lock-On×3 Medic×4.

## Degradation
- Armor −1 currentRating when damage bleeds through (CD-19).
- Medic −1 currentRating per invocation regardless of outcome (CD-20, L114).
- currentRating vs storedRating; effects use currentRating (CD-21). currentRating→0 auto-unload + deplete (CD-22).

## Interrogation (SO-05..09): net successes accumulate; ≥5 locates (Locate Slave: ≥3, L335). Query precision TN mod: VERY_VAGUE +2, VAGUE +1, NORMAL 0, SPECIFIC −1, VERY_SPECIFIC −2 (SO-07).

## Combat
- Attack Test uses offensive utility rating (+Hacking Pool); IC uses host Security Value pool, IC rating = weapon (CC-23).
- Attack TN by security code × intruder/legit (CC-24 table): Blue 6/3, Green 5/4, Orange 4/5, Red 3/6.
- Damage resist: Bod dice vs TN=Power; staging ±1 per 2 net successes (CC-28/29). Armor reduces Power.
- Condition Monitor: single 10-box track; full → crash/dump (CC-30).
- IC damage level: Blue/Green Moderate, Orange/Red Serious (CC-27, ICC-02).
- Crash IC → +IC rating to tally (CC-21) unless suppressed (CC-22, −1 DF each).
- IC Initiative: Blue 1D6, Green 2D6, Orange 3D6, Red 4D6, +IC Rating (CC-07).
- Dump shock: Body resist vs TN=SV; Blue Light/Green Mod/Orange Serious/Red Deadly, Stun→Mental (CC-32).
- Simsense overload (white/gray IC): Willpower TN Light 2/Mod 3/Serious 5; Deadly auto-crash; not black IC (CC-31).

## IC / ICC (sealed variants expected)
Crippler (Acid→Bod, Binder→Evasion, Jammer→Sensor, Marker→Masking; ICC-01);
Killer (ICC-02); Probe (reactive, IC-rating dice vs DF, adds to tally per System Test; ICC-03);
Scramble (reactive; ICC-04); Tar Baby (ICC-05); Blaster (ICC-06); Ripper (Acid-Rip/Bind-Rip/Jam-Rip/Mark-Rip; ICC-07);
Sparky (ICC-08); Tar Pit (ICC-09); Black IC pin (ICC-10); Lethal (ICC-11)/Non-Lethal (ICC-12) Black IC;
Black Hammer (ICC-13); Killjoy (ICC-14); Slow (reactive IC immune; ICC-15).

## Alert (AL-01/02): Passive → all 5 subsystems +2 (not reversed); Active → may spawn security decker NPCs.

## Movement (M-01..18): jackpoint→entry rules; tally persistence M-09 (RTG+LTGs share), M-10 (new RTG fresh), M-11 (PLTG inherits RTG tally); graceful logoff clears traces; jack out free unless black-IC pinned.

## Wire protocol (protocol.md)
- Endpoint `ws://<host>/decker/ws`. Messages JSON with `type` discriminator.
- Server→client: control/state/result/error. Client→server: join/action.
- ControlMessage{type,role,deckerName?,reconnectToken?}. reconnectToken non-null only for registered_decker (UI-01..04).
- ResultMessage: deckerSuccesses & hostSuccesses ALWAYS present (non-null).
- ActionCommand{type,actionIndex,params?}. params per op: LOCATE_* {precision,query}, EDIT_FILE {newContent}, TAP_COMCALL {scannerDeviceRating 0-10}, MAKE_COMCALL {hasValidPasscode}, UPLOAD_DATA {dataSize=100}, NULL_OPERATION {inactivitySeconds=0}.
- AvailableActionDto sealed by `kind`: LogonToRtg/Ltg/Pltg/Host, GracefulLogoff, JackOut, Operation{operation,targetKind,targetName,paramKind}.
- MatrixObjectDto sealed by `kind`: GridNode/LocalGrid/PrivateGrid/HostNode/HostSubsystem/IcProgram/File/Device (field lists at protocol L205-213).
- IcProgram: analyzed:Boolean, rating:Int? (null until analyzed), behavior:String?, guardedNodeType:String? (null until analyzed).
- Error codes: not_your_turn, no_action_pending, already_registered, name_already_taken, name_too_long, unknown_message_type, bad_request, server_full (MAX_CONNECTIONS 32). Timeout 120s.
- Deferred ops never in availableActions: LOCATE_DECKER, SWAP_MEMORY.

## Deck catalog (CD-25) — verify decks.yaml verbatim
Allegiance Sigma 3/1/200/500/100/14000; Sony CTY-360-D 5/3/300/600/200/70000; Novatech Hyperdeck-6 6/4/500/1000/240/125000;
CMT Avatar 7/4/700/1400/300/250000; Renraku Kraftwerk-8 8/4/1000/2000/360/400000; Transys Highlander 9/4/1500/2500/400/600000;
Novatech Slimcase-10 10/5/2000/2500/480/960000; Fairlight Excalibur 12/6/3000/5000/600/1500000.
(MPCP/Hardening/ActiveMem/StorageMem/IO/Cost)

## Open forks / findings
- **GL-1**: RESOLVED (2026-09-03, Option B) — Hacking Pool is optional (PRD "may"); the Align XV
  auto-application at 32 System Test call sites was reverted, resolver keeps opt-in param. Tree green.
- **GL-2**: RESOLVED — `resolveSlow` test stub recalibrated (assertion was unsatisfiable with all-zero roller).
