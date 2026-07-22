# SKILL-137 Subtask 8 - Compatibility, Telemetry, and End-to-End Hardening

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Complete the cross-cutting migration after all projection paths exist. Pin final contract versions, enforce loud compatibility behavior, add privacy-safe projection telemetry, reconcile architecture/governed guidance and fixtures, and run full standalone, goal-child, prose, runtime, verification, and delegated-review acceptance/rejection coverage.

## Acceptance Criteria

1. Every new or changed runtime YAML contract has its final pinned Kotlin version constant, parity test, typed schema error, all parse-seam validation, and classpath bundling.
2. Feature-task runtime, prose workflow, feature-verification workflow, phase-output, launch-briefing, and workflow-state versions are bumped where their durable meaning changed.
3. Legacy durable records containing broad upstream briefing payloads or stale prose graph semantics fail loudly with workflow id, incompatible version, and actionable restart/out-of-band migration guidance.
4. No fallback path converts an invalid projected handoff into a full producer artifact, reconstructs it from chat, or silently treats missing context as an empty projection.
5. Telemetry records per-phase delivered projection bytes, private source bytes, estimated delivered tokens, projection contract id/version, budget rejection counts, and stale-checkpoint rejection counts.
6. Telemetry never records projection contents, prompts, diffs, source bodies, receipt bodies, raw outputs, paths where current privacy policy requires path-free output, or tool/log bodies.
7. Status, watch, goal event, PR, and normal CLI/MCP output remain compact and path-free where required. Private diagnostic evidence remains behind its explicit existing retrieval/inspection surfaces.
8. Tests enumerate every runtime and prose forward edge, audit/review backward edge, retry, resume, crash recovery, latest iteration, validation repair, and finalization edge, asserting exact required and forbidden context.
9. Tests enumerate every feature-verification edge and assert evaluator isolation plus same-checkpoint consolidation.
10. Tests enumerate every delegated-review provider path and assert lane-scoped hunk delivery, cross-lane hunk absence, and no complete-diff or unexpanded whole-file admission.
11. Prompt byte-budget tests cover ASCII, multibyte UTF-8, maximum item counts, oversized required fields, compact references, and add-on content. Required JSON is never truncated.
12. Persistence round-trip and upgrade tests prove private evidence/delivered projections remain separate, and startup behavior against incompatible records is deterministic.
13. Standalone feature-task, decomposed goal child, multi-subtask isolation, runtime mode, prose mode, feature verification, delegated review, retry, continuation, and terminal success/block/failure paths pass.
14. `runtime-kotlin/ARCHITECTURE.md` and relevant docs describe the least-context principle, projection matrix, receipt-as-claim rule, repository authority, checkpoint freshness, private evidence, delegated-review lane projections, and telemetry separation.
15. Governed `content.md` for feature routing/task/runtime/prose/goal/subtask-runner, code review, PR description, code check, and feature verification uses the new contract names and does not reintroduce whole-artifact or whole-diff instructions.
16. Generated wrappers, support pointers, provider-specific agent outputs, installed staging, prompts, diff bodies, and raw tool output remain uncommitted.
17. Repository validation completes with:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

18. Because governed skill source changes require install refresh, final handoff tells the operator to run `./install.sh` after the goal; no installer or uninstall flow runs inside goal continuation.

## Non-Goals

- In-place automatic migration of arbitrary historical terminal workflow rows.
- Expanding telemetry content or changing its privacy levels.
- Changing product policies unrelated to phase context selection.
- Adding further phase types or changing feature decomposition scheduling.

## Dependency Notes

Depends on: 1, 2, 3, 4, 5, 6, 7.

This is the integration and hardening subtask; it runs only after all runtime, prose, finalization, verification, and delegated-review projections exist.

## Validation Strategy

- Contract/version/upgrade rejection suites.
- Full projection-matrix forbidden-field suite.
- Telemetry schema and privacy tests.
- Persistence upgrade and round-trip tests.
- Standalone, goal-child, prose, runtime, verify, delegated-review, retry, resume, and end-to-end suites.
- Full repository validation commands from acceptance criterion 17.

## Next Path

Open the parent pull request after this subtask commits, then run `./install.sh` outside goal continuation.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_8_compatibility-telemetry-and-end-to-end-hardening.md
