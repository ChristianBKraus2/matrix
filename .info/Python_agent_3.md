# A2A + LangGraph Agent — Code Review Checklist (v2)

*Revised from the original to add LangGraph-specific safety checks, A2A cancellation/idempotency coverage, LLM cost controls, multi-agent loop detection, expanded testing, and priority tags.*

**Priority:** 🔴 Critical (security/safety — should block release) · 🟡 Recommended (fix before scaling) · ⚪ Nice-to-have

## 1. Graph / Agent Design
- [ ] 🟡 Agent logic (nodes, tools, routing, conditional edges) is a plain LangGraph graph with no A2A-specific code leaking into it
- [ ] 🟡 Graph and state schemas are pinned to LangGraph >= 1.0 (stabilized graph/state APIs)
- [ ] 🟡 Checkpointer is configured for conversational memory; team has explicitly decided whether session-only memory is acceptable or persistence across restarts is required
- [ ] 🔴 Tool calls have timeouts, retries (where idempotent), and explicit error handling so one stuck/failing tool can't hang the whole task
- [ ] ⚪ Final output key used by the A2A output mapper is clearly documented (custom state keys need a custom output_mapper)
- [ ] ⚪ Long-running graph paths emit intermediate state so streaming status updates are meaningful, not generic placeholders
- [ ] 🔴 No secrets, API keys, or credentials embedded in prompts, tool docstrings, or graph state that could leak into an artifact
- [ ] 🔴 Recursion/step limit (`recursion_limit`) is configured so a misrouted conditional edge can't loop indefinitely
- [ ] 🟡 Checkpointer backend is production-appropriate (e.g., Postgres/Redis-backed) rather than the in-memory default, if persistence is required
- [ ] 🟡 State-schema changes ship with a migration/versioning plan so existing checkpoints don't break resumability across deploys
- [ ] 🟡 Human-in-the-loop nodes (`interrupt()`) correctly pause execution and resume from the right checkpoint when paired with the `input-required` task state

## 2. AgentCard
- [ ] 🟡 Served correctly at `/.well-known/agent-card.json`
- [ ] 🟡 Name, description, skills, and examples fields are accurate and don't overclaim capabilities
- [ ] 🔴 AgentCard is signed by a trusted issuer (AgentCardSignature) if consuming or publishing into a multi-party ecosystem
- [ ] 🟡 `capabilities` block accurately reflects supported features (streaming, pushNotifications, etc.) — no false advertising
- [ ] ⚪ URL/host override configured correctly for the deployment environment (not hardcoded to localhost)
- [ ] 🔴 Skills are scoped to least privilege — sensitive skills (e.g., data analysis, financial actions) are restricted to specific authorized callers, not globally advertised

## 3. Protocol Compliance
- [ ] 🟡 Both `message/send` (sync) and `message/stream` (SSE) implemented and tested
- [ ] ⚪ `tasks/resubscribe` supported for clients reconnecting to a long-running task
- [ ] ⚪ Non-blocking mode (`returnImmediately`/submitted state + polling via `GetTask`) tested in addition to the blocking default
- [ ] 🟡 All task lifecycle states reachable and correctly transitioned: `submitted`, `working`, `input-required`, `auth-required`, `completed`, `failed`, `canceled`
- [ ] 🟡 Multi-turn flow correctly threads `contextId`/`taskId` when resuming an `input-required` task
- [ ] ⚪ Artifacts are well-formed (`artifactId`, `name`, `parts`) and map cleanly from the graph's final output
- [ ] 🟡 All `Part` types your agent claims to support (text, structured `data`, `url`, raw bytes) are validated, not just plain text
- [ ] ⚪ `A2A-Version` header handled/checked for protocol compatibility
- [ ] 🟡 JSON-RPC error responses used for protocol-level failures (not raw stack traces or generic 500s)
- [ ] 🔴 `tasks/cancel` is verified to actually halt in-flight tool/LLM calls and release resources — not just flip the task status
- [ ] 🟡 `message/send` is idempotent (e.g., via a client-supplied message/task ID) so a client retry after a timeout doesn't spawn a duplicate task

## 4. Security — Untrusted Input Handling
- [ ] 🔴 Every field from a remote AgentCard (name, description, skills.description, examples, tags) is sanitized/filtered before being interpolated into any prompt
- [ ] 🔴 Meta-prompt structure clearly delimits external content (cards, messages, artifacts from other agents) from your own system instructions
- [ ] 🔴 Incoming messages/artifacts from other agents are schema-validated (e.g., via Pydantic) before use, not trusted blindly
- [ ] 🔴 No task status or claim from a remote agent triggers a privileged action without independent verification
- [ ] 🔴 Consider running externally-sourced content through a prompt-injection filter that flags directives requesting data exfiltration or system/tool access
- [ ] 🔴 Output filtering in place to prevent artifacts from leaking sensitive or forbidden content back to the caller
- [ ] 🔴 Agent operates under principle of least privilege — only the tools/permissions needed for its advertised skills
- [ ] 🟡 Delegation to other A2A agents includes loop detection (e.g., call-depth or visited-agent tracking) so Agent A ↔ Agent B can't cycle indefinitely

## 5. Push Notifications (if supported)
- [ ] ⚪ Agent Card correctly sets `capabilities.pushNotifications: true` only if actually implemented
- [ ] 🔴 Webhook URLs are HTTPS-only, with ownership verified via challenge-response before first use
- [ ] 🔴 Client-registered webhook URLs are checked against an allowlist / not resolvable to internal/private network ranges (SSRF protection)
- [ ] 🔴 Outgoing notification payloads are signed (JWT with RSA/ECDSA, or HMAC/mTLS) with standard claims: `iss`, `aud`, `iat`, `exp`, `jti`
- [ ] 🟡 Notifications include a timestamp; webhook receiver rejects notifications that are too old
- [ ] 🔴 Replay protection in place using `jti` or a unique event ID so duplicate notifications aren't reprocessed
- [ ] 🟡 Signing keys support rotation via JWKS
- [ ] ⚪ PushNotificationConfig persists correctly until task completion or explicit deletion

## 6. Server / Infra
- [ ] ⚪ A2A endpoints mounted on your own FastAPI app (or equivalent) rather than a fully-managed black-box server, if custom endpoints are needed alongside A2A
- [ ] 🔴 All endpoints (including the agent card file) served over HTTPS with strong TLS (ideally 1.3) and current certificates
- [ ] 🔴 Rate limiting / concurrency limits exist for inbound A2A requests to prevent resource-exhaustion (DoS) attacks
- [ ] ⚪ Health check / readiness endpoint kept separate from the A2A JSON-RPC endpoint
- [ ] 🟡 Logging and observability cover both protocol-level events (task state transitions) and security-relevant events (auth failures, rejected AgentCards), ideally with trace-level tooling (e.g., LangSmith, OpenTelemetry) rather than log lines alone
- [ ] 🔴 Secrets/credentials never appear in logs or error responses
- [ ] 🟡 LLM-specific cost controls (token budgets, per-task spend caps) are enforced separately from inbound request rate limiting

## 7. Authentication & Authorization
- [ ] 🔴 Clear authN/authZ mechanism enforced for both inbound requests and outbound calls to other agents (zero-trust: verify, don't assume)
- [ ] 🔴 OAuth or skill-scoped tokens used to restrict which callers can invoke sensitive skills
- [ ] 🔴 Credential validation is rigorous — no implicit trust based on network location or prior success

## 8. Testing
- [ ] 🟡 Sync, streaming, and multi-turn conversation paths covered by integration tests
- [ ] ⚪ Non-blocking/polling and resubscribe paths tested, not just the default blocking flow
- [ ] 🔴 Adversarial test fixture: a crafted/malicious AgentCard or message designed to attempt prompt injection, confirmed to be neutralized
- [ ] 🟡 Schema-violation and malformed JSON-RPC requests tested for correct error handling
- [ ] 🟡 Rate-limit and abuse scenarios tested
- [ ] 🟡 Failure paths (tool errors, LLM timeout, malformed request, upstream API failure) return proper A2A error responses, not stack traces
- [ ] 🔴 Webhook signature verification tested with both valid and tampered/expired tokens
- [ ] 🟡 Concurrency/load testing performed with multiple simultaneous tasks
- [ ] ⚪ Failure-injection ("chaos") testing for downstream timeouts and network partitions, beyond unit-level failure paths
- [ ] 🟡 Regression suite covers prompt/agent behavior to catch silent drift after model or dependency upgrades

## 9. Documentation & Operational Readiness
- [ ] 🟡 README documents required env vars, model/LLM provider expectations, and how to run locally vs. in a container
- [ ] ⚪ Known limitations documented (e.g., no multi-modal support, session-only memory, rate limits of any downstream APIs used)
- [ ] 🔴 Disclaimer/warning present if the sample or service treats external agents as trusted by default (it shouldn't — flag if it does)
- [ ] ⚪ Versioning/compatibility notes for the A2A SDK and LangGraph versions pinned in dependencies
- [ ] ⚪ Deprecation and version-support policy documented for the AgentCard and its advertised skills

---

## 10. Full-Coverage Review Process

*Sections 1–9 define **what** to check. This section defines **how** to review a large A2A/LangGraph codebase without silently skipping parts of it.*

### 10.1 Philosophy — Completeness Over Sampling

This is a full-codebase review, not a spot check. Two principles are non-negotiable:

- **Every relevant file is read in full.** No file is declared clean without applying Sections 1–9 to it.
- **No risk-based skipping.** "This node looks like the others" or "this tool is simple" is not a valid skip reason. Similar files are a reason to move faster, never a reason to skip.

Never write:
- "The remaining nodes/tools follow the same pattern"
- "Other files are assumed consistent with the reviewed ones"
- "Checked a representative sample of tool files"
- Grouping multiple files into one manifest row (e.g. "all files in `tools/` — no issues")
- Declaring a file clean without reading it in full this session
- A manifest excerpt that describes behavior ("this node calls the search tool") rather than a verbatim code token
- Reusing an excerpt from a prior review without re-reading the file this session

If you catch yourself writing one of these, stop and go read the skipped files.

### 10.2 Project Layers (for coverage mapping)

Adapt paths to your actual repo; the point is that every file has a home before the review starts.

| Layer | Typical location | Role |
|---|---|---|
| **agent_core** | graph/, nodes/, tools/, state schema | LangGraph graph: nodes, tools, routing, prompts — no A2A/network types |
| **a2a_server** | server/, api/ | A2A protocol surface: AgentCard, task lifecycle, output mapper, push notifications |
| **integration** | clients/, remote_agents/ | Outbound calls to other A2A agents, remote AgentCard consumption, delegation |

Intended dependency direction:
```
integration (calls other agents)
    ↓
a2a_server (protocol, task lifecycle)
    ↓
agent_core (LangGraph graph — no framework/network deps)
```
A finding that reverses this (e.g. the graph importing FastAPI types, or a node calling another A2A agent directly instead of through the integration layer) is an architecture violation.

### 10.3 Review Phases

**Phase 0 — Setup** (before reading any source file)
1. Run the toolchain — tests, types, lint, and security scanning are free findings:
   ```
   pytest
   mypy .
   ruff check .
   bandit -r .
   ```
   Log every failure as a finding before proceeding.
2. Build the Coverage Manifest (10.4) from a full file listing: `git ls-files '*.py'`
3. Read any existing known-limitations/deferred doc in full (Section 9 already asks whether one exists) — it's the only valid basis for a `Skip:deferred` row.

**Phase 1 — Architecture pass** (all files, broad view, no findings written yet)
Read every file once to map dependencies. Answer:
- Does dependency direction match 10.2's diagram?
- Does any `agent_core` file import A2A/FastAPI/HTTP types?
- Does any node call another A2A agent directly instead of through `integration`?
- Is any file doing more than one job (e.g. a node that also owns webhook signing)?

**Phase 2 — Category deep dives** — run Sections 1–9 across all three layers, in this order:

| # | Category | Why first |
|---|---|---|
| 1 | Graph/Agent Design (§1) | Silent logic and safety bugs are hardest to detect |
| 2 | Security — Untrusted Input (§4) | Must be checked before concurrency/timing issues |
| 3 | Protocol Compliance (§3) | Task lifecycle correctness underlies everything else |
| 4 | AgentCard (§2) | Defines the contract every caller trusts |
| 5 | Push Notifications (§5) | Security-sensitive, isolated blast radius |
| 6 | Authentication & Authorization (§7) | Must hold across every entry point |
| 7 | Server/Infra (§6) | Availability and cost controls |
| 8 | Testing (§8) | Confirms behavior, not just presence of tests |
| 9 | Documentation (§9) | Last, since it describes everything above |

**Phase 3 — Cross-layer checks** — trace each path in 10.5 end-to-end.

**Phase 4 — Completion gate** — verify all conditions in 10.6.

### 10.4 Coverage Manifest

| File path | Status | Verbatim excerpt | Findings |
|---|---|---|---|
| *(populate from `git ls-files` before starting)* | | | |

**Status values:**
- `✓ Read — N lines` — file was read in full; line count proves it was opened to the end
- `Skip:deferred` — feature is explicitly out of scope per the documented known-limitations list (cite it)
- `Skip:infra` — build/tooling file with no reviewable agent logic (state why)

No other skip reason is valid.

**Verbatim excerpt requirements** (minimum):
- Files ≤ 100 lines: one excerpt from anywhere in the file
- Files 101–300 lines: two excerpts — opening third and closing third
- Files > 300 lines: three excerpts, one per third, each ≥ 50 lines apart

For any file with findings, at least one excerpt must come from the finding location itself. For graph/node files, list every node/tool function checked (`nodes: route_intent, call_search_tool, summarize_result`). For server files, list every route handler checked (`routes: message_send, message_stream, tasks_cancel`).

### 10.5 Cross-Layer Checks

These fail even when every layer passes its own review in isolation.

- **AgentCard ↔ implementation parity** — every advertised capability is backed by working code; every implemented skill is advertised, not silently hidden.
- **Task lifecycle ↔ graph state parity** — each LangGraph terminal/interrupt state maps to the correct A2A task state (`completed`, `failed`, `input-required`); trace at least one real execution through both.
- **Error propagation path** — a tool exception reaches the caller as a well-formed A2A JSON-RPC error or failed-task artifact, not a dropped connection or raw stack trace. Trace: `tool raises` → `graph node catches` → `output mapper` → `A2A error response`.
- **Push notification ↔ task completion parity** — a webhook fires for every terminal state it claims to cover, with a payload matching the final task artifact.
- **Multi-turn context threading** — `contextId`/`taskId` survive a full `input-required` → resume cycle, and resume from the correct LangGraph checkpoint, not a fresh one.
- **Cancellation cleanup** — `tasks/cancel` actually interrupts the running graph and releases open tool/LLM calls; confirm no orphaned background work continues after cancellation.

### 10.6 Completion Gate

Before declaring the review complete, verify:
1. **Count match** — file count in the manifest equals ✓ + justified Skip rows. State both counts.
2. **Toolchain clean** — tests, type-check, and lint all pass, or every failure is logged as a finding.
3. **Cross-layer checks complete** — all six paths in 10.5 traced.
4. **Adversarial check** — "If I'd stopped after the first N interesting findings, what would I have missed?" Go examine anything this reveals.
5. **Deferred currency** — every `Skip:deferred` row still matches current code; a known limitation that's since been implemented is a finding, not a valid skip.
6. **Root-cause consolidation** — group findings by underlying cause (e.g. "four findings trace to the graph having no recursion limit") rather than leaving a flat list.

### 10.7 Finding Format

```
### [SEVERITY] Short title
**File:** relative/path/to/file.py:line
**Layer:** agent_core | a2a_server | integration | cross-layer
**Issue:** What is wrong and why it matters at runtime.
**Recommendation:** Specific fix, referencing the relevant checklist item (e.g. §4).
```

| Level | Meaning | Example |
|---|---|---|
| 🔴 CRITICAL | Safety bypass, data loss, or crash | Missing recursion limit causes runaway LLM cost loop |
| 🔴 HIGH | Significant correctness/security problem | Remote AgentCard content interpolated into prompt unsanitized |
| 🟠 MEDIUM | Degraded behavior or latent bug | Webhook timestamp not checked, stale replay accepted |
| 🟡 LOW | Should improve, doesn't break functionality | Inconsistent error message format |
| 🔵 INFO | Optional improvement | Naming, minor duplication |

Avoid inflating style preferences to HIGH/CRITICAL — a good finding explains *why* it's the stated severity.

### 10.8 Common Full-Coverage Pitfalls

| Pitfall | Where it hides |
|---|---|
| Declaring all files in `tools/` clean after reading only one or two | Tool implementations |
| Skipping AgentCard `skills[]` entries because "they're just metadata" | `agent_card.py` / static JSON |
| Certifying push-notification code without tracing one real signed payload | `push_notifications.py` |
| Assuming `input-required` resume hits the right checkpoint without tracing an actual resume | Checkpointer + task manager glue code |
| Marking `tasks/cancel` done because the status flips, without confirming the graph run stopped | Task manager / cancellation handler |
| Marking test coverage sufficient because tests exist, without checking they assert on task/artifact content, not just HTTP status | Test suite |

### 10.9 Execution Checklist

```
[ ] Phase 0: Run tests, type-check, lint, security scan — log all failures
[ ] Phase 0: Build coverage manifest from full file listing
[ ] Phase 0: Read known-limitations/deferred doc in full

[ ] Phase 1: Architecture pass — dependency direction, layer violations

[ ] Phase 2: §1 Graph/Agent Design — full pass
[ ] Phase 2: §4 Security — Untrusted Input — full pass
[ ] Phase 2: §3 Protocol Compliance — full pass
[ ] Phase 2: §2 AgentCard — full pass
[ ] Phase 2: §5 Push Notifications — full pass
[ ] Phase 2: §7 Authentication & Authorization — full pass
[ ] Phase 2: §6 Server/Infra — full pass
[ ] Phase 2: §8 Testing — full pass
[ ] Phase 2: §9 Documentation — full pass

[ ] Phase 3: Cross-layer checks — all six paths in 10.5

[ ] Phase 4: Completion gate — all six conditions in 10.6
[ ] Phase 4: Root-cause consolidation
[ ] Phase 4: Findings published (e.g. code_review/<category>_<layer>.md)
```

---
*Adapted from `ChristianBKraus2/matrix/.info/Python_agent.md`.*
*Section 10 process adapted from `ChristianBKraus2/matrix/code_review/guidelines_for_code_review.md`.*
