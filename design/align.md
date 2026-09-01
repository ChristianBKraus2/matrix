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
