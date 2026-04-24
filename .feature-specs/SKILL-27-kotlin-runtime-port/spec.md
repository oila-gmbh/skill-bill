---
issue_key: SKILL-27
feature_name: kotlin-runtime-port
feature_size: LARGE
status: In Progress
created: 2026-04-22
depends_on: SKILL-15 (new-skill scaffolder), workflow runtime pilots, telemetry proxy stats, current Python test suite as behavioral oracle
---

# SKILL-27 — Port the local Skill Bill runtime from Python to Kotlin

## Problem

Skill Bill's governance model, contracts, docs, and tests have become real
product surface. But the local runtime is still implemented in Python, and the
maintainer's primary engineering fluency is Kotlin rather than Python.

That creates a practical ownership problem:

1. the maintainer cannot confidently explain or evolve large parts of the
   current runtime without leaning on AI
2. the project's most important operational surfaces now live in code rather
   than only in Markdown contracts
3. continued growth in a language the maintainer does not truly own increases
   long-term fragility, even if short-term AI-assisted delivery stays fast

The project should not stay trapped in a state where the governance system is
valuable but the maintainer cannot safely reason about the runtime that makes
it work.

## Product stance

This is **not** a greenfield rewrite of Skill Bill's behavior.

It is a **contract-preserving runtime port**:

- preserve the current external behavior as closely as practical
- use the current Python implementation plus tests as the migration oracle
- move the runtime into Kotlin so the maintainer can actually own it

The product value is not Python itself. The value is the governed behavior:

- stable skill entry points
- manifest-driven platform packs
- workflow state and continuation
- telemetry contracts
- scaffolding and validation
- cross-agent portability

## Why now

- The runtime surface is now large enough that maintainability matters more
  than language neutrality.
- The current repo has enough contracts, tests, and docs to make a behavior-
  preserving port realistic.
- Workflow runtime, CLI surface, MCP tools, telemetry proxy stats, and
  scaffolding are now explicit enough to port intentionally instead of
  guessing.
- Delaying too long risks adding more Python-only behavior the maintainer
  still does not understand.

## Current runtime size

At the time this spec was written, the local runtime surface was roughly:

- `skill_bill/`: about 25 Python files and about 13.9k lines
- `tests/`: about 30 Python test files and about 13.6k lines

This is large enough that a casual rewrite is risky, but small enough that a
phased contract-preserving port is still feasible.

## Goal

Move the local Skill Bill runtime from Python to Kotlin so the maintainer can
own, debug, and extend it directly, without changing the product thesis or
silently redesigning the external behavior.

## Non-goals

- Rewriting the Markdown skill content, platform packs, or orchestration
  contracts into Kotlin-native representations.
- Redesigning the product surface during the port.
- Inventing new stable commands, new workflow families, or new telemetry
  semantics as part of the migration.
- Porting every repo utility script on day one if a script can remain Python
  temporarily without blocking ownership of the runtime.
- Deleting the Python runtime before the Kotlin runtime reaches parity.
- Using the migration as an excuse to skip tests, specs, or explicit contracts.

## Migration principles

### 1. Port behavior, do not redesign it

The Kotlin implementation should preserve the current CLI, MCP, workflow,
telemetry, and scaffolding behavior unless a deliberate follow-up spec changes
that contract.

### 2. Freeze contracts first

The port should treat the existing external surfaces as frozen until parity is
reached:

- CLI command names and argument shapes
- JSON output shapes
- MCP tool names and payloads
- SQLite schema and semantics
- scaffold payload contract
- governed loader loud-fail behavior

### 3. Keep Python as the reference implementation until parity

Python stays in the repo during the migration as:

- the behavioral oracle
- the fallback implementation
- the source of parity comparison during the port

### 4. Migrate subsystem by subsystem

Do not attempt one giant cutover.

### 5. Tests are part of the product contract

The migration is not complete because the Kotlin code "looks right." It is
complete when the relevant behavior is verified through parity-focused tests.

### 6. End every phase with a reusable handoff

This feature will be implemented across multiple sessions. Each phase must end
with:

- committed code or docs for that phase's scope
- an explicit validation result
- a carryover note naming what is done, what remains, and what the next
  session should start with

Do not leave the runtime in a half-moved state that only makes sense if the
same agent continues immediately afterward.

## Target runtime shape

Introduce a dedicated Gradle-based Kotlin runtime module at:

```text
runtime-kotlin/
```

Recommended contents:

```text
runtime-kotlin/
  build.gradle.kts
  settings.gradle.kts
  src/main/kotlin/skillbill/...
  src/test/kotlin/skillbill/...
```

Recommended package boundaries:

- `skillbill.cli`
- `skillbill.mcp`
- `skillbill.db`
- `skillbill.telemetry`
- `skillbill.review`
- `skillbill.learnings`
- `skillbill.workflow.implement`
- `skillbill.workflow.verify`
- `skillbill.scaffold`
- `skillbill.contracts`
- `skillbill.install`

This module should eventually own the local runtime entry points now exposed as
`skill-bill` and `skill-bill-mcp`.

## Sessionable phased migration plan

This migration is intentionally split into carryover-friendly phases. A phase
may take more than one session, but the stopping point must always be at a
named phase checkpoint rather than in the middle of an implicit rewrite.

### Carryover contract between sessions

At the end of every phase, the repo must contain:

1. a phase note in `docs/migrations/SKILL-27-kotlin-runtime-port.md`
   describing:
   - what the phase changed
   - what contracts are now covered
   - what validation ran
   - what the next phase should start with
2. any parity fixtures or representative outputs introduced by that phase
3. a clear statement of whether Python or Kotlin is the active source of truth
   for the touched subsystem

If a session ends before a phase checkpoint is reached, the work should be
considered incomplete and resumed before starting a new phase.

### Phase ledger

| Phase | Name | Goal | Session checkpoint |
| --- | --- | --- | --- |
| 0 | Freeze and map | Capture the migration contract and subsystem ownership | Migration note + contract inventory exist |
| 1 | Runtime foundation | Create the JVM-only Kotlin module and shared contract layer | `runtime-kotlin/` builds |
| 2 | Persistence core | Port SQLite, DB access, and telemetry persistence primitives | Kotlin DB layer passes parity checks |
| 3 | Review domain | Port review, triage, learnings, stats, and sync behavior | Review-domain parity checks pass |
| 4 | Workflow runtime | Port feature-implement and feature-verify workflow state/runtime | Workflow parity checks pass |
| 5 | CLI surface | Port `skill-bill` command behavior | CLI parity checks pass |
| 6 | MCP surface | Port `skill-bill-mcp` tools and payloads | MCP parity checks pass |
| 7 | Scaffold and loader | Port governed loader, scaffold, and install primitives | Loader/scaffold parity checks pass |
| 8 | Cutover preparation | Wire launchers, docs, and fallback strategy without deleting Python | Dual-runtime handoff works |
| 9 | Final cutover | Make Kotlin the default runtime when parity gates are satisfied | Kotlin is default; Python remains temporary fallback |
| 10 | Python retirement | Remove Python runtime only after normal-use confidence exists | Python runtime deleted intentionally |

### Phase 0 — Freeze and map

Before porting runtime behavior:

- document subsystem ownership of the current Python modules
- list the external contracts that must remain stable
- identify which tests protect which subsystem
- capture representative CLI and MCP JSON outputs for parity fixtures
- write the carryover structure in `docs/migrations/SKILL-27-kotlin-runtime-port.md`

Deliverables:

- migration note with subsystem map and frozen-contract inventory
- initial parity fixture list
- explicit statement that Python remains the active source of truth

Exit criteria:

- another session can start Phase 1 without re-discovering runtime boundaries

### Phase 1 — Runtime foundation

Create the Kotlin runtime foundation as a JVM-only Gradle module:

- `runtime-kotlin/`
- shared Gradle conventions inspired by `KMPLibraryStarter` where appropriate
- version catalog / dependency management
- base package layout for contracts, errors, launcher, db, cli, mcp, review,
  telemetry, workflows, scaffold, and install
- shared Kotlin contract types and error taxonomy

Deliverables:

- buildable `runtime-kotlin/` skeleton
- documented dependency choices and rejected mobile/KMP-only pieces
- initial Kotlin test harness

Exit criteria:

- `runtime-kotlin` builds successfully
- later phases can add behavior without reworking the project foundation

### Phase 2 — Persistence core

Port the persistence layer first:

- constants and enums that define persisted/runtime contracts
- DB schema ownership
- connection and migration logic
- telemetry outbox and persistence helpers

Why first:

- everything else depends on stable data shapes
- schema parity is easier to validate early than late

Deliverables:

- Kotlin SQLite access layer
- parity fixtures for representative DB rows and schema behavior
- explicit source-of-truth note for DB ownership during transition

Exit criteria:

- Kotlin DB operations match the representative Python behavior for the frozen
  persistence contract

### Phase 3 — Review domain

Port:

- review import and parsing helpers
- feedback recording
- learnings resolution and persistence
- local stats aggregation
- telemetry sync and remote-stats client behavior

Deliverables:

- Kotlin review/learnings/stats/sync services
- parity tests for representative review and telemetry flows
- carryover note describing any remaining Python-owned review paths

Exit criteria:

- the review domain can be reasoned about from Kotlin code and tests without
  reverse-engineering Python every time

### Phase 4 — Workflow runtime

Port:

- `bill-feature-implement` workflow state
- `bill-feature-verify` workflow state
- resume and continue payload builders
- workflow listing and latest-resolution logic

Deliverables:

- Kotlin workflow runtime module
- parity fixtures for workflow-state rows and continuation payloads
- explicit preservation of the top-level workflow contract boundary

Exit criteria:

- workflow runtime behavior is reproducible in Kotlin with parity coverage

### Phase 5 — CLI surface

Port:

- current `skill-bill` command tree
- argument shapes
- output modes (`text` and `json`)
- error semantics where they are already part of the user contract

Deliverables:

- Kotlin CLI implementation
- representative command-output parity tests
- migration note entries for any intentionally deferred or non-identical text
  output

Exit criteria:

- the stable CLI surface is covered by Kotlin implementation plus parity checks

### Phase 6 — MCP surface

Port:

- all currently exposed `skill-bill-mcp` tools
- tool names
- request parameter names
- returned payload shapes

Deliverables:

- Kotlin MCP server implementation
- representative request/response parity fixtures
- explicit documentation of any fallback behavior that still depends on Python

Exit criteria:

- machine-facing MCP contracts match the frozen Python behavior at the payload
  shape level

### Phase 7 — Scaffold and loader

Port:

- shell/content contract loader
- scaffolder transaction and rollback logic
- install primitives

This phase stays late because it touches the most repo mutation and
contract-heavy behavior.

Deliverables:

- Kotlin governed-loader implementation
- Kotlin scaffold/install primitives
- loud-fail rejection parity tests

Exit criteria:

- governed repo mutation behavior is covered by Kotlin plus rejection-path
  tests, not just happy-path smoke checks

### Phase 8 — Cutover preparation

Before switching defaults:

- wire launchers and entry-point routing
- document dual-runtime behavior
- keep Python as the fallback implementation
- document the exact gates required before Kotlin becomes default

Deliverables:

- dual-runtime launcher strategy
- maintainer-facing runtime map docs
- cutover checklist with rollback steps

Exit criteria:

- a future session can switch defaults intentionally without inventing the
  cutover plan from scratch

### Phase 9 — Final cutover

Only after subsystem parity exists:

- switch the default runtime entry points to Kotlin
- keep Python available briefly as fallback and parity reference
- verify normal workflows against the Kotlin-first path

Deliverables:

- Kotlin-first runtime entrypoints
- temporary documented Python fallback
- post-cutover validation report

Exit criteria:

- Kotlin is the default runtime and Python remains only as a temporary escape
  hatch

### Phase 10 — Python retirement

Only after the Kotlin runtime is proven in normal use:

- remove the Python runtime entrypoints and implementation
- delete Python-only tests that no longer protect active behavior
- keep migration notes describing what was removed and why

Deliverables:

- Python runtime removed intentionally
- updated docs and validation story
- final migration note marking the port complete

Exit criteria:

- the repo no longer depends on the legacy Python runtime for the active local
  product surface

## Acceptance criteria

1. A Kotlin runtime module exists and builds successfully with Gradle.

2. The migration defines explicit contract boundaries for the current Python
   runtime, covering at minimum:
   - CLI command names and options
   - MCP tool names and payloads
   - SQLite schema
   - scaffold payload schema
   - loud-fail governed loader behavior

3. The Kotlin runtime reaches parity for the core operational surfaces:
   - review/learnings/stats/telemetry logic
   - workflow runtime for implement and verify
   - CLI surface
   - MCP surface

4. The Kotlin runtime preserves the current SQLite-backed behavior rather than
   silently switching persistence models.

5. The Kotlin CLI preserves the current stable command surface or documents
   every intentional difference in a migration note approved separately from
   this spec.

6. The Kotlin MCP server preserves the current tool names and returned payload
   shapes or documents every intentional difference in a migration note
   approved separately from this spec.

7. The migration includes parity-focused tests, not only brand-new Kotlin unit
   tests. At minimum there must be subsystem checks proving that Kotlin and
   Python agree on representative inputs and outputs for the frozen contracts.

8. The migration keeps the Python runtime in the repo until the Kotlin runtime
   is validated enough for cutover.

9. The cutover plan is explicit: how the default executable names move to
   Kotlin, how fallback works, and what conditions allow deleting the Python
   runtime later.

10. The migration updates maintainer documentation so a Kotlin developer can
    find the runtime entry points, data boundaries, and protected contracts
    without reverse-engineering the old Python code.

## Parity requirements

The migration should preserve or intentionally account for all of the
following:

- `skill-bill` CLI command tree
- `skill-bill-mcp` tool inventory
- JSON output field names
- workflow ids and session-id conventions where they are externally visible
- review import and triage behavior
- learning scope precedence
- telemetry sync semantics
- remote telemetry stats request and response shape
- scaffold payload validation
- shell/content loader loud-fail exceptions
- install-path resolution across supported agents

## Recommended implementation bias

- Prefer simple, explicit Kotlin over framework-heavy abstraction.
- Prefer sealed classes / enums / data classes for runtime contracts.
- Prefer preserving behavior before pursuing elegance.
- Prefer small translation layers over clever generic architecture.
- Prefer writing new parity tests before deleting old Python code.

## Risks

### Risk: accidental redesign during port

Mitigation:

- freeze contracts up front
- require parity fixtures and explicit diff review

### Risk: loss of test coverage during migration

Mitigation:

- keep Python tests as migration oracle
- add Kotlin-side subsystem parity checks before cutover

### Risk: two runtimes diverge

Mitigation:

- migrate in vertical slices
- keep one active source of truth per subsystem at a time
- document which runtime owns which path during transition

### Risk: the migration becomes another vibe-coded codebase

Mitigation:

- require specs and subsystem ownership docs
- require explanation of every major module in maintainer-facing docs
- keep the port narrow and contract-driven

## Open questions to resolve in planning

1. Should the repo keep Python for repo validators and migration scripts even
   after the local runtime moves to Kotlin, or should those also be ported?
   Recommendation: keep them in Python initially unless they block ownership
   of the main runtime.

2. Which Kotlin libraries should back the new runtime?
   Recommendation: choose minimal, boring libraries for CLI, SQLite, JSON,
   HTTP, and MCP/stdio integration rather than a large framework stack.

3. Should the Kotlin runtime live in a dedicated submodule or become the
   primary root build?
   Recommendation: start with `runtime-kotlin/` as a dedicated module so the
   migration can proceed without destabilizing the current repo layout.

4. Should cutover happen subsystem-by-subsystem or in one runtime switch after
   parity?
   Recommendation: preserve a single user-facing cutover after subsystem
   parity, but allow internal Kotlin progress to land incrementally before
   the final switch.

5. How much output parity should be exact byte-for-byte versus shape-level?
   Recommendation: exact field and semantics parity for machine-facing JSON;
   text output may tolerate minor wording differences if they are deliberate
   and documented.

## Files and areas expected to change

Created:

- `runtime-kotlin/`
- Kotlin build files and source tree
- maintainer-facing runtime map docs
- parity-test fixtures and migration notes

Modified:

- runtime-entrypoint docs
- installation docs
- maintainer docs
- release and validation docs as needed for dual-runtime or cutover support

Later removal candidates after parity and cutover:

- `skill_bill/`
- Python runtime tests that only protect deleted behavior

## Success definition

SKILL-27 succeeds when Skill Bill still behaves like the same governed product,
but the local runtime is implemented in a language the maintainer can actually
own.
