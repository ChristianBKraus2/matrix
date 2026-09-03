How best to code review a complete coding project that does not fit into a context window by llm (e.g. Claude code based on sonnet 4.6 or opus 4.6)

For a project that is larger than an LLM's context window, the best approach is not to try to stuff the entire repository into context. Instead, treat the review as a multi-pass investigation with persistent artifacts.

A strong workflow looks like this:

Build a map of the repository
Establish architecture and invariants
Review subsystem-by-subsystem
Trace important cross-cutting flows
Run automated/static tests and use their output to guide further review
Do dedicated passes for security, concurrency, performance, error handling, and maintainability
Have a final "review the review" pass
Maintain a persistent findings file so context can be safely discarded between passes
1. Start with a repository map

Have the model inspect only things needed to understand the structure:

README
package manifests / lockfiles
build configuration
CI configuration
directory tree
entry points
configuration
database/schema/migrations
API definitions
tests

Then ask it to create something like:

docs/code-review/
  architecture.md
  components.md
  data-flows.md
  invariants.md
  review-plan.md
  findings.md

The important point is that these files become external memory.

For example, architecture.md might contain:

# Architecture

## Components

- api/
  HTTP/API boundary
- domain/
  business logic
- persistence/
  database access
- workers/
  asynchronous processing
- auth/
  authentication/authorization

## Important flows

1. HTTP request -> API -> domain -> persistence
2. Event -> worker -> domain -> persistence
3. Login -> auth -> session/token -> API middleware

## Critical invariants

- Users can only access resources belonging to their organization.
- Payment state transitions are monotonic.
- Database writes must be transactional with corresponding outbox events.

This is much more useful than a gigantic summary of every file.

2. Review in coherent chunks, not arbitrary chunks

Don't divide a repository into "first 50 files / next 50 files."

Divide it according to architectural boundaries:

Pass 1: authentication
Pass 2: authorization
Pass 3: API layer
Pass 4: business/domain logic
Pass 5: persistence
Pass 6: background jobs
Pass 7: frontend
Pass 8: infrastructure/deployment

Within each subsystem, give the model enough surrounding code to understand its dependencies.

For example, reviewing payments/ should also expose:

interfaces it calls
database models
relevant configuration
authentication/authorization boundaries
callers
tests

The goal is not:

"Review these 30 files."

It is:

"Understand and review this architectural responsibility."

3. Use a hierarchical review

This is probably the single biggest improvement over naive LLM code review.

Pass A — architectural review

Ask:

Identify architectural risks, broken invariants, dangerous coupling, missing boundaries, and suspicious data flows. Do not perform detailed line-by-line review yet.

This catches things such as:

authorization enforced in the wrong layer
inconsistent transaction boundaries
duplicated sources of truth
incorrect service boundaries
unsafe trust boundaries
state machines that don't actually enforce valid transitions
Pass B — subsystem review

Then go deep into each component.

Ask the model to look for:

Correctness
Security
Concurrency
Error handling
Resource management
Transactions
Validation
API contracts
State management
Tests
Observability
Performance
Maintainability
Pass C — cross-component review

This is crucial.

Many serious bugs exist between components rather than inside them.

For example:

API -> service -> database

Each component might look correct independently, while the combined system has:

TOCTOU race
authorization bypass
transaction inconsistency
incorrect retry semantics
idempotency failure
stale-cache bug

So explicitly review flows across boundaries.

4. Ask the model to follow data, not files

A particularly powerful technique is flow-based review.

Instead of:

Review OrderService.java.

Ask:

Trace an order from HTTP request through validation, authorization, domain logic, persistence, event publication, asynchronous processing, and eventual response. Identify every place where assumptions about the order's state or identity change. Look for security and correctness vulnerabilities.

Then do the same for critical flows:

Authentication
Authorization
Payments
User creation
Data deletion
File upload
Password reset
Background jobs
External API calls
Database migrations
Message processing

This is much closer to how an experienced human reviewer investigates a large system.

5. Make the LLM maintain a finding ledger

Don't let findings disappear when the context resets.

For example:

# findings.md

## CRITICAL

### F-001 — Organization authorization bypass
Status: confirmed
Location: api/orders.ts:84
Flow: GET /orders/:id
Evidence:
...
Impact:
...
Suggested fix:
...

## HIGH

### F-002 — Payment retry is not idempotent
Status: needs verification
...

## MEDIUM

### F-003 — Worker silently drops malformed messages
...

Give every finding an ID.

Then future sessions can say:

Read architecture.md, invariants.md, findings.md. Continue the review. Do not repeat already-investigated areas unless new evidence requires revisiting them.

This turns an LLM with a finite context window into something closer to a persistent reviewing agent.

6. Separate discovery from verification

This is another major improvement.

During the first pass, allow the model to be suspicious.

You want:

"Potential SQL injection here."

not:

"SQL injection exists here."

Then perform a second pass:

Verify F-017. Trace all inputs to this query, inspect the query construction, identify whether attacker-controlled data can reach it, and determine whether the finding is real. Do not assume the previous review was correct.

This dramatically reduces false positives.

A useful lifecycle is:

Candidate
   ↓
Investigate
   ↓
Confirmed / Rejected / Needs more evidence
   ↓
Impact assessment
   ↓
Recommended remediation
7. Use tools aggressively

The LLM shouldn't be doing everything by reading source.

Have it use:

compiler/type checker
unit tests
integration tests
linters
static analyzers
dependency scanners
SAST
secret scanners
race detectors
sanitizers
coverage tools
database/schema validators
benchmarks/profilers where appropriate

Then feed the interesting output back into the review.

For example:

Run tests
    ↓
42 failures
    ↓
Cluster failures
    ↓
Investigate clusters
    ↓
Trace into architecture
    ↓
Look for related latent bugs

The LLM becomes the investigator rather than merely a syntax reader.

8. Use multiple independent review passes

Don't ask one giant prompt:

"Review this entire project."

Instead use different reviewers/prompts with deliberately different objectives.

For example:

Reviewer 1 — correctness

Find bugs that can cause incorrect behavior under valid inputs.

Reviewer 2 — security

Assume an attacker controls every external input. Trace trust boundaries and identify authorization, injection, SSRF, deserialization, secrets, path traversal, race and privilege-escalation issues.

Reviewer 3 — concurrency

Look specifically for races, deadlocks, lost updates, duplicate processing, inconsistent locking, transaction isolation problems, and retry/idempotency bugs.

Reviewer 4 — reliability

Look for failures involving retries, timeouts, partial failures, queues, transactions, external dependencies, crashes and recovery.

Reviewer 5 — performance

Identify unnecessary I/O, N+1 queries, excessive allocations, unbounded operations, contention and scalability bottlenecks.

Reviewer 6 — tests

Identify important behavior that isn't adequately tested and infer likely hidden bugs from the testing gaps.

Then consolidate.

This gives you something analogous to independent reviewers with different specialties.

9. Explicitly review the tests as evidence

Tests are extremely valuable context compression.

Instead of feeding thousands of lines of implementation to the model, first have it understand:

What behavior does the test suite assert?
What behavior does it fail to assert?
What invariants appear to be important?
Which components have almost no tests?

Then compare:

intended behavior
        ↓
tests
        ↓
implementation

Disagreement between those three is often where the bugs are.

10. Use a "claim → evidence" discipline

LLMs are prone to producing plausible-sounding findings.

Require every finding to contain:

Finding
Severity
Location
Execution/data flow
Evidence
Why this is actually a bug
Conditions required
Impact
Confidence
Suggested fix

For example:

F-012
Severity: HIGH
Confidence: HIGH

Claim:
A user can retrieve another organization's invoice.

Evidence:
1. request.user.orgId comes from authenticated session
2. invoice lookup at X does not constrain org_id
3. authorization check at Y occurs after retrieval
4. returned object is serialized at Z

Exploit condition:
Attacker knows another invoice ID.

Impact:
Cross-tenant data disclosure.

That's vastly more useful than:

"Potential IDOR vulnerability."

11. Have a final adversarial pass

Once you've accumulated findings, start a fresh context.

Give the model:

architecture.md
invariants.md
findings.md
review plan
test results

Then ask:

Act as a skeptical senior engineer reviewing the findings from another reviewer. Attempt to disprove every finding. Identify false positives, duplicated findings, incorrect severity ratings, missing evidence, and important issues the previous review likely missed.

Then another useful question:

Given the architecture and all confirmed findings, what common root causes could explain multiple findings?

This often turns 20 findings into 4 underlying design problems.

A practical Claude Code workflow

If you're using Claude Code, I'd structure the repository like this:

project/
├── src/
├── tests/
├── ...
└── .review/
    ├── architecture.md
    ├── components.md
    ├── invariants.md
    ├── flows.md
    ├── plan.md
    ├── findings.md
    ├── test-results.md
    └── completed.md

Then use separate sessions for major passes.

Session 1
You are preparing a comprehensive code review.

Do not attempt to review every source file yet.

First understand the repository:
- architecture
- components
- entry points
- dependencies
- trust boundaries
- important data flows
- persistence
- concurrency model
- external integrations
- tests
- deployment

Create/update .review/architecture.md
.review/components.md
.review/invariants.md
.review/flows.md
.review/plan.md

Do not invent facts. Mark uncertain conclusions explicitly.
Session 2
Read the existing .review documentation.

Review the authentication and authorization subsystem deeply.

Trace relevant callers and callees as necessary.

Look for:
- authentication bypass
- authorization bypass
- privilege escalation
- tenant isolation
- session/token problems
- race conditions
- insecure defaults
- error handling issues

Record only evidence-backed findings in .review/findings.md.

Do not duplicate existing findings.
Session 3

Same pattern for another subsystem.

And so on.

One important refinement: dynamically allocate context

Don't treat every part of the codebase equally.

A good review should spend more context on high-risk/high-complexity areas.

You can rank components:

Component	Risk	Complexity	Review depth
Authentication	Very high	Medium	Very deep
Payments	Very high	High	Very deep
Database layer	High	High	Deep
CLI formatting	Low	Low	Shallow
Generated code	Low	High	Minimal
CRUD admin UI	Medium	Medium	Moderate

This makes the finite context budget work in your favor.

Even better: build a dependency/impact graph

For a serious project, I'd have the LLM produce:

Component
   ↓
entry points
   ↓
dependencies
   ↓
data stores
   ↓
external systems
   ↓
security boundaries
   ↓
async boundaries

Then use that graph to decide what context to load.

For example, if reviewing:

POST /transfer

you might automatically collect:

route
→ controller
→ auth middleware
→ transfer service
→ account service
→ transaction abstraction
→ DB models
→ event publisher
→ queue consumer
→ notification service
→ relevant tests

That's much better than blindly selecting files by directory.

The key idea

Think of the LLM's context window as working memory, not storage.

Your repository + .review/ artifacts + test output + git history are the long-term memory.

The architecture becomes:

                    ┌──────────────────┐
                    │    Repository    │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ Repository Map   │
                    └────────┬─────────┘
                             │
             ┌───────────────┼───────────────┐
             ▼               ▼               ▼
        Security        Correctness      Reliability
         review            review           review
             │               │               │
             └───────────────┼───────────────┘
                             ▼
                    ┌──────────────────┐
                    │ Findings Ledger  │
                    └────────┬─────────┘
                             ▼
                    ┌──────────────────┐
                    │ Verification     │
                    └────────┬─────────┘
                             ▼
                    ┌──────────────────┐
                    │ Final Review     │
                    └──────────────────┘

The biggest mistake is optimizing for "how do I fit the whole codebase into Sonnet/Opus?" The better question is "how do I give the model the right slice of the codebase plus enough persistent understanding to reason about it?"

For a genuinely large codebase, I'd favor 20–50 focused review sessions with persistent artifacts over one enormous context-window review. The latter feels comprehensive but tends to produce shallow coverage and poor cross-file reasoning.