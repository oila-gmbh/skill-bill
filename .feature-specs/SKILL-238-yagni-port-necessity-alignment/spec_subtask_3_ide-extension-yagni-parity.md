# SKILL-238 · Subtask 3 — IDE extension YAGNI parity

## Scope

Remove duplicated and unused abstraction in the IntelliJ plugin and VS Code
extension status/CLI surfaces while preserving deliberate isolation from
`runtime-kotlin` / SQLite.

Work includes:

- Unify IntelliJ `CliGoalPauseRepository` / `CliGoalStopRepository` into one
  parameterized CLI mutator (verb + labels). Mirror the same collapse in
  `vscode-extension` pause/stop repositories.
- Delete dead `StatusClock.from(Clock)` (IntelliJ) and any equivalent unused
  factory on the VS Code `StatusClock` twin. Keep `StatusClock` /
  `system()` / `fixed()` — they are the active test seam for status ViewModels.
- Delete unused top-level / companion `defaultRefreshIntervalSeconds()` helpers
  on preference ports in both IDEs when still caller-less; callers should use
  `DEFAULT_REFRESH_INTERVAL_SECONDS` (or the port’s getter) directly.
- Drop foreshadowing-only “future tool-window” prose that invents an unused
  extension consumer; keep the live status-bar project service.
- Optional shrink only when touching adjacent files: reduce near-identical
  cached display snapshot constructors / `"key" in state` accessor forests
  without changing observable status UI behavior.
- Update `intellij-plugin/agent/history.md` and VS Code area history if present.

## Acceptance Criteria

1. Pause and stop CLI mutations share one parameterized repository (or clear shared helper) in IntelliJ and the same shape in VS Code; the two near-duplicate classes per IDE are gone.
2. Dead `StatusClock.from` (and any unused twin factory) is removed; `StatusClock.system` / `fixed` (or TS equivalents) remain as the injectable test seam.
3. Caller-less `defaultRefreshIntervalSeconds()` helpers are deleted in both IDEs.
4. Status-bar / preference refresh behavior remains equivalent; IDE isolation from runtime DB and `runtime-kotlin` internals is preserved.
5. IntelliJ plugin and VS Code extension builds succeed after the cuts.

## Non-Goals

- Building the deferred full tool window.
- Reworking status projection domain models beyond the listed shrinks.
- Deleting `StatusClock` wholesale (it has production and test callers).
- runtime-kotlin role-port work (subtask 2) or leftover file deletion (subtask 1).
- Adding new IDE features or preferences.

## Dependency Notes

- Independent of subtasks 1 and 2. Can ship on the same feature branch as its
  own commit under `same_branch_commit_per_subtask`.

## Validation Strategy

```bash
# IntelliJ
cd intellij-plugin && ./gradlew build
# VS Code
cd vscode-extension && npm test || npm run compile
```

Use the repo’s established plugin/extension build commands if the above
differ; prefer existing CI-equivalent tasks. Smoke the status-bar refresh path
only if an existing automated test covers it — do not add low-value UI tests.

## Next Path

`skill-bill goal SKILL-238`
