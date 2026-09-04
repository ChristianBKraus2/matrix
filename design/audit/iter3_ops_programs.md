# Iteration 3 — Domain Model Audit: Operations types, Programs, Accessories, Main

Scope: domain data/sealed/enum types under `operations/`, `programs/`, `accessories/`, plus
`Main.kt`. (Already-audited `SystemOperation.kt`, `SystemTestOutcome.kt`, `OperationResult.kt`,
`SystemTestResolver.kt` are excluded.) Read against `spec_baseline.md`, `iter2_operations.md`,
`iter2_cyberdeck.md`, and — for the two touched clauses — `cyberdeck_and_program_mechanics.md`
(ACC-01, L588-600) read in this session.

All 15 assigned files are ≤100 lines → Rule 2 requires 1 verbatim excerpt each.

## Coverage table

| File | Lines | Verbatim excerpt(s) | Notes |
|---|---|---|---|
| `programs/Utility.kt` | 49 | `ANALYZE(3, OPERATIONAL),` … `BLACK_HAMMER(20, OFFENSIVE),` `KILLJOY(10, OFFENSIVE),` `SLOW(4, OFFENSIVE),` ; `multiplier = if (type == UtilityType.ATTACK && attackDamageLevel != null) attackDamageLevel.ordinal + 2 else type.multiplier` | ALL multipliers match spec verbatim: Analyze3/Browse1/Commlink1/Deception2/Decrypt1/Read_Write2/Relocate2/Scanner3/Spoof3; Sleaze3/Track8; Attack2(base)/BlackHammer20/Killjoy10/Slow4; Armor3/Cloak3/Lock_On3/Medic4. Category groupings (OPERATIONAL/SPECIAL/OFFENSIVE/DEFENSIVE) match. ATTACK L/M/S/D = ordinal+2 = 2/3/4/5 (DamageLevel order LIGHT,MODERATE,SERIOUS,DEADLY verified in Enums.kt:9). `rating` (stored, from Program) drives mpSize; `currentRating = rating` default per CD-19/21. No finding. |
| `programs/Program.kt` | 9 | `val mpSize: Int get() = rating * rating * multiplier` | Mp = Rating²×mult (spec L18/§Utility multipliers). Uses stored `rating` (correct — hardware size is fixed). No finding. |
| `programs/PersonaProgram.kt` | 8 | `PersonaProgram(val attributeType: PersonaAttributeType, rating: Int) : Program(name = attributeType.name, rating = rating, multiplier = 1)` | Persona programs (Bod/Evasion/Masking/Sensors) multiplier=1. No spec multiplier assigned; consistent. No finding. |
| `operations/InterrogationState.kt` | 25 | `data class InterrogationState(val operation: SystemOperation, ... val accumulatedSuccesses: Int = 0)` ; `enum class QueryPrecision(val modifier: Int) { VERY_VAGUE(+2), VAGUE(+1), NORMAL(0), SPECIFIC(-1), VERY_SPECIFIC(-2) }` | Fields (operation, query, accumulatedSuccesses=0) match iter2 L91-101. QueryPrecision all 5 values + modifiers match SO-07 / spec §Interrogation. Accumulation `max(0,net)` lives in resolver (SO-06), not here. No finding. |
| `operations/MonitoredOperationHandle.kt` | 27 | `sealed class MonitoredTarget { data class SlaveDevice(...) ...; data class ComcallHost(val host: Host) ... }` ; `data class MonitoredOperationHandle(val operation..., val target: MonitoredTarget, val active: Boolean = true, val needsMaintenance: Boolean = false)` | Fields + MonitoredTarget variants match iter2 L112-133. active=true, needsMaintenance=false defaults correct. No finding. |
| `operations/DownloadHandle.kt` | 19 | `data class DownloadHandle(val file: DataFile, val totalMp: Int, val ioSpeedMpPerTurn: Int, val turnsRemaining: Int, val active: Boolean = true, val destination: DownloadDestination = DownloadDestination.StorageMemory)` | Core fields match iter2 L548-554. **`destination` field present** — contradicts design ACC-01 which says the field is absent → D3O-1. |
| `operations/UploadHandle.kt` | 17 | `data class UploadHandle(val file: DataFile, val totalMp: Int, val ioSpeedMpPerTurn: Int, val turnsRemaining: Int, val active: Boolean = true)` | Fields match iter2 L577-583. Resolves OPS DOC-11: `uploadData` synthesizes `file = DataFile(name="upload to …", sizeMp = dataSizeMp)` and `totalMp = dataSizeMp` (DeckerOperationsExtensions.kt:404) — see D3O-2. No code finding. |
| `operations/NullOperationModifier.kt` | 33 | `UNDER_TEN_SECONDS(0), TEN_SECONDS_TO_ONE_MINUTE(1), ONE_MINUTE_TO_ONE_HOUR(2), ONE_HOUR_TO_TWELVE_HOURS(4)` ; `else -> ONE_HOUR_TO_TWELVE_HOURS` ; `val extraIncrements = (seconds - 43200) / 43200` | Enum bonuses 0/1/2/4 and `forDuration` thresholds (<10,<60,<3600,else) match iter2 L147-161. `totalBonusForDuration` adds +1 per additional 12h beyond first (L164). No finding. |
| `operations/AvailableAction.kt` | 27 | `sealed class AvailableAction { ... data class JackOut(override val actionType: ActionType = FREE) ... data class Operation(val operation: SystemOperation, val target: MatrixObject? = null, override val actionType: ActionType = operation.actionType) }` | Variants LogonToRtg/Ltg/Pltg/Host, GracefulLogoff, JackOut, Operation match protocol AvailableActionDto `kind` set. JackOut=FREE, others=COMPLEX. DTO-only fields (targetKind/targetName/paramKind) belong to mapping layer. No finding. |
| `operations/MatrixObject.kt` | 21 | `sealed class MatrixObject { data class GridNode(val rtg: RTG) ...; data class IcProgram(val ic: IC, val analyzed: Boolean = false) ...; data class Device(val device: RemoteDevice) ... }` | All 8 variants (GridNode/LocalGrid/PrivateGrid/HostNode/HostSubsystem/IcProgram/File/Device) match protocol MatrixObjectDto `kind` set. DTO detail fields (rating/behavior/guardedNodeType) belong to mapping layer. No finding. |
| `operations/MatrixIcon.kt` | 32 | `sealed class Icon { data class PersonaIcon(val persona: Persona, val sleazeRating: Int = 0) ...; data class IcIcon(val ic: IC) ... }` ; `sealed class IcDetectionResult { object Undetected...; data class PresenceOnly...; data class TypeKnown...; data class FullyLocated... }` | SensorTestResult{Undetected,Detected(icon,successes)} and IcDetectionResult 4-variant set match iter2 L308-311/L362-367. Persona notice TN masking+sleaze uses persona.masking + this sleazeRating. No finding. |
| `operations/BufferedMessage.kt` | 17 | `data class LinkedObserver(val name: String)` ; `data class BufferedMessage(val text: String, val recipient: LinkedObserver)` | Matches iter2 L731-738. No finding. |
| `operations/PointerChain.kt` | 15 | `data class PointerChain(val links: List<Host>, val finalFile: DataFile)` | Matches iter2 L799-802 (links length=1D6 set by resolver). No finding. |
| `accessories/Accessory.kt` | 9 | `sealed class Accessory { data class OfflineStorage(val capacityMp: Int) ...; object VidScreen ...; data class HitcherJack(val type: HitcherJackType) ... }` ; `enum class HitcherJackType { ELECTRODE_NET, DATAJACK_FEED }` | Matches iter2_cyberdeck L556-570. No finding. |
| `Main.kt` | 56 | `val context = GameContext(host = host, securityCode = host.securityRating.code, deckers = listOf(decker), matrix = matrix)` | Rule 9: GameContext(host, securityCode, deckers, activeIc=default, matrix) — all required fields supplied; `activeIc` legitimately defaulted to emptyList. Loaders (DeckCatalogLoader/DeckerLoader/HostLoader) construct via config. No finding. |

Files read: 15 (10 operations + 3 programs + 1 accessories + Main.kt). Total ≈ 364 lines.

## Findings

### D3O-1 — `DownloadHandle` now carries a `destination` field the design doc says is absent (doc-stale)
**File:** `operations/DownloadHandle.kt:18`
```kotlin
val destination: DownloadDestination = DownloadDestination.StorageMemory
```
**Violated clause:** `cyberdeck_and_program_mechanics.md` ACC-01, L600 (verbatim):
"**Current implementation:** `DownloadHandle` (designed in `operations.md`) does **not** yet
include a `destination` field. Downloads always route to deck storage. The `DownloadDestination`
type is defined in code but unused."
The code has advanced past the design: `DownloadHandle` DOES include `destination`, defaulting to
`StorageMemory`. The referenced `DownloadDestination` sealed type (decker/DownloadDestination.kt:6-10:
`ActiveMemory`/`StorageMemory`/`OfflineStorage(accessory: Accessory.OfflineStorage)`) matches the
design's declared shape, so this is purely the "field absent" statement being stale.
**Classification:** Rule 5 — code correct, design doc stale. Fix required: update ACC-01 L600 to
state `DownloadHandle` now carries `destination: DownloadDestination = StorageMemory`. Also stales
the sibling audit note `iter3_decker_domain.md` D3D-3 ("DownloadHandle carries no destination"),
which should be reconciled.

### D3O-2 — OPS DOC-11 (UploadHandle `file`/`totalMp` source gap) is resolved in code, not a bug
**Files:** `operations/UploadHandle.kt:11-17` (handle fields `file: DataFile`, `totalMp: Int`) +
`decker/DeckerOperationsExtensions.kt:404`:
```kotlin
val handle = UploadHandle(file = DataFile(name = "upload to ${host.name}", sizeMp = dataSizeMp), totalMp = dataSizeMp, ioSpeedMpPerTurn = ioSpeed, turnsRemaining = turns)
```
**iter2_operations.md DOC-11** flagged that `uploadData(host, dataSizeMp: Int, …)` provides no
`DataFile` for the handle's `file` field and that `dataSizeMp`↔`totalMp` are inconsistent. The code
resolves both: it synthesizes a `DataFile` from `dataSizeMp` and sets `totalMp = dataSizeMp`.
The `UploadHandle` domain type itself conforms exactly to the design handle spec (iter2 L577-583).
**Classification:** doc-stale (design-doc-internal contradiction), not a code bug. No code change.
DOC-11 should be closed against the design docs. Wire default confirmed alongside:
`WebSocketDeckerController.kt:320` `(p?.dataSize ?: 100).coerceAtLeast(1)` matches spec UPLOAD_DATA
`dataSize=100`.

## Root cause
Both findings trace to a single cause: the download/upload-routing design text in
`cyberdeck_and_program_mechanics.md` / `operations.md` predates the handle implementations. The
handle domain types (`DownloadHandle`, `UploadHandle`) were completed (destination field added;
upload file synthesized) without the prose being updated. No domain-type conformance bugs found in
this iteration — every multiplier, enum variant, sealed hierarchy, and field default matches spec.
