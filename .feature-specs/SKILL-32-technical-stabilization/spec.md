# SKILL-32 Technical Stabilization Plan

## Status

- Status: In Progress
- Issue: `SKILL-32`
- Purpose: stabilize the Kotlin-default runtime and remove the technical risks
  surfaced during the project assessment.
- Scope: runtime health, Python retirement readiness, packaged Kotlin
  execution, MCP schema strictness, and deterministic contract coverage.
- Non-goal: adding more platform-pack content. Platform packs remain reference
  examples; this plan targets the governance/runtime system around them.

## Problem Statement

Skill Bill's product value is the governed skill system: stable commands,
manifest-driven packs, validators, cross-agent installation, workflow state,
telemetry, and scaffolded authoring. The architecture is sound, but the current
runtime state still has technical drag that weakens adoption confidence:

1. `runtime-kotlin` is the intended source of truth, but `./gradlew check` is
   currently red.
2. Python remains both fallback and compatibility surface, so ownership is
   split across two runtimes.
3. Installed commands still launch Kotlin through Gradle by default unless an
   override is configured, which is not a production-quality runtime path.
4. MCP tool schemas are only partially strict; open-object schemas still hide
   argument-contract drift.
5. Some contract guarantees are tested indirectly or through Python parity,
   rather than as first-class Kotlin runtime guarantees.

## Desired End State

At the end of SKILL-32:

- Kotlin runtime validation is green from a clean checkout.
- Every normal-use CLI and MCP path is Kotlin-owned or explicitly documented as
  not yet retired.
- The default installed runtime uses packaged Kotlin executables, not Gradle
  `run`.
- MCP `tools/list` exposes strict input schemas for every tool whose handler
  validates, persists, or emits telemetry.
- Contract and parity tests make Python retirement a deliberate checklist item,
  not a confidence guess.
- Documentation clearly states the remaining fallback boundary, if any.

## Phase 1: Restore Kotlin Runtime Health

Goal: make the advertised Kotlin validation gate true again before deeper work
continues.

Tasks:

- Fix current `runtime-application` quality failures:
  - `LifecycleTelemetryValidation.kt` exceeds Detekt `TooManyFunctions`.
  - `TelemetryService.autoSync` exceeds Detekt `ReturnCount`.
  - Spotless wants `validateCompletedValidationResult` reformatted.
- Prefer small structural fixes over suppressions:
  - group lifecycle validation helpers into focused validator objects or
    private section-owned files;
  - replace guard-return chains with one readable predicate or branch.
- Run:
  - `(cd runtime-kotlin && ./gradlew check)`
  - `.venv/bin/python3 -m unittest discover -s tests`
  - `npx --yes agnix --strict .`
  - `.venv/bin/python3 scripts/validate_agent_configs.py`

Acceptance criteria:

- All four validation commands pass.
- No new Detekt suppressions are added for the current failures.
- The result is recorded in `runtime-kotlin/agent/history.md` if reusable.

## Phase 2: Inventory Remaining Python Ownership

Goal: make Python retirement scope explicit before deleting or replacing
anything.

Tasks:

- Create a runtime ownership table covering:
  - CLI commands;
  - MCP tools;
  - scaffold and authoring commands;
  - install primitives;
  - validation scripts;
  - release/support scripts.
- Mark each item as:
  - Kotlin-owned;
  - Python-backed but acceptable as script/tooling;
  - Python-backed and blocking retirement;
  - deliberately retained compatibility fallback.
- Add focused tests for any "Kotlin-owned" item that is only covered through
  Python parity today.
- Decide what "remove Python runtime" means:
  - remove Python CLI/MCP fallback only;
  - keep Python repo validators/scripts;
  - or port all operator scripts as well.

Acceptance criteria:

- `docs/migrations/SKILL-27-cutover-checklist.md` has an updated retirement
  checklist that references this ownership table.
- No Python deletion starts until every blocking runtime item has an owner and
  test path.

## Phase 3: Package Kotlin Runtime For Normal Use

Goal: stop treating Gradle `run` as the default installed runtime path.

Tasks:

- Add a packaged runtime path for both entry points:
  - `runtime-cli` application distribution;
  - `runtime-mcp` application distribution.
- Teach the installer to build or locate those distributions.
- Update launcher behavior so normal installed use resolves to packaged
  executables.
- Keep Gradle execution as a development fallback only.
- Preserve explicit rollback:
  - `SKILL_BILL_RUNTIME=python`
  - `SKILL_BILL_MCP_RUNTIME=python`
  until the retirement phase removes them.
- Add installer tests for:
  - packaged CLI path resolution;
  - packaged MCP path resolution;
  - missing packaged runtime error message;
  - fallback environment variables.

Acceptance criteria:

- Fresh install can run `skill-bill doctor --format json` without invoking
  Gradle.
- Fresh MCP registration starts packaged `skill-bill-mcp` without invoking
  Gradle.
- Documentation names the packaged path and the development fallback.

## Phase 4: Complete MCP Input Schemas

Goal: make MCP tool contracts machine-readable and fail-fast.

Tasks:

- Replace open-object schemas for all handler-backed tools that require or
  validate arguments.
- Keep open-object schemas only for genuinely argument-free or intentionally
  passthrough tools, with tests documenting why.
- Add schema coverage tests that assert:
  - every required handler argument appears in `tools/list`;
  - every enum accepted by a handler appears in the schema;
  - unknown properties are rejected for strict tools;
  - zero-argument tools declare an empty strict object, not an open object.
- Prioritize:
  - `quality_check_started`
  - `quality_check_finished`
  - `feature_verify_started`
  - `feature_verify_finished`
  - `pr_description_generated`
  - `import_review`
  - `triage_findings`
  - `resolve_learnings`
  - workflow open/update/get/list/latest/resume/continue tools
  - `new_skill_scaffold`

Acceptance criteria:

- `McpStdioServerTest` fails if a validating/persisting tool falls back to
  `additionalProperties: true`.
- Existing MCP payload tests still pass.
- Tool schema docs and `docs/getting-started.md` stay accurate.

## Phase 5: Strengthen Kotlin Contract Tests

Goal: move from "Kotlin seems compatible" to executable contract confidence.

Tasks:

- Add golden JSON fixtures for representative CLI outputs:
  - `version --format json`
  - `doctor --format json`
  - `import-review --format json`
  - `triage --format json`
  - `learnings resolve --format json`
  - `workflow show --format json`
  - `verify-workflow show --format json`
  - `new-skill --dry-run --format json`
- Add MCP contract fixtures for:
  - `doctor`
  - `import_review`
  - `triage_findings`
  - `resolve_learnings`
  - workflow continuation tools
  - `new_skill_scaffold`
- Add architecture tests that prevent CLI/MCP adapters from reintroducing
  direct DB, filesystem, HTTP, or Python bridge dependencies.
- Add runtime surface tests that assert each active surface has:
  - owner package;
  - supported operations;
  - contract version;
  - status.

Acceptance criteria:

- Kotlin tests are sufficient to catch contract drift without running the
  Python oracle for every normal-use path.
- Python parity tests remain during transition, but are no longer the only
  source of confidence.

## Phase 6: Retire Python Runtime Fallback Deliberately

Goal: remove the runtime split only after packaged Kotlin and contract tests
are in place.

Tasks:

- Remove Python fallback only after Phases 1-5 pass.
- Delete or deprecate:
  - `SKILL_BILL_RUNTIME=python`;
  - `SKILL_BILL_MCP_RUNTIME=python`;
  - Python CLI/MCP runtime entry paths.
- Keep Python scripts only where they are intentionally repo tooling, unless
  the ownership table says otherwise.
- Update:
  - `pyproject.toml`;
  - `skill_bill/launcher.py`;
  - installer docs;
  - migration docs;
  - getting-started docs.
- Add release notes with rollback guidance. If rollback is still required,
  rollback should be "install previous release", not "select Python runtime".

Acceptance criteria:

- Normal CLI and MCP use has one source of truth: packaged Kotlin.
- No user-facing docs present Python as a normal runtime fallback.
- Python remains only in explicitly retained tooling, not in active runtime
  ownership.

## Phase 7: Adoption-Facing Hardening

Goal: make the technical improvements visible and useful to teams.

Tasks:

- Update `docs/getting-started.md` and `docs/getting-started-for-teams.md` with:
  - packaged runtime behavior;
  - supported fallback boundary;
  - validation commands;
  - what guarantees are strict vs model-mediated.
- Add a short "governance system vs reference packs" section if the distinction
  is still easy to miss.
- Add an external-author dry run:
  - scaffold a temporary platform pack in a fixture or temp repo;
  - validate it;
  - install/link it into a temp agent path;
  - remove it cleanly.

Acceptance criteria:

- A non-maintainer can understand the runtime model without reading migration
  history.
- The docs keep platform packs framed as examples of the governance system.

## Recommended Execution Order

1. Fix `runtime-kotlin` check first. Do not layer more migration work onto a red
   runtime gate.
2. Inventory Python ownership before deleting anything.
3. Package Kotlin runtime before removing Python fallback.
4. Complete MCP schemas before relying on MCP as a stable team contract.
5. Add Kotlin-first contract tests before declaring Python retired.
6. Only then remove fallback runtime code and update public docs.

## Validation Gate For SKILL-32 Completion

Run all of the following from a clean checkout:

```bash
(cd runtime-kotlin && ./gradlew check)
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
skill-bill doctor --format json
skill-bill-mcp
```

For `skill-bill-mcp`, verify at least:

- `initialize`
- `tools/list`
- `tools/call` for `doctor`
- one lifecycle telemetry tool with required arguments

## Open Decisions

- Should Python repo validators remain long-term tooling, or should SKILL-32
  define a full Kotlin-only repository?
- Should packaged Kotlin distributions be generated during `./install.sh`, or
  should release artifacts be built once and installed from a stable location?
- Should strict MCP schemas reject unknown fields at the stdio adapter boundary,
  or should handlers ignore unknown fields after schema publication?
- Should runtime packaging target plain JVM application distributions first, or
  jump directly to a single-file/native-image style distribution later?
