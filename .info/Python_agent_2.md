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
*Adapted from `ChristianBKraus2/matrix/.info/Python_agent.md`.*
