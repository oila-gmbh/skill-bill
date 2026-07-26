# SKILL-52.1 Install Policy Extraction - Subtask 2: Install Capability Ports And Adapters

Parent overview: [../spec.md](../spec.md)  
Immediate parent spec: [../spec_subtask_3_install-policy-extraction.md](../spec_subtask_3_install-policy-extraction.md)  
Issue key: SKILL-52.1  
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before child subtask 3.
Status: Complete
Sources: parent decomposition context; subtask briefing for child 2 of 3; accepted feature criteria.

## Scope

Replace the single install passthrough gateway shape with capability-named ports and filesystem adapters, then wire `InstallService` to orchestrate the extracted policy plus those ports.

This subtask owns runtime wiring:

- Replace or split `InstallPlanGateway` into capability-named install ports under `runtime-ports/src/main/kotlin/skillbill/ports/install/`, with typed request/result models in capability-owned `model` packages.
- Keep no public raw `Map<String, Any?>` returns on the install port surface.
- Implement corresponding `FileSystem*` adapters in `runtime-infra-fs` for external mechanics such as skill/source discovery snapshots, symlink/link application, agent config mutation, runtime binary discovery, Windows preflight checks, and rollback coordination.
- Update `InstallService` so `planInstall`, `applyInstall`, and `linkSkill` orchestrate domain/application policy plus capability ports rather than passing through to a single gateway.
- Update `runtime-core` DI wiring to bind the new ports and adapters without broadening module dependencies.
- Preserve install shell delegation and existing CLI/MCP call paths at the behavioral boundary.

## Acceptance Criteria

1. Install ports surface capability-named ports with typed request/result models.
2. No public install port API returns raw `Map<String, Any?>`.
3. `InstallService` orchestrates install policy plus capability ports rather than passing through to a single `InstallPlanGateway`.
4. Symlink operations, agent config mutation, runtime binary discovery, Windows preflight checks, and rollback mechanics remain in `runtime-infra-fs`.
5. Runtime DI wiring compiles with direct capability adapters and does not reintroduce adapter-owned install policy.
6. Existing install service behavior is preserved for `planInstall`, `applyInstall`, and `linkSkill`.

## Non-Goals

- Do not change CLI JSON payload shapes, MCP envelope schemas, command names, or persisted formats.
- Do not redesign rollback semantics; keep rollback mechanics adapter-owned.
- Do not move pure policy back into `runtime-infra-fs` to simplify wiring.
- Do not introduce SKILL-53 install-selection persistence.
- Do not address path/core shrink work.

## Dependencies

Depends on child subtask 1. The capability ports should wrap the extracted policy surface instead of preserving the old passthrough gateway design.

## Validation Strategy

Primary: `bill-quality-check`.

Recommended local focus before the full check:

```bash
(cd runtime-kotlin && ./gradlew :runtime-application:test :runtime-infra-fs:test :runtime-core:test)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy-extraction/spec_subtask_2_install-capability-ports-and-adapters.md`.
