# SKILL-52.1 Subtask 5 — Final Validation + Contract Lock

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 5 of 5
Depends on: subtasks 1-4.
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); final commit before PR.

## Why this is last

Behavior preservation across install, scaffold, native-agent, workflow, review,
telemetry, CLI, MCP, Desktop packaging, and repo validation flows can only be
established once all architectural moves (subtasks 1-4) are in place. This subtask is
the contract-lock checkpoint: run the full required validation suite, audit committed
artifacts, and update boundary history / decisions docs.

## Scope

Covers parent acceptance criteria: **AC10 (existing behavior preserved across all
flows)**, **AC12 (required validation passes)**. Acts as the final cross-check that
ACs 1-11 from prior subtasks remain green together.

In scope:
- Run the required validation suite end-to-end and resolve any drift:
  - `(cd runtime-kotlin && ./gradlew check)`
  - `skill-bill validate`
  - `scripts/validate_agent_configs`
  - `npx --yes agnix --strict .`
- Re-run targeted golden test groups to confirm no late drift:
  - CLI workflow / install / scaffold JSON payloads,
  - MCP envelope schemas,
  - workflow state schema,
  - `InstallerShellDelegationTest`,
  - architecture test family under
    `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`.
- Audit committed artifacts: ensure no generated support pointers, `SKILL.md`
  wrappers, native-agent outputs, or install staging artifacts are committed
  (per `docs/skill-source-generation.md`).
- Update boundary history (`bill-boundary-history`) for the touched modules:
  - `runtime-kotlin/runtime-core`,
  - `runtime-kotlin/runtime-application`,
  - `runtime-kotlin/runtime-domain`,
  - `runtime-kotlin/runtime-ports`,
  - `runtime-kotlin/runtime-infra-fs`,
  - `runtime-kotlin/runtime-cli`, `runtime-mcp`, `runtime-desktop`,
  - any module that received a typed-model package.
  Record the reusable patterns: typed-model arch test, capability port carving for
  scaffold and install, Path policy decision, `runtime-core` shrink to
  implementation-only deps.
- Record key architectural decisions in the relevant `agent/decisions.md`
  (`bill-boundary-decisions`):
  - chosen `Path` policy and rationale,
  - retained dual-validation seam for install plan,
  - any narrow `runtime-core` public edge retained for Kotlin-Inject.
- Confirm `runtime-kotlin/ARCHITECTURE.md` is the source of truth for every new rule
  (raw-map exceptions, Path policy, `runtime-core` shrink rules).

Out of scope:
- New code changes beyond fixing late drift surfaced by the validation suite.
- Performance work.
- SKILL-53.

## Acceptance criteria

1. `(cd runtime-kotlin && ./gradlew check)` passes from a clean state.
2. `skill-bill validate` passes.
3. `scripts/validate_agent_configs` passes.
4. `npx --yes agnix --strict .` passes.
5. No generated support pointers, `SKILL.md` wrappers, native-agent outputs, or
   install staging artifacts are committed.
6. Boundary history is updated for the touched modules with reusable, high-signal
   entries (no churn-only entries).
7. Architecture decisions are recorded in `agent/decisions.md` for: Path policy,
   install-plan dual-validation seam preservation, any retained narrow
   `runtime-core` public edge.
8. `runtime-kotlin/ARCHITECTURE.md` documents all new rules introduced by SKILL-52.1
   (raw-map exceptions, Path policy, `runtime-core` shrink rule).
9. Parent ACs 1-12 are collectively satisfied across the 5 subtasks (verified by
   green arch tests + golden tests + validation suite).

## Non-goals

- Do not introduce new behavior.
- Do not weaken any loud-fail behavior to make the suite green.
- Do not add suppressions; fix root causes (per `bill-kotlin-quality-check`).

## Dependencies

- Subtasks 1-4 must be committed on the parent branch.

## Reference files

- `runtime-kotlin/ARCHITECTURE.md`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/` (full family)
- `docs/skill-source-generation.md` (committed-artifact audit)
- `scripts/validate_agent_configs`
- All `agent/history.md` files for touched modules (boundary history updates)
- All `agent/decisions.md` files for touched modules (decision records)

## Validation strategy

Run the full required suite:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

If any step fails, fix root cause (no suppressions), recommit on the parent branch,
and re-run.

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_5_final-validation-and-contract-lock.md
```

After completion, open a PR for `feat/SKILL-52.1-hexagonal-runtime-hardening` using
`bill-pr-description`.
