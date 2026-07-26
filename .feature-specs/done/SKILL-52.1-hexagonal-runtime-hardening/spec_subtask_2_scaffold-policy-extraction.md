# SKILL-52.1 Subtask 2 — Scaffold Policy Extraction (SUPERSEDED)

> **SUPERSEDED.** This subtask was too large for one reliable feature-implement run.
> It has been split into two smaller subtasks. Do **not** plan against this spec —
> use the new specs instead:
>
> - [`spec_subtask_2_scaffold-ports-and-pure-policy.md`](./spec_subtask_2_scaffold-ports-and-pure-policy.md)
>   — capability ports, typed request/result models, and genuinely pure-policy
>   extraction into `runtime-domain`.
> - [`spec_subtask_3_scaffold-raw-map-elimination.md`](./spec_subtask_3_scaffold-raw-map-elimination.md)
>   — `ScaffoldGateway` raw-map elimination with byte-equivalent CLI/MCP mappers,
>   IO-coupled validator carve into capability-aligned adapter classes, and
>   allow-list cleanup.
>
> The decomposition manifest at `decomposition-manifest.yaml` reflects the new
> 6-subtask ordering. The original 8 acceptance criteria below are now covered
> collectively by the two new specs (see each new spec's "Acceptance criteria"
> section for the explicit AC mapping). Content below is retained for historical
> reference only.

---

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 2 of 5 (historical — see superseded notice above)
Depends on: subtask 1 (typed boundary foundation + raw-map arch test must be in place).
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before subtask 3.

## Why this comes after subtask 1

Carved scaffold ports must use typed result models, not raw maps. The raw-map arch test
from subtask 1 enforces that automatically. Doing scaffold first (before install) keeps
the diff focused — scaffold has the bigger policy surface (1358 LOC in
`runtime-infra-fs/scaffold/ScaffoldService.kt`).

## Scope

Covers parent acceptance criteria: **AC1 (pure scaffold planning/validation moves out
of `runtime-infra-fs`)**, **AC2 (filesystem adapters still own IO/symlinks/manifest
persistence/rollback)**, **AC5 (scaffold adapter outputs still match CLI/MCP
contracts)**, **AC11 (arch tests fail loudly for scaffold/install policy reintroduced
into filesystem adapters)**.

In scope:
- Split `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt`
  (1358 LOC) by behavior:
  - Pure policy that moves inward to `runtime-domain` or `runtime-application`:
    - scaffold kind detection,
    - payload validation,
    - platform-pack planning,
    - file planning,
    - manifest edit planning,
    - install target planning.
  - Stays in `runtime-infra-fs` (filesystem mechanics): file reads/writes, symlink
    operations, manifest persistence, rollback file operations, generated artifact
    staging, install target mutation, process/env access.
- Reshape `runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
  away from broad gateway methods into smaller, capability-named ports
  (per spec implementation notes and `TelemetryLevelMutator` shape — see boundary
  history). Suggested capabilities:
  - source loading,
  - manifest persistence,
  - generated-file staging,
  - install link application,
  - repo validation.
  Each capability port gets typed request/result models under
  `ports/scaffold/<capability>/model/` consistent with the existing typed DTO
  convention.
- Replace `ScaffoldGateway` raw-map returns with typed result models for
  list/show/explain/validate/upgrade/fill/edit. Add mappers from typed model to CLI
  JSON / MCP response map at the adapter boundary so contracts are byte-equivalent.
- Update `runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
  (46 LOC, 10 methods) to orchestrate the policy use-cases rather than pass-through to
  `ScaffoldGateway`.
- Preserve atomic rollback semantics (boundary-history pitfall).
- Preserve `content.md` / generated `SKILL.md` boundary and install-staging exclusions
  per `docs/skill-source-generation.md` (no generated files committed).
- Extend the existing scaffold/install policy arch coverage so adapter modules
  (`runtime-infra-fs`) cannot reintroduce planner/validator policy. Use the
  `DecompositionManifestArchitectureTest` projection-pattern guard shape as a model
  (boundary history).

Out of scope:
- Install policy extraction (subtask 3).
- `Path` policy decision and migration (subtask 4).
- `runtime-core` API shrink (subtask 4).
- Generated-output staging redesign or schema codegen.

## Acceptance criteria

1. Scaffold kind detection, payload validation, platform-pack planning, file planning,
   manifest edit planning, and install target planning are owned by `runtime-domain`
   or `runtime-application` with focused unit tests.
2. `runtime-infra-fs` scaffold adapters still own file IO, symlink operations,
   manifest persistence, rollback file operations, generated artifact staging, and
   install target mutation.
3. The scaffold port surface is split into capability-named ports (source loading,
   manifest persistence, generated-file staging, install link application, repo
   validation) with typed request/result models under `ports/scaffold/<capability>/model/`.
4. `ScaffoldGateway` no longer exposes raw `Map<String, Any?>` results to
   `runtime-application`; the new arch test from subtask 1 passes without an
   allow-list addition for scaffold.
5. CLI JSON outputs and MCP envelope outputs for
   `scaffold list/show/explain/validate/upgrade/fill/edit` are byte-equivalent to
   pre-change goldens.
6. Atomic rollback behavior for scaffold operations is preserved (covered by
   existing scaffold rollback tests).
7. New or extended architecture coverage rejects scaffold planner/validator policy
   that imports `runtime-infra-fs` packages.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Do not move adapter-only code inward (rollback file IO, symlink mechanics, process
  env) just to shrink `runtime-infra-fs`.
- Do not redesign scaffold CLI prompts, command names, or persisted manifest formats.
- Do not change the source/generated boundary or `content.md` -> `SKILL.md` flow.
- Do not introduce Kotlin codegen from YAML schemas.
- Do not address install policy here (subtask 3).

## Dependencies

- Subtask 1: typed-boundary arch test + workflow typed models must already be merged
  on the parent branch.

## Reference files

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt` (46 LOC, 10 methods, mostly passthroughs)
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt` (1358 LOC — main extraction target)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/DecompositionManifestArchitectureTest.kt` (projection-pattern guard model)
- `docs/skill-source-generation.md` (generated boundary + install staging exclusions)
- Existing typed DTO packages under `ports/<area>/model/` as shape templates.

## Validation strategy

Primary: `bill-quality-check`.
Full local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
```

`skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`
run in subtask 5.

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_2_scaffold-policy-extraction.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then proceed
to subtask 3 (Install Policy Extraction).
