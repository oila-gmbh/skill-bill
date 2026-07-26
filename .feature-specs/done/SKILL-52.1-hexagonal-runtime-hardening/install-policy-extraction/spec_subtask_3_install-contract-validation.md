# SKILL-52.1 Install Policy Extraction - Subtask 3: Install Contract Validation

Parent overview: [../spec.md](../spec.md)  
Immediate parent spec: [../spec_subtask_3_install-policy-extraction.md](../spec_subtask_3_install-policy-extraction.md)  
Issue key: SKILL-52.1  
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before returning to parent subtask 4.

## Scope

Lock down install behavior, serialization, and architecture guardrails after policy and port extraction.

This subtask owns validation hardening:

- Preserve the dual-validation seam: the builder validates install plans, and the CLI seam revalidates after re-assembly. This duplicate-looking validation is intentional per the 2026-05-19 boundary decision.
- Verify typed `Invalid*SchemaError` loud-fail behavior remains intact at both seams.
- Confirm `install.sh` still delegates exactly as before and `InstallerShellDelegationTest` passes unchanged.
- Preserve CLI JSON `install plan` and `install apply` payloads byte-for-byte against existing goldens.
- Preserve MCP install envelope schema behavior and any existing smoke coverage.
- Extend architecture coverage so install planner/validator policy cannot be reintroduced into adapter modules; use the FQN and fixture-regex guard patterns established earlier in SKILL-52.1.
- Update `runtime-kotlin/ARCHITECTURE.md` only where needed to document the install boundary rule and any narrow allowed exceptions.
- Run the full validation gate.

## Acceptance Criteria

1. Both builder and CLI seams validate install plans, and typed invalid-schema loud-fail behavior is covered by tests.
2. `install.sh` delegation behavior and `InstallerShellDelegationTest` pass unchanged.
3. CLI JSON `install plan` / `install apply` payloads are byte-equivalent to pre-change goldens.
4. MCP install envelope schemas and smoke coverage remain byte-equivalent or behavior-equivalent to pre-change expectations.
5. Architecture coverage rejects install planner/validator policy in adapter modules.
6. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Do not redesign install CLI commands, MCP tool names, or persisted formats.
- Do not change `install.sh` semantics.
- Do not collapse the dual-validation seam.
- Do not move filesystem/process mechanics out of `runtime-infra-fs`.
- Do not implement path/core shrink work.

## Dependencies

Depends on child subtasks 1 and 2. Contract preservation should run after the policy surface and runtime wiring are complete.

## Validation Strategy

Primary: `bill-quality-check`.

Required local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy-extraction/spec_subtask_3_install-contract-validation.md`.

