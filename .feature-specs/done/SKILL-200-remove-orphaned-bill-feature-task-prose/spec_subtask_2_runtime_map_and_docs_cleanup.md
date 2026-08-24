# SKILL-200 Subtask 2 — Continuation map and documentation cleanup

## Intended Outcome

The dead continuation content-path entries are gone from the runtime, and every remaining
document, hint, and architecture claim describes the feature family as it actually is after
subtask 1.

## Scope

- `WorkflowEngine.kt` `CONTINUATION_CONTENT_PATHS`: remove the `bill-feature-task` and
  `feature-task-runtime` entries. Both are dead. The map is only consulted when building a
  continuation prompt through `workflow continue`, and the sole registered continue command is
  `VerifyWorkflowContinueCommand` with `WorkflowFamilyKind.VERIFY`
  (`WorkflowCliCommands.kt:251`). Every `continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, ...)`
  call site in the repository is a test. Goal-child phase prompts come from
  `FeatureTaskRuntimePhasePromptComposer` instead.
- Update or delete the tests that exercise the removed entries. Their current assertion is that
  a resuming agent is told to follow "the normal step instructions" in
  `skills/bill-feature-task/content.md`, a file that contains no step instructions even today.
- `../../../install.sh`: the closing next-step hint tells users to run `/bill-feature-task`. That has not
  been an installed listed command since SKILL-102 made it `internal-for: bill-feature`. Point
  it at a command that exists.
- `../../../runtime-kotlin/ARCHITECTURE.md`: the Feature-Task Workflow Family section claims
  "`bill-feature` routes single-spec work to the canonical `bill-feature-task` runtime entry."
  False since SKILL-133. Correct the section so it describes the workflow identity and the CLI
  without implying a prose entry point.
- `../../../docs/internal-skills-architecture.md`: the sidecar inventory, the pointer table, the
  worked dispatch example, and the source-path table all name the deleted skills.
- `../../../docs/skill-source-generation.md`: the internal-skill parent list and the sidecar naming
  examples name the deleted skills.
- `../../../docs/capabilities.md`: the feature-factory section header is written around
  `bill-feature-task` as a user-facing entry point.

## Acceptance Criteria

1. `CONTINUATION_CONTENT_PATHS` contains no `bill-feature-task` or `feature-task-runtime` entry.
2. No production code path resolves a continuation content path for the feature-task workflow definition, and no test asserts a continuation prompt pointing at a deleted skill file.
3. `../../../install.sh` no longer names `/bill-feature-task` in its next-step hint, and the command it names is an installed listed skill.
4. `../../../runtime-kotlin/ARCHITECTURE.md` no longer claims `bill-feature` routes single-spec work to a `bill-feature-task` runtime entry, and its Feature-Task Workflow Family section describes only the durable workflow identity and the CLI.
5. `../../../docs/internal-skills-architecture.md` contains no pointer-table row, sidecar mapping, source-path row, or dispatch example naming either deleted skill.
6. `../../../docs/skill-source-generation.md` contains no internal-skill listing or sidecar naming example naming either deleted skill.
7. `../../../docs/capabilities.md` describes the feature factory without presenting `bill-feature-task` as an entry point.
8. Outside `../..` and `../../../agent/history.md`, no live source, contract, doc, or script references `bill-feature-task-runtime`.
9. Outside `../..` and `../../../agent/history.md`, the only surviving references to `bill-feature-task` are the durable workflow identity (DB CHECK constraint, `workflow-state-schema.yaml` `const`, telemetry enum, `FeatureTaskRuntimePhaseWorkflowDefinition`) and the `skill-bill feature-task` CLI command name.
10. `(cd runtime-kotlin && ./gradlew check)` passes.
11. `skill-bill validate`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

Deleting the skill trees or editing skill classes and pack manifests. That is subtask 1.

Renaming the durable workflow identity or altering the `skill-bill feature-task` CLI.

Trimming `bill-feature-goal.md`.

## Dependency Notes

Depends on subtask 1. The documentation statements written here are only true once the skills
are actually gone, and criteria 8 and 9 cannot pass until subtask 1 has landed.

## Validation Strategy

Run `(cd runtime-kotlin && ./gradlew check)` for the map change and its tests. Prove criteria 8
and 9 with a repository sweep for both names, excluding `../..` and `../../../agent/history.md`,
and confirm each surviving hit is one of the four allowed identity sites or the CLI command name.
Finish with `skill-bill validate`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs`.

## Next Path

Proceed to subtask 5, which extends this cleanup to the merged single-skill family.
