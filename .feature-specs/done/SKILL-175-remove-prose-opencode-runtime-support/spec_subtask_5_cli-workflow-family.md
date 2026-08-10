# SKILL-175 Subtask 5 - CLI prose workflow family and implement-stats

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Remove the operator CLI surfaces that exist solely for the prose workflow
family, so CLI and MCP agree that prose cannot be opened or continued.

### Detailed surfaces

- `runtime-cli/.../WorkflowCliCommands.kt` — entire
  `skill-bill workflow {open,update,show,get,list,latest,resume,continue}` tree
  bound to `WorkflowFamilyKind.TASK_PROSE`
- CLI help root registration / Clikt command tree wiring for `workflow`
- `skill-bill implement-stats` (and any alias) aggregating
  `feature_implement_sessions` / `featureImplementStats`
- Presenters/mappers dedicated to prose workflow continue/show output
- Tests: `CliRuntimeTest` (and any `WorkflowCli*` tests), stats CLI tests that
  require implement-stats
- Docs: getting-started CLI tables listing `skill-bill workflow …` and
  `implement-stats` as supported commands
- Completion scripts / help snapshots if checked in

**Keep / point operators to:** `skill-bill feature-task status|…`,
`skill-bill goal …`, runtime stats commands that are not prose-implement
specific (`feature-task-stats` / goal stats as applicable).

## Acceptance Criteria

1. `skill-bill workflow` and its subcommands are removed from the Clikt tree and
   help.
2. `skill-bill implement-stats` is removed from the Clikt tree and help.
3. CLI tests no longer expect prose workflow commands; replacements assert
   absence or point at runtime/goal commands.
4. Getting-started (and any CLI reference docs) no longer document those
   commands as product surface.
5. Invoking removed commands (if a stub remains temporarily) must not open prose
   workflows — prefer hard removal over stub-to-prose.
6. Targeted CLI test suite passes.

## Non-Goals

- Deleting underlying `FeatureImplement*` services/tables (subtask 6) — CLI may
  be removed first while application types still exist briefly, or this subtask
  may land in the same PR as subtask 6 if cleaner; do not leave a CLI that calls
  deleted services.
- Changing `skill-bill feature-task` / `goal` runtime CLIs beyond help text that
  still mentions prose.

## Dependency Notes

- Depends on subtask 4 (MCP already gone) preferred; may merge with subtask 6 if
  command wiring is inseparable from `WorkflowFamily.IMPLEMENT` deletion.
- Blocks a clean subtask 6 (no CLI re-entry into prose APIs).

## Validation Strategy

- `skill-bill --help` / workflow help absence.
- CLI unit tests.
- Doc grep for `skill-bill workflow` and `implement-stats`.

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: remove Kotlin persistence/IDE prose branch (subtask 6).
