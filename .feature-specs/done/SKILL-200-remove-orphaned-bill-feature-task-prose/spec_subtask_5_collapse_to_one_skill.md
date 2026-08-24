# SKILL-200 Subtask 5 — Collapse the family into one entry-point skill

## Intended Outcome

`bill-feature` is the only skill in the feature entry family. It gathers intake, calls preflight
once, prints the gate block the runtime composed, asks one question, fetches any spec preflight
listed as missing, launches one command, and relays runtime output verbatim.
`skills/bill-feature-goal/` is deleted.

## Scope

Rewrite `../../../skills/bill-feature/content.md` to exactly these responsibilities:

- **Intake.** Establish the issue key; stop and ask if it is missing, and never invent one.
  Establish the intended outcome, acceptance criteria, constraints, and non-goals well enough for
  spec preparation.
- **Update check.** Call `mcp__skill-bill__update_check` first and handle `update_available` as
  today.
- **Token handling.** Accept at most one `code-review:auto|inline|delegated`, at most one
  `parallel-review:<agent>`, and zero or more ordered `agent-addon:<slug>`. Forward them as flags.
  Do not resolve, validate against a catalogue, or construct JSON: subtask 4 made the CLI reject
  bad values before any side effect.
- **Preflight.** Call `skill-bill goal preflight <issue-key>` once and act on the verdict. Invoke
  `bill-feature-spec` when it reports new work. Report and stop for already-running and
  terminal-only. Report every candidate for ambiguous. Surface loud failures.
- **Gate.** Print preflight's `gate_block` verbatim and ask exactly one question: whether to
  proceed. Do not launch while unconfirmed. If the user declines, stop.
- **Rehydrate.** For each entry in preflight's `rehydrate_targets`, fetch the issue over the Linear
  MCP and write the file, then launch. Empty list means nothing to do.
- **Launch.** Run `skill-bill goal <issue-key> --agent <currently-executing-agent> --no-live-output`
  with the forwarded flags. Never ask the user to run it manually.
- **Relay.** Do not poll, sleep, tail logs, re-read status, or launch an observer. Await the
  process through the harness's completion primitive and relay the runtime's output verbatim,
  adding nothing. Run `goal status` only when the user explicitly asks.

Delete `skills/bill-feature-goal/`. Update the `feature-launch-warning` skill class, install
staging, and the `internal-for` sidecar documentation so the family has one member. Update
`../../../docs/internal-skills-architecture.md`, `../../../docs/skill-source-generation.md`, and
`../../../docs/capabilities.md` to describe one skill. Update `../../../runtime-kotlin/ARCHITECTURE.md` if it still
maps a goal sidecar.

## Acceptance Criteria

1. `skills/bill-feature-goal/` is absent, and `bill-feature` is the only skill in the feature entry family.
2. `bill-feature` contains no continuation-lookup branch table, no manifest discovery or disambiguation algorithm, no `spec_source` or scratch-deletion reasoning, and no add-on catalogue resolution.
3. `bill-feature` calls `skill-bill goal preflight` exactly once per run and derives its behavior from the returned verdict.
4. `bill-feature` prints preflight's `gate_block` verbatim and asks exactly one confirmation question, and launches nothing while unconfirmed.
5. `bill-feature` fetches only the spec files preflight listed in `rehydrate_targets`, and makes no Linear call when that list is empty.
6. `bill-feature` relays runtime output verbatim and composes no monitor block, completion line, summary line, or progress relay of its own.
7. `bill-feature`'s prose covers only intake, update check, token forwarding, preflight, the gate, rehydrate, launch, and relay.
8. No file read of a sibling sidecar remains in the feature entry path, and no skill instructs an agent to read another skill's file and execute it.
9. `../../../orchestration/skill-classes/feature-launch-warning.yaml` matches only skills that exist.
10. A clean `./install.sh` stages `bill-feature/` with the seven `kmp` Android add-ons and its governed support pointers, and with none of `bill-feature-task.md`, `bill-feature-task-runtime.md`, or `bill-feature-goal.md`.
11. `../../../docs/internal-skills-architecture.md`, `../../../docs/skill-source-generation.md`, `../../../docs/capabilities.md`, and `../../../runtime-kotlin/ARCHITECTURE.md` describe a single-skill feature entry family with no goal sidecar.
12. Outside `../..` and `../../../agent/history.md`, no live source, contract, doc, or script references `bill-feature-goal` or `bill-feature-task-runtime`, and the only surviving references to `bill-feature-task` are the durable workflow identity and the CLI command name.
13. A goal launched end to end through the single skill reaches a terminal outcome with the same durable state, telemetry events, review behavior, and exit codes as before: `complete=0`, `failed=1`, `paused=2`, `blocked=3`.
14. A goal that is already running is reported and not relaunched, and a goal with a requested pause still clears that pause on relaunch exactly as it does today.
15. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

Adding runtime surface. Everything this subtask consumes exists after subtask 4.

Changing the phase loop, review contract, remediation loops, durable schemas, or telemetry shapes.

Moving intake, the confirmation gate, or the Linear MCP fetch into the runtime. Those three are
the residual and are why the skill still exists.

## Dependency Notes

Depends on subtask 4 for `goal preflight` and raw add-on slug resolution. Without them criterion 2
cannot hold, because the agent would have nothing to replace the branch table with.

Depends on subtask 3 having removed the unread prose, so this rewrite starts from a small file and
the diff shows the reshaping rather than a wholesale replacement.

Depends on subtasks 1 and 2 for the removals and doc corrections this subtask extends.

## Validation Strategy

Criterion 13 is the real gate and needs a live run, not a fixture: launch a goal through the
rewritten skill and compare durable state, emitted telemetry, and the process exit code against a
pre-change baseline. Criterion 14 needs the already-running and pause-requested paths exercised
deliberately, since those are the two behaviors most easily lost when a branch table is replaced
by a single verdict.

Prove criterion 12 with a repository sweep for all three names, excluding `../..` and
`../../../agent/history.md`, and confirm each surviving hit is the workflow identity or the CLI command.

Verify criterion 10 by listing the staged `bill-feature/` directory after a clean install.

Close with the four validation commands.

## Next Path

Open the PR for SKILL-200.
