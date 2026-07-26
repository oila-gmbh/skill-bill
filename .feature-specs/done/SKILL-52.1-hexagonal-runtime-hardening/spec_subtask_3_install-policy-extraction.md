# SKILL-52.1 Subtask 3 — Install Policy Extraction

Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 3 of 5
Depends on: subtask 1 (typed boundary foundation); does **not** depend on subtask 2 in
principle, but runs after subtask 2 in same-branch sequential flow.
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before subtask 4.

## Why this comes after subtasks 1-2

Install policy uses the typed-model patterns established in subtask 1 and the
capability-port shape established in subtask 2. Install also has a deliberate
dual-validation seam (boundary decisions 2026-05-19): builder validates, CLI seam
revalidates after re-assembly. This subtask must preserve that seam.

## Scope

Covers parent acceptance criteria: **AC3 (install plan policy moves inward, external
effects stay in `runtime-infra-fs`)**, **AC5 (install adapter outputs preserved)**,
**AC10 (install/install.sh behavior preserved)**, **AC11 (arch tests fail loudly for
install policy reintroduced into filesystem adapters)**.

In scope:
- Move pure install planning and application rules into `runtime-domain` or
  `runtime-application`:
  - install request validation,
  - install plan construction policy,
  - apply-plan ordering / preconditions that do not require filesystem state.
- Keep in `runtime-infra-fs` (filesystem/process mechanics):
  - symlink operations,
  - agent config mutation,
  - runtime binary discovery,
  - Windows preflight checks,
  - rollback mechanics.
- Reshape `InstallGateways` into capability-named ports if helpful (e.g. symlink
  application, agent-config writer, runtime-binary probe, Windows-preflight probe),
  with typed request/result models under `ports/install/<capability>/model/`.
- Update `runtime-application/src/main/kotlin/skillbill/application/InstallService.kt`
  (22 LOC, currently pure passthrough) to orchestrate policy use-cases (`planInstall`,
  `applyInstall`, `linkSkill`) using domain policy + capability ports.
- **Preserve** the install-plan dual-validation seam: the CLI seam still revalidates
  after re-assembly. Typed result models must NOT collapse this — see boundary
  decisions 2026-05-19. Keep `Invalid<Contract>SchemaError extends
  ShellContentContractException` shape.
- Preserve `install.sh` delegation behavior and `InstallerShellDelegationTest`
  expectations (boundary history pitfall).
- Preserve install CLI golden payloads (install plan / apply payloads) byte-for-byte.
- Extend arch coverage so adapter modules cannot reintroduce install planner/validator
  policy.

Out of scope:
- Scaffold policy extraction (subtask 2 already done).
- `Path` policy decision (subtask 4).
- `runtime-core` API shrink (subtask 4).
- SKILL-53 shared install-selection persistence (explicit non-goal).
- Installer redesign or persisted format change.

## Acceptance criteria

1. Install request validation and plan construction policy independent of
   filesystem/process state live in `runtime-domain` or `runtime-application` with
   focused unit tests.
2. Symlink operations, agent config mutation, runtime binary discovery, Windows
   preflight checks, and rollback mechanics remain in `runtime-infra-fs`.
3. Install ports surface capability-named ports with typed request/result models;
   no public raw `Map<String, Any?>` returns from the install port surface.
4. `InstallService` orchestrates policy + capability ports rather than passing through
   to a single `InstallPlanGateway`.
5. The dual-validation seam is preserved: both builder and CLI seams validate
   install plans (boundary decisions 2026-05-19); typed `Invalid*SchemaError`
   loud-fail behavior is intact.
6. `install.sh` delegation behavior and `InstallerShellDelegationTest` pass unchanged.
7. CLI JSON `install plan` / `install apply` payloads and MCP install envelope
   schemas are byte-equivalent to pre-change goldens.
8. New or extended architecture coverage rejects install planner/validator policy in
   adapter modules.
9. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Do not collapse the install-plan dual-validation seam.
- Do not redesign install CLI commands, MCP tool names, or persisted formats.
- Do not change `install.sh` semantics.
- Do not introduce SKILL-53 install-selection persistence.
- Do not address scaffold policy here (done in subtask 2) or path/core shrink (subtask 4).

## Dependencies

- Subtask 1: typed-boundary arch test + typed-model package convention.
- Subtask 2: capability-port shape pattern (helpful but not strictly required); same-branch
  sequential flow places this after 2.

## Reference files

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/InstallService.kt` (22 LOC, pure passthrough)
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/InstallGateways.kt`
- Existing install model packages under `ports/install/model/` (typed DTO template)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/InstallerShellDelegationTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
- `install.sh`
- Install golden test fixtures (CLI install plan / apply, MCP install envelope schema)

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
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_3_install-policy-extraction.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then proceed
to subtask 4 (Path Policy + Runtime-Core Shrink).
