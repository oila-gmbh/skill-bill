# SKILL-56 Subtask 4 - CLI Surface + Status, and the bill-goal Skill Front

Parent spec: [.feature-specs/SKILL-56-agent-independent-goal-runner/spec.md](./spec.md)
Issue key: SKILL-56
Subtask order: 4 of 4
Depends on: subtask 3
Branch model: same-branch, commit per subtask

## Purpose

Expose the goal runner to humans: a **foreground CLI command** that runs the
loop with readable progress, a read-only **status** command, and the
**`bill-goal` skill** that is the user's actual entry point — it runs the
interactive decomposition, gates on a **single human confirmation**, then hands
`issue_key` off to the runtime driver. This is the seam where the structured
domain service (subtask 3) becomes a usable product surface, and where Skill
Bill's agent-independence shows up at the front door (the same skill works in
whichever agent the user runs).

## Scope

In scope:

- **CLI command** `skill-bill goal <issue_key>` in `runtime-kotlin/runtime-cli`
  (registered in the existing CLI command groups): runs `GoalRunner` in the
  **foreground**, streaming human-readable per-subtask progress
  (`subtask i/N [agent: X] running → complete (commit …)` / blocked report), and
  exits non-zero on a stop-and-report. Because the human is present (this is the
  observability substitute for the lost interactive UI), the command must **tee
  each child process's live stdout/stderr** to the terminal — optionally behind a
  `--quiet`/`--verbose` toggle — so a subtask that misbehaves is visible in real
  time rather than only at its terminal status.
- **CLI command** `skill-bill goal status <issue_key>`: read-only, prints the
  status projection from subtask 3 (complete/pending/blocked counts, current
  subtask + step, active agent). No mutation.
- The **`bill-goal` skill** under `skills/bill-goal/content.md` following the
  canonical content.md shape, with behavior:
  1. take the goal (design doc / spec / issue key);
  2. run `bill-feature-implement` interactively once;
     - if it completes a small feature directly (no decomposition) → done,
       report; the goal *was* the feature;
     - if it decomposes → manifest written, feature-implement stops at `plan`
       as today;
  3. **present the decomposition** (ordered subtasks, dependencies, scope) and
     ask for **one** confirmation;
  4. on confirm → invoke the driver (`skill-bill goal <issue_key>`), passing
     through **the agent the skill was invoked from** as the loop's default
     agent, and report the outcome; on reject → stop, leaving specs/manifest for
     the user to edit.
- **Catalog + sync**: add the README skill-catalog row for `bill-goal`, run the
  native-agent codegen / cross-agent sync so the skill is installed for all
  detected agents (the `bill-create-skill` path), and ensure
  `skill-bill validate` and agent-config validation pass with the new skill.
- Skill content must make the single-gate and stop-and-report contract explicit,
  and must **not** start the autonomous loop on unconfirmed decomposition.

Out of scope:

- The loop logic, manifest updates, single-PR finalization (subtask 3).
- The launcher and continuation contract (subtasks 1–2).
- Any background/daemon execution or cross-session status (feature non-goal).
- New agent onboarding.

## Acceptance Criteria

1. `skill-bill goal <issue_key>` runs the goal loop in the foreground with
   readable per-subtask progress, tees each child process's live output to the
   terminal (toggleable), and exits non-zero when it stops-and-reports on a
   blocked/failed subtask.
2. `skill-bill goal status <issue_key>` is read-only and prints
   complete/pending/blocked counts, the current subtask + step, and the active
   agent, from the subtask-3 projection.
3. `skills/bill-goal/content.md` exists in the canonical shape and implements:
   interactive decomposition → single confirmation gate → hand off to the driver;
   it completes a small (non-decomposed) goal directly and never starts the loop
   on unconfirmed decomposition.
4. The README skill catalog includes a `bill-goal` row, and the skill is rendered
   / synced for all detected agents via the existing codegen path.
5. `skill-bill validate`, `scripts/validate_agent_configs`, and
   `npx --yes agnix --strict .` pass with `bill-goal` present.
6. An end-to-end run of a small purpose-built decomposed feature (2–3 trivial
   subtasks) via `skill-bill goal` is exercised and documented: fresh process per
   subtask, manifest advanced, single PR at the end, and a clean stop-and-report
   when one subtask is forced to fail. Agents actually exercised are documented
   rather than coverage being claimed.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test)
(cd runtime-kotlin && ./gradlew check)

skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .

# Manual E2E: scaffold a tiny 2–3 subtask decomposed feature, run
#   skill-bill goal <issue_key>
# assert: one fresh process per subtask, manifest advances, single PR opened,
# and forcing one subtask to fail produces a foreground stop-and-report (non-zero
# exit) with the manifest pointer left on the failed subtask.
```

## Implementation Notes

- Register `goal` in the existing CLI command groups (`CliCommandGroups.kt`) and
  presenters (`CliPresenters.kt` / `CliOutput.kt`) following how current commands
  are wired; keep all loop logic in the domain service — the CLI is a thin
  foreground driver + renderer.
- The `bill-goal` skill is intentionally thin: it owns only the
  interactive-decomposition + confirmation gate, then shells out to
  `skill-bill goal <issue_key>`. Keep feature-implement as a black box — do not
  duplicate its phases in the skill.
- Note the process model explicitly in the skill content for future readers: the
  agent session shells out to `skill-bill goal`, which spawns sibling top-level
  agent processes per subtask. These are OS-level siblings, not subagents — this
  is what avoids the nesting limit and guarantees clean context.
- Follow `bill-create-skill` for catalog row + cross-agent codegen/sync so the
  skill exists identically across Claude/Codex/Opencode; do not hand-edit
  generated agent artifacts.
- Foreground progress should be honest: show the active agent and the current
  subtask/step; on stop, print exactly which subtask blocked and why, and how to
  resume (`skill-bill goal <issue_key>` again).
## E2E Evidence

Subtask 4 goal CLI evidence is recorded in
`.feature-specs/SKILL-56-agent-independent-goal-runner/subtask_4_goal_cli_e2e_evidence.md`.
