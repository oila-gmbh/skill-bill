# SKILL-56 Subtask 2 - Agent-Agnostic Headless-Run Launcher (port + adapters)

Parent spec: [.feature-specs/SKILL-56-agent-independent-goal-runner/spec.md](./spec.md)
Issue key: SKILL-56
Subtask order: 2 of 4
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Add the runtime's ability to spawn a **fresh headless run of a skill for
whichever agent the user uses** — Claude, Codex, Opencode, or any future
supported agent — without hardcoding a single agent's CLI. This is the piece
that turns "feature-implement can run one subtask non-interactively" (subtask 1)
into "the runtime can *launch* that run as its own top-level process." It is the
structural enabler of the clean-context guarantee: each launch is an independent
OS process with empty context, never a nested subagent of the caller.

This subtask follows the hexagonal runtime architecture: a port defines the
contract; per-agent adapters implement it; the application layer wires them.

## Scope

In scope:

- Define an **`AgentRunLauncher` port** in `runtime-kotlin/runtime-ports`
  (alongside `install`, `scaffold`, `workflow`) modeling: given an agent
  identifier + a skill-run request (skill name, the goal-continuation arguments
  from subtask 1, working directory), launch a fresh **headless** process and
  return a **launch-level** outcome: `exitStatus`, captured `stdout`/`stderr`
  (diagnostic), and timeout/spawn-failure signals. The launcher reports *whether
  the process ran*; **it does not parse the subtask result from stdout** — the
  authoritative subtask outcome is read from the durable workflow store by the
  goal runner (subtask 3), since the headless run records it there (subtask 1).
- Implement **per-agent adapters** in `runtime-infra-*` (process-spawning infra)
  for the currently supported agents, each encapsulating that agent's
  non-interactive invocation (the agent's print/exec/run mode + skill trigger).
  The set of agents must be derived from the existing supported-agent registry
  the scaffold/install path already uses — do not invent a parallel list.
- **Ensure skill + MCP availability per launch.** Per subtask 1's headless
  de-risk: if an agent's headless context does not inherit the
  `bill-feature-implement` skill and the skill-bill MCP server, the adapter must
  inject the necessary config (skill path / MCP server registration) into the
  spawned process so the run can resolve the manifest and record its outcome. If
  subtask 1 found a given agent's headless context already includes them, the
  adapter simply spawns.
- **Agent resolution**: default to **the agent the user invoked `bill-goal`
  from** (passed through from subtask 4), overridable by explicit config; reuse
  existing agent detection used by install/scaffold. If the resolved agent has
  **no headless skill-run path**, return an explicit `unsupported-agent` outcome
  — never silently skip or fall back to a different agent.
- Credential/auth model: the launched process **inherits the host environment**
  (the user is already authenticated to use the agent locally); the launcher
  does not manage or store credentials.
- Wire the port + adapters in `runtime-application` DI and expose them to the
  domain layer for subtask 3.

Out of scope:

- The loop that decides *which* subtask to launch and *when* (subtask 3).
- The CLI command and skill front (subtask 4).
- The non-interactive entry contract itself (subtask 1, consumed here).
- Persistent/background process supervision, retries across reboots, or any
  daemon behavior (out of scope for the whole feature).
- Onboarding agents that are not already supported by Skill Bill.

## Acceptance Criteria

1. An `AgentRunLauncher` port exists in `runtime-ports` with a clear contract:
   `(agentId, skillRunRequest) → launchOutcome { exitStatus, stdout, stderr,
   timedOut, spawnFailed }`. It is technology-neutral (no agent CLI names in the
   port) and reports launch-level facts only — the subtask result is read from
   the workflow store, not from this outcome.
2. Per-agent adapters implement the port for each currently supported agent that
   has a headless skill-run path; each adapter spawns a **fresh process** (no
   shared/reused process, no nesting).
3. Each adapter guarantees the spawned process can reach the
   `bill-feature-implement` skill and the skill-bill MCP workflow tools —
   inheriting them where the agent's headless context provides them, injecting
   the config where (per subtask 1) it does not.
4. The launcher adapter defaults to the agent the user invoked `bill-goal` from
   (overridable by config), resolved via the existing supported-agent registry;
   no hardcoded default agent, no parallel agent list.
5. An agent without a headless skill-run path yields an explicit
   `unsupported-agent` outcome that the caller can surface; it is never silently
   skipped or substituted.
6. The launched process inherits host environment/credentials; the launcher
   stores no secrets and adds no new auth step.
7. The port + adapters are wired in `runtime-application` and available to the
   domain layer; unit tests cover adapter command construction (including any
   injected skill/MCP config) with a fake/recording process runner (no real agent
   process required in CI), plus the unsupported-agent and timeout/spawn-failure
   paths.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-ports:test :runtime-application:test)
(cd runtime-kotlin && ./gradlew check)

# Adapter command-construction tests assert the correct per-agent headless
# invocation is built (recording process runner), including the
# unsupported-agent outcome. A real end-to-end launch against an installed
# agent is exercised manually and the agents tested are documented.
```

## Implementation Notes

- Keep the port at the same altitude as existing ports (`workflow`, `install`):
  it describes intent ("launch a headless skill run for this agent"), not
  mechanics. All `ProcessBuilder`/exec detail lives in the infra adapter.
- Reuse the agent identity/detection already used to sync skills across agents
  (install/scaffold path). The launcher's agent set must equal the supported set
  the rest of the runtime already knows; a new agent should require only an
  adapter, not changes elsewhere.
- Do **not** parse the subtask result from stdout. The launcher reports only
  launch-level facts (exit/timeout/spawn-failure) plus captured stdout/stderr for
  diagnostics and live tee-ing (subtask 4). Whether the subtask actually
  completed is determined by the goal runner reading the workflow store
  (subtask 3) — a process can exit zero with the subtask still blocked, and vice
  versa, so the store is the truth.
- Do not bake in retry/timeout policy here beyond returning a timeout outcome;
  the *policy* (stop vs retry) belongs to the goal-runner service (subtask 3),
  consistent with the parent decision to "stop and report" on failure.
- This subtask only needs to launch *a* headless run; proving the run does the
  right thing is subtask 1's job and the integration is subtask 3's.
