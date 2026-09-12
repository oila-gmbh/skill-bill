# SKILL-239: Runtime architecture ownership and simplicity

## Intended outcome

Keep the existing eleven-module runtime and make its boundaries hold during failure and ordinary maintenance. A failed process callback must release its child. Routine database writes must not repeat historical migrations and repair scans. A committed workflow whose manifest projection failed must report that distinction and remain recoverable. The feature run loop must own its state transitions instead of sharing writable session state among helper objects.

The investigation is in [investigation.md](investigation.md). It covers all declared runtime modules through dependency and source inventories, selected execution traces, 36 passing architecture tests, and three isolated executable probes. This spec prepares work only. It does not claim that the runtime has already met these criteria.

The lasting requirements live in [Design Principles](../../runtime-kotlin/ARCHITECTURE.md#design-principles), which AGENTS.md requires runtime contributors to follow. This spec owns the bounded implementation and validation of those requirements. Each subtask updates the documented enforcement status when its checks land.

## Mode

decomposed

Four independently shippable commits cover process lifetime, persistence lifecycle, engine state ownership, and enforcement. Each includes its own tests and documentation. These are separate operational changes, not layer-by-layer steps toward one incomplete replacement. The final enforcement commit follows the engine change so it documents the resulting boundary and baseline.

## Evidence and priorities

| Finding | Evidence | Owner |
| --- | --- | --- |
| F-001 | An output-sink exception escapes the runner while its real child remains alive. | Subtask 1 |
| F-002 | A second open of an initialized empty database executes 250 SQL statements, including ten updates and two immediate transactions. | Subtask 2 |
| F-003 | Manifest projection catches IOException and returns null after the authoritative database write. Callers discard the distinction. | Subtask 2 |
| F-004 | Twenty run-loop files contain 9,779 lines and 280 parameters typed as the whole run loop. Multiple helper objects write session fields. A recovery-message helper creates the reverse featuretask-to-goalrunner import. | Subtask 3 |
| F-005 | Nine role interfaces repeat 71 collaborator properties in their same-module implementations. Two application services only forward calls. | Existing SKILL-238 |
| F-006 | The wire scanner accepts an undeclared literal payload key with zero violations because it derives its checked vocabulary only from existing key declarations. | Subtask 4 |
| F-007 | Written 500-line and empty-baseline claims disagree with the enforced 1,200-line limit and live baselines. Some tests enforce incidental prose. | Subtask 4 |

The SQL count is work amplification on an empty fixture, not a production latency estimate. The run-loop count is a coupling inventory, not a proposed size target.

## Scope

- Process ownership and bounded cleanup in the existing filesystem launcher.
- Database initialization, recurring maintenance, and manifest projection outcomes at the existing persistence boundaries.
- Feature-task session state, checkpoint and reentry transitions, and helper inputs in runtime-engine.
- The specific wire-vocabulary and documentation enforcement gaps identified in the investigation.
- Focused regression coverage and updates to the existing architecture and code-principles documents.

## Acceptance criteria

1. Every exit after successful process spawn releases the owned child, drains, and review endpoint through one cleanup owner. A throwing output sink, interrupted wait, and failing cleanup preserve the primary failure and leave no owned child running after the cleanup deadline. Cleanup failure has bounded diagnostic evidence.
2. After a database session factory has initialized its bound database, ordinary write acquisition does not rerun base-schema creation, historical backfills, or full-table repair scans. Connection setup and the requested transaction remain. Initialization remains safe for concurrent processes, failed initialization, legacy databases, and a replaced or reset database at the same path.
3. Required corruption recovery still runs at an explicit, documented boundary. Optimization cannot turn a stale success flag into a permanently skipped repair. A recording JDBC fixture demonstrates the before/after statements for repeated transaction and self-managed-write calls. No unmeasured latency claim is an acceptance result.
4. Manifest projection distinguishes no applicable projection, successful projection, and projection failure. Database success followed by filesystem failure remains visible and can regenerate the projection from authoritative database state without repeating workflow mutations. Do not claim atomicity across SQLite and the filesystem.
5. Feature-task session state has one mutation owner. Helper objects cannot assign terminal reports, pending or active reentry, branch ownership, or record-rejection settlement flags directly. Completed phase/output state exposes queries and named transitions rather than public mutable collections. Mutually exclusive report outcomes cannot coexist.
6. Helper functions in the run-loop family receive the facts or capabilities they use instead of the whole FeatureTaskRuntimeRunLoop. The top-level coordinator composes phase work. Helpers do not regain the same access through a renamed context, dependency bag, callback bag, or interface with getters for the entire coordinator. Preserve the current workflow, review, build, cancellation, and resume behavior.
7. The featuretask-to-goalrunner dependency for recovery-command rendering is removed without changing the displayed command. The engine package-cycle baseline becomes empty. No new Gradle module, dependency cycle, public inbound API, or production null implementation is introduced.
8. Wire-key enforcement detects undeclared literal keys at governed payload seams, including a field introduced by a canonical schema before an owning Kotlin key exists. Complete the key ownership migration for decomposition manifests and workflow envelopes in the same commit that enables these checks. Keep genuine extension maps open and distinguish them from governed envelopes. State remaining scanner scope accurately.
9. Architecture documentation names the actual line ceiling, current module graph, allowed library dependencies, and nonempty baseline policies. Remove incidental English-phrase assertions while retaining governed section, module, contract-version, and compatibility checks. Add only checks that catch a named failure, not a test that compares a value with itself or duplicates its scanner.
10. Every subtask preserves governed source generation, manifest-driven pack discovery, typed schema errors, schema parity, lease fencing, database-authoritative continuation, single-round review remediation, and runtime-owned commit/push. Changed contract shapes follow schema-first versioning and parse-seam validation. No source baseline or suppression is expanded to make the work pass.

## Existing ownership and dependencies

SKILL-233 is archived, but its intended cleanup is only partially reflected in the current tree. This feature uses current evidence and does not assume that archive placement proves every historical criterion.

SKILL-238 owns deletion of same-module role-interface wrappers and thin application forwarding services. Keep that ownership. Subtask 3 adapts to whichever signatures exist when it runs and does not require the unrelated IDE or platform cleanup in SKILL-238. If those wrappers still exist, this feature may change their signatures for the state refactor but must not claim their deletion as new acceptance work.

SKILL-236 owns telemetry event identity, delivery replay, and broad lifecycle metrics. Subtask 1 owns process cleanup and local evidence for failures in that boundary. It does not introduce a second telemetry pipeline.

347 owns validation-gate execution evidence. Preserve its working changes and final receipt behavior. The investigation observed concurrent edits and commits in that area; execution must reread the current state before applying this spec.

## Constraints and non-goals

- Use existing modules, DI, diagnostics, migration infrastructure, and ports. Add a port only for an actual adapter boundary or needed test substitute.
- Keep the JVM and thread-based execution model. Do not add coroutines, a process framework, a generic event bus, a new workflow engine, or an ORM.
- Do not remove one-adapter hexagonal ports, schema validators, parity tests, lease generations, or durable evidence because they add code.
- Do not convert every String identifier to a value class or every open map to a DTO. Type the state whose invariants this feature changes.
- Do not merge the run loop into one large file or split helpers just to satisfy a numeric limit. No target for file count, line reduction, or constructor arity replaces a responsibility boundary.
- No blanket dependency upgrades, Kotlin compiler migration, platform-pack edits, skill rendering changes, or IDE redesign.
- No new comments. Remove existing comments in edited code when that code is changed. Leave unrelated files untouched.
- Implementation starts only through a later goal invocation. This preparation performs no commit, push, installation, or live data mutation.

## Validation strategy

Use the focused commands and failure cases in each subtask. Normal regression tests belong to implementation validation, not to the goal runtime's build-only agent session. If the runtime selects build, that session runs only the pack-declared build command and cache-bypassing confirmation under the current AGENTS.md contract.

The final evidence must distinguish tests run from code inspected. Preserve process probe cleanup, use temporary databases, and avoid the user's live runtime database. Do not add a suite-wide percentage target or an exact SQL-count pin.

## Delivery and next path

1. [Process lifetime](spec_subtask_1_process-lifetime.md).
2. [Persistence lifecycle](spec_subtask_2_persistence-lifecycle.md).
3. [Engine state ownership](spec_subtask_3_engine-state-ownership.md).
4. [Architecture enforcement](spec_subtask_4_architecture-enforcement.md).

Run `skill-bill goal SKILL-239` when implementation should begin.
