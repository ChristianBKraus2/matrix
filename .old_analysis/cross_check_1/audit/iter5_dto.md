# Iteration 5 — DTO Layer Audit (`src/main/kotlin/com/shadowrun/matrix/server/dto/`)

Strict design-vs-code conformance audit of the Kotlin DTO / wire-serialization layer against
`protocol.md`, with emphasis on align.md **Rule 12** (wire-field end-to-end tracing). Every file
was read in full from line 1 in this session (align.md Rule 1). `dto/` dir globbed to confirm
exactly four files.

## Coverage table

| File | Lines | Verbatim excerpts | Notes |
|---|---|---|---|
| `AvailableActionDto.kt` | 92 | (open) `@JsonClassDiscriminator("kind")` / `sealed class AvailableActionDto {` / `abstract val actionType: String` — (close, L70-79) `paramKind = when (operation) { … SystemOperation.UPLOAD_DATA -> "dataSize"; else -> null }` | Sealed by `kind` ✓. 7 variants match protocol L184-190. paramKind map matches protocol L190 exactly (precision/hasValidPasscode/scannerDeviceRating/newContent/dataSize/null). Common fields `index`,`actionType` present but undocumented in protocol table → D5D-1. |
| `DeckerStateDto.kt` | 44 | (open, L11) `val locationIndex: Int? = null,` — (close, L28-29) `location = currentLocation?.label() ?: "not jacked in", locationIndex = if (currentLocation != null) 0 else null,` | All 11 protocol L160-172 fields present. `locationIndex` Int?=null, always-0-when-jacked-in stub matches protocol L164 + iter2 DOC-6 verbatim. UtilityDto(type,rating) ✓. No findings. |
| `MatrixObjectDto.kt` | 141 | (open, L16) `@JsonClassDiscriminator("kind")` — (mid, L79-86) `data class IcProgram(… val analyzed: Boolean, val rating: Int?, val behavior: String?, val guardedNodeType: String?)` — (close, L131-134) `rating = if (analyzed) ic.rating else null, behavior = if (analyzed) ic.behavior.name else null, guardedNodeType = if (analyzed) ic.guardedNode?.subsystemType?.name else null` | Sealed by `kind` ✓. All 8 variants + field lists match protocol L205-212 field-for-field. IcProgram nullability (rating/behavior/guardedNodeType null-until-analyzed) matches protocol L210 exactly. Common field `index` present but undocumented in protocol table → D5D-1. |
| `Messages.kt` | 86 | (open, L7) `val MatrixJson = Json { encodeDefaults = true }` — (mid, L51-60) `data class ActionParams(val newContent: String? = null, val inactivitySeconds: Int? = null, val precision: String? = null, val query: String? = null, val hasValidPasscode: Boolean? = null, val scannerDeviceRating: Int? = null, val dataSize: Int? = null)` — (close, L62-77) `data class ResultMessage(… val deckerSuccesses: Int, val hostSuccesses: Int, val details: String)` / `data class ControlMessage(… val reconnectToken: String? = null)` | Messages use `type` discriminator ✓. ResultMessage deckerSuccesses/hostSuccesses/details all non-null ✓ (protocol L61). ControlMessage.reconnectToken nullable ✓ (L37). ErrorMessage.details nullable ✓ (L65). ActionParams carries all 7 protocol params incl `dataSize` ✓. ErrorCode/SessionRole @SerialName sets match protocol L143-152. No backend findings. |

Total: 4 files, 92+44+141+86 = 363 lines read in full.

---

## Findings

### D5D-1 — protocol.md DTO field tables omit the `index` common field (both sealed hierarchies) and `actionType` (AvailableActionDto)

**File:** `AvailableActionDto.kt:15-16`, `MatrixObjectDto.kt:18`.

**Code (verbatim):**
```
// AvailableActionDto.kt L15-16
abstract val index: Int
abstract val actionType: String
// MatrixObjectDto.kt L18
abstract val index: Int
```
Every variant serializes `index` (and, for AvailableActionDto, `actionType`) with no `@SerialName`,
so wire keys are `"index"` and `"actionType"`.

**Violated clause:** protocol.md §`AvailableActionDto Discriminant` (L182-190) and §`MatrixObjectDto
Discriminant` (L203-212) list only per-kind fields (e.g. `rtgName`, `name`, `region`…); neither table
documents the `index` field carried by every variant, nor the `actionType` field carried by every
AvailableActionDto variant. The frontend contract (iter2_ui.md L43-44: "All carry `index:number` and
`actionType:ActionType`") confirms these fields are expected on the wire — protocol.md is the doc that
is incomplete.

**Classification:** Code correct, design doc stale (align.md §Classifying, case 5). Fix = amend
protocol.md field tables to list the `index`/`actionType` common fields. No code change.

### D5D-2 — prd_ui.md internally contradicts itself on MAKE_COMCALL params; DTO conforms to protocol

**File:** `AvailableActionDto.kt:74` — `SystemOperation.MAKE_COMCALL -> "hasValidPasscode"`.

**Detail:** The DTO advertises `paramKind="hasValidPasscode"` for MAKE_COMCALL, matching protocol.md
L190 and prd_ui.md L140-144 (MAKE_COMCALL listed under "Actions that require inline parameter input").
However prd_ui.md L127 simultaneously lists `MAKE_COMCALL` among "All `Operation` actions where `params`
is ignored by the server." The two prd_ui clauses disagree.

**Violated clause:** prd_ui.md L127 vs L141 (self-contradiction). The DTO is **correct** — it follows
protocol.md L190. This is a PRD documentation discrepancy, not a DTO bug; recorded here because the
audit brief emphasizes paramKind correctness. Fix belongs to prd_ui.md (remove MAKE_COMCALL from the
L127 "params ignored" list).

---

## Rule 12 — traced wire fields (domain source → DTO mapping → serialized field name → frontend)

1. **`securityCode`** (GridNode): `rtg.securityRating.code.name` (`MatrixObjectDto.kt:113`) →
   `GridNode.securityCode: String` (no @SerialName) → wire key `"securityCode"` → messages.ts
   `GridNode.securityCode: SecurityCode`. Survives unchanged ✓. Value = enum name (`"BLUE"` etc.),
   matches SecurityCode union (iter2_ui L40).

2. **`deckerSuccesses`** (ResultMessage): resolver result → controller mapping → `ResultMessage.deckerSuccesses: Int`
   (non-null, `Messages.kt:66`) → wire key `"deckerSuccesses"` → messages.ts `number` (iter2_ui L31).
   Non-null contract (protocol L61) preserved end-to-end ✓.

3. **`behavior`** (IcProgram): `ic.behavior.name` gated by `analyzed` (`MatrixObjectDto.kt:133`) →
   `IcProgram.behavior: String?` → wire key `"behavior"` → messages.ts `'PROACTIVE'|'REACTIVE'|null`.
   DTO nullability (null-until-analyzed) matches protocol L210 ✓. NOTE for later iterations: the frontend
   union restricts `behavior` to `PROACTIVE|REACTIVE`; the DTO emits `IcBehavior.name` unconditionally —
   if the domain `IcBehavior` enum has variants beyond those two, a wire-value mismatch exists at the
   frontend hop. Not confirmable from the DTO layer alone; flagged for the domain/frontend iterations.

4. **`reconnectToken`** (ControlMessage): session token → `ControlMessage.reconnectToken: String? = null`
   (`Messages.kt:76`) → `MatrixJson = Json { encodeDefaults = true }` emits the key even when null →
   wire key `"reconnectToken"` → messages.ts `reconnectToken?: string`. Nullable contract (protocol L37,
   UI-01..04) preserved ✓.

## Cross-check of iter2_ui.md UI findings vs actual backend DTO shape

- **DOC-1** (dataSize paramKind has no carrier in ActionParams): **frontend/doc-stale, NOT a backend bug.**
  Backend `ActionParams.dataSize: Int? = null` exists (`Messages.kt:59`). The advertised `dataSize`
  paramKind is fully carriable by the backend DTO.
- **DOC-2** (query field scoping): backend `ActionParams.query` (`Messages.kt:56`) is unscoped and available
  to all LOCATE ops; no backend bug. Doc-scoping issue only.
- **DOC-3** (inactivitySeconds orphaned — no paramKind): consistent with protocol (NULL_OPERATION →
  `else -> null` paramKind, `AvailableActionDto.kt:78`); the param is still carried by
  `ActionParams.inactivitySeconds`. By design (default sufficient), not a backend bug.
- **DOC-6** (locationIndex permanent stub = 0): backend `DeckerStateDto` matches the documented stub
  verbatim (`locationIndex = if (currentLocation != null) 0 else null`, `DeckerStateDto.kt:29`). Documented, not a bug.

**Verdict:** the four DTO files are conformant to protocol.md on discriminator (`kind` not `type`),
field presence, nullability, and paramKind mapping. The only findings are documentation gaps/contradictions
(D5D-1 protocol.md incomplete; D5D-2 prd_ui.md self-contradiction) — no backend code change required.
