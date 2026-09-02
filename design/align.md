# Design-vs-Code Alignment Process

A repeatable process for auditing a codebase against its design documents.
Completeness must be **provable by artifact**, not by assertion.

---

## Prerequisites

| Artifact | Typical location | Role |
|---|---|---|
| Design docs | `design/design_core/`, `design/design_game/`, `design/design_ui/` | Specification |
| PRDs | `design/prd_*.md` | Authoritative rule source — consulted when design and code disagree |
| Protocol doc | `design/protocol.md` | Wire format spec |
| Discrepancies log | `design/discrepancies_without_prd.md` | Accumulates unresolved findings |
| Deferred list | `design/deferred.md` | Explicitly out-of-scope items — do not flag these |

---

## Step 0 — Build the Coverage Manifest (before reading anything)

Run the following and record every result in the manifest table below:

```
find src -name "*.kt"
find frontend/src \( -name "*.ts" -o -name "*.tsx" \)
find design -name "*.md"
```

The manifest is a living artifact updated throughout the audit.
**An audit is not complete until every row has a ✓ or a justified Skip.**

| File path | Status | Verbatim excerpt (proves file was read) | Findings |
|---|---|---|---|
| _(populate from find output before starting)_ | | | |

**Status values:**
- ✓ Read — the file was read in full
- Skip:deferred — the feature is in `deferred.md` (cite the entry)
- Skip:infra — build / tooling file with no design-doc coverage (state why)

No other skip reason is valid. "File looks unimportant" is not a valid reason.

---

## Prohibited Patterns

These phrases indicate a spot check, not an audit. They are never acceptable:

- "The remaining files follow the same pattern"
- "Other files are assumed consistent"
- "Similar files were checked"
- "No issues expected in..."
- "I checked representative files from this layer"
- Citing a PRD clause without having read the PRD in full in this session
- Grouping multiple files into a single manifest row (e.g. "all files in combat/ — no issues")
- Declaring a file clean without having applied the per-file checklist to **every method** in it
- A manifest excerpt that is a behavioural description ("method X sets field Y to Z") rather than a code token copied verbatim from the file
- A manifest excerpt identical or nearly identical to the finding text in the same manifest row — the excerpt must come from the file, not from the finding
- A manifest excerpt that synthesises multiple lines or multiple methods into one sentence

If you find yourself writing one of these, stop and read the skipped files.

---

## Methodology

### Rule 1 — Never read excerpts

**Never use an agent type that reads excerpts** (e.g. the `Explore` subagent).
It silently drops content past its read window. Use direct `Read` tool calls
or `general-purpose` agents with full tool access.

### Rule 2 — Verbatim excerpt required for every file

For every file you mark ✓ in the manifest, the manifest entry must contain a
verbatim excerpt copied from that file (a field name, a function signature, a
literal value). Paraphrase is not acceptable. This is the only proof that the
file was read rather than inferred from context.

For files exceeding 100 lines, provide **two** verbatim excerpts separated by
at least 30 lines in the source — one from the opening third, one from the
closing third. A function signature from line 12 of a 400-line file does not
prove the final 350 lines were read.

For any file whose Findings column is non-empty, at least one excerpt must come
from the **specific code location that contains the finding** — not from a
passing line elsewhere in the file.

### Rule 3 — Read PRDs first, completely

Read every `prd_*.md` in full before auditing implementation files.
Prior verdicts that cited a PRD by name without reading it may be wrong.
The PRD is the final arbiter of every finding.

### Rule 4 — No inference from patterns

Each file must be read independently. You may not conclude a file is correct
because other files in the same layer followed the design. Patterns are a
reason to be faster, not to skip.

### Rule 5 — Zero-finding files must still appear in the log

For every file read, the discrepancies log must contain an entry — even if
that entry only says "No discrepancies found." Silent omission is
indistinguishable from "not checked."

### Rule 6 — Include test files

Test files encode expected behavior. A test that passes but encodes the wrong
expectation is a discrepancy. Priority: integration tests, DTO mapping tests,
and any test whose name matches a design doc section.

### Rule 7 — Include every layer

- Domain model (data classes, sealed classes, enums)
- Business logic (resolvers, extensions, game loop)
- Server / controller layer (dispatch, DTO mapping, wire protocol)
- Config loaders (field names must match the domain model)
- Frontend types (`messages.ts` / equivalent) — must match server DTOs
- Frontend components — rendering logic must match design_ui spec

### Rule 8 — Apply the per-file checklist to every method, not to the file as a whole

A file with 12 methods that has 11 correct ones and 1 wrong one is not "clean." For every
function / method in the file, step through the checklist independently. Pay special
attention to:

- Resolver files (`CombatResolver`, `SystemTestResolver`, etc.) where each `resolve*`
  function has its own algorithm, TN formula, and edge-case handling.
- Extension files (`Decker*Extensions.kt`) where each extension encodes a separate
  operation with distinct pre-conditions and return types.
- Controller dispatch blocks: each `when` / `if` branch for each operation is a
  separate unit to check.

For files whose name contains `Resolver`, `Extensions`, or `Controller`, the manifest
entry must include an explicit list of every method/function checked alongside the
verbatim excerpt, e.g. `methods: resolveBlackHammer, resolveCrippler, resolveRipper`.
This makes the per-method coverage claim falsifiable.

### Rule 9 — Constructor calls must be verified for completeness

Whenever a domain object is constructed (e.g. `Persona(…)`, `Cyberdeck(…)`,
`GameContext(…)`), cross-reference the constructor call against the design doc to verify
that every field the design specifies is supplied. Omissions are silent: a missing
`sleazeRating = …` compiles without error but violates the spec.

Also applies to factory functions and loader methods that produce domain objects from
config or from other domain objects.

### Rule 10 — Verify the post-fix surface after a prior audit

When running an audit on a codebase that has had previous findings applied, each fixed
finding may have been applied only to the most obvious code path. For every partial-fix
pattern — especially conditional guards of the form `if (x != null) callResolver(…)` —
enumerate all code paths that should trigger the resolver and verify each one. A fix that
covers the host case but not the grid case is a new finding, not a closed one.

---

## Iteration Structure

| Iteration | What to read | Against what |
|---|---|---|
| 1 | PRDs in full | Nothing yet — establish ground truth |
| 2 | Design docs (domain core) | PRDs |
| 3 | Domain model source (data classes, enums, sealed classes) | Design docs |
| 4 | Business logic (resolvers, extensions, game loop) | Design doc algorithms |
| 5 | Server / controller layer + DTOs | Design docs + protocol doc |
| 6 | Frontend types + components | design_ui spec |
| 7 | Config loaders | Design doc field names |
| 8 | Test files | Design docs |

Iterations can be merged when the project is small. **No iteration may be
declared complete until every file assigned to it has a ✓ in the manifest.**

---

## Completion Gate

Before declaring the audit complete, verify all three conditions:

1. **Count match** — count of files returned by `find` equals count of
   ✓ + justified Skip rows in the manifest. State both counts explicitly.
2. **PRD coverage** — every PRD has been read in full in this session.
3. **Adversarial check** — answer explicitly: "If I had done a spot check and
   stopped after the first N interesting findings, what would I have missed?"
   If the answer reveals anything unexamined, go examine it.

Do not write "audit complete" until all three are satisfied.

---

## Per-File Checklist

For every file read:

- [ ] Every field name matches the design doc (case, pluralization, abbreviation)
- [ ] Every default value matches the design doc
- [ ] Every algorithm step matches the design doc (TN formulas, die pools, floors/ceilings)
- [ ] Every sealed class / enum variant matches the design doc (no missing, no extra)
- [ ] Every method the design doc names is implemented with the right signature
- [ ] No stub implementations the design doc treats as complete (`TODO`,
      hardcoded returns, `error("not implemented")`)
- [ ] No dead code for operations the design doc describes as active
- [ ] Wire format field names (`@SerialName`, JSON keys) match the protocol doc
- [ ] `paramKind` advertised to the client matches what the server actually reads
- [ ] Every constructor / factory call that builds a designed type supplies all fields
      the design specifies — omitted optional fields with wrong defaults count
- [ ] Every method returning a `Pair` / tuple whose second element is a handle or
      side-effect result: the caller stores / uses that second element; `.first`-only
      calls are a finding unless the design explicitly permits discarding the second
- [ ] Every `coerceIn` / `clamp` / `min` applied to a PRD-governed numeric parameter:
      the upper bound is verified against the PRD's actual maximum (not a guess)
- [ ] Every conditional guard on a resolver call (`if (x != null) resolve(…)`): all
      code paths that should trigger the resolver are enumerated and verified — a guard
      that covers only the host path but not the grid path is incomplete
- [ ] For frontend hooks: every behavioral contract in the UI design doc is checked
      (not just type shapes) — state-transition invariants, role guards, token lifecycle

---

## Classifying Each Finding

1. **In `deferred.md`?** → Skip; not a discrepancy.
2. **PRD clause directly resolves it?** → Apply the fix. Mark ✓ resolved.
3. **PRD mentions topic but doesn't resolve the gap?** → Document with PRD
   clause cited. No code change.
4. **Code-quality only (duplication, dead code, naming)?** → Document only.
5. **Code correct, design doc stale?** → Update the design doc. Mark ✓ resolved.

---

## Discrepancies Log Format

```markdown
## XX-N — Short description

**Design:** What the design doc says.

**Code:** What the code actually does (file path + relevant snippet).

**Impact:** What goes wrong at runtime, if anything.

**PRD verdict:** Which PRD clause applies and what it says.
State explicitly if there is no PRD guidance.

**Status / Fix applied / Fix required:** What was done or what needs doing.
```

Prefix conventions: AP- ActionsPanel · AS- AnalyzeSecurity · CD- Cyberdeck ·
CM- ConditionMonitor/combat · DC- Dead code · DF- Detection factor ·
DS- Dead/stub field · DU- Download/upload · DUP- Duplication · EP- EntitiesPanel ·
GC- GameContext · GL- Game loop · GM- game.md · GR- Grid · IC- IC behavior ·
IM- INVOKE_MEDIC · INT- Interrogation · MC- Missing/coverage · MS- missing.md ·
NM- Naming mismatch · NFR- Non-functional · OP- Operations · PB- Probe ·
PG- PrivateGrid · PR- Protocol · RL- Relocate icon · RT- Reconnect token ·
SD- Shadowing · TS- TypeScript type · TRK- Track/lock · UI- UI component · UP- Upload

---

## Common Failure Modes

| Failure mode | How to catch it |
|---|---|
| Wrong field name in design doc (stale draft) | Read both design doc and source for the same type; compare every field |
| Param not advertised to client | Read the full DTO mapping `when` expression; cross-check against every operation the controller reads params for |
| Operation silently uses wrong default | Search for `?: <literal>` in the controller; verify each default against the PRD |
| Grid and host overloads share a state key | Read both overloads; check the map key used for accumulated state |
| Stub field declared but never read | Read every field in every data class; grep for its name in all non-test files |
| Design doc describes wrong trigger for a reactive component | Read the component's `action()` or equivalent |
| Dead extension function for a deferred operation | Check every extension function against the set of operations that can dispatch to it |
| DTO field order differs from design doc | Note in discrepancies log to keep the doc in sync |
| Test encodes the wrong expectation | Read integration tests; verify the assertion matches the PRD, not just current code |
| Constructor omits a design-required field | For every `data class` instantiation in init/loader/navigation code, list the design's fields alongside the constructor arguments and compare one-for-one |
| Live runtime value shadowed by stored/immutable copy | When a domain object has both a stored-program value and a live persona attribute for the same concept (e.g. `personaPrograms[MASKING].rating` vs `persona?.masking`), verify the runtime path reads the degradable live value |
| Result handle discarded via `.first` / `component1()` | For every operation that returns `Pair<Result, Handle>` or similar, verify the handle is stored somewhere; `.first`-only extraction is a finding |
| Partial fix covers only one code path | After a prior finding is applied, enumerate all code paths the fix should cover; a condition `if (x != null)` may silently skip the null/grid/secondary path |
| Numeric param clamped below PRD maximum | For every `coerceIn(a, b)` on a PRD-governed value, verify `b` against the PRD's stated maximum; an incorrect cap silently prevents extended algorithm branches from firing |
| Test assertion trivially true by construction | `assertEquals(0, x.coerceAtMost(0))` is always 0 for non-negative x; `assertTrue(n >= 0)` is always true; these give false confidence — verify the assertion actually distinguishes correct from incorrect behaviour |
| Frontend hook behavioral contract unchecked | Reading types is not enough; verify every state-transition rule, role guard, and token-lifecycle clause in design_ui.md against the hook implementation |
| Manifest excerpt is a paraphrase or summary | Replace with a code token that could only have been written by someone who opened the file; if none can be supplied, re-read the file |
| File with findings has excerpt from a passing line only | Replace or supplement with an excerpt from the specific location that contains the finding — a passing-line excerpt does not prove the bug site was read |
