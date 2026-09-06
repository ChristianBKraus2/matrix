# Python Code Review Checklist (2026)

Priority tags used throughout: **[CRITICAL]** blocks merge no matter what · **[MUST-FIX]** blocks merge · **[HIGH]** strongly expected, context-dependent · **[RECOMMENDED]** improves quality, not a blocker.

---

## 1. Style & Formatting
- [ ] **[MUST-FIX]** 4-space indentation, no mixed tabs/spaces
- [ ] **[MUST-FIX]** Line length ≤88 chars (Ruff default) or per team's agreed limit (up to 99 per PEP 8 exception); docstrings/comments wrapped at 72
- [ ] **[MUST-FIX]** Naming: `snake_case` for functions/variables, `PascalCase` for classes, `UPPER_CASE` for constants
- [ ] **[MUST-FIX]** Imports in three sorted groups — stdlib, third-party, local — separated by blank lines (Ruff `I` rules)
- [ ] **[MUST-FIX]** `.pre-commit-config.yaml` present with `ruff-check --fix`, `ruff-format`, and a security hook (e.g., Bandit); `ruff-check` runs before `ruff-format` since fixes may need reformatting
- [ ] Ruff config lives in `pyproject.toml` (`[tool.ruff]`), not a separate `.flake8`/`setup.cfg`

## 2. Typing
- [ ] **[MUST-FIX]** Modern syntax: `list[str]` / `dict[str, int]` (PEP 585) instead of `List`/`Dict`; `X | None` (PEP 604) instead of `Optional[X]` — safe on all supported versions now that 3.9 is EOL
- [ ] **[MUST-FIX]** `mypy --strict` (or `ty`/`pyright` strict-equivalent) runs in CI, not just locally
- [ ] PEP 695 generics (`class Foo[T]:`, `type` alias statement) only used if minimum supported Python is 3.12+
- [ ] Public library packages ship a `py.typed` marker (PEP 561) so consumers get type info
- [ ] No blanket `# type: ignore` without a specific error code and a comment explaining why

## 3. Testing
- [ ] **[MUST-FIX]** ≥80% coverage on new/changed code (`--cov-fail-under=80`); coverage measures meaningful assertions, not just line execution
- [ ] Edge cases, error paths, and boundary conditions are explicitly tested, not just the happy path
- [ ] **[RECOMMENDED]** Assertions check exact expected values (`result == "expected"`), not just truthiness/non-null
- [ ] **[RECOMMENDED]** `pytest.raises(ExceptionType, match=...)` used to verify both exception type and message
- [ ] **[RECOMMENDED]** Tests are order-independent with no shared mutable state between them
- [ ] **[RECOMMENDED]** Fixture scope matches setup cost — `session` for expensive setup, `function` when isolation matters
- [ ] Tests actually fail when the underlying logic is broken (mutation-test spot check, or manually break the code and confirm red)
- [ ] Network/external-service calls are mocked or explicitly marked and skippable, so the suite is deterministic offline

## 4. Error Handling & Logging
- [ ] **[MUST-FIX]** No bare `except:` — always catch specific exception types
- [ ] **[MUST-FIX]** `try` blocks scoped to only the statement(s) that can actually raise the caught exception
- [ ] Custom exceptions inherit from the appropriate base (`ValueError`, `TypeError`, etc.) rather than generic `Exception`
- [ ] `raise ... from err` used when re-raising, to preserve the original traceback/context
- [ ] `logger.exception()` (not `print` or bare `logger.error(str(e))`) used when logging a caught exception, so the traceback is captured
- [ ] Structured logging (e.g., `structlog`/`loguru`, or stdlib `logging` with extra fields) used instead of unstructured string concatenation, especially in services
- [ ] No sensitive data (passwords, tokens, PII) written to logs
- [ ] Failures are surfaced (logged/raised), never silently swallowed with a bare `pass`

## 5. Readability & Structure
- [ ] **[HIGH]** Cyclomatic complexity ≤10 per function (or team-agreed threshold)
- [ ] **[HIGH]** Functions fit roughly on one screen (~40–60 lines) with a single responsibility
- [ ] **[MUST-FIX]** `with` statements used for files, DB connections, locks, and other resources needing guaranteed cleanup
- [ ] **[MUST-FIX]** No mutable default arguments (`def f(x, target=[])` is a real bug, not a style nit) — use `None` and initialize inside the function
- [ ] **[MUST-FIX]** `enumerate()` instead of manual index counters; comprehensions/generator expressions used for simple transformations instead of manual loop+append
- [ ] EAFP (try/except) preferred over LBYL existence checks where idiomatic (e.g., dict access)
- [ ] No deeply nested conditionals — consider early returns/guard clauses
- [ ] Dead code, commented-out blocks, and unused imports removed (Ruff `F` rules catch most of this automatically)

## 6. Async Code (if applicable)
- [ ] No blocking/synchronous calls (`requests`, `time.sleep`, blocking file I/O) inside `async def` functions — use async-native equivalents
- [ ] Async context managers used for connections/sessions (`async with aiohttp.ClientSession() as session:`)
- [ ] `asyncio.gather`/`TaskGroup` used appropriately for concurrent work, with exception handling for partial failures
- [ ] No fire-and-forget tasks without a reference kept and exception handling (orphaned tasks can silently swallow errors)
- [ ] Cancellation and timeouts handled explicitly for long-running async operations

## 7. Performance
- [ ] **[HIGH, profile first]** Membership tests against large collections use `set`/`dict` (O(1)), not `list` (O(n)) inside loops
- [ ] No accidental O(n²) patterns (e.g., list `.append` + `in` check inside a loop over another list)
- [ ] **[RECOMMENDED]** Generators used for streaming/large datasets instead of materializing full lists in memory
- [ ] **[RECOMMENDED]** `__slots__` considered for classes instantiated at high volume
- [ ] Performance claims/optimizations are backed by profiling (`cProfile`, `py-spy`, `Scalene`), not guesswork

## 8. Security (OWASP Top 10:2025-aligned)
- [ ] **[MUST-FIX]** SQL: all queries parameterized; no f-string/`.format()`/`%`/`+` building of SQL from user input (Bandit B608)
- [ ] **[MUST-FIX]** No `subprocess`/`os.system` with `shell=True` or string-concatenated commands on user-controlled input
- [ ] **[MUST-FIX]** No `pickle.load(s)` (incl. `pandas.read_pickle`, `torch.load`) on untrusted data; YAML loaded with `safe_load`/`SafeLoader` only; no `eval()`/`exec()` on user input
- [ ] **[CRITICAL]** No hardcoded secrets, API keys, passwords, or tokens in source — pulled from env vars or a secrets manager; `.env` files gitignored
- [ ] **[MUST-FIX]** User-supplied URLs used in outbound requests are validated against an allowlist (scheme, host, IP) to block SSRF — reject private ranges (`10.x`, `172.16-31.x`, `192.168.x`, `169.254.x`) and dangerous schemes (`file://`, `gopher://`)
- [ ] **[MUST-FIX]** Password hashing uses `bcrypt`/`argon2`/`scrypt`, never MD5/SHA1; `secrets` module (not `random`) for tokens/keys; no `verify=False` or `ssl.CERT_NONE` in HTTP clients
- [ ] **[MUST-FIX]** `pip-audit` (or equivalent) runs in CI; HIGH/CRITICAL CVEs block merge
- [ ] Bandit and/or Semgrep run on every diff, including AI-generated code — they catch different classes of issue and both are worth running
- [ ] Input validation applied at trust boundaries (API inputs, file uploads, deserialized payloads), not just at the UI layer

## 9. Documentation & Dependencies
- [ ] **[MUST-FIX]** Public functions/classes have docstrings documenting Args, Returns, and Raises (Google or NumPy style, applied consistently)
- [ ] **[MUST-FIX]** Lock file (`uv.lock`, `poetry.lock`, or pinned `requirements.txt` with hashes) committed and enforced in CI (`uv sync --locked` / `uv lock --check`)
- [ ] New dependencies come with a written justification: necessity, maintenance status, security history
- [ ] Dev-only dependencies live in `[dependency-groups]` (PEP 735) or `[project.optional-dependencies]`, not the main dependency list
- [ ] `requires-python` set in `pyproject.toml` so the package can't silently install on an incompatible interpreter
- [ ] Version declared as `dynamic` and derived from one authoritative source, not duplicated between `pyproject.toml` and `__version__`

## 10. Packaging & Project Structure
- [ ] `pyproject.toml` is the single source of truth (build system, project metadata, and all `[tool.*]` config) — no stray `setup.py`, `setup.cfg`, `.flake8`, or `pytest.ini` unless there's a specific reason
- [ ] `src/` layout used (`src/mypackage/`) to avoid accidental imports of the working directory during tests
- [ ] Build backend declared explicitly (`hatchling`, `poetry-core`, etc.) under `[build-system]`
- [ ] Package installs cleanly with `pip install -e .` and builds with `python -m build`
- [ ] CI pipeline runs lint, type-check, test, coverage, and dependency-audit as separate, clearly-labeled steps

## 11. Reviewing AI-Generated Python Code
AI-assisted code needs its own pass — the same study set showing 90%+ AI adoption also found high vulnerability-introduction rates in AI output.
- [ ] **[MUST-FIX]** Every new package name verified against the live PyPI registry before install — never trust an `import`/`pip install` line on sight (hallucinated/"slopsquatted" package names are a real, exploited risk)
- [ ] Bandit and Semgrep run specifically against AI-generated diffs — AI output tends to be syntactically clean, which makes insecure code easy to wave through
- [ ] Extra scrutiny on the highest AI-failure classes: XSS, log injection, SQL injection, insecure deserialization, hardcoded credentials
- [ ] AI-assistance disclosed in the commit/PR (e.g., an `Assisted-by:` trailer), with a named human owner accountable for the change
- [ ] API usage checked against the actual pinned library version — AI models frequently hallucinate deprecated interfaces or incorrect parameters
- [ ] Reviewer doesn't rubber-stamp based on clean formatting alone — formatting confidence and correctness confidence are not the same thing

## 12. Observability (for services/long-running agents)
- [ ] Logs include correlation/request IDs so a single request can be traced across components
- [ ] No unstructured log strings where structured fields would let logs be queried/aggregated
- [ ] Metrics/tracing (e.g., OpenTelemetry) instrumented for critical paths, not just errors
- [ ] Alerting thresholds tied to actual SLOs, not arbitrary defaults

---

### Quick automation setup
Wire these into CI/pre-commit first so reviewers focus on logic, not mechanics:
```yaml
repos:
  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.16.2
    hooks:
      - id: ruff-check
        args: [--fix]
      - id: ruff-format
  - repo: https://github.com/pre-commit/pre-commit-hooks
    rev: v5.0.0
    hooks:
      - id: trailing-whitespace
      - id: check-yaml
  - repo: https://github.com/PyCQA/bandit
    rev: 1.9.4
    hooks:
      - id: bandit
        args: ["-c", "pyproject.toml"]
        additional_dependencies: ["bandit[toml]"]
```
Plus in CI: `mypy --strict`, `pytest --cov=src --cov-fail-under=80`, `pip-audit`, `uv lock --check`.
