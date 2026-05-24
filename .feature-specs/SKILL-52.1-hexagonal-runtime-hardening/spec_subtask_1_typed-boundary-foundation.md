# SKILL-52.1 Subtask 1 — Typed Boundary Foundation

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 1 of 5
Depends on: none
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before subtask 2 starts.

## Why this is first

Subtasks 2 (Scaffold Policy Extraction) and 3 (Install Policy Extraction) both need a
typed-model + architecture-test foundation so they do not reintroduce `Map<String, Any?>`
into newly carved ports. Carving this first means the rest of the work is enforced by
arch tests instead of by review discipline. Workflow result types ship in this subtask
because workflow services are the largest raw-map surface and have golden coverage that
makes typed-model migration low-risk.

## Scope

Covers parent acceptance criteria: **AC4 (typed request/result models)**, **AC5
(adapter outputs still match CLI/MCP contracts)** for workflow, **AC11 (arch tests fail
loudly)** for raw-map portion.

In scope:
- Add an architecture-test guard that forbids new public `Map<String, Any?>` (and
  related raw map shapes) on `runtime-application`, `runtime-domain`, and
  `runtime-ports` APIs, with an explicit allow-list / annotation for documented
  open-boundary exceptions (schema custom fields, MCP input maps before parse,
  contract helper serialization internals, `PlatformManifest.customFields`).
  Extend the existing arch test family under
  `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`
  (`RuntimeArchitectureTest`, `ImplementationOwnershipArchitectureTest`); do **not**
  duplicate coverage — extend, per boundary history.
- Introduce typed application result models for workflow use cases:
  `WorkflowOpenResult`, `WorkflowUpdateResult`, `WorkflowGetResult`, `WorkflowListResult`,
  `WorkflowLatestResult`, `WorkflowResumeResult`, `WorkflowContinueResult` (names
  illustrative; pick the closest fit to existing `WorkflowEngine` payload shapes). Place
  them under a `model` package in `runtime-application` (or `runtime-ports` if they
  describe a port contract) per the existing typed DTO convention.
- Update `WorkflowService` (runtime-application/src/main/kotlin/skillbill/application/WorkflowService.kt, 315 LOC)
  and `WorkflowEngine` so public methods return typed results. Provide mappers from
  typed result -> existing CLI JSON payload and MCP response map so adapter contracts
  (CLI JSON, MCP envelope schema, workflow state schema) are byte-equivalent.
- Confirm via golden tests (CLI workflow payloads, MCP envelope schemas, workflow state
  schema) that adapter output is unchanged.
- Document the open-boundary exceptions in `runtime-kotlin/ARCHITECTURE.md` (source of
  truth first, then tests). Standards-notes rule: ARCHITECTURE.md update precedes arch
  test change.

Out of scope (deferred to later subtasks):
- Scaffold typed models (subtask 2).
- Install typed models (subtask 3).
- Telemetry/repo-validation typed models — only convert if trivially in the way of the
  arch test; otherwise leave for a future pass.
- `Path` policy decision (subtask 4).
- `runtime-core` API shrink (subtask 4).

## Acceptance criteria

1. New arch test fails loudly when a public `runtime-application`,
   `runtime-domain`, or `runtime-ports` declaration returns or accepts
   `Map<String, Any?>` (or `Map<String, *>`, `MutableMap<String, Any?>`) without being
   listed in an explicit open-boundary allow-list or marked with the documented
   annotation.
2. The open-boundary allow-list documents at minimum: schema custom fields,
   `PlatformManifest.customFields`, MCP input argument maps pre-parse, and contract
   helper serializer internals.
3. `WorkflowService` public methods return typed result models, not `Map<String, Any?>`.
4. `WorkflowEngine.fullPayload`/`summaryPayload`/`resumePayload` either return typed
   models directly or are private serialization helpers fed by typed models.
5. CLI JSON workflow payloads, MCP envelope schemas, and workflow state schema goldens
   are unchanged.
6. `runtime-kotlin/ARCHITECTURE.md` documents the raw-map rule and its exceptions.
7. `(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')` passes.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Do not redesign workflow CLI/MCP contracts.
- Do not collapse the install-plan dual-validation seam (builder + CLI re-assembly);
  workflow models are independent of that decision.
- Do not weaken loud-fail behavior for typed schema errors.
- Do not introduce generated Kotlin types from schemas.

## Dependencies

None. This subtask is the foundation.

## Reference files

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/WorkflowService.kt` (315 LOC)
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt` (only for raw-map audit; do not convert here)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt` (666 LOC, 24 tests)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt` (402 LOC, 10 tests)
- `runtime-kotlin/ARCHITECTURE.md`
- Existing typed DTO packages under `ports/<area>/model/` (telemetry-level, install,
  scaffold catalog/render, repo-validation, persistence) as the shape template.
- Golden test fixtures for CLI workflow output, MCP envelope schemas, workflow state.

## Validation strategy

Primary: `bill-quality-check` (routes to `bill-kotlin-quality-check`).
Full local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
```

`skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`
run in subtask 5 (full contract lock), but a quick local sanity run is welcome here.

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_1_typed-boundary-foundation.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then proceed
to subtask 2 (Scaffold Policy Extraction).
