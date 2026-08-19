# SKILL-200 Subtask 1 — Skill removal and governed-surface repoint

## Intended Outcome

The two orphaned prose skills are deleted from source, and every governed surface that
depended on their existence is repointed or retired. Install output for `bill-feature/` loses
exactly the two removed sidecars and keeps everything else it stages today.

## Scope

- Delete `skills/bill-feature-task/` (57 lines) and `skills/bill-feature-task-runtime/` (422 lines).
- Edit `skills/bill-feature/content.md`: remove the `resumable` dispatch branch and the
  composition sentence that names `bill-feature-task` as an owned executable unit. Every
  continuation-lookup result must resolve to goal dispatch, a report-and-stop, or a loud
  failure. `resumable` is currently the only result that dispatches to a task sidecar.
- `orchestration/skill-classes/feature-task.yaml`: the matcher `^bill-[a-z0-9-]*feature-task$`
  matches only `bill-feature-task` today and will match zero skills after deletion, leaving its
  `shell-ceremony`, `telemetry-contract`, and `peak-hours-warner` pointers with no producer.
  Retire the class or repoint it at a skill that still exists. `bill-feature-goal` does not
  match the current pattern.
- `orchestration/skill-classes/feature-launch-warning.yaml`: drop the
  `exact: bill-feature-task-runtime` matcher. Keep `exact: bill-feature`.
- `platform-packs/kmp/platform.yaml`: retarget the `feature_addon_usage.feature-task` block
  (seven Android implementation add-ons, lines 206 onward) to the goal surface. The
  `pointers.feature-task` directory key at line 227 already resolves to the installed
  `bill-feature/` directory, so no add-on file moves; this is a consumer declaration change.
- Update install-staging, rendering, and validator tests that assert the deleted sidecars are
  present in the staged feature family.

## Acceptance Criteria

1. `skills/bill-feature-task/` and `skills/bill-feature-task-runtime/` are absent from the repository.
2. `skills/bill-feature/content.md` contains no dispatch to a task sidecar and no reference to `bill-feature-task` or `bill-feature-task-runtime`; the `resumable` continuation-lookup result is handled without one.
3. Every continuation-lookup result named in `skills/bill-feature/content.md` resolves to goal dispatch, a report-and-stop, or a loud failure, with no result left unhandled.
4. `orchestration/skill-classes/feature-task.yaml` is either removed or has a matcher that matches at least one existing skill, and its `shell-ceremony`, `telemetry-contract`, and `peak-hours-warner` pointers are not left without a producing skill.
5. `orchestration/skill-classes/feature-launch-warning.yaml` no longer matches `bill-feature-task-runtime`.
6. `platform-packs/kmp/platform.yaml` declares all seven `feature-task` add-ons against the goal surface, and pack-manifest schema validation accepts the result.
7. A clean `./install.sh` stages `bill-feature/` containing `bill-feature-goal.md`, all seven `kmp` Android add-on pointers, `shell-ceremony.md`, `telemetry-contract.md`, and `peak-hours-warner.md`, and containing neither `bill-feature-task.md` nor `bill-feature-task-runtime.md`.
8. `skill-bill validate` passes.
9. `npx --yes agnix --strict .` and `scripts/validate_agent_configs` pass.
10. No test asserts the presence of either deleted sidecar, and the full test suite for install staging and feature-family rendering passes.

## Non-Goals

Touching `WorkflowEngine.kt`, `install.sh`, `ARCHITECTURE.md`, or the `docs/` prose. Those are
subtask 2.

Trimming `bill-feature-goal.md`. Out of scope for SKILL-200 entirely.

Changing the durable `bill-feature-task` workflow identity, the schema `const`, the DB CHECK
constraint, the telemetry enum, or the `skill-bill feature-task` CLI.

## Dependency Notes

None. This is the first subtask and the substantive change. Subtask 2 depends on this landing,
because its documentation edits assert facts that only become true here.

Do not start while the SKILL-190 goal is live: writing to `skills/` would land inside that
subtask's review delta.

## Validation Strategy

Run `skill-bill validate` first; it is the fastest signal on skill-class and pack-manifest
coherence. Then `./install.sh` and inspect the staged `bill-feature/` directory against
criterion 7 by listing it. Then the Kotlin suites covering install staging and feature-family
rendering. Finish with `npx --yes agnix --strict .` and `scripts/validate_agent_configs`.

The governed-content and rendering tests are expected to fail before they are updated, because
they currently assert the deleted sidecars are staged. That is the signal, not a surprise.

## Next Path

Proceed to subtask 2.
