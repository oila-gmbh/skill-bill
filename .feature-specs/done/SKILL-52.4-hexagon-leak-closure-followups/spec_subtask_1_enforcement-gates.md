# SKILL-52.4 · Subtask 1 — Enforcement gates (Phase 0)

Parent overview: [spec.md](./spec.md)

This is the **first** subtask and the foundation for everything that follows.
It closes the enforcement holes (F6–F9) so every later phase's fix cannot
silently regress, and it lands the live-drift declaration (`skillbill.goalrunner`)
that proves the new completeness test has teeth.

Branch: `feat/SKILL-52.4-hexagon-leak-closure-followups` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: [] (runs first)
- dependency_reason: Phase 0 tests define and guard the contract every later
  phase satisfies. F6 (goalrunner declaration) and F8 (test-source scan) are
  themselves Phase 0 deliverables that Phases 1–3 must not regress. Land the
  guard tests red→green here before any structural change relies on them.

## Scope (owns)

- **F6 — subsystem-package completeness.** New arch test that walks every
  declared Gradle main source root, extracts each `package` declaration, maps it
  to its owning declared subsystem (longest declared prefix), and fails if any
  real source package has no declaring subsystem entry. Confirm it fails today
  against `skillbill.goalrunner`, then add `skillbill.goalrunner` to:
  - `runtime-core/src/main/kotlin/skillbill/RuntimeModule.kt:33-59`
    (`declaredSubsystemPackages`)
  - the literal set in
    `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureDocumentationTest.kt:85-111`
  - `runtime-kotlin/ARCHITECTURE.md` Package Ownership block (lines ~144-219)
    and the subsystem-package allow-list block.
  All three artifacts must stay in parity; the new walking test — not the
  hard-coded snapshot — becomes the source→doc guarantee.
- **F7 — raw-map scanner widening.** Extend `findRawMapViolations`
  (`runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt:1294-1369`,
  banned-shape literals around lines 851-853) to also flag `Map<String, Any>`
  (non-null), `HashMap`/`LinkedHashMap`/`MutableMap` string-keyed
  `Any?`/`Any`/`*` returns, and typealiases that resolve to a banned shape
  (resolve `typealias X = Map<String, Any?>` in scope, then treat `X` as banned).
  Add one positive fixture per new pattern in the test's fixture block. Any newly
  caught real declaration must be typed OR allow-listed via the **three-place
  lockstep**: `@OpenBoundaryMap` annotation + `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST`
  + ARCHITECTURE.md entry. No silent grandfathering.
- **F8 — test-source boundary scan.** Add a scan over `src/test` / `src/jvmTest`
  / `src/commonTest` that forbids inner-layer test code (`runtime-domain`,
  `runtime-ports`, `runtime-application`) from importing
  `skillbill.infrastructure.*`, `skillbill.cli.*`, `skillbill.mcp.*`,
  `skillbill.desktop.*`. **Land enforcing immediately** (resolves open question
  3). Entry-adapter and infra test trees keep legitimate seams via an explicit
  documented allow-list. Confirm which trees count as "inner-layer": the known
  `JvmDesktopFirstRunGatewayTest` infra import lives in a desktop/infra test tree
  (`runtime-desktop/core/data/src/jvmTest`), NOT an inner-layer tree, so it is
  outside the ban surface — document this finding rather than allow-listing it
  inside the inner scan.
- **F9 — per-module dependency-direction coverage.** Generalize
  `RuntimeAdapterDependencyAllowlistTest` (currently 4 modules:
  `runtime-cli`, `runtime-mcp`, `runtime-desktop:core:data`,
  `runtime-desktop:feature:skillbill`) into a per-module rule set covering all
  **21** Gradle modules. For each module assert its non-test `project(...)` deps
  are a subset of an allowed set derived from its layer (infra →
  ports/domain/contracts only; core → no infra/entrypoint as `api`; desktop
  leaves → declared allow-lists). Pin current edges; fail on any new
  upward/sibling-concrete edge.

The 21 modules: runtime-application, runtime-contracts, runtime-core,
runtime-domain, runtime-infra-fs, runtime-infra-http, runtime-infra-sqlite,
runtime-cli, runtime-desktop, runtime-desktop:core:common,
runtime-desktop:core:data, runtime-desktop:core:database,
runtime-desktop:core:datastore, runtime-desktop:core:designsystem,
runtime-desktop:core:domain, runtime-desktop:core:navigation,
runtime-desktop:core:testing, runtime-desktop:core:ui,
runtime-desktop:feature:skillbill, runtime-mcp, runtime-ports.

## Reusable patterns / pitfalls

- Three-place raw-map lockstep is mandatory (SKILL-52.1/SKILL-66/SKILL-65.1).
- Fixture-based negative tests: prove each new scanner rule with a positive
  fixture that the rule catches.
- Write each guard test FIRST and confirm it is red against current `main`
  before adding the fix (the F6 test MUST fail against current main).
- The widened raw-map scanner and per-module test may surface pre-existing debt:
  triage each (type it, allow-list with rationale, or document exception) — do
  NOT weaken the rule to make a pre-existing case pass.
- No explanatory comments (CLAUDE.md: comments are a code smell).

## Acceptance Criteria

1. AC1: new arch test walks all declared main source roots, fails if any real
   `package` has no declaring subsystem entry; fails today against
   `skillbill.goalrunner`, passes after declaration added.
2. AC2: `skillbill.goalrunner` added to `RuntimeModule.declaredSubsystemPackages`,
   `RuntimeArchitectureDocumentationTest` literal set, ARCHITECTURE.md Package
   Ownership block + subsystem-package allow-list block; all in parity.
3. AC3: raw-map scanner flags `Map<String, Any>`,
   `HashMap`/`LinkedHashMap`/`MutableMap` string-keyed maps, and
   typealias-laundered banned shapes, each proven by a fixture; newly-caught real
   decls typed or allow-listed (three-place lockstep).
4. AC4: test scans inner-layer test sources and forbids
   `skillbill.infrastructure.*`/`skillbill.cli.*`/`skillbill.mcp.*`/`skillbill.desktop.*`
   imports; LAND ENFORCING IMMEDIATELY; documented allow-list; inner-layer trees
   confirmed.
5. AC5: per-module dependency-direction test covers all 21 Gradle modules; fails
   on any new upward/sibling-concrete edge; current edges pinned.
6. AC14 (this subtask's slice): `skill-bill validate`,
   `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`,
   `scripts/validate_agent_configs` all pass.

## Non-goals

- No Phase 1/2/3 structural change (timing/logging ports, desktop leaks,
  god-object splits).
- No behavioral change; golden/wire outputs byte-identical.
- Not retiring `skillbill.contracts.*` split package (F16 — later subtask).

## Validation strategy

`bill-code-check` (routes Kotlin/Gradle → `(cd runtime-kotlin && ./gradlew check)`).
Run all four canonical gates: `skill-bill validate`;
`(cd runtime-kotlin && ./gradlew check)`; `npx --yes agnix --strict .`;
`scripts/validate_agent_configs`. Each new guard test proven red on the unfixed
tree, then green.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-52.4-hexagon-leak-closure-followups/spec_subtask_1_enforcement-gates.md`.
