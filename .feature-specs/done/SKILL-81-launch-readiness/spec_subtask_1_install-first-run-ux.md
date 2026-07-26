# SKILL-81 · Subtask 1 — Install first-run UX

## Scope

`install.sh` completes successfully but can silently leave a first-time user unable to
run anything, which at launch scale becomes the dominant negative comment. Three gaps:

1. **No PATH check.** Launchers are written to `RUNTIME_LAUNCHER_BIN_DIR`
   (`${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}`, `install.sh:16`). The completion banner
   (`install.sh:2224`–`2271`) prints the launcher path but never checks whether that
   directory is on `PATH`. On distros/shells where `~/.local/bin` is not on PATH by
   default, the README's very next step (`skill-bill version`) fails with
   `command not found`.
2. **Silent zero-agent link.** In `detected` agent-selection mode (`install.sh:1625`–
   `1628`), if the runtime detects no agents, the install completes with skills linked
   nowhere and no warning. The user discovers this only when a slash command later fails.
3. **No "what next" guidance.** The banner ends with "Edit skills in: …" and a reinstall
   hint (`install.sh:2266`–`2271`); it never tells the user to go run a starter command
   in their agent.

In scope (the apply step / completion banner of `install.sh`, completion path only):
- After launchers are installed, detect whether `RUNTIME_LAUNCHER_BIN_DIR` is on the
  user's `PATH`. If not, print a clearly-marked warning with a copy-pasteable remedy
  (e.g. the exact `export PATH="$RUNTIME_LAUNCHER_BIN_DIR:$PATH"` line and which shell
  rc file to add it to). Do not attempt to silently rewrite the user's shell config.
- When the resolved install linked **zero** agents (detected-mode found none, or the
  reused/selected set produced no agent links), print a clearly-marked warning that no
  agent received the skills and how to fix it (re-run and choose `manual`, or install a
  supported agent first). Do not change the existing manual-mode "choose at least one
  agent" loop (`install.sh:1661`/`1693`), which already guards manual selection.
- Add a concrete first-action next step to the completion banner — a starter command to
  run inside an installed agent (e.g. invoke `/bill-feature-task` or `/bill-code-review`)
  — so the path from "installed" to "did something useful" is explicit.

## Acceptance Criteria

1. When `RUNTIME_LAUNCHER_BIN_DIR` is not present in `PATH` at the end of a successful
   install, the script prints a distinct, visible warning containing the exact
   copy-pasteable command to add it and the shell rc file to put it in. When it *is* on
   PATH, no such warning is printed.
2. When the completed install linked zero agents, the script prints a distinct warning
   stating that no agent received the skills and the concrete remedy. When at least one
   agent was linked, no such warning is printed.
3. The completion banner includes a concrete next-step line directing the user to run a
   starter slash command inside one of their installed agents.
4. No existing install path regresses: prebuilt, `--from-source`, reinstall/reconcile,
   `--desktop-app-only`, and manual/detected/reuse selection all still complete; the
   manual-mode "at least one agent" loop is unchanged.
5. The changed shell passes `shellcheck` at the level the rest of `install.sh` already
   satisfies (no new warnings introduced); warnings use the existing `warn`/`info`
   helpers and color conventions, not raw `echo`.

## Non-goals

- Auto-editing the user's shell rc files or auto-`source`-ing them.
- Pre-flight verification that a selected agent's CLI binary is installed (only that the
  install linked at least one agent directory).
- Changing the default `RUNTIME_LAUNCHER_BIN_DIR` or the manual-selection loop behavior.
- Desktop-app or telemetry banner changes beyond the next-step line.

## Dependency notes

Independent. No dependency on subtasks 2–4. Touches only `install.sh`.

## Validation strategy

- Run `./install.sh` (prebuilt) in an environment where `~/.local/bin` is **not** on
  PATH and confirm the PATH warning fires with a correct, copy-pasteable remedy; repeat
  with it on PATH and confirm the warning is suppressed.
- Drive the completion path with zero linked agents (detected mode, no agents) and
  confirm the zero-agent warning fires; with ≥1 agent, confirm it does not.
- `shellcheck install.sh` shows no new findings versus baseline.

## Next path

```bash
skill-bill goal SKILL-81
```
