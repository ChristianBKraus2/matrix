# Iteration 2 Audit — cyberdeck_and_program_mechanics.md

## Coverage table

| File | Lines | Verbatim excerpts | Notes/findings |
|---|---|---|---|
| `design/design_core/cyberdeck_and_program_mechanics.md` | 631 | (1) L107: `require(activeUtilities.all { it.rating <= mcpRating }) {` — (2) L331: `get() = (decker.intelligence + cyberdeck.mcpRating) / 3` — (3) L502: `require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01); got $mcpRating" }` | Read in full L1→L631. 7 candidate findings (DOC-1..7). Mp-size formula and Armor degradation rule are referenced but never specified in this doc; two internal contradictions in the SystemTestResolver algorithm. |

Excerpts are ≥50 source lines apart (L107, L331, L502), one per third of the 631-line file.

---

## Distilled spec additions

**New types**
- `PendingUpload(utility: Utility, turnsRemaining: Int)` — file `decker/ActiveMemory.kt` (L13-20). `turnsRemaining` decremented at start of each Combat Turn by `Decker.advanceCombatTurn()`; at 0 the utility moves into `activeUtilities` (CD-11).
- `LoadUtilityResult` sealed class — file `decker/ActiveMemory.kt`. Variants: `Success(decker: Decker)`; `InsufficientMemory(decker: Decker, requiredMp: Int, availableMp: Int)` (L31-41, CD-07/CD-08).
- `DeckCatalogEntry(model: String, mpcp: Int, hardening: Int, activeMemoryMp: Int, storageMemoryMp: Int, ioSpeedMpPerTurn: Int, costNuyen: Int)` — file `config/DeckCatalogEntry.kt` (L53-61, CD-24). No `responseIncrease` field by design (L64).

**Utility fields** (`programs/Utility.kt`, L77-83): `type: UtilityType`, `rating: Int`, `attackDamageLevel: DamageLevel? = null`, `currentRating: Int = rating`, `sourceCode: Boolean = false`. `rating` = immutable stored (from YAML); `currentRating` starts = `rating`, decremented by degradation (CD-19/CD-20). All game effects (TN reduction, DF, Armor absorption, Medic power) use `currentRating` (L86). Degraded copy: `utility.copy(currentRating = utility.currentRating - 1)` (L88).

**Cyberdeck** (`decker/Cyberdeck.kt`):
- New field `pendingUploads: List<PendingUpload> = emptyList()` (L101).
- init requires (L107-118): `activeUtilities.all { it.rating <= mcpRating }`; `storedUtilities.all { it.rating <= mcpRating }`; `activeMp <= activeMemoryMp`; `storageMp <= storageMemoryMp`.
- Computed `usedActiveMemoryMp = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }` (L124-126); `freeActiveMemoryMp = activeMemoryMp - usedActiveMemoryMp` (L127-129).
- Method `detectionFactor(maskingRating: Int, sleazeRating: Int? = null): Int` (L136): if sleaze non-null `ceil((maskingRating + sleazeRating) / 2)` else `ceil(maskingRating / 2)` (L139, CD-18).
- Field `hitchers: List<HitcherObserver>` default empty (L580); `HitcherObserver(name: String)` (L583).

**SystemOperation** (`operations/SystemOperation.kt`): add `RELOCATE_ICON(CONTROL, UtilityType.RELOCATE, SIMPLE, STANDARD)` (L150, CD-16).

**SystemTestResolver** (`operations/SystemTestResolver.kt`, L163-172): signature `resolve(decker, operation: SystemOperation, targetNumber: Int, hostSecurityValue: Int, diceRoller: DiceRoller): SystemTestOutcome`. Algorithm (L176-182):
1. Look up `operation.utility` (e.g. DECEPTION, ANALYZE, null for SWAP_MEMORY).
2. Match `Utility` in `activeUtilities` where `type == operation.utility`; pending uploads do NOT qualify; found → `modifier = utility.currentRating`, else `modifier = 0`.
3. Roll decker: `computerSkill` dice vs `max(2, targetNumber - modifier)` → `deckerSuccesses`.
4. Roll host: `hostSecurityValue` dice vs `decker.detectionFactor` → `hostSuccesses`.
5. `deckerWins = deckerSuccesses >= hostSuccesses`.
6. Return `SystemTestOutcome(deckerSuccesses, hostSuccesses, deckerWins)`.
TN floor of 2 applies to all operations (L184, CD-14). CT-03 helper `effectiveRating(utility, deck) = if (deck.isCyberterminal) max(0, utility.currentRating - 1) else utility.currentRating` (L530-533).

**DeckerLoader** (`config/DeckerLoader.kt`): `load(inputStream, catalog: List<DeckCatalogEntry> = emptyList()): Decker` (L200-203). Catalog values are defaults when `model:` matches; explicit YAML overrides; unknown model → warn + inline values (L206, CD-26). Parses per-utility `active: true` and `source_code: true` (L210). Partition: `active:true` → `activeUtilities` (currentRating = rating, no countdown), rest → `storedUtilities`; validate total active Mp ≤ activeMemoryMp (L214-219, CD-05/CD-06).

**Decker public methods** (all pure, return `.copy()`, log intention+outcome, L227):
- `loadUtility(utility): LoadUtilityResult` (L229, CD-07/08/10/12): (1) `turnsRequired = ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn)`; if `ioSpeedMpPerTurn <= 0` warn + `turnsRequired = 0`. (2) if `freeActiveMemoryMp < utility.mpSize` → `InsufficientMemory(this, utility.mpSize, freeActiveMemoryMp)`, no action spent. (3) add `PendingUpload(utility, turnsRequired)`. (4) return `Success`. Edge: `turnsRequired == 0` → add directly to `activeUtilities` (L246). Pending Mp counts against Active Memory immediately (L248).
- `unloadUtility(utility): Decker` (L252, CD-09): remove match from `activeUtilities` OR `pendingUploads` (by type); `storedUtilities` copy untouched, retains its own `currentRating`; memory freed immediately.
- `swapUtility(toUnload, toLoad): LoadUtilityResult` (L269, CD-13): unload then load against freed capacity; one Simple Action total.
- `advanceCombatTurn(): Decker` (L285, CD-11/CD-22): (1) decrement each `turnsRemaining` by 1; (2) move `turnsRemaining==0` uploads to `activeUtilities`; (3) any active utility with `currentRating <= 0` auto-unloaded from BOTH `activeUtilities` and `storedUtilities`, log depletion (CD-22).

**Detection Factor dynamic** (L306-319): `Decker.detectionFactor` computed property reading `cyberdeck.activeUtilities` each call; Sleaze counts only when fully active (not pending). `effectiveDetectionFactor = detectionFactor - suppressionDfPenalty` (L317-319).

**Hacking Pool** (L329-338): `hackingPool = (decker.intelligence + cyberdeck.mcpRating) / 3`. Addable to any Matrix test; NOT to Body/Willpower resist vs gray/black IC (ICC-11). Exposed on `Decker`, passed into `ManeuverParticipant`/`AttackParticipant` by callers; `CombatResolver` does not read it directly (L338).

**Medic** (`decker/DeckerOperationsExtensions.kt` `invokeMedic(diceRoller): MedicResult`; `MedicResult(updatedDecker, boxesRepaired, medicRating)` in `decker/MedicResult.kt`, L346-360). Algorithm (L363-374): TN by CM state — Light 1-3→TN4, Moderate 4-6→TN5, Serious 7-9→TN6, Deadly 10→cannot use return `MedicResult(this,0,medic.currentRating)`; roll `medic.currentRating` dice vs TN → successes; `boxesRepaired = successes` (floor 0); decrement `medic.currentRating` by 1 regardless of pass/fail (CD-20); if `<= 0` trigger CD-22 auto-unload. Complex Action.

**Catalog** (`config/DeckCatalogLoader.kt` `load(inputStream): List<DeckCatalogEntry>`, L385-388) — SnakeYAML like `GridLoader`, loaded once at startup, passed to `DeckerLoader.load`. `decks.yaml` (L399-457) 8 models: Allegiance Sigma (mpcp3), Sony CTY-360-D (5), Novatech Hyperdeck-6 (6), CMT Avatar (7), Renraku Kraftwerk-8 (8), Transys Highlander (9), Novatech Slimcase-10 (10), Fairlight Excalibur (12). YAML keys: `mpcp`, `hardening`, `active_memory`, `storage_memory`, `io_speed`, `cost_nuyen` (CD-25).

**Updated Decker Initialization Sequence (10 steps, verbatim, L467-476)** — replaces the 7-step in `creation.md`:
1. Load `decks.yaml` catalog via `DeckCatalogLoader` (once at startup; reused for all deckers).
2. Parse `<decker_name>.yaml`.
3. Resolve `model:` field against the catalog; apply catalog values as hardware defaults. Explicit YAML fields override catalog values.
4. Instantiate the `Decker` with physical stats.
5. Instantiate the `Cyberdeck` with hardware values.
6. Instantiate the four `PersonaPrograms`; validate ratings ≤ MPCP and sum ≤ MPCP × 3.
7. Validate Response Increase ≤ min(3, floor(MPCP ÷ 4)).
8. Instantiate each `Utility` with `rating`, `currentRating = rating`, `sourceCode` flag; validate `rating ≤ MPCP` (CD-01); calculate Mp sizes; validate total ≤ Storage Memory. Note: `currentRating` is not validated at load time — it starts equal to `rating` and can only decrease during play, so it is always within bounds at construction.
9. All utilities go into `storedUtilities`; those with `active: true` also go into `activeUtilities` (turnsRemaining = 0, fully uploaded). Validate total active Mp ≤ Active Memory.
10. Derive and attach the `Persona`: Bod/Evasion/Masking/Sensor from persona programs; Reaction = base + Response Increase × 2; Hacking Pool and Detection Factor computed lazily.

**Cyberterminal** (`decker/Cyberterminal.kt`, L491-516): standalone factory (NOT a subclass — `Cyberdeck` is a final data class) returning a `Cyberdeck` with `responseIncrease = 0`, `isCyberterminal = true`. `require(mcpRating <= 4)` (CT-01, L502). `isCyberterminal: Boolean = false` on `Cyberdeck` (L534) covers CT-03 rating reduction AND CT-04 dump-shock/black-IC immunity. `jackOut()`/`gracefulLogoff()` pass `dumpShock = !decker.cyberdeck.isCyberterminal` (L535). CT-05 cost ≈10% of equivalent deck (L537). Loader: `type: cyberterminal` in `decks.yaml` → instantiate `Cyberterminal`, else `Cyberdeck` (L541).

**Accessories** (`accessories/Accessory.kt` sealed, L556-570): `OfflineStorage(capacityMp: Int)`, `VidScreen` (object), `HitcherJack(type: HitcherJackType)`; `enum HitcherJackType { ELECTRODE_NET, DATAJACK_FEED }`. `DownloadDestination` sealed (ActiveMemory/StorageMemory/OfflineStorage) is defined but UNUSED — `DownloadHandle` has no `destination` field yet; downloads always route to deck storage (L590-600).

**Response Increase**: not a hardware/catalog property (L64); validated ≤ min(3, floor(MPCP÷4)) at init (step 7); Cyberterminal fixed 0; Reaction bonus = RI × 2 (step 10).

---

## Candidate findings

**DOC-1 — SystemTestResolver host roll uses `decker.detectionFactor`, not `effectiveDetectionFactor`.**
L180: `4. Roll host: \`hostSecurityValue\` dice vs \`decker.detectionFactor\` → \`hostSuccesses\`.`
Contradicts L314-319: "The effective Detection Factor passed to `SystemTestResolver` must subtract `decker.suppressionDfPenalty`" and the `effectiveDetectionFactor` property. The algorithm step names the un-suppressed `detectionFactor`, so IC suppression would silently have no effect. Internal contradiction.

**DOC-2 — SystemTestResolver TN modifier ignores the CT-03 cyberterminal rating reduction.**
L178: `If found: \`modifier = utility.currentRating\`; otherwise \`modifier = 0\`.`
Contradicts CT-03 (L525-533), which requires the TN-reduction rating be treated as `max(0, currentRating - 1)` when `deck.isCyberterminal`, and even defines `effectiveRating(utility, deck)` for exactly this. The main algorithm uses raw `currentRating` and never calls `effectiveRating`, so as written a cyberterminal gets full (un-reduced) TN modifier. Internal contradiction.

**DOC-3 — Mp size formula and multipliers are referenced but never defined in this doc.**
L125: `get() = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }`; L241-242 and step 8 L474 "calculate Mp sizes". `mpSize` / the size multiplier table is used pervasively yet no formula, per-utility multiplier, or definition appears anywhere in the document. Staleness/gap: the reference that later code (Utility.kt) compares against is absent here.

**DOC-4 — Armor degradation (CD-19) is in scope but has no specified algorithm.**
Purpose L5: "utility degradation (Armor and Medic)"; L86 cites CD-19/CD-20; Verification L620: "Armor-5 takes bleed-through damage → `armor.currentRating` decrements to 4 (CD-19)". No section specifies when/how Armor `currentRating` decrements (Medic gets a full algorithm at L363-374, Armor gets none). Gap/contradiction between stated scope and delivered spec.

**DOC-5 — Inconsistent MPCP naming: prose "MPCP" vs domain field `mcpRating` vs catalog field `mpcp`.**
L55: `val mpcp: Int,` (DeckCatalogEntry); L107/L110/L331/L502: `mcpRating`; prose and init steps 6-8 say "MPCP". Three spellings for one concept (`mpcp`, `mcpRating`, "MPCP"). The catalog→domain mapping (`mpcp` → `mcpRating`) is implicit and never stated; naming/staleness risk.

**DOC-6 — Two divergent names/semantics for active-memory usage.**
init require L113: `require(activeMp <= activeMemoryMp)` and L116 `storageMp`; but the computed property is L124 `usedActiveMemoryMp` (which *includes* `pendingUploads`). `activeMp` (implied: activeUtilities only) and `usedActiveMemoryMp` (activeUtilities + pendingUploads) differ in both name and inclusion of pending uploads, with no reconciliation. Ambiguity for the capacity check.

**DOC-7 — Cross-doc staleness pointer: "Replaces the 7-step sequence in creation.md".**
L465: `Replaces the 7-step sequence in \`creation.md\`.` The sequence here has 10 steps. If `creation.md` still carries the old 7-step sequence, the two docs contradict; flagged for verification against creation.md in a later iteration.
