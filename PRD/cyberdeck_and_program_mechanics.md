# Cyberdeck and Program Mechanics Design Document

## Purpose

This document specifies the design for implementing the cyberdeck and program mechanics requirements defined in `prd.md` (CD-01 through CD-26). It covers runtime active memory management, utility loading and unloading, upload countdown via I/O Speed, operational utility TN reduction, passive Sleaze behavior, utility degradation (Armor and Medic), and the cyberdeck catalog. IC interaction and combat are out of scope.

---

## New Types

### `PendingUpload` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/ActiveMemory.kt`

```kotlin
data class PendingUpload(
    val utility: Utility,
    val turnsRemaining: Int
)
```

PRD: CD-11. Tracks a utility that has been accepted into active memory but whose upload has not yet completed. `turnsRemaining` is decremented at the start of each Combat Turn by `Decker.advanceCombatTurn()`. When it reaches 0 the utility transitions into `activeUtilities` and becomes effective.

---

### `LoadUtilityResult` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/ActiveMemory.kt`

```kotlin
sealed class LoadUtilityResult {
    /** Utility accepted; now in pendingUploads with upload countdown running. */
    data class Success(val decker: Decker) : LoadUtilityResult()

    /** Insufficient active memory; decker state unchanged; no action economy spent. */
    data class InsufficientMemory(
        val decker: Decker,
        val requiredMp: Int,
        val availableMp: Int
    ) : LoadUtilityResult()
}
```

PRD: CD-07, CD-08.

---

### `DeckCatalogEntry` (data class)

**File:** `src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogEntry.kt`

```kotlin
data class DeckCatalogEntry(
    val model: String,
    val mpcp: Int,
    val hardening: Int,
    val activeMemoryMp: Int,
    val storageMemoryMp: Int,
    val ioSpeedMpPerTurn: Int,
    val costNuyen: Int
)
```

PRD: CD-24. Response Increase is absent by design — it is a per-decker configuration value, not a hardware property of the deck model.

---

## Changes to Existing Types

### `Utility`

**File:** `src/main/kotlin/com/shadowrun/matrix/programs/Utility.kt`

Add two fields to the constructor:

```kotlin
class Utility(
    val type: UtilityType,
    rating: Int,
    val attackDamageLevel: DamageLevel? = null,
    val currentRating: Int = rating,   // in-memory instance; degrades at runtime
    val sourceCode: Boolean = false
) : Program(...)
```

`rating` (inherited from `Program`) is the immutable stored rating — the value from the YAML, never changed at runtime. `currentRating` starts equal to `rating` and is decremented by degradation rules (CD-19, CD-20). All game-mechanical effects (TN reduction, Detection Factor contribution, Armor absorption, Medic repair power) use `currentRating`.

To produce a degraded copy: `utility.copy(currentRating = utility.currentRating - 1)`.

PRD: CD-03, CD-21.

---

### `Cyberdeck`

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt`

**Add one field:**

```kotlin
val pendingUploads: List<PendingUpload> = emptyList()
```

**Add to `init` block** (after existing persona program checks):

```kotlin
require(activeUtilities.all { it.rating <= mcpRating }) {
    "Utility rating exceeds MPCP: ${activeUtilities.first { it.rating > mcpRating }.type}"
}
require(storedUtilities.all { it.rating <= mcpRating }) {
    "Utility rating exceeds MPCP: ${storedUtilities.first { it.rating > mcpRating }.type}"
}
```

**Add computed properties** (used by `loadUtility` to check capacity):

```kotlin
val usedActiveMemoryMp: Int
    get() = activeUtilities.sumOf { it.mpSize } + pendingUploads.sumOf { it.utility.mpSize }

val freeActiveMemoryMp: Int
    get() = activeMemoryMp - usedActiveMemoryMp
```

PRD: CD-01, CD-11.

---

### `SystemOperation`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemOperation.kt`

Add one entry to the existing enum, alongside the other CONTROL operations:

```kotlin
RELOCATE_ICON(CONTROL, UtilityType.RELOCATE, SIMPLE, STANDARD),
```

PRD: CD-16. `UtilityType.RELOCATE` already exists; this registers the operation that activates it.

---

### `SystemTestResolver`

**File:** `src/main/kotlin/com/shadowrun/matrix/operations/SystemTestResolver.kt`

Add `operation: SystemOperation` as a new parameter. Replace the hardcoded Deception check with a general lookup driven by `operation.utility`.

```kotlin
object SystemTestResolver {
    fun resolve(
        decker: Decker,
        operation: SystemOperation,
        targetNumber: Int,
        hostSecurityValue: Int,
        diceRoller: DiceRoller
    ): SystemTestOutcome
}
```

**Updated algorithm:**

1. Look up `operation.utility` (e.g. `DECEPTION` for logon operations, `ANALYZE` for analyze operations, `null` for `SWAP_MEMORY`).
2. Find a matching `Utility` in `decker.cyberdeck.activeUtilities` where `utility.type == operation.utility`. Pending uploads do **not** qualify. If found: `modifier = utility.currentRating`; otherwise `modifier = 0`.
3. Roll decker: `computerSkill` dice vs `max(2, targetNumber - modifier)` → `deckerSuccesses`.
4. Roll host: `hostSecurityValue` dice vs `decker.detectionFactor` → `hostSuccesses`.
5. `deckerWins = deckerSuccesses >= hostSuccesses`.
6. Return `SystemTestOutcome(deckerSuccesses, hostSuccesses, deckerWins)`.

The TN floor of 2 (step 3) applies to all operations uniformly (CD-14).

All existing callers of `SystemTestResolver.resolve()` in `Decker.kt` must be updated to pass the relevant `SystemOperation` enum value (e.g. `SystemOperation.LOGON_TO_HOST`, `SystemOperation.LOGON_TO_LTG`, `SystemOperation.GRACEFUL_LOGOFF`).

PRD: CD-14, CD-15.

---

### `DeckerLoader`

**File:** `src/main/kotlin/com/shadowrun/matrix/config/DeckerLoader.kt`

Three additions:

**1. Catalog lookup parameter:**

```kotlin
object DeckerLoader {
    fun load(inputStream: InputStream, catalog: List<DeckCatalogEntry> = emptyList()): Decker
}
```

When the YAML deck block has a `model:` key and the name matches a catalog entry, the catalog values serve as defaults for all hardware fields. Any field explicitly present in the YAML overrides the catalog value. If the model name is not found in the catalog, log a warning and continue using all inline values (CD-26).

**2. Utility `active` and `source_code` flags:**

Each utility map in the YAML may include `active: true` and `source_code: true`. Parse both; pass as constructor arguments to `Utility(...)`.

**3. Active-at-startup partition:**

After instantiating all utilities, partition them by `active` flag:

- Utilities with `active: true` → `activeUtilities`, placed there with `currentRating = rating` and no upload countdown (CD-05, CD-06).
- All other utilities → `storedUtilities`.

Validate: total Mp of all `active: true` utilities ≤ `activeMemoryMp`. Violation is a configuration error.

PRD: CD-05, CD-06, CD-26.

---

## Public Methods on `Decker`

All four methods are **pure**: they return new `Decker` instances via `.copy()`; no shared mutable state. All are subject to the logging NFR (log intention at start, outcome at end).

### 1. `loadUtility(utility: Utility): LoadUtilityResult`

**PRD:** CD-07, CD-08, CD-10, CD-12

**Preconditions:**

- `persona != null` (decker must be jacked in)
- `utility` is present in `cyberdeck.storedUtilities`
- `utility` is not already present in `cyberdeck.activeUtilities` or `cyberdeck.pendingUploads`

**Logic:**

1. Calculate `turnsRequired = ceil(utility.mpSize.toDouble() / cyberdeck.ioSpeedMpPerTurn)`.
2. If `cyberdeck.freeActiveMemoryMp < utility.mpSize` → return `LoadUtilityResult.InsufficientMemory(this, utility.mpSize, cyberdeck.freeActiveMemoryMp)`. No action economy spent (CD-08).
3. Add `PendingUpload(utility, turnsRequired)` to `cyberdeck.pendingUploads`.
4. Return `LoadUtilityResult.Success(updatedDecker)`.

Edge case: if `turnsRequired == 0` (zero-Mp utility), skip the pending state and add directly to `activeUtilities`.

The Mp of a pending utility counts against Active Memory immediately from step 3 (CD-11).

---

### 2. `unloadUtility(utility: Utility): Decker`

**PRD:** CD-09

**Preconditions:**

- `persona != null`
- `utility` is present in `cyberdeck.activeUtilities` **or** `cyberdeck.pendingUploads` (matched by type)

**Logic:**

1. Remove the matching entry from `activeUtilities` or `pendingUploads` (whichever contains it).
2. The stored copy in `storedUtilities` is untouched; it retains its own `currentRating`.
3. Return new `Decker` with updated `cyberdeck`. Active memory is freed immediately.

---

### 3. `swapUtility(toUnload: Utility, toLoad: Utility): LoadUtilityResult`

**PRD:** CD-13

**Preconditions:** Same as `unloadUtility` for `toUnload`; same as `loadUtility` for `toLoad`.

**Logic:**

1. Unload `toUnload` (frees its Mp from active memory).
2. Attempt to load `toLoad` against the now-freed capacity.
3. Return the `LoadUtilityResult` from step 2.

This is a Simple Action total. The unload is absorbed into the swap; the caller deducts one Simple Action from the decker's action economy.

---

### 4. `advanceCombatTurn(): Decker`

**PRD:** CD-11, CD-22

Not a player action (no action economy cost); called by the game clock at the start of each Combat Turn.

**Logic:**

1. Decrement every `PendingUpload.turnsRemaining` by 1.
2. Move each upload where `turnsRemaining == 0` from `pendingUploads` into `activeUtilities`.
3. Inspect all `activeUtilities`: if any utility has `currentRating <= 0`, auto-unload it — remove it from both `activeUtilities` and `storedUtilities`, log the depletion event (CD-22).
4. Return new `Decker` with the updated `cyberdeck`.

---

## Detection Factor — Dynamic Recalculation

**PRD:** CD-17, CD-18

`Decker.detectionFactor` must be recalculated at the moment each System Test is resolved, not cached at jack-in. The Sleaze utility contributes only when it is **fully active** (present in `activeUtilities`); a pending upload does not count.

```kotlin
detectionFactor =
    if sleaze in activeUtilities:  ceil((masking + sleaze.currentRating) / 2)
    else:                          ceil(masking / 2)
```

`Decker.detectionFactor` should be implemented as a computed property (or computed at test time) that reads from `cyberdeck.activeUtilities` each time it is called. Loading or unloading Sleaze mid-run automatically changes the Detection Factor for all subsequent tests.

The effective Detection Factor passed to `SystemTestResolver` must subtract `decker.suppressionDfPenalty` (from IC suppression — see `combat.md`):

```kotlin
val effectiveDetectionFactor: Int
    get() = detectionFactor - suppressionDfPenalty
```

---

## Hacking Pool

**PRD:** CD (Decker section), CC-23

`hackingPool` is a computed property:

```kotlin
val hackingPool: Int
    get() = (decker.intelligence + cyberdeck.mcpRating) / 3
```

**General usage:** Hacking Pool dice may be added to any test made in the Matrix — System Tests, Attack or Defense tests, maneuvers, or Attribute Tests.

**Exception (PRD: ICC-11):** Hacking Pool dice may **not** be added to Body or Willpower Tests made to resist damage from gray or black IC attacking the decker's physical body. Only Karma Pool dice, cyberdeck-connected enhancements, or magic boosts to the decker's Body or Willpower apply in those situations.

`hackingPool` is exposed on `Decker` and passed into `ManeuverParticipant` and `AttackParticipant` by callers; `CombatResolver` methods do not read it from the `Decker` directly.

---

## Medic Utility Method

**PRD:** CD-20, Utilities section (Medic mechanics)

```kotlin
fun invokeMediac(diceRoller: DiceRoller): MedicResult
```

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Decker.kt`

```kotlin
data class MedicResult(
    val updatedDecker: Decker,
    val boxesRepaired: Int,
    val medicRating: Int   // currentRating after decrement
)
```

**Algorithm:**

1. Determine target number from current icon Condition Monitor state:
   - 1–3 boxes filled (Light) → TN 4
   - 4–6 boxes filled (Moderate) → TN 5
   - 7–9 boxes filled (Serious) → TN 6
   - 10 boxes (Deadly/Crashed) → cannot be used; return `MedicResult(this, 0, medic.currentRating)`
2. Roll `medic.currentRating` dice vs. TN → `successes`.
3. `boxesRepaired = successes` — each success removes one filled box from the persona's Condition Monitor (floor at 0 filled boxes).
4. Decrement `medic.currentRating` by 1 (degradation per CD-20), regardless of success or failure.
5. If `medic.currentRating == 0`: trigger CD-22 auto-unload (medic removed from active memory and storage).
6. Return `MedicResult(updatedDecker, boxesRepaired, medic.currentRating)`.

**Action cost:** Complex Action (caller deducts from action budget).

---

## Cyberdeck Catalog

### `DeckCatalogLoader`

**File:** `src/main/kotlin/com/shadowrun/matrix/config/DeckCatalogLoader.kt`

```kotlin
object DeckCatalogLoader {
    fun load(inputStream: InputStream): List<DeckCatalogEntry>
}
```

Uses the same SnakeYAML pattern as `GridLoader`. Loaded once at application startup before any decker YAML is parsed. The result is passed as the `catalog` argument to `DeckerLoader.load()`.

---

### `decks.yaml`

**File:** `src/main/resources/decks.yaml`

```yaml
decks:
  - model: Allegiance Sigma
    mpcp: 3
    hardening: 1
    active_memory: 200
    storage_memory: 500
    io_speed: 100
    cost_nuyen: 14000
  - model: Sony CTY-360-D
    mpcp: 5
    hardening: 3
    active_memory: 300
    storage_memory: 600
    io_speed: 200
    cost_nuyen: 70000
  - model: Novatech Hyperdeck-6
    mpcp: 6
    hardening: 4
    active_memory: 500
    storage_memory: 1000
    io_speed: 240
    cost_nuyen: 125000
  - model: CMT Avatar
    mpcp: 7
    hardening: 4
    active_memory: 700
    storage_memory: 1400
    io_speed: 300
    cost_nuyen: 250000
  - model: Renraku Kraftwerk-8
    mpcp: 8
    hardening: 4
    active_memory: 1000
    storage_memory: 2000
    io_speed: 360
    cost_nuyen: 400000
  - model: Transys Highlander
    mpcp: 9
    hardening: 4
    active_memory: 1500
    storage_memory: 2500
    io_speed: 400
    cost_nuyen: 600000
  - model: Novatech Slimcase-10
    mpcp: 10
    hardening: 5
    active_memory: 2000
    storage_memory: 2500
    io_speed: 480
    cost_nuyen: 960000
  - model: Fairlight Excalibur
    mpcp: 12
    hardening: 6
    active_memory: 3000
    storage_memory: 5000
    io_speed: 600
    cost_nuyen: 1500000
```

PRD: CD-25.

---

## Updated Decker Initialization Sequence

Replaces the 7-step sequence in `creation.md`:

1. Load `decks.yaml` catalog via `DeckCatalogLoader` (once at startup; reused for all deckers).
2. Parse `<decker_name>.yaml`.
3. Resolve `model:` field against the catalog; apply catalog values as hardware defaults. Explicit YAML fields override catalog values.
4. Instantiate the `Decker` with physical stats.
5. Instantiate the `Cyberdeck` with hardware values.
6. Instantiate the four `PersonaPrograms`; validate ratings ≤ MPCP and sum ≤ MPCP × 3.
7. Validate Response Increase ≤ min(3, floor(MPCP ÷ 4)).
8. Instantiate each `Utility` with `rating`, `currentRating = rating`, `sourceCode` flag; validate `rating ≤ MPCP` (CD-01); calculate Mp sizes; validate total ≤ Storage Memory.
9. Partition utilities by `active` flag: `active: true` → `activeUtilities` (turnsRemaining = 0, fully uploaded); others → `storedUtilities`. Validate total active Mp ≤ Active Memory.
10. Derive and attach the `Persona`: Bod/Evasion/Masking/Sensor from persona programs; Reaction = base + Response Increase × 2; Hacking Pool and Detection Factor computed lazily.

---

## Cyberterminals

**PRD:** CT-01 through CT-05

### `Cyberterminal` (subclass of `Cyberdeck`)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Cyberdeck.kt`

`Cyberterminal` extends `Cyberdeck` and enforces its own constraints in `init`:

```kotlin
class Cyberterminal(
    mcpRating: Int,
    hardening: Int,
    activeMemoryMp: Int,
    storageMemoryMp: Int,
    ioSpeedMpPerTurn: Int,
    activeUtilities: List<Utility> = emptyList(),
    storedUtilities: List<Utility> = emptyList(),
    pendingUploads: List<PendingUpload> = emptyList(),
    costNuyen: Int = 0
) : Cyberdeck(
    mcpRating = mcpRating,
    hardening = hardening,
    activeMemoryMp = activeMemoryMp,
    storageMemoryMp = storageMemoryMp,
    ioSpeedMpPerTurn = ioSpeedMpPerTurn,
    responseIncrease = 0,       // CT-02: no Response Increase
    activeUtilities = activeUtilities,
    storedUtilities = storedUtilities,
    pendingUploads = pendingUploads,
    costNuyen = costNuyen
) {
    init {
        require(mcpRating <= 4) { "Cyberterminal MPCP may not exceed 4 (CT-01); got $mcpRating" }
    }
}
```

Constraints enforced at construction time (CT-01, CT-02):

- MPCP ≤ 4 (hard requirement).
- `responseIncrease` is always fixed at 0; the constructor accepts no `responseIncrease` argument.

**CT-03 — Program rating reduction:** Applied transparently inside `SystemTestResolver`. When the active decker is using a `Cyberterminal`, each utility's `currentRating` used in TN reduction is treated as `max(0, currentRating - 1)`. No change to the stored `Utility` objects; the adjustment is applied at test resolution time.

Add a helper to `SystemTestResolver`:

```kotlin
private fun effectiveRating(utility: Utility, deck: Cyberdeck): Int =
    if (deck is Cyberterminal) max(0, utility.currentRating - 1)
    else utility.currentRating
```

**CT-04 — Immunity to Black IC and Dump Shock:** The `jackOut()` and `gracefulLogoff()` methods (in `movement.md`) already check the decker's deck type to decide whether dump shock applies. Add a computed property to `Cyberdeck`:

```kotlin
open val immuneToDumpShock: Boolean get() = false
```

Override in `Cyberterminal`:

```kotlin
override val immuneToDumpShock: Boolean get() = true
```

The `LogoffResult.JackOut` constructor should pass `dumpShock = !decker.cyberdeck.immuneToDumpShock`.

**CT-05 — Cost:** No code change required; the cost is a data value seeded in YAML. Cyberterminal models are seeded at approximately 10% of the equivalent cyberdeck's `costNuyen`.

### Initialization

The `DeckCatalogLoader` and `DeckerLoader` support cyberterminals transparently: if `model:` resolves to a catalog entry with `type: cyberterminal` (new optional field in `decks.yaml`), the loader instantiates `Cyberterminal` instead of `Cyberdeck`. If no `type` field is present, `Cyberdeck` is used (default).

---

## Cyberdeck Accessories

**PRD:** ACC-01 through ACC-03

### `Accessory` (sealed class)

**File:** `src/main/kotlin/com/shadowrun/matrix/decker/Accessory.kt`

Already present in `ord.md`. Make it a sealed class:

```kotlin
sealed class Accessory {
    /** ACC-01: External storage beyond the deck's built-in Storage Memory. */
    data class OfflineStorage(val capacityMp: Int) : Accessory()

    /** ACC-02: Screen that lets bystanders observe the decker's Matrix view. */
    object VidScreen : Accessory()

    /**
     * ACC-03: Electrode net or datajack feed that allows a second person to
     * jack in and observe as a passive hitcher.
     */
    data class HitcherJack(val type: HitcherJackType) : Accessory()
}

enum class HitcherJackType { ELECTRODE_NET, DATAJACK_FEED }
```

### Rules enforced by code

**ACC-03 — Hitcher behavior:**

- A hitcher is represented as a read-only observer on the `Persona`; the hitcher has no `Decker` of their own and cannot modify any state.
- `HitcherJack` carries `immuneToDumpShock = true` for its passenger, mirroring the cyberterminal rule (CT-04).

Add a `hitchers: List<HitcherObserver>` field to `Cyberdeck` (default empty):

```kotlin
data class HitcherObserver(val name: String)
```

Hitchers are purely informational for the current scope (no game-mechanical effect beyond IC immunity, which the GM enforces narratively).

**ACC-01 — Off-line storage effect on downloads:**

`DownloadHandle` (designed in `operations.md`) accepts an optional `destination: DownloadDestination`:

```kotlin
sealed class DownloadDestination {
    object ActiveMemory : DownloadDestination()
    object StorageMemory : DownloadDestination()
    data class OfflineStorage(val accessory: Accessory.OfflineStorage) : DownloadDestination()
}
```

When `destination` is `OfflineStorage`, the download does not consume `storedUtilities` capacity on the cyberdeck itself; it flows to the external device. The total available Mp for offline storage is `accessory.capacityMp`.

---

## Verification

| Scenario | Expected Result |
| --- | --- |
| Utility `rating: 9` on MPCP-8 deck at parse time | Config error naming the offending utility (CD-01) |
| Response Increase 3 on MPCP-8 deck (floor(8÷4)=2) | Config error (CD-02) |
| `active: true` utilities exceed Active Memory | Config error at parse time (CD-05) |
| `loadUtility` with sufficient free memory | `LoadUtilityResult.Success`; utility appears in `pendingUploads` |
| `loadUtility` with insufficient free memory | `LoadUtilityResult.InsufficientMemory`; decker unchanged |
| `advanceCombatTurn()` called N times (N = upload turns) | Utility moves from `pendingUploads` to `activeUtilities` |
| System test resolved while utility still in `pendingUploads` | No TN modifier applied (CD-12) |
| Logon TN = 10 with Deception-4 fully active | Effective TN = max(2, 10−4) = 6 (CD-14, CD-15) |
| Logon with no Deception in active memory | TN unchanged at base value |
| Sleaze-5 fully active, Masking 6 | Detection Factor = ceil((6+5)÷2) = 6 (CD-17, CD-18) |
| Sleaze unloaded mid-run; test resolved | Detection Factor = ceil(6÷2) = 3 (CD-18) |
| Sleaze in pending-upload state during test | Detection Factor uses Masking only (CD-12) |
| Armor-5 takes bleed-through damage | `armor.currentRating` decrements to 4 (CD-19) |
| Armor `currentRating` reaches 0 | Auto-unloaded, marked depleted, event logged (CD-22) |
| Medic invoked (success or failure) | `medic.currentRating` decrements by 1 (CD-20) |
| Medic invoked on icon with 5 boxes filled | TN = 5; each success repairs 1 box; rating decrements whether test passes or fails |
| Hacking Pool on Attack Test | Pool dice added to `attacker.hackingPool`; excluded on body/willpower resist vs. black IC |
| Suppression DF penalty: 2 ICs suppressed | `effectiveDetectionFactor = detectionFactor - 2` applied on next System Test |
| `swapUtility`: unload frees exact space needed | `LoadUtilityResult.Success` after swap (CD-13) |
| `model: Renraku Kraftwerk-8` in decker YAML | Hardware defaults from `decks.yaml` catalog (CD-26) |
| Unknown `model:` value in decker YAML | Warning logged; inline values used (CD-26) |
| Decker YAML utility with `active: true` | In `activeUtilities` at jack-in with `turnsRemaining = 0` (CD-05) |
| `RELOCATE_ICON` with Relocate-5 active, base TN = 8 | Effective TN = max(2, 8−5) = 3 (CD-16) |
