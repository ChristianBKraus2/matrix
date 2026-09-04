# Design-vs-Code Alignment Process

A repeatable process for auditing a codebase against its design documents.
Completeness must be **provable by artifact**, not by assertion.

---

## Scope — a complete audit, not a sampled review

This is a **design-vs-code conformance audit**, not a classical code review. The goal is to
prove, file by file, that the code matches every field name, default, formula, and enum
variant the design docs / PRDs specify. Two consequences are non-negotiable:

- **Every file is read in full**, and the full Per-File Checklist is applied to all of them.
- There is **no risk-based skipping and no risk-based depth-scaling.** The one file with the
  wrong `@SerialName` is invisible to any risk ranking, so ranking cannot be trusted to
  decide what to read deeply. Completeness is the whole point.

Generic large-codebase review advice (e.g. `.info/large_reviews.md`) optimises the opposite
way — by *sampling*. The following ideas from it are **deliberately rejected**; do not
reintroduce them under the guise of efficiency:

- Risk-weighted review depth, dynamic context allocation, dependency-graph-driven loading.
- "Follow data, not files" as a *substitute* for reading every file.
- Multiple specialist reviewer passes (security / performance / concurrency / reliability).
- Tests as "context compression" — reading tests *instead of* implementation.

Only the parts of that advice that **support completeness at scale** are adopted here: a
persistent spec baseline and coverage ledger (so a complete audit can span many context
windows), the toolchain as a free conformance-break detector, end-to-end field-propagation
checks layered *on top of* full file coverage, and root-cause consolidation of the complete
finding set. These appear as Iteration 0, the cross-session subsection, and the Completion
Gate below.

---

## Prerequisites

| Artifact | Typical location | Role |
|---|---|---|
| Design docs | `design/design_core/`, `design/design_game/`, `design/design_ui/` | Specification |
| PRDs | `design/prd_*.md` | Authoritative rule source — consulted when design and code disagree |
| Protocol doc | `design/protocol.md` | Wire format spec |
| Discrepancies log | `design/discrepancies_without_prd.md` | Accumulates unresolved findings + the run's prompt log (see Step 0.5) |
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

## Step 0.5 — Capture the run context (prompt log)

As the **first action of the run** — before reading any file — open the run's discrepancies log
(`design/discrepancies_without_prd.md`) and create an **Audit Run Context** section at the very
top of the file, immediately after the title and before the prefix conventions. This log records
the prompts that drove the audit so two runs (and the context each was given) can be compared
later.

Rules for the prompt log:

- Record the **initiating user prompt verbatim**, dated. This is the first message that started
  the alignment.
- **Append every subsequent user instruction verbatim**, in the order received, as the run
  proceeds — scope changes, clarifications, files to ignore, deferred-list pointers, etc.
- **Quote exactly. Never paraphrase.** The point is faithful run-to-run comparison; a summary
  loses the exact wording that may differ between runs.
- Stop at the **audit → correction boundary**: the first user message that directs code changes /
  fixes / corrections (as opposed to audit scope, findings, or their classification). Mark that
  boundary explicitly. Prompts after it are correction-phase and are **not** recorded in this log.

Copy-paste template:

```markdown
## Audit Run Context (prompt log)

Verbatim record of the prompts that drove this audit — the initiating request plus every
subsequent user instruction, up to (not including) the point where correction/fixes began.
Purpose: compare audit runs and the context each was run in. Quote exactly; do not paraphrase.

### Initiating prompt — <YYYY-MM-DD>
> <verbatim first user message that started the alignment>

### Additional context / clarifications (in order received)
- <YYYY-MM-DD> — > <verbatim user message>

### Audit → correction boundary
Correction phase began <YYYY-MM-DD> with:
> <verbatim user message that first directed fixes/corrections>
(Prompts after this line are correction-phase and are NOT recorded in this log.)
```

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
- Using Grep or a search call to locate specific content and reporting only those hits as the manifest excerpt — this is the same failure mode as using Explore: it finds only what you already know to look for
- A manifest excerpt reused from a prior audit run without re-reading the file in the current session — the excerpt must be obtained from a `Read` call made in this session, not copied from a previous manifest
- Citing a design doc clause without having read that design doc in full in this session (mirrors the PRD rule above; applies to `design_core/`, `design_game/`, and `design_ui/` files equally)

If you find yourself writing one of these, stop and read the skipped files.

---

## Methodology

### Rule 1 — Read complete files, not excerpts

**Never use an agent type that reads excerpts** (e.g. the `Explore` subagent) —
it silently drops content past its read window.

**Never substitute a Grep or search call for a Read.** Grep finds only what you already
know to look for; a complete Read finds what you don't. For every file in the manifest,
use the `Read` tool (or a `general-purpose` agent with full `Read` access) starting at
line 1. For files that exceed the Read tool's single-call limit, issue sequential `Read`
calls with increasing `offset` until the entire file is consumed. A file is not read
until its last line has been seen.

### Rule 2 — Record line count and verbatim excerpts proving full coverage

The Status field for every ✓ row must include the file's total line count:
`✓ Read — N lines`. This proves the file was opened to its end, not grepped.

The Verbatim excerpt field must contain code tokens copied directly from the file.
Paraphrase is not acceptable. Minimum excerpt requirements scale with file size:

- **Files ≤ 100 lines:** one verbatim excerpt from anywhere in the file.
- **Files 101–300 lines:** two verbatim excerpts — one from the opening third,
  one from the closing third (each separated by at least 30 source lines).
- **Files > 300 lines:** three verbatim excerpts — one from each third of the
  file (opening, middle, closing), each separated by at least 50 source lines.

For any file whose Findings column is non-empty, at least one excerpt must come
from the **specific code location that contains the finding** — not from a
passing line elsewhere in the file.

More excerpts are always acceptable; the above are minimums.

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

The same requirement applies to frontend hook files (any `.ts` file under `hooks/`)
and any `.tsx` component file that exports more than one named function or component.
The manifest entry must list every exported hook, function, and component checked,
e.g. `exports: useWebSocket, join, connect, reset`.

### Rule 9 — Constructor calls must be verified for completeness

Whenever a domain object is constructed (e.g. `Persona(…)`, `Cyberdeck(…)`,
`GameContext(…)`), cross-reference the constructor call against the design doc to verify
that every field the design specifies is supplied. Omissions are silent: a missing
`sleazeRating = …` compiles without error but violates the spec.

Also applies to factory functions and loader methods that produce domain objects from
config or from other domain objects.

### Rule 11 — Verify deferred items are current before skipping

Before marking any file or feature Skip:deferred, read the corresponding entry in
`deferred.md` and verify it still accurately describes the current code state.
If the code has advanced past the deferred entry — a field the entry says is absent
now exists, a stub the entry treats as intentional now has a real implementation, a
restriction the entry documents has since been lifted — that gap is a DS- finding
against `deferred.md`, and the file must receive a ✓ Read entry in the manifest
rather than Skip:deferred.

### Rule 10 — Verify the post-fix surface after a prior audit

When running an audit on a codebase that has had previous findings applied, each fixed
finding may have been applied only to the most obvious code path. For every partial-fix
pattern — especially conditional guards of the form `if (x != null) callResolver(…)` —
enumerate all code paths that should trigger the resolver and verify each one. A fix that
covers the host case but not the grid case is a new finding, not a closed one.

### Rule 12 — Trace each wire field end-to-end (additive to full file coverage)

After the per-file passes are complete, add one more coverage layer: for every field that
crosses the wire, follow its name and shape through **every hop** and confirm it survives
unchanged. This is *additional* coverage layered on top of reading every file — never a
substitute for it. A concrete decker-operation path in this repo:

```
client action
  → WebSocketDeckerController (dispatch + DTO mapping)
  → DeckerOperationsExtensions (operation)
  → resolver
  → domain mutation
  → DTO @SerialName
  → frontend/src/types/messages.ts
  → component render
```

A field can pass the per-file checklist at every individual hop yet still be renamed or
dropped *between* hops — that is exactly the "grid vs host overload" and "DTO field order /
name differs" failure mode this catches. Record each traced field in the manifest so the
claim is falsifiable.

---

## Iteration Structure

| Iteration | What to read | Against what |
|---|---|---|
| 0 | Run the toolchain first; distill a spec baseline from the Iter 1–2 reads | → `design/audit/spec_baseline.md` |
| 1 | PRDs in full | Nothing yet — establish ground truth |
| 2 | Design docs (domain core) | PRDs |
| 3 | Domain model source (data classes, enums, sealed classes) | Design docs |
| 4 | Business logic (resolvers, extensions, game loop) | Design doc algorithms |
| 5 | Server / controller layer + DTOs | Design docs + protocol doc |
| 5b | Wire fields traced end-to-end (field-propagation, see below) | Protocol doc |
| 6 | Frontend types + components | design_ui spec |
| 7 | Config loaders | Design doc field names |
| 8 | Test files | Design docs |

Iterations can be merged when the project is small. **No iteration may be
declared complete until every file assigned to it has a ✓ in the manifest.**

### Iteration 0 — Extract the spec baseline and run the toolchain

Two setup steps that make a *complete* audit cheaper without cutting any coverage:

1. **Distill the spec baseline.** After reading the PRDs and design docs (Iterations 1–2),
   write the comparison baseline to `design/audit/spec_baseline.md`: invariants, TN / dice
   formulas, the field list per domain type, enum / sealed-variant sets, and wire field
   names. This is the single artifact every later session reads first, so the spec is not
   re-derived each time. It **supplements, never replaces** reading the actual PRDs and
   design docs in each session — Rule 3 and the Prohibited Patterns still forbid citing a
   PRD or design clause you have not read in full this session.

2. **Run the toolchain first.** Before deep reading, run the build, type-checker, and tests:

   ```
   gradlew.bat test integrationTest      # Kotlin
   cd frontend && npx tsc --noEmit        # frontend types
   ```

   A compile error, a type mismatch, or a DTO ↔ `messages.ts` disagreement is a conformance
   finding the toolchain hands you for free — log it like any other. This **supplements the
   complete read; it never substitutes for it.** Test files are still read and audited in
   full in Iteration 8 (Rule 6).

### Running the audit across sessions

A complete audit of a large codebase will not fit one context window. Treat the context
window as working memory and the on-disk artifacts as long-term memory:

- Each iteration (or subsystem within one) is a **session**.
- Every session **begins by reading** the coverage manifest, `spec_baseline.md`, and the
  discrepancies log, then continues only with manifest rows not yet ✓.
- The manifest is the **completeness ledger** that survives context resets — it, not memory,
  is what proves the audit reached every file.
- Before continuing each session, **append any new user instructions to the Audit Run Context
  prompt log verbatim** (Step 0.5), until the correction phase begins — so the run's driving
  context survives context resets alongside the manifest and findings.

This does not relax any rule: a row becomes ✓ only after a full `Read` **in the current
session** (Rule 2 and the Prohibited Patterns are unchanged — a reused excerpt from a prior
session is not a ✓).

---

## Completion Gate

Before declaring the audit complete, verify all six conditions:

1. **Count match** — count of files returned by `find` equals count of
   ✓ + justified Skip rows in the manifest. State both counts explicitly.
2. **PRD coverage** — every PRD has been read in full in this session.
3. **Adversarial check** — answer explicitly: "If I had done a spot check and
   stopped after the first N interesting findings, what would I have missed?"
   If the answer reveals anything unexamined, go examine it.
4. **Deferred currency** — for every Skip:deferred row in the manifest, confirm
   that the corresponding `deferred.md` entry still matches the current code state.
   A deferred entry that no longer reflects reality is a DS- finding, not a valid
   skip reason.
5. **Root-cause consolidation** — group the complete set of confirmed findings into the
   underlying design causes that explain them (e.g. "design doc draft never updated after
   the `sleaze` → `sleazeRating` rename," "protocol.md predates the grid/host split"). This
   drops no finding — every discrepancy still stands in the log — but it turns a long flat
   list into a few actionable causes and often exposes sibling discrepancies the flat pass
   missed.
6. **Run context recorded** — the Audit Run Context prompt log (Step 0.5) exists at the top of
   the discrepancies log, records the initiating prompt and every subsequent user instruction
   verbatim (not paraphrased), and marks the audit→correction boundary.

Do not write "audit complete" until all six are satisfied.

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
| Agent used Grep instead of Read for a manifest file | Grep hits are not a substitute for a full Read; re-read the file completely from line 1 and update the line count in the Status field |
| Deferred entry describes absent feature that now exists in code | Before accepting Skip:deferred, read the deferred.md entry and verify it matches the current file; if code has advanced past the entry, raise DS- and mark the file ✓ Read |
