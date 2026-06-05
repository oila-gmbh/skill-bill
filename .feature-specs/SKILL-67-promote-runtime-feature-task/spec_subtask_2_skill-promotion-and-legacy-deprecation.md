---
status: Complete
---

# SKILL-67 Subtask 2 - Skill Promotion and Legacy Deprecation

Parent spec: [.feature-specs/SKILL-67-promote-runtime-feature-task/spec.md](./spec.md)
Issue key: SKILL-67

## Scope

Make the runtime-backed skill the canonical `bill-feature-task` and demote the
prose orchestrator to a deprecated `bill-feature-task-legacy`, reusing the
established `InstallLegacySkillNames.kt` alias mechanism (SKILL-13 precedent).

- Promote the runtime skill: the content currently at
  `skills/bill-feature-task-runtime/content.md` becomes the canonical
  `skills/bill-feature-task/content.md`, rewritten to drop all EXPERIMENTAL
  framing ("experimental — not a default path", "do not route normal work here")
  and to reference the canonical `skill-bill feature-task run/resume/status`
  command from subtask 1. The skill stays a thin trigger over the runtime; it
  must not restate phase orchestration.
- Demote the prose orchestrator: move the existing
  `skills/bill-feature-task/content.md` (and its `native-agents/` assets) to
  `skills/bill-feature-task-legacy/`, with frontmatter and a header banner
  marking it DEPRECATED, pointing to the canonical skill, and never
  auto-routed. Keep its behavior intact (it still drives `feature_implement_*`).
- Update `InstallLegacySkillNames.kt`: add `bill-feature-task-runtime` ->
  `bill-feature-task`; preserve the existing legacy aliases that already map to
  `bill-feature-task`. Ensure `bill-feature-task-legacy` is a real installed
  skill (not an alias) for the window.
- Update `install.sh` and `uninstall.sh` skill enumeration so the canonical
  skill, the legacy skill, and the aliases all install/uninstall correctly,
  including DB-preserving reinstall.

## Acceptance Criteria

1. `skills/bill-feature-task/content.md` is the runtime-backed trigger with no
   EXPERIMENTAL language and references `skill-bill feature-task`; its frontmatter
   `name` is `bill-feature-task`.
2. `skills/bill-feature-task-legacy/` contains the former prose orchestrator
   content and assets, frontmatter `name` `bill-feature-task-legacy`, a loud
   DEPRECATED banner, a pointer to the canonical skill, and a removal-window note.
3. `InstallLegacySkillNames.kt` maps `bill-feature-task-runtime` ->
   `bill-feature-task` and retains all prior aliases mapping to
   `bill-feature-task`; installing resolves an old `bill-feature-task-runtime`
   invocation to the canonical skill.
4. `install.sh` / `uninstall.sh` install and remove the canonical skill, the
   legacy skill, and the aliases without wiping the goal/workflow DB on reinstall.
5. `skill-bill validate` and `scripts/validate_agent_configs` accept the new
   skill layout.

## Non-Goals

- No CLI/MCP renames (subtask 1).
- No goal-runner changes (subtask 3).
- No `bill-feature` dispatcher edits (subtask 4).
- Do not delete the legacy skill or its `feature_implement_*` dependency.

## Dependency Notes

Depends on: Subtask 1 (the canonical `skill-bill feature-task` command the
promoted skill content references).

## Validation Strategy

`skill-bill validate`; `scripts/validate_agent_configs`; a local install/reinstall
dry-run confirming alias resolution and DB preservation; `npx --yes agnix
--strict .` for the skill content.

## Next Path

Subtask 3 wires `skill-bill goal` to invoke the canonical runtime directly per
subtask.

## Spec Path

.feature-specs/SKILL-67-promote-runtime-feature-task/spec_subtask_2_skill-promotion-and-legacy-deprecation.md
