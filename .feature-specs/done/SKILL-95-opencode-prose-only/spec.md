# SKILL-95 — opencode is prose-only (runtime mode refuses on opencode)

status: complete

## Summary

Make opencode a **prose-only** agent in skill-bill. On opencode, **prose is the implicit default** — no `mode:prose` argument is required — and an **explicit** `mode:runtime` (or any runtime invocation whose agent resolves to opencode) must **fail fast with a clear, actionable message**, instead of attempting a run that wedges. All opencode install, scaffold, MCP, prose orchestration, and telemetry support stay exactly as they are.

> Scope note: this changes the default **only when the host/invoking agent is opencode**. It does NOT change the framework-wide default (which `#197` set to `mode:runtime`) for other agents. Flipping the global default to prose is a separate decision tracked outside this spec unless explicitly folded in.

## Background

A real run (NEWS-141, workflow `wftr-20260626-193556-a4lk`) proved runtime mode is non-viable under opencode, for two independent reasons:

- **A — 120 s harness kill.** The Kotlin runtime driver runs the whole phase loop synchronously in one foreground process, but opencode's Bash tool hard-kills foreground commands at 120 000 ms. A single phase (preplan) took ~241 s, so the driver is guillotined before even one phase completes.
- **B — per-phase output cannot be harvested.** Even given a longer timeout, the nested `opencode run` produced a valid contract JSON but the runtime never captured it back (the opencode builder uses `usePtyStdio=true` + opencode's default formatted/TUI output, so the phase-output JSON can't be parsed out of the ANSI stream). The phase stays `running` and the loop wedges.

These are not worth fixing: opencode is the highest-churn, lowest-usage runtime target, and prose mode runs the identical governed loop in-session with none of these problems. The decision is to **stop supporting runtime on opencode and fail loudly so the user is told to use prose**, rather than silently degrade or wedge.

## Intended Outcome

A user invoking runtime mode on opencode gets an immediate, legible failure that names the supported path (prose). No opencode runtime workflow is opened, no branch is created, and no opencode per-phase subprocess is spawned. opencode remains fully usable in prose mode.

## Acceptance Criteria

1. On opencode, prose is the implicit default: invoking `bill-feature-task` or `bill-feature-goal` with **no mode argument** while the host/invoking agent is opencode runs in prose, with no need to pass `mode:prose`.
2. Invoking `bill-feature-task mode:runtime` while the host/invoking agent is opencode fails fast with a clear message stating opencode does not support runtime mode and to use prose; the router refuses before launching the runtime driver.
3. The runtime CLI refuses whenever the resolved runtime agent is opencode by any route — host-agent detection, `SKILL_BILL_AGENT=opencode`, `--agent opencode`, or `--phase-agent <phase>=opencode` — exiting non-zero before opening a workflow, resolving a branch, or spawning any phase.
4. `bill-feature-goal mode:runtime` under opencode (and the goal runtime continuation `--agent opencode` path) refuses identically; no opencode runtime subprocess is ever spawned.
5. The opencode runtime execution path is disabled at the source: the opencode agent-run command builder / launcher adapter no longer offers a runtime launch for opencode and yields the unsupported-launch outcome, so no code path can spawn opencode for a runtime phase even if a guard is bypassed.
6. The refusal message is actionable and names the supported alternative explicitly (`bill-feature-task-prose` / `bill-feature-goal mode:prose`).
7. opencode prose support is unchanged: install/scaffold (skills, generated opencode agents, MCP registration into `~/.config/opencode`), in-session prose orchestration, and telemetry all continue to work.
8. Runtime support for the other agents (claude, codex, junie, glm) is unchanged; they still run runtime mode as before.
9. Tests are updated: assertions that opencode is an accepted runtime agent become fast-refusal assertions, and a test covers opencode-host detection refusing runtime; opencode prose/install tests stay green; `./gradlew check` passes.
10. The decision and its rationale are recorded in the relevant module's `agent/decisions.md`.

## Non-Goals

- Removing opencode from install/scaffold or from prose mode.
- Fixing the opencode runtime bugs (A/B above) — explicitly declined.
- Changing runtime support for claude, codex, junie, or glm.
- Removing or changing the `--agent` / `--phase-agent` flags for other agents.
- Any change to the GLM-in-Claude-Code workflow.

## Constraints

- Fail loud at the boundary (preflight): refuse before opening a workflow, creating/reusing a branch, or spawning any subprocess.
- Leave the prose execution path byte-for-byte unaffected.
- The guard must cover implicit resolution (host is opencode, no explicit flag), not only the explicit `--agent opencode` flag.

## Affected Areas (implementation guidance, not binding)

- `runtime-kotlin/runtime-infra-fs/.../launcher/agentrun/AgentRunCommandBuilders.kt` — `OpencodeAgentRunCommandBuilder` and its registration in the headless agent-run adapters / `FileSystemAgentRunLauncher`.
- `runtime-kotlin/runtime-cli/.../featuretask/FeatureTaskRuntimeCliCommands.kt` — runtime agent resolution (`resolveInvokedRuntimeAgentId`, `parsePhaseAgents`); add the fast-refusal preflight for an opencode-resolved runtime agent.
- Goal runtime agent-resolution / `goalContinuationCommand` `--agent opencode` path.
- The `bill-feature-task-runtime` / goal runtime skill confirmation step (surface the refusal at the skill layer too).
- Tests under `runtime-kotlin/runtime-cli/src/test` and `runtime-kotlin/runtime-application/src/test` that currently assert opencode runtime acceptance.

## Validation Strategy

- `./gradlew check` is green.
- Negative path: a runtime invocation resolving the agent to opencode (each of: host detection, `SKILL_BILL_AGENT`, `--agent`, `--phase-agent`) exits non-zero with the refusal message and opens no workflow.
- Positive path: opencode prose (`bill-feature-task-prose`) and a runtime invocation on a non-opencode agent both still succeed.

## Next

```bash
Run bill-feature-task on .feature-specs/SKILL-95-opencode-prose-only/spec.md
```
