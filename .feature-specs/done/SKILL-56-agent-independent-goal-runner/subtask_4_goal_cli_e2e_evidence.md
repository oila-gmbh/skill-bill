# SKILL-56 Subtask 4 Goal CLI E2E Evidence

Date: 2026-05-30

Command surface exercised:

- `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli goal <issue_key>`
- `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli goal status <issue_key>`

Harness:

- Temporary git repositories under `/tmp/tmp.9RP5z3X1VF`.
- A fake `codex` executable was placed first on `PATH`; the real goal runner launched it through the normal headless Codex adapter.
- A fake `gh` executable was placed first on `PATH`; it recorded PR calls and returned a deterministic PR URL.
- No real hosted Codex, Claude, OpenCode, Copilot, Junie, or GitHub service was exercised.

Successful decomposed goal:

- Issue: `SKILL-901`
- Subtasks: 2
- Exit status: `0`
- Foreground output showed:
  - `goal SKILL-901: subtask 1 start`
  - `goal SKILL-901: subtask 1 complete`
  - `goal SKILL-901: subtask 2 start`
  - `goal SKILL-901: subtask 2 complete`
  - `goal SKILL-901: complete (https://github.com/example/skill-bill/pull/901)`
- Child stdout/stderr was live-tee'd:
  - `child stdout subtask 1`
  - `child stderr subtask 1`
  - `child stdout subtask 2`
  - `child stderr subtask 2`
- Fresh process per subtask was observed in the fake Codex log:
  - subtask 1: `codex pid=95746`
  - subtask 2: `codex pid=95888`
- Manifest advancement was observed through the runner completing both subtasks and opening the final PR with both commit SHAs in the body:
  - `- 1. Part 1 (sha-1)`
  - `- 2. Part 2 (sha-2)`
- Single PR finalization was observed:
  - one `gh pr create --head feat/SKILL-901-goal --base main --draft ...` call after subtask 2 completed.

Forced-failure decomposed goal:

- Issue: `SKILL-902`
- Subtasks: 3
- Forced failure: subtask 2
- Exit status: `1`
- Foreground output showed:
  - `goal SKILL-902: subtask 1 complete`
  - `goal SKILL-902: subtask 2 stopped (failed): forced e2e failure`
  - final status `stopped`
  - `subtask_id: 2`
  - `reason: failed`
  - `last_resumable_step: review`
- Fresh process per attempted subtask was observed:
  - subtask 1: `codex pid=96077`
  - subtask 2: `codex pid=96187`
- Read-only status after the forced failure reported the subtask-3 projection:
  - `complete: 1`
  - `pending: 1`
  - `blocked: 1`
  - `current_subtask: 2`
  - `current_step: review`
  - `active_agent: codex`
