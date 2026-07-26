# SKILL-112 Subtask 2 - Kotlin Pack Elevation

## Scope

Elevate the kotlin pack's review surfaces from structurally-correct-but-generic
to the reference standard defined in subtask 1. The kotlin pack already uses
the canonical `## Focus` / `## Ignore` / `## Applicability` /
`## Project-Specific Rules` skeleton; this subtask adds the missing substance.
The 2026-07-09 kotlin audit is the source of the additions below.

### 1. Kotlin-named failure modes per specialist

Today no enforceable rule in the pack names a Kotlin/JVM API or failure mode
(`runBlocking`, `CancellationException`, `SupervisorJob`, `Exposed`,
`@Transactional`, `kotlinx.serialization` appear only in routing signals and
Applicability text). Add, at minimum:

**platform-correctness** (`bill-kotlin-code-review-platform-correctness`)

- `catch (e: Exception)` and `runCatching` around suspend calls must rethrow
  `CancellationException`; swallowing it breaks structured concurrency
- `Job` vs `SupervisorJob` failure propagation: one child failure cancelling
  sibling consumers
- `withContext(NonCancellable)` required for suspending cleanup in `finally`
- `Mutex` is non-reentrant; flag reentrant locking paths
- cold-vs-hot Flow semantics: `StateFlow` conflation dropping intermediate
  events consumers depend on; `SharedFlow(replay = 0)` losing emissions with
  no collector
- port the go error-handling rules: faults must not collapse into misleading
  success; multi-step workflows must not report full success on partial
  effect; retry-sensitive paths must not duplicate billing or user-visible
  effects

**persistence** (`bill-kotlin-code-review-persistence`)

- Exposed `transaction {}` is thread-bound: suspend calls or dispatcher hops
  inside it lose the transaction; `newSuspendedTransaction` misuse
- Spring `@Transactional` silently not applying on self-invocation or
  final/non-open functions
- Hibernate `LazyInitializationException` when entities escape the session;
  dirty-checking auto-flush writing unintended updates
- atomic predicates, version checks, locks, or unique constraints for
  counters, balances, status transitions, and reservation flows
- mass update/delete paths must carry the same tenant and soft-delete filters
  as read paths
- extend the existing no-transaction-across-remote-I/O rule to event
  publishing and queue dispatch

**security** (`bill-kotlin-code-review-security`)

- injection-class enumeration ported from go: SSRF, command execution, path
  traversal, SQL injection, template injection, unsafe deserialization, and
  object-level access control gaps; untrusted data must not reach dangerous
  sinks without allowlisting
- cross-tenant or cross-account access must be impossible unless the contract
  explicitly permits it
- temporary debug code, bypass flags, test credentials, and relaxed
  verification paths must not ship
- Kotlin/JVM sinks: Java `ObjectInputStream` deserialization,
  kotlinx.serialization open polymorphism with attacker-influenced subtypes,
  SnakeYAML unsafe `load`, hand-rolled JWT validation (unverified `alg`,
  missing audience/expiry)

**api-contracts** (`bill-kotlin-code-review-api-contracts`)

- kotlinx.serialization config drift (`explicitNulls`, `encodeDefaults`,
  `ignoreUnknownKeys`) as a client-visible contract change
- Kotlin default parameter values masking absent fields at the boundary
- Jackson without (or with misconfigured) `jackson-module-kotlin` writing
  null into non-null constructor parameters and failing at runtime instead of
  the boundary
- distinct, stable error responses for validation vs authn vs authz vs domain
  faults; enum/date/nullability/default-field serialization drift checks

**performance** (`bill-kotlin-code-review-performance`)

- `runBlocking` inside coroutines or on `Dispatchers.Default`; blocking JDBC
  off `Dispatchers.IO`; the IO dispatcher's 64-thread ceiling under load
- missing `flowOn`/`buffer` pushing upstream work onto the collector thread
- eager collection operator chains vs `asSequence()` on large collections
- port the go ORM/query-shape rules: count/exists/aggregate paths must not
  hydrate full rows; serialization paths must not trigger hidden lazy loads

**reliability** (`bill-kotlin-code-review-reliability`)

- long-lived consumers need `SupervisorJob`-scoped children so one poison
  message does not cancel the consumer scope
- `withTimeout` throws `TimeoutCancellationException`; generic catch blocks
  misclassify it
- `Thread.sleep` vs `delay` in retry loops pinning dispatcher threads
- replay/rebuild/republish flows bounded, observable, and re-runnable;
  after-commit/outbox dispatch must not fire before durable commit; poison
  vs transient vs permanent failure distinction in job telemetry

**testing** (`bill-kotlin-code-review-testing`)

- `runTest` virtual time (`advanceTimeBy`, `advanceUntilIdle`) required for
  retry/timeout logic; real-`delay` waits are flaky by construction
- `StandardTestDispatcher` vs `UnconfinedTestDispatcher` ordering semantics
- Flow assertions need collection control, not `first()` snapshots
- persistence-backed integration tests verify actual persistence effects;
  negative paths asserted separately per failure type

**architecture** (`bill-kotlin-code-review-architecture`)

- port the go architectural-precedence section: identify the local
  architecture first; report only concrete risk, not layering purity
- one use-case owner and one transaction owner per business operation; event
  persistence in the same transaction as business state
- Kotlin boundary mechanics: `suspend` leaking into domain interfaces,
  injected `CoroutineScope` crossing layers, `internal` visibility and Gradle
  module boundaries as enforcement

### 2. Baseline upgrades (`bill-kotlin-code-review/content.md`)

- add a `## Mixed Diffs` section matching the standard: keep the baseline
  specialists for the whole review; add only the specialists the relevant
  areas need; do not force every file through every specialist
- add a generated/vendored exclusion to specialist scoping (build outputs,
  generated serialization/protobuf code, non-Kotlin files)
- add a finding-discipline section (severity calibration, precondition
  verification before reporting) modeled on the ios baseline's
  `## Finding Discipline`
- state decision-rule precedence explicitly for kmp-baseline mode when
  backend markers are present (`content.md` decision rules currently rely on
  implied ordering)
- keep the existing classification, deterministic-wave batching, and
  attributed merge/dedup sections — they are best-in-class and become part
  of the reference standard

### 3. Quality-check upgrade (`bill-kotlin-code-check/content.md`)

The checker currently hardcodes `./gradlew check` with no command discovery.
Bring it to the standard's quality-check skeleton: discover commands from
Gradle wrappers, version catalogs, `Makefile`, and CI workflows before
falling back to `./gradlew check`; name the common tool configs it should
look for (`detekt.yml`, `.editorconfig`, ktlint/spotless configuration);
keep the existing structural-fix ladder and when-to-ask list; add the
targeted-vs-full-suite escalation rule from the python checker.

### 4. Manifest enrichment (`platform-packs/kotlin/platform.yaml`)

- grow the strong routing signals beyond the current three entries with
  config-file signals (`build.gradle`, `settings.gradle.kts`,
  `gradle/libs.versions.toml`, `.kts`, `detekt.yml`), keeping the dual
  bare/glob extension forms from subtask 1
- replace the generic `area_metadata.focus` boilerplate with Kotlin-bespoke
  strings in the go/python style

### 5. Structure conformance

Apply the subtask 1 standard end-to-end: severity closers on all eight
specialists using the canonical wording, and Compose-specific ignore entries
in the performance specialist moved to the kmp pack or generalized (Compose
belongs to the kmp lane). Remove `kotlin` from the conformance-test
exemption list.

## Acceptance Criteria

1. Every rule addition listed in Scope section 1 appears in the named
   specialist as an enforceable rule or ignore boundary, not a topic mention.
2. Grep gates prove Kotlin substance exists:
   `grep -rl "CancellationException" platform-packs/kotlin/code-review/` and
   equivalent greps for `SupervisorJob`, `runBlocking`, `runTest`,
   `explicitNulls`, and `newSuspendedTransaction` each match at least one
   specialist file.
3. The kotlin baseline contains a `## Mixed Diffs` section with the
   keep-baseline-specialists instruction and a generated/vendored exclusion
   in its scoping step.
4. All eight kotlin specialists carry the canonical severity closer defined
   by subtask 1, and `kotlin` is removed from the conformance-test exemption
   list with the conformance test passing.
5. `bill-kotlin-code-check/content.md` contains command discovery, named
   tool-config lookups, the fix ladder, the never-suppress rule, and the
   targeted-vs-full-suite escalation rule.
6. The kotlin manifest carries enriched strong signals and Kotlin-bespoke
   `area_metadata.focus` strings.
7. Specialist files keep the canonical H2 skeleton, their frontmatter
   (`name`, `description`, `internal-for`), and `contract_version` values are
   unchanged pack-wide.
8. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes including
   `KotlinPlatformPackTest` and
   `PlatformPackSchemaValidatesExistingPacksTest`.

## Non-Goals

- No changes to kmp pack files (subtask 3), go/php/python/ios files (later
  subtasks), or `orchestration/` contracts beyond what subtask 1 already
  landed.
- No new review areas and no changes to the kotlin manifest beyond what
  subtask 1's normalization already applied.
- No add-on system for the kotlin pack in this subtask.

## Dependency Notes

Depends on subtask 1 (the structure standard defines the canonical severity
closer wording and skeleton this subtask conforms to). Blocks subtask 3 (kmp
layers this baseline) and subtasks 4-7 (packs are modeled after the elevated
kotlin/kmp pair).

## Validation Strategy

```bash
skill-bill validate --skill-name bill-kotlin-code-review-<area>
skill-bill render --skill-name bill-kotlin-code-review-<area>
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

Grep gates from Acceptance Criteria 2 run before the full suite.

## Next Path

On completion, proceed to subtask 3 (kmp pack elevation), which layers the
baseline upgraded here.
