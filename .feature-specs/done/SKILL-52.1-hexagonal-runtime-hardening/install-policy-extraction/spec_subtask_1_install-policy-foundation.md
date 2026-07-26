# SKILL-52.1 Install Policy Extraction - Subtask 1: Install Policy Foundation

Parent overview: [../spec.md](../spec.md)  
Immediate parent spec: [../spec_subtask_3_install-policy-extraction.md](../spec_subtask_3_install-policy-extraction.md)  
Issue key: SKILL-52.1  
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before child subtask 2.

## Scope

Extract install request validation and pure plan construction policy from filesystem adapters into `runtime-domain` or `runtime-application`.

This subtask owns the foundation only:

- Introduce or move install policy types/functions that operate on typed inputs and deterministic snapshots, without reading the filesystem or invoking processes.
- Preserve the existing install plan builder validation seam: plan construction still validates emitted plans with `InstallPlanSchemaValidator.validate(...)`, and failures still loud-fail through the existing typed `Invalid*SchemaError` path.
- Add focused unit tests for request validation, deterministic plan construction, ordering/precondition policy that does not require filesystem state, and loud-fail behavior.
- Leave filesystem discovery, runtime binary probing, Windows checks, symlink/config mutation, rollback, CLI JSON mapping, MCP envelopes, and DI rewiring to later child subtasks.

## Acceptance Criteria

1. Install request validation and plan construction policy independent of filesystem/process state live in `runtime-domain` or `runtime-application`.
2. Focused unit tests cover the extracted validation and planning policy, including invalid input and schema loud-fail behavior.
3. The builder-side install plan validation seam remains intact and still raises the existing typed schema error.
4. New policy APIs use typed request/result/snapshot models and do not introduce public raw `Map<String, Any?>` returns.
5. Filesystem/process mechanics remain in `runtime-infra-fs`; any remaining adapter calls needed by the policy are represented as typed snapshots or narrow input models.

## Non-Goals

- Do not replace all install gateway wiring in this subtask.
- Do not change install CLI commands, MCP tool names, install JSON payloads, or persisted formats.
- Do not move symlink operations, agent config mutation, runtime binary discovery, Windows preflight checks, or rollback mechanics out of `runtime-infra-fs`.
- Do not collapse the CLI-side install plan revalidation seam.
- Do not change `install.sh` semantics.

## Dependencies

No child-subtask dependencies. This runs first because later port and adapter wiring needs a stable policy surface to orchestrate.

## Validation Strategy

Primary: `bill-quality-check`.

Recommended local focus before the full check:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy-extraction/spec_subtask_1_install-policy-foundation.md`.

