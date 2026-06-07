---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill. Use when user mentions code review, review my changes, review this PR, review staged changes, or asks to review code.
---

# Parallel Review Argument

When the caller passes `parallel:<agent>` or `parallel:<agent>:<model>` in args — for example `parallel:codex`, `parallel:codex:o3`, or `parallel:claude:claude-opus-4-8` — run two review lanes on the same diff and merge their findings with provenance labels.

Lane 1 is the normal routed stack-specific review, run in this session through its standard flow — which means it spawns the routed pack's specialist subagents (and any baseline review layer) exactly as a non-parallel `/bill-code-review` would. "In this session" only distinguishes it from lane 2; it does **not** mean a single in-thread read by the current agent. Lane 2 is the named agent, launched as a background subprocess via its CLI; it also runs `bill-code-review` in full — routing to the dominant stack, spawning its own specialist subagents, and producing its own risk register. Both lanes run the same full review pipeline independently. Findings are merged deterministically by the `skill-bill code-review-merge` CLI so the output is machine-readable by downstream tooling.

When the argument is absent, fall through to normal shell behaviour: scope detection, stack routing, and execution-mode selection per the shell contract.

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
| `claude`   | yes                   | `claude -p < /tmp/lane2-prompt.txt`   |
| `codex`    | yes                   | `codex exec - < /tmp/lane2-prompt.txt`|
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
- Run the full routed review by spawning one isolated-context subagent per specialist domain — do NOT do a single flat read of the diff.
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

Give each specialist subagent this brief:

  You are the <domain> specialist reviewing <scope> in <repo-root>.
  Focus: <focus>.
  Read the changed files from the repo, then output ONLY Risk Register findings:
  - [F-NNN] Severity | Confidence | file:line | description
  Severity: Blocker, Major, Minor, Nit. Confidence: High, Medium, Low.

Specialist domains and their focus:
- architecture: layer boundaries, DI scopes, module ownership, dependency direction, source-of-truth consistency
- platform-correctness: concurrency, threading, coroutines, race conditions, state-machine correctness, business invariants
- testing: test coverage quality, regression protection, brittle tests, determinism, weak assertions
- reliability: timeouts, retries, background work, subprocess lifecycle, observability, failure handling
- performance: hot paths, blocking I/O, memory pressure, redundant computation, resource usage
- security: secrets handling, subprocess injection, sensitive data exposure, environment inheritance

Run all six subagents, collect their output, concatenate all findings, and emit them as your final output.
Before emitting, normalise every finding line so it starts with `- [F-NNN]`. If a subagent omitted the leading `- `, prepend it. Do not alter anything else.
```

Write the prompt to a temp file first — many agent CLIs check for an interactive terminal and
reject piped stdin:

```bash
cat > /tmp/lane2-prompt.txt << 'PROMPT_EOF'
<LANE_2_PROMPT>
PROMPT_EOF
```

Then launch lane 2 in the background using the Bash tool with `run_in_background: true`,
redirecting output to a temp file:

#### claude
```bash
claude -p [--model <lane2Model>] < /tmp/lane2-prompt.txt > /tmp/lane2-review.txt 2>&1
```

Omit `--model` when `lane2Model` is empty.

#### codex
```bash
codex exec [--model <lane2Model>] - < /tmp/lane2-prompt.txt > /tmp/lane2-review.txt 2>&1
```

Omit `--model` when `lane2Model` is empty.

If the CLI exits with "stdin is not a terminal", "not a tty", or a similar rejection, try passing
the prompt as a positional argument instead: `<agent> "<prompt>"`. If the agent CLI is not found in
`PATH` or all invocation attempts fail, abort lane 2, skip the merge step, and report
`Parallel lane: <agent> unavailable — <reason>` in the summary. Continue with lane 1 results only.

## Lane 1: Routed Review

While lane 2 runs in the background, run the normal routed stack-specific review in this session, following all standard shell-contract steps. This is the full routed review, **not** a single in-thread read of the diff: route to the dominant stack's pack and execute it exactly as a non-parallel `/bill-code-review` would, including spawning that pack's specialist subagents (and its baseline review layer) and applying its execution-mode selection. Collect every specialist's risk register into the routed review output, then capture that full output to `/tmp/lane1-review.txt`.

## Merge

Once both lanes finish, merge findings:

```bash
skill-bill code-review-merge \
  --lane1-agent <invoking-agent-id> \
  --lane1 /tmp/lane1-review.txt \
  --lane2-agent <parallel-agent-id> \
  --lane2 /tmp/lane2-review.txt
```

The CLI parses both outputs, coalesces findings that share the same root cause and location, and emits a single risk register with provenance labels. Display the merged output as the final review result.

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
