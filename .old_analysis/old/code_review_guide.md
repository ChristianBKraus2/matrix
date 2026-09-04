# Code Review Guide

Covers Kotlin backend code, Ktor WebSocket server implementations, and React UIs. Each section lists patterns to enforce and anti-patterns to flag.

---

## Table of Contents

1. [Kotlin](#1-kotlin)
2. [Ktor WebSockets](#2-ktor-websockets)
3. [React UI](#3-react-ui)
4. [Cross-Cutting Checks](#4-cross-cutting-checks)

---

## 1. Kotlin

### Nullability

**Enforce**
- `?.let { }` / `?: return` for null guards instead of nested `if (x != null)` blocks
- `requireNotNull(x) { "message" }` / `checkNotNull(x) { "message" }` at entry points — self-documenting preconditions
- `as?` safe cast with a fallback rather than an unchecked `as`
- `?.takeIf { }` / `?.takeUnless { }` for conditional null propagation

**Flag**
- `!!` — every occurrence needs a justification comment; almost always replaceable with a safe call or early return
- Returning `null` as a sentinel value where a sealed type or `Result<T>` communicates intent better
- Silent `?.` chains (`user?.address?.city`) where the null case could hide a bug — ask whether the null is intentional or an unhandled error

---

### Data Classes

**Enforce**
- Use `data class` for value objects (DTOs, game state snapshots) — gets `equals`, `hashCode`, `toString`, `copy` for free
- Use `copy()` to produce modified instances rather than making fields `var`
- All `data class` fields should be `val` unless mutation is genuinely required

**Flag**
- `data class` with `var` fields — defeats structural equality and makes reasoning about state harder
- `data class` used for entities with identity semantics (should be a plain `class`)
- Plain `class` used for what is clearly a DTO — missing the convenience methods

---

### Sealed Classes and Exhaustive `when`

**Enforce**
- `sealed class` / `sealed interface` for any type whose variants are fully known at compile time (message types, game phases, error categories)
- `when` used as an expression (assigned to a value) — compiler enforces exhaustiveness
- `when` on a sealed type with no `else` branch — lets the compiler catch missing cases when new variants are added

**Flag**
- `when` with `else -> Unit` or `else -> { }` on a sealed type — silently swallows unhandled variants
- `when` as a statement on a sealed type — no compiler enforcement
- Long `if/else if` chains that should be a `when`

---

### Collections

**Enforce**
- `listOf`, `setOf`, `mapOf` for read-only collections; `mutableListOf` etc. only when mutation is actually needed
- Expose immutable types in public APIs even when the backing structure is mutable (return `List<T>`, not `MutableList<T>`)
- Prefer `filter`, `map`, `fold`, `groupBy` over imperative loops for transformations
- `buildList { }` / `buildMap { }` when construction logic is complex

**Flag**
- `ArrayList`, `HashMap` used directly instead of the idiomatic factory functions
- Mutable collection returned from a public API
- `for` loop where a single-expression collection operation reads more clearly

---

### Error Handling

**Enforce**
- `Result<T>` or a domain-specific sealed type for recoverable errors in business logic
- `require(condition) { "message" }` for precondition checks (throws `IllegalArgumentException`)
- `check(condition) { "message" }` for state invariants (throws `IllegalStateException`)
- `error("message")` for unreachable branches

**Flag**
- Empty `catch` blocks or `catch (e: Exception) { }` that swallow the exception silently
- Catching `Exception` at a low level and returning `null` — loses the error context entirely
- Using exceptions for control flow in hot paths

---

### Coroutines

**Enforce**
- `suspend` functions for all async operations — no callbacks in new code
- Structured concurrency: launch coroutines on an injected or enclosing `CoroutineScope`, never on `GlobalScope`
- `Dispatchers.IO` for blocking I/O; `Dispatchers.Default` for CPU-bound work
- `supervisorScope { }` when child failures must not cancel siblings
- Always re-throw `CancellationException` — catching and swallowing it breaks cancellation
- `withContext(dispatcher)` to switch dispatchers inside a suspend function rather than launching a new coroutine

**Flag**
- `GlobalScope.launch` — coroutines not tied to any lifecycle
- `runBlocking` in production code (acceptable in tests and `main()`)
- `async { }` with no corresponding `await` — fire-and-forget without error handling
- Blocking calls (`Thread.sleep`, raw JDBC) inside a coroutine body on the wrong dispatcher
- `CoroutineScope(Dispatchers.IO)` stored in a field with no `cancel()` call on teardown

---

## 2. Ktor WebSockets

### Session Lifecycle

**Enforce**
- Register the session in the registry *before* entering the message loop; deregister in a `finally` block
- Standard pattern:
  ```kotlin
  webSocket("/ws") {
      val id = registry.register(this)
      try {
          incoming.consumeEach { frame -> handle(frame) }
      } catch (e: ClosedReceiveChannelException) {
          // normal close — not an error
      } finally {
          registry.remove(id)
      }
  }
  ```
- Log connect/disconnect at INFO with the session ID; log unexpected errors at ERROR with the exception

**Flag**
- Session added to the registry but removed only inside the happy path — a disconnect during an error leaves a ghost entry
- No `finally` block — sessions leak on exceptions
- `ClosedReceiveChannelException` caught and logged as an error instead of treated as a normal close event

---

### Coroutine Scopes

**Enforce**
- Use the coroutine scope provided by the `webSocket { }` block — it is a `CoroutineScope` tied to the session and automatically cancelled when the socket closes
- `supervisorScope` when launching parallel tasks within a session where partial failure is acceptable

**Flag**
- Launching coroutines on an application-level scope from inside a session handler — they outlive the session
- `GlobalScope.launch` for per-session work
- Background jobs started for a session that are not cancelled when the session ends

---

### Message Serialization

**Enforce**
- A sealed class hierarchy for all incoming and outgoing messages with a `type` discriminator field
- `kotlinx.serialization` with `@Serializable` and `@SerialName` for each variant
- Validate deserialized messages before acting on them (required fields, value ranges)
- Send a structured error frame back to the client on a malformed message rather than silently dropping it

**Flag**
- Manual JSON string manipulation instead of a type-safe serializer
- No error handling around deserialization — a single malformed frame should not kill the session
- Raw exception messages sent to the client — leaks internals

---

### Concurrency and Shared State

**Enforce**
- Session registry backed by `ConcurrentHashMap` (or a `Mutex`-guarded `Map`) — broadcast loops and session connect/disconnect race
- Reads and writes of shared mutable state inside `Mutex.withLock { }`
- `Channel` or `StateFlow` for fan-out broadcasts rather than iterating a collection under a lock with `send` inside the lock

**Flag**
- Plain `HashMap` or `MutableList` for the session registry — not thread-safe
- `send()` called while holding a lock — can deadlock if `send` suspends
- Iterating the session map and calling `send` in the same `synchronized` block
- Mutable per-session state (score, position) modified from multiple coroutines without synchronization

---

### Connection Management

**Enforce**
- Heartbeat / ping-pong to detect zombie connections (Ktor has built-in `pingPeriod` on the WebSocket plugin)
- Maximum frame size configured to prevent memory exhaustion from a single large message
- Graceful shutdown: cancel all sessions and send `CloseReason.Codes.GOING_AWAY` before the server stops
- Authentication verified before the WebSocket upgrade completes

**Flag**
- No heartbeat — dead connections accumulate in the registry indefinitely
- No message size cap
- `send()` calls not wrapped in try/catch — `ClosedSendChannelException` crashes the sender coroutine on a disconnected client
- WebSocket endpoint accessible without authentication

---

### Error Handling

**Enforce**
- Wrap each `send()` in try/catch for `ClosedSendChannelException`; remove the session from the registry on failure
- Top-level `try/catch(e: Exception)` inside the `webSocket` handler to log unexpected errors before cleanup runs
- Distinguish expected errors (bad client input) from unexpected errors (null pointer, corrupted state)

**Flag**
- Unhandled exception inside `webSocket { }` that propagates without logging
- `catch (e: Throwable)` that swallows `CancellationException` — breaks structured concurrency
- A single error path for both validation failures and programmer errors

---

## 3. React UI

### Component Design

**Enforce**
- Single responsibility — a component does one thing; extract sub-concerns into named child components or custom hooks
- Keep components under ~200 lines; larger components are a signal to decompose
- Presentational components receive all data via props and emit events via callbacks; they hold no network or server state
- Name components by intent: `PlayerStatusCard`, not `Card2`

**Flag**
- A single component doing data fetching, business logic, layout, and styling
- Anonymous arrow function components (`export default () => { }`) — anonymous in DevTools stack traces
- Component files that export multiple components — each gets its own file

---

### Hooks

**Enforce**
- Custom hooks for any logic reused across two or more components (`useWebSocket`, `useGameState`)
- `useCallback` for callbacks passed as props to child components (prevents unnecessary child re-renders)
- `useMemo` for genuinely expensive derived values — not for every computation
- Exhaustive `useEffect` dependency arrays — enforced by `eslint-plugin-react-hooks`

**Flag**
- Hooks called conditionally or inside loops — violates the Rules of Hooks
- `useEffect` with an empty dependency array `[]` that references changing props or state — stale closure
- `useEffect` with no dependency array at all — runs on every render (almost always unintentional)
- Suppressed `exhaustive-deps` ESLint warnings without an explanatory comment

---

### `useEffect` Cleanup

**Enforce**
- Every `useEffect` that creates a subscription, timer, or async operation returns a cleanup function
- WebSocket connections: `return () => ws.close()`
- Fetch calls: `AbortController` with `return () => controller.abort()`
- Event listeners added in `useEffect` removed in the cleanup

**Flag**
- `useEffect` that opens a WebSocket with no cleanup — connection survives unmount
- `setInterval` / `setTimeout` with no `clearInterval` / `clearTimeout` in cleanup — timer fires on unmounted component
- State updates inside async callbacks after component unmount — use an `isMounted` flag or `AbortController`
- `addEventListener` with no corresponding `removeEventListener`

---

### State Management

**Enforce**
- State collocated with the component that needs it; lift only when siblings genuinely share it
- Derived values computed inline from existing state rather than stored as additional state variables
- `useReducer` when state transitions are complex or interrelated
- Immutable state updates — spread objects/arrays rather than mutating in place

**Flag**
- Storing derived data as state: `const [double, setDouble] = useState(count * 2)` — derive it instead
- `setState(obj); obj.field = newValue` — direct mutation; React will not re-render
- Storing the entire network response object in state — extract only what the UI needs
- `useContext` for high-frequency state — context re-renders all consumers; use it for infrequent changes (theme, auth user)

---

### Props and TypeScript

**Enforce**
- Explicit TypeScript interfaces for all props; no `any`
- `children: React.ReactNode` typed correctly
- Required vs. optional props modelled accurately — avoid making everything optional as a shortcut
- Callback props named `onX` (e.g., `onConnect`, `onMessageReceived`) per React convention

**Flag**
- Props typed as `any` or `object`
- Props interface with 10+ members — likely a component doing too much
- Prop drilling through 3+ levels — candidate for context or component composition
- Boolean props named as commands (`loading`, `error`) rather than questions (`isLoading`, `hasError`)

---

### Lists and Keys

**Enforce**
- Stable, unique keys on list items — use an entity ID, not the array index
- `key` placed on the outermost element returned from `.map()`

**Flag**
- `key={index}` — causes incorrect reconciliation when items are reordered or removed
- Missing `key` prop on list items — React warning and potential rendering bugs
- `key` placed on an inner element instead of the root element of the map callback

---

### Performance

**Enforce**
- `React.memo` on components that are expensive to render and receive stable props
- Extract object/array literals that are passed as props into variables or `useMemo` — inline definitions create new references every render: `<Component config={{ a: 1 }} />`
- Lazy-load heavy components with `React.lazy` + `Suspense`

**Flag**
- `useCallback(() => doThing(), [])` with a stale closure over state — missing state in the dependency array
- Expensive computation inline in the render body with no `useMemo`
- `React.memo` on a component whose parent always passes new object references as props — memo never hits; fix the parent

---

### Accessibility

**Enforce**
- Semantic HTML: `<button>` for actions, `<a>` for navigation, `<nav>`, `<main>`, `<section>`, proper `<h1>`–`<h6>` hierarchy
- Every interactive element reachable by keyboard and has a visible focus state
- Images have `alt` text; decorative images have `alt=""`
- Form inputs have `<label htmlFor="...">` or `aria-label`
- Modals/dialogs use `role="dialog"` with `aria-modal="true"` and trap focus

**Flag**
- `<div onClick={...}>` without `role="button"`, `tabIndex={0}`, and a keyboard event handler
- `<img>` with no `alt` attribute
- Dynamic content that appears without a screen reader announcement — use `aria-live` regions
- Color as the only indicator of state (red = error) — pair with text or an icon

---

## 4. Cross-Cutting Checks

These checks span all three layers and are easy to miss when reviewing each file in isolation.

### Message Contract Parity
Verify that the sealed class hierarchy on the Kotlin side exactly matches the TypeScript union types on the React side for every message. A mismatch causes silent parse failures or runtime errors.

### Disconnection Handling End-to-End
Trace all disconnection paths:
1. React `useEffect` cleanup closes the WebSocket
2. Ktor `ClosedReceiveChannelException` is caught and treated as a normal close
3. Session `finally` block fires and removes the entry from the registry

Verify this holds for normal close, network error, and server restart scenarios.

### Error Propagation
Errors from Kotlin business logic should reach the React UI as a typed error message frame, not as an unhandled exception that silently drops the connection. Confirm the error message type is part of the message contract and the UI handles it.

### Coroutine Scope vs. Application Scope
Any coroutine that touches per-session state must live in the session scope so it is cancelled when the socket closes. Application-wide coroutines (e.g., broadcast loops) must not hold direct references to session state that could leak.

### Shared State Race Conditions
Any collection touched by both the WebSocket handler coroutine and an application-level coroutine (e.g., a broadcast loop iterating all sessions) must be either `ConcurrentHashMap` or accessed under a `Mutex`. Review both the read and write sites.
