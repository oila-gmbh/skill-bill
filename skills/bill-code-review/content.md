---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill. Use when user mentions code review, review my changes, review this PR, review staged changes, or asks to review code.
---

# Parallel Review Argument

## Review mode argument

Recognize at most one `mode:auto`, `mode:inline`, or `mode:delegated` argument.
Omission means `mode:auto`.
Reject malformed, unknown, duplicate, or conflicting values before resolving
scope, starting a lane, or importing telemetry.

`auto` preserves the shell contract's existing eligibility decision. `inline`
is allowed only after every shared eligibility condition passes; otherwise stop
with the failed reasons and state that delegated review is required. Do not
silently replace it. `delegated` always runs the normal routed delegated path,
including specialist selection; inability to launch required workers blocks
loudly and never falls back to inline. Report both requested mode and resolved
execution mode in the normal review metadata.

When the caller passes `parallel:<agent>` or `parallel:<agent>:<model>` in args — for example `parallel:codex`, `parallel:codex:o3`, or `parallel:claude:claude-opus-4-8` — run two review lanes on the same diff and merge their findings with provenance labels.

Lane 1 is the normal routed stack-specific review, run in this session through its standard flow — which means it spawns the routed pack's specialist subagents (and any baseline review layer) exactly as a non-parallel `/bill-code-review` would. "In this session" only distinguishes it from lane 2; it does **not** mean a single in-thread read by the current agent. Lane 2 is the named agent, launched as a background subprocess via its CLI; it also runs `bill-code-review mode:<selected-mode>` in full — routing to the dominant stack, spawning its own specialist subagents, and producing its own risk register. Do not pass `parallel:` into lane 2. Both lanes run the same full review pipeline independently. Findings are merged deterministically by the `skill-bill code-review-merge` CLI so the output is machine-readable by downstream tooling.

When the argument is absent, consult the repo-local config fallback (next section) before falling through to normal shell behaviour.

## Config Fallback (when `parallel:` is absent)

The `parallel:` arg, when present, always wins — skip this section entirely. When no `parallel:` arg is passed, source the lane-2 agent from repo-local config so a project can set it once instead of retyping it per review. Code review has no durable downstream state, so config-as-default is sufficient; there is no stamp.

Resolve the effective lane-2 agent with the runtime, which applies the precedence `parallel: arg > code_review_parallel_agent config > none` and validates any config value against the supported agent ids:

```bash
skill-bill config resolve-parallel-agent --repo-root <repo-root>
```

- It prints `none` when there is no config file, no `code_review_parallel_agent` key, or the key is explicitly `none` → run the normal single-lane routed review, identical to today's no-arg behaviour. Then fall through to normal shell behaviour: scope detection, stack routing, and execution-mode selection per the shell contract.
- It prints a supported agent id → run the two-lane parallel flow with that id as the lane-2 agent, exactly as if `parallel:<id>` had been passed (no model override; `lane2Model` is empty).
- It exits non-zero when the configured value is unrecognized (validated against `InstallAgent.supportedIds + none`) or the config is malformed → stop immediately and surface the runtime's named error verbatim. Do not start either lane.

When a feature-goal caller supplies a durable child-owned diff, preserve it verbatim for both lanes. The CLI delegation route uses `--diff-file <exact-diff-path>` and must not resolve `--scope branch`, `origin/main...HEAD`, or a replacement baseline.

## Argument Recognition

- Recognise `parallel:<agent>` or `parallel:<agent>:<model>` where `<agent>` is a supported agent ID and `<model>` is an optional model override for lane 2 (e.g. `o3`, `claude-opus-4-8`).
- Parse by splitting on `:` — the first token is always `parallel`, the second is the agent ID, and the optional third token is the model. Any further colons are part of the model ID (e.g. `us.anthropic.claude-opus-4-8`).
- To verify supported agent IDs, run `skill-bill code-review-merge --help`; the `--lane2-agent` help line lists them.
- If `<agent>` is blank or unsupported, stop immediately, name the unsupported value, and list supported agents. Do not start either lane.
- Store the parsed model as `lane2Model` (empty string means no override). Pass it through to both routing paths below.

## Scope Resolution

Determine the diff scope from the caller's request using the same labels as the normal flow: `staged`, `unstaged`, `branch` (default), or `pr`. Resolve the diff text for that scope.

## Lane 2: Routing Capability Table

This table is the single source of truth for lane 2 routing. Update the values here to retune
behaviour — the routing steps below read from this table, so no routing prose changes when an
agent gains stdin support.

| Agent      | stdin-pipe supported? | Within-threshold path                 |
| ---------- | --------------------- | ------------------------------------- |
| `claude`   | yes                   | `claude -p < <lane2-prompt-path>`     |
| `codex`    | yes                   | `codex exec - < <lane2-prompt-path>`  |
| `opencode` | no                    | CLI delegation (always)               |
| `copilot`  | no                    | CLI delegation (always)               |
| `junie`    | no                    | CLI delegation (always)               |

Agents marked "no" under stdin-pipe always use CLI delegation — their `$(cat ...)` shell-argument
form breaks above `ARG_MAX` on most shells, so it is not their route.

## Lane 2: Routing Decision

Decide the lane 2 path:

1. **Agent is not stdin-capable** (`opencode`, `copilot`, `junie` per the table) → **CLI delegation path, always**.
2. **Else** (stdin-capable agent) → **stdin-pipe path**.

### CLI delegation path

Delegate **both lanes** to the in-process CLI, which runs each agent via `AgentRunLauncher` and
merges findings internally:

```bash
skill-bill code-review-parallel \
  --agent1 <lane1-agent-id> \
  --agent2 <lane2-agent-id> \
  [--model2 <lane2Model>] \
  --execution-mode <selected-mode> \
  --scope <scope> \
  --repo-root <repo-root>
```

Omit `--model2` when `lane2Model` is empty.

On this path the routed lane-1 review, the stdin lane-2 subprocess, and the separate
`skill-bill code-review-merge` step below are **superseded** — `code-review-parallel` runs both
lanes and merges in-process. Display its merged output as the final review result.

### stdin-pipe path

Lane 2 agents are skill-bill-supported agents running in a skill-bill repo — they have full access
to the skill infrastructure and must run `bill-code-review` exactly as lane 1 does, spawning their
own specialist subagents. Do **not** bake rubrics into a flat single-pass prompt; that produces an
inferior, non-agentic review.

Build `LANE_2_PROMPT` as a short skill-invocation instruction:

```
You are running lane 2 of a parallel code review.

Scope: <scope description — e.g. "commit <SHA>", "staged changes", "branch diff against main", "PR diff">
Repo root: <repo-root>

Rules:
- Run the full routed review using the routed pack's Diff-Signal Routing Table: retain required baseline layers, add only signal-relevant specialists, and drop empty lanes.
- Invoke `bill-code-review mode:<selected-mode>` for this lane. Preserve the caller's selected mode; do not replace it with `auto`.
- Do NOT pass parallel:<agent> to bill-code-review — you are already lane 2; recursion is not allowed.
- Spawn each specialist as an isolated-context subagent using your agent's native subagent mechanism (see Agent-specific instructions below). What matters is that each specialist reasons in its own context window — not that it runs as a separate OS process.
- When all subagents complete, collect their findings and output ONLY the concatenated Risk Register, one finding per line:
  - [F-NNN] Severity | Confidence | file:line | description
  Severity: Blocker, Major, Minor, Nit. Confidence: High, Medium, Low.

## Agent-specific subagent instructions

**If you are `claude`:**
Invoke `/bill-code-review` as a slash command — it handles all specialist routing automatically.

**If you are `codex`:**
Spawn one isolated-context subagent per specialist domain below using your native `SpawnAgent` mechanism — the same primitive Codex uses for parallel subagents. Do **not** shell out to `codex exec` subprocesses; the native subagent already gives each specialist its own context window, which is the only isolation this review needs. Run all subagents in parallel from the repo root and wait for them to complete.

Prepare the compact shared review-context packet from `review-delegation.md` once. Derive specialist domains from the routed platform pack's Diff-Signal Routing Table, including required baseline layers, and spawn only selected non-empty lanes. Give each worker the shared packet, its lane assignment, and only its applicable rubric. Packet facts are authoritative; workers must not repeat repository, scope, stack, routing, or guidance discovery.

Give each specialist subagent this brief:

  You are the <domain> specialist reviewing <scope> in <repo-root>.
  Focus: <focus>.
  Read only the assigned changed code and direct dependencies needed to prove a reachable finding, then output ONLY Risk Register findings:
  - [F-NNN] Severity | Confidence | file:line | description
  Severity: Blocker, Major, Minor, Nit. Confidence: High, Medium, Low.

Run all derived specialist subagents, collect their output, concatenate all findings, and emit them as your final output.
Before emitting, normalise every finding line so it starts with `- [F-NNN]`. If a subagent omitted the leading `- `, prepend it. Do not alter anything else.
```

Create per-run scratch files with `mktemp` first. Do **not** use fixed, predictable `/tmp` paths:
the lane prompt holds the full diff, which may contain secrets or unreleased code, and a fixed
world-readable path on a shared host leaks it to other local users and is open to symlink clobber.
`mktemp` gives owner-only (`0600`), unpredictable names. Shell variables do **not** persist across
separate Bash invocations, so run this once, note the three printed absolute paths, and reuse those
literal paths verbatim in every later command:

```bash
mktemp   # lane-2 prompt   -> reuse as <lane2-prompt-path>
mktemp   # lane-2 review   -> reuse as <lane2-review-path>
mktemp   # lane-1 review   -> reuse as <lane1-review-path>
```

Write the prompt to the lane-2 prompt file (many agent CLIs check for an interactive terminal and
reject piped stdin):

```bash
cat > <lane2-prompt-path> << 'PROMPT_EOF'
<LANE_2_PROMPT>
PROMPT_EOF
```

Then launch lane 2 in the background using the Bash tool with `run_in_background: true`,
redirecting output to the lane-2 review file:

#### claude
```bash
claude -p [--model <lane2Model>] < <lane2-prompt-path> > <lane2-review-path> 2>&1
```

Omit `--model` when `lane2Model` is empty.

#### codex
```bash
codex exec [--model <lane2Model>] - < <lane2-prompt-path> > <lane2-review-path> 2>&1
```

Omit `--model` when `lane2Model` is empty.

If the CLI exits with "stdin is not a terminal", "not a tty", or a similar rejection, try passing
the prompt as a positional argument instead: `<agent> "<prompt>"`. If the agent CLI is not found in
`PATH` or all invocation attempts fail, abort lane 2, skip the merge step, and report
`Parallel lane: <agent> unavailable — <reason>` in the summary. Continue with lane 1 results only.

## Lane 1: Routed Review

While lane 2 runs in the background, run the normal routed stack-specific review in this session, following all standard shell-contract steps. This is the full routed review, **not** a single in-thread read of the diff: route to the dominant stack's pack and execute it exactly as a non-parallel `/bill-code-review` would, including spawning that pack's specialist subagents (and its baseline review layer) and applying its execution-mode selection. Collect every specialist's risk register into the routed review output, then capture that full output to the lane-1 review file (`<lane1-review-path>`).

## Merge

Once both lanes finish, merge findings:

```bash
skill-bill code-review-merge \
  --lane1-agent <invoking-agent-id> \
  --lane1 <lane1-review-path> \
  --lane2-agent <parallel-agent-id> \
  --lane2 <lane2-review-path>
```

The CLI parses both outputs, coalesces findings that share the same root cause and location, and emits a single risk register with provenance labels. Display the merged output as the final review result.

Once the merged result is displayed, delete the scratch files: `rm -f <lane2-prompt-path> <lane2-review-path> <lane1-review-path>`.

## Parallel Output Format

```
- [F-001] [claude, codex] Major | High | file:line | description
- [F-002] [claude] Minor | Medium | file:line | description
- [F-003] [codex] Blocker | High | file:line | description
```

Coalesced findings appear before single-lane findings within each severity tier.

The Section 1 summary must add:
```
Parallel lane: <agent> [model: <lane2Model>] (success | failed: <reason>)
```

Omit `[model: ...]` when no model override was specified.

## Failure Handling

- Lane 2 fails (non-zero exit, timeout, missing CLI): note in summary, continue with lane 1 findings only, skip the merge step.
- Lane 1 fails but lane 2 succeeds: use lane 2 findings only, all labeled `[<agent>]`, note in summary.
- Both fail: report both failures and stop with no verdict.
