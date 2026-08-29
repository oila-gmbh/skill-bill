# SKILL-220: Principle Conformance Refactor

## Intended Outcome

Bring this repository into conformance with the code principles delivered by
skill-bill-v2 SKILL-18 (`docs/code-principles.md` and that tree's AGENTS Coding
Conventions), adapted to this hexagonal `skillbill.*` runtime, and make each
principle enforceable so conformance survives future work. Observable runtime
behavior does not change: the same workflow phases, durable execution,
persistence, diagnostics, telemetry, cancellation, resume, CLI, and MCP
surfaces. What changes is where types live, how closed sets are modelled and
dispatched, how failures carry identity, how types are imported, how large
units are split, and which of these rules the build can prove.

This is a structural conformance program, not a rewrite and not a hardening
effort. No public wire contract, durable encoding, or workflow semantic
changes except where a subtask names the change and its migration.

## Principle Sources

Three inputs define conformance:

- skill-bill-v2 `docs/code-principles.md` — type modeling, failure contracts,
  capability signaling, single source of truth, module and package layout,
  concurrency and lifetime, composition and API surface, build and tooling.
  This program publishes an adapted copy at `docs/code-principles.md` in this
  tree (subtask 8) with reference examples that exist here.
- skill-bill-v2 AGENTS.md Coding Conventions — string-literal constants,
  `.model` package clustering, UUID identifiers, comment policy, test value.
- This repository's `runtime-kotlin/ARCHITECTURE.md` hexagonal module graph
  and existing architecture-test suite. New guards must not duplicate that
  suite (module layering, raw-map allow-lists, schema ownership, CLI/MCP
  import bans).

Where a principle and existing code disagree and the existing code carries a
written justification, this program does not overrule it silently. Subtask 3
records the capability-vocabulary case for decision rather than assuming the
principle wins.

## Audit Finding Ownership

Findings are from a 2026-08-29 principle-conformance survey of this tree
against the v2 SKILL-18 rules. v2 findings that do not exist here (domain-root
grab-bag, eight-slot Input/Output/Outcome/Status quartet, module-local detekt
source) are not tickets.

| Finding | Severity | Problem | Owning subtask |
| --- | --- | --- | --- |
| P-01 | Major | `skillbill.application.model` holds 28 files spanning ~16 noun families — a layer-wide type bucket. `skillbill.ports.persistence` (+ `.model`) groups every `*Repository` by type, not by area (~10 product nouns). | 1 |
| P-02 | Major | `skillbill.workflow` holds 19 loose files mixing FeatureTaskRuntime, decomposition, engine, goal, IDE status, and spec-source. `application.featuretask` (65) and `application.goalrunner` (30) mix FeatureSpec, GoalPlanning, and findings into the area root. `scaffold.policy` mixes PlatformPack and Scaffold. | 1 |
| P-03 | Major | Production and test sources embed ~1,100 inline fully-qualified type names (`java.time.Instant.parse(...)`, `skillbill.workflow.Foo`, extension receivers on `java.sql.PreparedStatement`) instead of imports. Existing architecture tests only ban adapter/infra FQNs leaking into pure layers. | 2 |
| P-04 | Major | Closed-set `when` still uses residual `else`. Worst: sealed `LaunchPreparation` in `FeatureTaskRuntimeRunLoop` with `else -> error("Unexpected...")`. ~129 sealed/enum `when`s have a residual `else`. | 3 |
| P-05 | Medium | Phase-output and decomposition `*FailureCode` enums pair with sealed results and a second `*FailureKind` / string wire code. `fromWire` collapses unknowns to `SCHEMA_INVALID`. Nothing proves the mapping is total and injective. | 3 |
| P-06 | Medium | Untrusted decode uses `error()`, `require()`, `IllegalArgumentException`, and `runCatching` as a classifier: `GoalRunnerControlStore` durable JSON, handoff/phase-output `fromWire`, YAML caps/exclusions, validation-gate XML → empty list. | 3 |
| P-07 | Medium | Open string capability vocabularies: `fallbackCapabilities: Set<String>`, duplicated `"code-review"`, `"entrypoint"` slot constants, `AgentRunIdlePolicy` as open fun-interface constants. The closed/open boundary is not recorded. | 3 |
| P-08 | Major | 52 production files exceed 500 lines. `FeatureTaskRuntimeRunLoop` is ~7095 lines. Goal runner, parallel review, CLI bags, pack loaders, and sqlite stores follow. Detekt `LargeClass` is suppressed rather than split. | 4, 5, 6 |
| P-09 | Minor | Identical `update-snapshots` Test `systemProperty` is declared in `runtime-infra-fs` and `runtime-contracts` module build files; convention plugins already own test configuration. | 7 |
| P-10 | Major | Nothing in the build proves package clustering, failure-code totality, exhaustive closed-set dispatch, typed parse boundaries, the 500-line ceiling, or the no-inline-FQN rule. Existing architecture tests cover module graph and raw maps only. | 7 |
| P-11 | Minor | This tree has no `docs/code-principles.md` and no AGENTS Coding Conventions section. `ARCHITECTURE.md` states rules without naming copyable reference examples for the principles this program applies. | 8 |

## Scope

- Cluster cross-area model and persistence buckets into area-owned packages,
  and split mixed workflow / featuretask / goalrunner / scaffold.policy
  packages by noun family.
- Replace inline fully-qualified type names with imports across
  `runtime-kotlin`, `intellij-plugin`, and `runtime-kotlin/build-logic`
  production and test sources.
- Bind every failure case to its stable code through one shared contract, make
  closed-set `when` exhaustive, and return typed failures for untrusted input.
- Decide and document the boundary between closed capability enums and open
  string key sets.
- Decompose every production file over 500 lines into cohesive collaborators
  without multiplying public abstractions.
- Add architecture tests that fail the build when a principle in this program
  regresses, and hoist repeated module build configuration into convention
  plugins.
- Publish `docs/code-principles.md` and reconcile AGENTS.md and area decision
  logs with the delivered structure.

## Applicable Principles And Invariants

- Dependencies point inward: entry adapters and infrastructure to application
  and ports to domain. No package move may create an outward or sideways edge.
- Composition (`runtime-core` / `skillbill.di`) is the only place that
  constructs the runtime graph.
- Only the workflow engine chooses transitions; phases report typed outcomes.
- A closed set of variants is one sealed hierarchy in one file; the compiler
  is the checklist when a variant is added.
- Expected failures cross a boundary as typed results; exceptions are for
  broken invariants.
- A surface is declared once as data and every consumer derives from it.
- No adapter, JDBC, or filesystem type leaks into domain or application APIs.
- Every replaceable implementation is bound by a conformance contract.
- Types are imported and referenced by simple name. Inline fully-qualified
  names are not a style option.

## Implementation Sequence

Package moves land before the FQN sweep and before decomposition so later
subtasks do not re-churn imports. Enforcement lands after the structure it
enforces exists.

1. Package clustering (P-01, P-02)
2. Inline fully-qualified name sweep (P-03)
3. Failure identity, typed parse boundaries, exhaustive dispatch, capability boundary (P-04, P-05, P-06, P-07)
4. Oversized unit decomposition: feature-task runtime (P-08)
5. Oversized unit decomposition: goal runner (P-08)
6. Oversized unit decomposition: remaining units (P-08)
7. Principle enforcement in architecture tests and build-convention promotion (P-09, P-10)
8. Documentation reconciliation and final verification (P-11)

## Acceptance Criteria

1. `scripts/validate` passes at every subtask boundary, and the full suite
   passes at program completion.
2. No subtask changes a durable encoding, a public wire contract version, an
   error code string, or a workflow transition, except where its spec names
   the change, its migration, and its conformance test.
3. Behavior parity is proved by the existing test suite passing unchanged in
   intent: a test may change imports and type names, but a test whose
   assertions had to be weakened to pass is a defect in the refactor, not in
   the test.
4. No package in `runtime-domain`, `runtime-ports`, or `runtime-application`
   holds files from two unrelated noun families after subtask 1.
5. Production and test Kotlin under `runtime-kotlin`, `intellij-plugin`, and
   `runtime-kotlin/build-logic` reference types by imported simple name.
   Remaining inline FQNs are only compiler-required disambiguation (prefer
   `import x.Foo as FooX`), string literals, `import`/`package` lines, or
   generated sources.
6. Every closed-set `when` in production code is exhaustive without `else`,
   or its `else` is over a genuinely open input and documented as such.
7. Every failure case in a `*Failure` / `*FailureCode` hierarchy resolves to
   exactly one code, and every code is reachable from exactly one case, proved
   by a conformance test rather than by review.
8. Untrusted operator or agent input produces a typed failure at every named
   parse boundary; no `error()`, `require()`, or bare `throw` reports
   malformed external input, and no `runCatching` classifies a decode as
   success-shaped empty.
9. No production file exceeds 500 lines after subtask 6, and no decomposition
   introduces a public type whose only caller is the file it was extracted
   from.
10. Each principle this program enforces has a test that fails when the
    principle is violated, demonstrated against a deliberately violating
    fixture.
11. Public failures keep stable typed codes and actionable recovery guidance
    throughout.

## Public Contracts Introduced Or Changed

- A shared contract binding a failure case to its stable code, implemented by
  every existing `*Failure` / `*FailureCode` hierarchy. This changes how the
  code is reached, not the code values themselves.
- Area-local packages replacing `skillbill.application.model` and the
  cross-area `skillbill.ports.persistence` bucket. Public type names stay;
  package paths change. Callers update imports only.
- A typed parse-boundary failure for unrecognized fallback capability tokens
  and for durable control-state JSON that today throws `error()` /
  `IllegalArgumentException`, joining the existing contract-error families.

No port interface semantic change, persistence schema change, or CLI/MCP
surface change except import paths.

## Constraints

- One subtask per commit on the feature branch, each independently green under
  `scripts/validate`.
- Package moves are mechanical: a subtask that moves files does not also
  change behavior, and a subtask that changes behavior does not also move
  files. The FQN sweep is mechanical import hygiene and does not change
  behavior.
- Existing suppressions, justified comments, and recorded decisions are
  preserved unless a subtask names the one it removes and why.
- No new dependency, no new module, and no dependency-injection framework
  decision is adjudicated here.
- Tests are held to the AGENTS.md value bar: a test added by this program
  must name the regression it catches. Package-move and FQN-sweep subtasks
  add no tests.
- Do not duplicate architecture tests that already pin the module graph,
  raw-map allow-lists, schema-validator ownership, or CLI/MCP import bans.
- `RuntimeComponent` remains the single construction site. Extracts from it
  stay internal construction steps, not a second DI graph.
- The 500-line ceiling is the same hard gate as v2 SKILL-18, applied to this
  larger tree via three independently reviewable oversized subtasks.

## Non-Goals

- Changing runtime behavior, workflow semantics, repair-hop policy, or
  operator surfaces.
- Reworking telemetry, diagnostics retention, or the learning system.
- Introducing a new concurrency model or altering cancellation protocols.
- General test expansion beyond the enforcement tests subtask 7 names.
- Replacing Konsist/ArchUnit; this tree already uses hand-rolled source
  scanners — extend those.
- Closing every `else` over genuinely open input (untrusted JSON maps,
  external process stdout). Those stay open and are documented.
- Splitting test sources to the 500-line ceiling (production only).
- Relitigating SKILL-52 raw-map allow-lists or hexagonal module edges.

## Dependency And Coordination Notes

Subtask 1 touches imports across most modules and must not run concurrently
with 2, 4, 5, or 6. Subtask 2 depends on 1 so the FQN sweep lands on settled
packages. Subtask 3 is behavioral and must not mix with file moves; it lands
before 4–6 so exhaustive dispatch and typed failures exist before those
files are split. Subtasks 4, 5, and 6 are independent of each other once 1–3
have landed; they are separate because one implement pass cannot carry
fifty-two files including a ~7k-line run loop. Subtask 7 depends on every
structural subtask landing first. Subtask 8 runs last so reference examples
point at delivered code.

Subtask 3 may conclude that some capability vocabularies should stay open.
That is an acceptable outcome; the deliverable is the recorded decision and
the documented boundary, not a forced conversion.

## Migration And Compatibility Policy

No durable data migration is expected. If subtask 3 finds a failure code that
no case produces, the code is retired only after confirming no persisted
diagnostic, telemetry record, or documented recovery table references it;
otherwise it is retained and its case is restored.

Package moves change Kotlin package paths. Downstream imports in this
repository update in the same subtask. No published Maven coordinate or
external ABI is claimed.

## Validation Strategy

Each subtask runs `scripts/validate` before commit. Package-move and FQN-sweep
subtasks additionally prove parity by confirming the test suite passes with no
assertion changes. Subtask 7 proves each new enforcement test against a
violating fixture as well as against the clean tree. The program completes
only when `scripts/validate` passes on a clean checkout.

## Next Path

Begin with subtask 1: cluster cross-area packages.
