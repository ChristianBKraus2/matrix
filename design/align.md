# Design-vs-Code Alignment Process

A repeatable process for auditing a codebase against its design documents, consulting PRDs for
verdicts, applying fixes where the PRD is clear, and documenting the rest.

---

## Prerequisites

Before starting, locate the following in the project:

| Artifact | Typical location | Role |
|---|---|---|
| Design docs | `design/design_core/`, `design/design_game/`, `design/design_ui/` | Specification — what the code should do |
| PRDs | `design/prd_*.md` | Authoritative rule source — consulted when design and code disagree |
| Protocol doc | `design/protocol.md` | Wire format spec — compare against DTO serialization |
| Discrepancies log | `design/discrepancies_without_prd.md` | Accumulates findings without a clear PRD verdict |
| Deferred list | `design/deferred.md` | Explicitly out-of-scope items — do not flag these as discrepancies |

---

## Methodology

### Rule 1 — Read everything completely

**Never use an agent type that reads excerpts** (e.g. the `Explore` subagent). It will miss content
past its read window and silently drop findings. Use direct `Read` tool calls in the main context,
or `general-purpose` agents that have full tool access and read whole files.

### Rule 2 — Start with the PRDs

Read `prd_*.md` files in full before auditing implementation files. Prior verdicts made by citing a
PRD by name (without reading it) may have been incorrect. The PRD is the final arbiter — every
finding's verdict depends on it.

### Rule 3 — Enumerate all files before starting

Run `find src -name "*.kt"`, `find frontend/src -name "*.ts" -o -name "*.tsx"`, and
`find design -name "*.md"` to get a complete file list. Check each file off as it is read.
Do not assume a file is unimportant because its name suggests it is a small data type.

### Rule 4 — Include test files

Test files encode expected behavior. A test that passes but encodes the wrong expectation is a
discrepancy. Priority test files: integration tests, DTO mapping tests, and any test whose name
matches a design doc section.

### Rule 5 — Include every layer

Audits that focus only on domain logic miss server, config, and frontend layers. Ensure coverage of:
- Domain model (data classes, sealed classes, enums)
- Business logic (resolvers, extensions, game loop)
- Server / controller layer (dispatch, DTO mapping, wire protocol)
- Config loaders (field names must match the domain model)
- Frontend types (`messages.ts` / equivalent) — must match the server DTOs
- Frontend components — rendering logic must match design_ui spec

---

## Iteration Structure

Run multiple iterations, each covering a distinct layer. After each iteration, apply PRD-backed
fixes and append unresolved findings to the discrepancies log.

### Recommended iteration order

| Iteration | What to read | Against what |
|---|---|---|
| 1 | PRDs in full | Nothing yet — establish the ground truth |
| 2 | Design docs (domain core) | PRDs — flag where design exceeds or contradicts PRD |
| 3 | Domain model source (data classes, enums, sealed classes) | Design docs — check field names, types, defaults |
| 4 | Business logic (resolvers, extensions, game loop) | Design doc algorithms section by section |
| 5 | Server / controller layer + DTOs | Design docs + protocol doc — check dispatch, paramKind, wire names |
| 6 | Frontend types + components | design_ui spec — check type shapes, conditional rendering rules |
| 7 | Config loaders | Design doc field names — check YAML/JSON key names match domain fields |
| 8 | Test files | Design docs — find tests that encode wrong expectations |

Iterations can be merged when the project is small. The order above ensures each layer is compared
against an already-verified truth source.

---

## Per-File Checklist

For every file read, check:

- [ ] Every field name matches the design doc (case, pluralization, abbreviation)
- [ ] Every default value matches the design doc
- [ ] Every algorithm step matches the design doc (TN formulas, die pools, floors/ceilings)
- [ ] Every sealed class / enum variant matches the design doc (no missing, no extra)
- [ ] Every method the design doc names is implemented and has the right signature
- [ ] No stub implementations that the design doc treats as complete (look for `TODO`, hardcoded
      return values, `error("not implemented")`)
- [ ] No dead code for operations the design doc describes as active
- [ ] Wire format field names (JSON keys, `@SerialName`) match the protocol doc
- [ ] `paramKind` advertised to the client matches what the server actually reads

---

## Classifying Each Finding

For each discrepancy found, determine:

1. **Is it in `deferred.md`?** → Not a discrepancy; skip.
2. **Does a PRD clause directly resolve it?** → Apply the fix. Mark ✓ resolved in the
   discrepancies log.
3. **Does the PRD mention the topic but not resolve the specific gap?** → Document in the
   discrepancies log with the PRD clause cited. No code change.
4. **Is it purely a code-quality issue (duplication, dead code, naming)?** → Document only; no
   PRD-backed fix.
5. **Does the code appear correct and the design doc stale?** → Update the design doc, not the
   code. Mark ✓ resolved.

---

## Discrepancies Log Format

Each entry in `discrepancies_without_prd.md` should contain:

```markdown
## XX-N — Short description

**Design:** What the design doc says.

**Code:** What the code actually does (include file path and relevant snippet).

**Impact:** What goes wrong at runtime, if anything.

**PRD verdict:** Which PRD clause applies, and what it says. State explicitly if there is no
PRD guidance.

**Status / Fix applied / Fix required:** What was done or what needs doing.
```

Prefix conventions:
- `AP-` ActionsPanel  
- `AS-` AnalyzeSecurity  
- `CD-` Cyberdeck/program mechanics  
- `CM-` ConditionMonitor / combat  
- `DC-` Dead code  
- `DF-` Detection factor  
- `DS-` Dead/stub field  
- `DU-` Download/upload handle  
- `DUP-` Code duplication  
- `EP-` EntitiesPanel  
- `GC-` GameContext  
- `GL-` Game loop  
- `GM-` game.md  
- `GR-` Grid-related  
- `IC-` IC behavior  
- `IM-` INVOKE_MEDIC  
- `INT-` Interrogation state  
- `MC-` Missing / coverage  
- `MS-` missing.md  
- `NM-` Naming mismatch  
- `NFR-` Non-functional requirement  
- `OP-` Operations  
- `PB-` Probe behavior  
- `PG-` PrivateGrid  
- `PR-` Protocol  
- `RL-` Relocate icon  
- `RT-` Reconnect token  
- `SD-` Shadowing / naming collision  
- `TS-` TypeScript type  
- `TRK-` Track/lock  
- `UI-` UI component  
- `UP-` Upload  

---

## Common Failure Modes

| Failure mode | How to catch it |
|---|---|
| Wrong field name in design doc (stale draft) | Read both design doc and source file for the same type; compare every field |
| Param not advertised to client (`paramKind = null`) | Read the full DTO mapping `when` expression; cross-check against every operation the controller reads params for |
| Operation silently uses wrong default | Search for `?: <literal>` in the controller; verify each default against the PRD |
| Grid and host overloads share a state key | Read both overloads of the same operation; check the map key used for accumulated state |
| Stub field declared but never read | Read every field in every data class; grep for its name in all non-test files |
| Design doc describes wrong trigger for a reactive component | Read the component's `action()` or equivalent; compare against the design doc description |
| Dead extension function for a deferred operation | Check every extension function defined in the controller against the set of operations that can actually dispatch to it |
| DTO field order differs from design doc | Non-functional, but worth noting in the discrepancies log to keep the doc in sync |
| Test encodes the wrong expectation | Read integration tests for the operation; verify the assertion matches the PRD, not just the current code |

---

## When to Stop

Stop when:
- Every file in the codebase has been fully read (use the enumeration list as a checklist)
- Every design doc has been compared against its corresponding source files
- Every PRD has been read in full
- Two consecutive iterations produce zero new findings

If a finding requires a large coordinated fix (multiple files, cross-cutting concern), document it
in the discrepancies log rather than applying it mid-iteration. Address it as a focused change after
the audit is complete.
