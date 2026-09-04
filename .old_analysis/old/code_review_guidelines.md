Kotlin Project Code Review Guide
1. Start with the review strategy

A good Kotlin review should not start with formatting. Review in roughly this order:

Correctness — Does the code do what it should?
Security — Can inputs, permissions, secrets, or dependencies be abused?
Concurrency — Are coroutines, threads, cancellation, and shared state handled correctly?
Architecture/design — Are responsibilities and dependencies sensible?
API quality — Is the public API clear, stable, and difficult to misuse?
Error handling — Are failures represented and handled appropriately?
Testing — Does the test suite prove important behavior?
Performance/resource management — Are there unnecessary allocations, blocking calls, leaks, etc.?
Kotlin idioms — Is the code using Kotlin's type system and language features effectively?
Style/maintainability — Naming, formatting, duplication, complexity, documentation.

This ordering prevents a review from becoming a discussion about whitespace while missing a race condition or authorization bug.

2. Understand the project before reviewing individual files

Before looking at the diff, establish:

Project structure

Identify:

application modules
library modules
domain/business modules
data/persistence modules
API/public modules
test modules
build/convention plugins
generated code
platform-specific source sets if Kotlin Multiplatform is used

Ask:

What are the architectural boundaries, and are dependencies flowing in the intended direction?

For example:

UI
 ↓
Application / ViewModel
 ↓
Domain
 ↓
Repository
 ↓
Data sources

A review should flag code that unexpectedly reverses these dependencies.

Build configuration

Inspect:

Kotlin version
Java/JVM target
Gradle version
Android Gradle Plugin, if applicable
dependency versions
compiler options
explicit API mode
static analysis configuration
test configuration
CI pipeline
dependency-update strategy

For library projects, Kotlin specifically recommends explicit visibility and explicit types for public API, and recommends KDoc for public members.

3. Correctness: the highest-priority review category

Ask:

Does the implementation actually satisfy the requirements?

Look for:

incorrect conditions
missing branches
off-by-one errors
incorrect default values
incorrect state transitions
incorrect assumptions about ordering
lost updates
duplicate processing
retry problems
incorrect caching
incorrect transaction boundaries
Boundary conditions

Explicitly check:

empty collections
empty strings
null
zero
negative numbers
maximum values
duplicate elements
missing records
malformed external data
timeouts
concurrent calls
repeated requests

Don't assume Kotlin's type safety eliminates these issues.

4. Kotlin null-safety

Nullability deserves special attention because Kotlin provides a strong type-system mechanism specifically for this problem. Kotlin's documentation notes that remaining NPE sources include !!, Java/platform-type interoperation, certain initialization problems, and explicit NPE throwing.

Red flags
user!!.profile!!.name!!

Ask:

Why can this value be null, and why is the reviewer being asked to trust that it isn't?

Prefer explicit handling:

val profile = user.profile ?: return
val name = profile.name ?: return

or, where appropriate:

val name = user.profile?.name ?: "Unknown"
Review carefully
!!
nullable collections
nullable properties
lateinit
Java interop
platform types
nullable generic types
initialization order
nullable state machines

Don't automatically reject every !!; determine whether the invariant is genuinely guaranteed.

5. val vs var and mutability

Kotlin's collection APIs distinguish read-only interfaces from mutable ones, and the Kotlin documentation recommends using val as much as possible for safer and more robust code.

Prefer:

val users = loadUsers()

over:

var users = loadUsers()

unless reassignment is actually required.

Also inspect collection exposure:

class UserService {
    val users = mutableListOf<User>()
}

This exposes mutable state.

Prefer an encapsulated design such as:

class UserService {
    private val users = mutableListOf<User>()

    fun users(): List<User> = users
}

or a more appropriate immutable/state-flow abstraction.

Ask
Who owns this state?
Who can mutate it?
Can callers accidentally modify it?
Is mutation synchronized?
Is the object shared between threads/coroutines?
6. Kotlin idiomaticity

Check whether the code uses Kotlin's strengths without becoming unnecessarily clever.

Good signs
val activeUsers = users.filter { it.isActive }

rather than unnecessarily verbose Java-style code.

Look for appropriate use of:

val
smart casts
when
data classes
sealed classes/interfaces
extension functions
scope functions
destructuring
default arguments
named arguments
collection operations
null-safety operators
delegated properties
value classes where appropriate
But don't over-idiomatize

This:

foo?.let {
    bar?.let {
        baz?.let {
            doSomething()
        }
    }
}

may be technically Kotlin-ish but harder to understand than explicit control flow.

The goal is clarity, not maximum language-feature usage.

7. Scope functions

Pay particular attention to chains of:

let
run
with
apply
also

For example:

user?.let {
    repository.save(it)
    analytics.track(it)
}

Ask whether the scope actually improves readability.

A common smell is deeply nested scope functions:

foo?.let { a ->
    a.bar?.let { b ->
        b.baz?.let { c ->
            ...
        }
    }
}

Prefer straightforward control flow when nesting obscures intent.

8. Data classes and equality semantics

Review whether data class is appropriate.

data class User(
    val id: Long,
    val name: String
)

Remember that generated equals(), hashCode(), toString(), and copy() have semantics that can matter.

Ask:

Should equality be based on these properties?
Can objects mutate after being placed in a HashSet/HashMap?
Does copy() create the semantics the application expects?
Are sensitive fields exposed through toString()?

Particularly watch for:

data class Credentials(
    val username: String,
    val password: String
)

A generated toString() can inadvertently expose sensitive data in logs.

9. Sealed types and state modeling

For state-heavy code, ask whether invalid states can be represented by the type system.

Instead of:

data class State(
    val loading: Boolean,
    val error: String?,
    val data: User?
)

consider whether the domain is really:

sealed interface State {
    data object Loading : State
    data class Success(val user: User) : State
    data class Error(val message: String) : State
}

The latter makes certain invalid combinations impossible.

Review question

Can the type system prevent a bug that is currently being prevented by convention?

10. Coroutines and concurrency

This is one of the most important Kotlin-specific review areas.

Kotlin coroutines use structured concurrency: child coroutines belong to a parent scope and cancellation/failure propagate through that hierarchy.

Red flags

Look closely at:

GlobalScope.launch { ... }

or manually created scopes:

CoroutineScope(Dispatchers.IO).launch {
    ...
}

Ask:

Who owns this coroutine's lifecycle?

Review

Check:

structured concurrency
cancellation propagation
coroutine scope ownership
dispatcher selection
blocking operations
exception propagation
supervisor vs regular jobs
timeout handling
coroutine leaks
shared mutable state
concurrent access
async/await
Flow collection
Dispatcher review

A suspicious pattern:

withContext(Dispatchers.IO) {
    cpuIntensiveCalculation()
}

If the operation is CPU-bound, Default may be more appropriate.

Conversely:

withContext(Dispatchers.Default) {
    database.query(...)
}

may be inappropriate if the database call blocks.

The reviewer should understand what the operation actually does, rather than blindly approving dispatcher usage.

11. Coroutine cancellation

Check whether long-running operations are cancellable.

For example:

while (true) {
    doSomething()
}

inside a coroutine is suspicious.

Ask:

Can the operation be cancelled?
Are suspending functions used appropriately?
Are cancellation exceptions accidentally swallowed?
Does finally release resources?

Be especially suspicious of:

catch (e: Exception) {
    // ignore
}

because it may also catch CancellationException and break structured cancellation semantics.

12. Flow review

For Flow, inspect:

cold vs hot flow semantics
StateFlow
SharedFlow
collection lifecycle
backpressure
exception handling
cancellation
replay configuration
unnecessary transformations
repeated collection
expensive work in operators

Ask:

Who collects this Flow, for how long, and what happens when the collector disappears?

For Android specifically, Google provides dedicated guidance for testing Flow producers and consumers.

13. Exception handling

Avoid generic exception handling such as:

try {
    doSomething()
} catch (e: Exception) {
    return null
}

This can hide:

programming bugs
cancellation
configuration errors
database failures
network failures
security failures

Instead, determine which failures are expected.

For example:

return try {
    repository.load()
} catch (e: IOException) {
    Result.failure(e)
}

Kotlin's API guidelines also recommend avoiding exceptions for normal control flow and using nullable values or Result where appropriate depending on the API semantics.

Review questions
Is the exception recoverable?
Is it being logged?
Is sensitive information included?
Is the caller informed?
Is retry appropriate?
Is cancellation preserved?
Is the original cause retained?
14. API design

For public APIs, review:

Visibility

Avoid accidentally exposing implementation details:

class Service {
    fun internalImplementationDetail() = ...
}

Prefer:

class Service {
    internal fun internalImplementationDetail() = ...
}

where appropriate.

Kotlin recommends explicit visibility and explicit return/property types for libraries because changes to inferred implementation details can otherwise unintentionally change the API.

Public API checklist
Is the API minimal?
Are names obvious?
Are parameters consistently ordered/named?
Are defaults sensible?
Are nullable types meaningful?
Are exceptions predictable?
Are return types appropriate?
Can callers misuse it?
Is binary/source compatibility relevant?
Is public behavior documented?

Kotlin's API guidelines emphasize simplicity, readability, consistency, predictability, debuggability, and testability.

15. KDoc and documentation

For public libraries, review KDoc:

/**
 * Loads the user's profile.
 *
 * @param userId identifier of the user.
 * @return the profile when it exists.
 */
fun loadProfile(userId: UserId): Profile?

Don't demand documentation for trivial private implementation details simply to increase comment count.

The important question is:

Does the documentation explain behavior that isn't obvious from the API itself?

Kotlin's KDoc documentation describes KDoc as Kotlin's documentation format and supports generation through Dokka.

16. Architecture and separation of concerns

Look for classes that have too many responsibilities.

Example:

class UserManager {
    fun validateUser()
    fun queryDatabase()
    fun callHttpApi()
    fun serializeJson()
    fun sendEmail()
    fun updateUi()
}

This should trigger architectural investigation.

Ask:

What is this class responsible for?
Does it have one reason to change?
Does it depend on infrastructure directly?
Can it be tested independently?
Is business logic mixed with I/O?
Are domain objects polluted with infrastructure concerns?
17. Dependency direction

Check imports and dependencies.

For example, a domain module importing:

android.*

or:

javax.persistence.*

may indicate an architectural boundary violation, depending on the project's architecture.

Look for:

domain → database
domain → HTTP client
domain → Android UI

when the intended architecture is:

UI → application → domain
                    ↑
              infrastructure
18. Testing

A code review should evaluate behavioral coverage, not just line coverage.

Look for tests covering:

Happy paths
valid input → expected result
Failure paths
invalid input
network failure
database failure
authorization failure
timeout
Boundaries
empty
one item
maximum
minimum
duplicate
missing
State transitions

Especially important for:

ViewModels
state machines
workflows
asynchronous processes

Kotlin's API guidelines recommend unit/integration coverage of documented behavior and emphasize boundary and edge cases.

19. Coroutine tests

Don't treat coroutine tests as ordinary synchronous tests.

Review whether tests properly control coroutine execution and virtual time.

Google's current Android guidance uses kotlinx.coroutines.test for coroutine testing.

Look for tests of:

cancellation
timeout
concurrent execution
dispatcher behavior
exception propagation
Flow emissions
state transitions

A test that simply waits with:

Thread.sleep(1000)

is usually a strong smell in coroutine-based code.

20. Security review

For security-sensitive code, inspect:

Secrets

Never hard-code:

val apiKey = "..."
val password = "..."

Check:

source code
resources
configuration
logs
test fixtures
CI configuration
Input validation

Review all external input:

HTTP requests
JSON
files
database data
user input
intents/deep links
environment variables

Ask:

What assumptions does this code make about data coming from outside the trust boundary?

Authorization

Don't confuse:

authentication = who are you?
authorization = are you allowed to do this?

Review authorization at the operation/resource boundary.

Logging

Search for:

Log.d(...)
println(...)
logger.info(...)

and check for:

passwords
access tokens
session IDs
personal data
database credentials
sensitive request/response bodies
21. Resource management

Check whether resources are reliably closed:

database connections
files
streams
sockets
cursors
HTTP response bodies
subscriptions
coroutine scopes
listeners

Look for Kotlin's resource-management mechanisms where appropriate:

file.inputStream().use { input ->
    ...
}

rather than manually relying on cleanup paths.

22. Performance

Don't optimize every line. Identify actual risk.

Look for:

Accidental repeated work
items.map { expensiveOperation(it) }

inside a loop that itself runs repeatedly.

Excessive allocations

Especially:

huge collections
repeated conversions
unnecessary toList()
unnecessary map().filter().map()
large temporary objects
Algorithmic complexity

Ask:

O(n)?
O(n log n)?
O(n²)?

For example:

users.forEach { user ->
    permissions.contains(user.id)
}

may be fine if permissions is a Set, but problematic if it is a List and this occurs for a large dataset.

23. Kotlin collection operations

Review chains such as:

items
    .filter(...)
    .map(...)
    .filter(...)
    .map(...)

Don't automatically optimize them. First determine whether the data volume warrants concern.

But check for:

unnecessary materialization
incorrect ordering
unintended duplicates
nullable elements
mutable collections
expensive operations inside lambdas

Also check whether a Sequence actually improves the situation rather than merely making the code look more sophisticated.

24. Code complexity

Use static analysis to identify candidates for manual review.

Kotlin's documentation recommends detekt for Kotlin static analysis; it detects code smells, complexity issues, and potential bugs.

Current detekt capabilities include configurable rules, baselines, multiple report formats, complexity reporting, and Gradle integration.

Useful metrics:

cyclomatic complexity
nesting depth
function length
class length
parameter count
duplicated code
magic numbers
overly broad visibility

But treat metrics as signals, not automatic defects.

A 50-line function may be perfectly understandable; a 15-line function can be dangerously complex.

25. Naming

Names should communicate domain intent.

Bad:

fun process(data: Data)
fun handle(x: String)
fun doStuff()

Better:

fun calculateInvoiceTotal(invoice: Invoice)
fun normalizeCustomerName(name: String)

Review:

class names
function names
Boolean names
variable names
generic type names
extension-function names

For example:

if (!user.isNotActive)

is much harder to reason about than:

if (user.isActive)

Google's Kotlin style guide provides detailed naming, structure, formatting, and documentation conventions for Android Kotlin projects.

26. Comments

Ask:

Does this comment explain why, or merely repeat what the code does?

Low-value:

// Increment counter
counter++

Potentially valuable:

// The API occasionally returns duplicate events, so we de-duplicate
// before updating the local cache.

Comments should ideally document:

business constraints
non-obvious invariants
external-system behavior
performance decisions
workarounds
compatibility constraints
27. Git/diff review

For an actual pull request, review the diff, not just the resulting files.

For every change ask:

Intent

What problem is this change solving?

Scope

Does the diff contain unrelated changes?

Regression

What existing behavior could this break?

Compatibility

Does this change alter a public API, database schema, serialized format, or protocol?

Migration

If behavior changes, is there a migration strategy?

Tests

What test proves this change works?

A small, focused PR is much easier to review reliably.

28. Automated review before human review

A useful pipeline is:

Compile
   ↓
Unit tests
   ↓
Integration tests
   ↓
Static analysis
   ↓
Formatting
   ↓
Security/dependency scanning
   ↓
Human review

For Kotlin, consider integrating:

Kotlin compiler
detekt
formatting/lint tooling appropriate to the project
unit/integration tests
dependency vulnerability scanning
coverage reporting
API compatibility checks for libraries

The goal isn't to replace the reviewer; it is to ensure the reviewer spends time on semantic issues instead of mechanical ones.

29. Review severity model

I recommend four levels.

Severity	Meaning	Example
🔴 Blocker	Must fix before merge	Security vulnerability, data corruption, race condition
🟠 Major	Significant correctness/maintainability problem	Broken cancellation, incorrect business logic
🟡 Minor	Should improve, but doesn't block functionality	Poor API naming, unnecessary complexity
🔵 Nit	Optional stylistic improvement	Formatting or naming preference

Avoid turning personal preferences into blockers.

A good review comment explains why:

🟠 Major: This coroutine is launched in a manually-created scope that is never cancelled. If the caller disappears, the operation can continue running and retain the repository. Please tie the coroutine to the lifecycle-owned scope instead.

rather than:

Don't use CoroutineScope here.

30. A practical Kotlin review checklist

You can use this directly during a PR review:

Correctness
 Does the implementation match the requirements?
 Are edge cases handled?
 Are error paths correct?
 Are state transitions correct?
 Are repeated/concurrent calls safe?
Kotlin
 Is val used where possible?
 Is nullability modeled correctly?
 Are !! usages justified?
 Are mutable collections encapsulated?
 Are scope functions improving readability?
 Are Kotlin idioms used appropriately?
 Is the code more clever than necessary?
Coroutines
 Is structured concurrency preserved?
 Is the scope lifecycle correct?
 Is cancellation propagated?
 Are dispatchers appropriate?
 Are blocking operations handled correctly?
 Are exceptions handled correctly?
 Is shared state safe?
 Are Flow collectors lifecycle-safe?
Architecture
 Are responsibilities separated?
 Are dependency directions correct?
 Are infrastructure concerns isolated?
 Are abstractions justified?
 Are public APIs minimal?
API
 Is visibility intentional?
 Are public types explicit where appropriate?
 Are nullable/exception semantics clear?
 Are names predictable?
 Is KDoc needed?
 Could the API be misused?
Testing
 Happy path covered?
 Failure paths covered?
 Boundary cases covered?
 Concurrency tested?
 Cancellation tested?
 Flow behavior tested?
 Tests verify behavior rather than implementation details?
Security
 No hard-coded secrets?
 External input validated?
 Authorization checked?
 Sensitive data excluded from logs?
 Exceptions don't leak sensitive information?
 Dependencies reasonably maintained?
Performance
 No accidental O(n²) behavior?
 No unnecessary allocations?
 No unnecessary collection materialization?
 Expensive operations appropriately dispatched?
 Resource usage bounded?
Maintainability
 Names communicate intent?
 Functions/classes have reasonable responsibilities?
 Complexity is manageable?
 Duplication is justified or removed?
 Comments explain why rather than what?
 No unrelated changes?
31. Recommended review workflow

For a real Kotlin project, I'd use this sequence:

Pass 1 — Architecture

Spend 5–10 minutes understanding the module and data flow.

Pass 2 — Diff

Review only changed lines and their immediate context.

Pass 3 — Correctness

Follow the data through the changed code and examine failure paths.

Pass 4 — Kotlin/concurrency

Specifically search the diff for:

!!
lateinit
GlobalScope
CoroutineScope
launch
async
runBlocking
withContext
catch (Exception)
mutable
lateinit
TODO
@Suppress

These aren't necessarily bugs; they're useful review hotspots.

Pass 5 — Tests

Ask whether the tests prove the new behavior and failure modes.

Pass 6 — Automated analysis

Run the project's compiler, tests, lint/static analysis, and security/dependency checks.

Pass 7 — Final API/design review

Look at the change from the perspective of the next developer who will have to maintain it.

32. The most important Kotlin-specific smells

If you have limited review time, prioritize these:

!! without a strong invariant
Unstructured coroutines
Incorrect coroutine scope/lifecycle
Swallowed exceptions
Accidentally swallowed CancellationException
Blocking work on inappropriate dispatchers
Shared mutable state
Mutable collections escaping their owner
Overly broad public APIs
Incorrect nullable/Java platform-type assumptions
Deeply nested scope functions
Overly clever collection pipelines
Large classes/functions with mixed responsibilities
Missing tests for failure and boundary conditions
Sensitive data in logs or generated toString() output
Primary references

For the review standard, these are the sources I'd keep open while reviewing:

Kotlin Coding Conventions — general Kotlin conventions and library-specific recommendations.
Kotlin Coroutines Guide — coroutine lifecycle, cancellation, Flow, concurrency and exception handling.
Kotlin Null Safety — nullability and !! risks.
Kotlin API Guidelines — public API design and maintainability.
Kotlin Code Analysis / detekt — static analysis and complexity checks.
detekt Documentation — Kotlin code-smell and complexity analysis.
Google Kotlin Style Guide — particularly useful for Android Kotlin projects.
Testing Kotlin Coroutines on Android — coroutine testing practices.

Bottom line: a strong Kotlin review is less about checking whether code “looks Kotlin-ish” and more about checking type safety, lifecycle/concurrency, domain correctness, API design, failure behavior, and test evidence. Automated tools should catch the mechanical issues so the human reviewer can concentrate on those higher-value questions.

Lost the resources you used

Yes — here are the web resources I actually used/re-checked, with direct links so you can keep them as a reference set.

Kotlin official documentation
Kotlin Coding Conventions — naming, formatting, idiomatic Kotlin, and additional rules for libraries/public APIs.
Kotlin Null Safety — nullable types, !!, smart casts, Java interop, and NPE risks.
Kotlin Coroutines Guide — coroutines, cancellation, Flow, dispatchers, exceptions, and shared mutable state.
Coroutines Basics / Structured Concurrency — coroutine scopes, parent/child lifecycles, cancellation and structured concurrency.
Kotlin Coroutines & Channels Tutorial — particularly useful for understanding why GlobalScope and unstructured concurrency are review concerns.
Kotlin Coroutines API Reference — launch, async, dispatchers, Mutex, Job, etc.
Kotlin API Guidelines — minimizing mental complexity — simplicity, readability, consistency, predictability, debuggability and testability.
Kotlin API Guidelines — simplicity — public API design and explicit API mode.
Kotlin Code Analysis — static analysis and detekt integration.
Static analysis
detekt Documentation — Kotlin code-smell analysis, complexity analysis, configuration, baselines and Gradle integration.
detekt Complexity Rules — cognitive/cyclomatic complexity and nesting.
detekt Style Rules — unnecessary operations and other maintainability issues.
detekt Baselines — useful when introducing static analysis into an existing/legacy project.
Testing
Testing Kotlin Coroutines on Android — runTest, TestDispatcher, virtual time, dispatcher injection and testing coroutine scopes.
Android-specific
Google Kotlin Style Guide — useful if the Kotlin project is Android-based.
The core sources I'd prioritize

If you don't want to bookmark everything, keep these six:

Kotlin Coding Conventions
Kotlin Coroutines Guide
Kotlin Null Safety
Kotlin API Guidelines
Kotlin Code Analysis / detekt
Kotlin Coroutine Testing

These are also the sources I'd use as the evidence base for a more formal Kotlin code-review checklist rather than relying on generic code-review advice.