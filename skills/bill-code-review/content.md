---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill. Use when user mentions code review, review my changes, review this PR, review staged changes, or asks to review code.
---

# Parallel Review Argument

When the caller passes `parallel:<agent>` in args — for example `parallel:codex` or `parallel:claude` — run two review lanes on the same diff and merge their findings with provenance labels.

Lane 1 is the current agent running this skill (inline, no subprocess). Lane 2 is the named agent, launched as a background subprocess via its CLI. Both lanes review the same diff. Findings are merged deterministically by the `skill-bill code-review-merge` CLI so the output is machine-readable by downstream tooling.

When the argument is absent, fall through to normal shell behaviour: scope detection, stack routing, and execution-mode selection per the shell contract.

## Argument Recognition

- Recognise `parallel:<agent>` where `<agent>` is a supported agent ID.
- To verify supported agent IDs, run `skill-bill code-review-merge --help`; the `--lane2-agent` help line lists them.
- If `<agent>` is blank or unsupported, stop immediately, name the unsupported value, and list supported agents. Do not start either lane.

## Scope Resolution

Determine the diff scope from the caller's request using the same labels as the normal flow: `staged`, `unstaged`, `branch` (default), or `pr`. Resolve the diff text for that scope.

## Lane 2: Routing Capability Table

This table is the single source of truth for lane 2 routing. Update the values here to retune
behaviour — the routing steps below read from this table, so no routing prose changes when an
agent gains stdin support or the threshold moves.

| Agent      | stdin-pipe supported? | Within-threshold path                 |
| ---------- | --------------------- | ------------------------------------- |
| `claude`   | yes                   | `claude -p < /tmp/lane2-prompt.txt`   |
| `codex`    | yes                   | `codex exec - < /tmp/lane2-prompt.txt`|
| `opencode` | no                    | CLI delegation (always)               |
| `copilot`  | no                    | CLI delegation (always)               |
| `junie`    | no                    | CLI delegation (always)               |

- `LANE2_DIFF_BYTE_THRESHOLD = 200 KB (204800 bytes)` — the diff byte count above which even a
  stdin-capable agent is routed to CLI delegation. Documented here so it can be changed without
  touching the routing logic.
- Agents marked "no" under stdin-pipe (`opencode`, `copilot`, `junie`) **always** use CLI
  delegation regardless of diff size — their previous `$(cat ...)` shell-argument form breaks
  above `ARG_MAX` (~200 KB, `E2BIG`) on most shells, so it is no longer their route.

## Lane 2: Size Guard and Routing Decision

Before composing `LANE_2_PROMPT`, measure the **byte count of the resolved diff text**:

```bash
DIFF_BYTES=$(wc -c < /tmp/lane2-diff.txt)
```

Then decide the lane 2 path, in this order:

1. **Agent is not stdin-capable** (`opencode`, `copilot`, `junie` per the table) → **CLI delegation
   path, always**, regardless of `DIFF_BYTES`.
2. **Else `DIFF_BYTES > LANE2_DIFF_BYTE_THRESHOLD` (204800)** → emit a notice
   (`Parallel lane: <agent> diff ${DIFF_BYTES}B exceeds ${LANE2_DIFF_BYTE_THRESHOLD}B — delegating to code-review-parallel CLI`)
   and take the **CLI delegation path**.
3. **Else** (stdin-capable agent, diff within threshold) → take the **stdin-pipe path** below.

### CLI delegation path

Delegate **both lanes** to the in-process CLI, which resolves the diff and builds the prompt
through `AgentRunLauncher` with no shell argument (no `$(cat ...)` expansion, so no `ARG_MAX`
limit) and merges findings internally:

```bash
skill-bill code-review-parallel \
  --agent1 <lane1-agent-id> \
  --agent2 <lane2-agent-id> \
  --scope <scope> \
  --repo-root <repo-root>
```

On this path the inline lane-1 review, the stdin lane-2 subprocess, and the separate
`skill-bill code-review-merge` step below are **superseded** — `code-review-parallel` runs both
lanes and merges in-process. Display its merged output as the final review result.

### stdin-pipe path

Build a self-contained `LANE_2_PROMPT` that includes:
1. The full diff text.
2. A compact instruction:
   > Review the diff above. Return only a Risk Register section using this exact format:
   > `- [F-NNN] Severity | Confidence | file:line | description`
   > Severity: Blocker, Major, Minor. Confidence: High, Medium, Low. Number findings sequentially from F-001.

Write the prompt to a temp file first — many agent CLIs check for an interactive terminal and reject piped stdin:

```bash
cat > /tmp/lane2-prompt.txt << 'PROMPT_EOF'
<LANE_2_PROMPT>
PROMPT_EOF
```

Then launch lane 2 in the background using the Bash tool with `run_in_background: true`, redirecting output to a temp file. Use the within-threshold path from the capability table for the resolved agent:

### claude
```bash
claude -p < /tmp/lane2-prompt.txt > /tmp/lane2-review.txt 2>&1
```

### codex
```bash
codex exec - < /tmp/lane2-prompt.txt > /tmp/lane2-review.txt 2>&1
```

`opencode`, `copilot`, and `junie` have no stdin-pipe path: per the capability table they take the
CLI delegation path above and never reach this section.

If the CLI exits with "stdin is not a terminal", "not a tty", or a similar rejection, try passing the prompt as a positional argument instead: `<agent> "<prompt>"`. If the agent CLI is not found in `PATH` or all invocation attempts fail, abort lane 2, skip the merge step, and report `Parallel lane: <agent> unavailable — <reason>` in the summary. Continue with lane 1 results only.

## Lane 1: Inline Review

While lane 2 runs in the background, execute the normal routed stack-specific review inline in the current agent session, following all standard shell-contract steps. Capture the full review output to `/tmp/lane1-review.txt`.

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
Parallel lane: <agent> (success | failed: <reason>)
```

## Failure Handling

- Lane 2 fails (non-zero exit, timeout, missing CLI): note in summary, continue with lane 1 findings only, skip the merge step.
- Lane 1 fails but lane 2 succeeds: use lane 2 findings only, all labeled `[<agent>]`, note in summary.
- Both fail: report both failures and stop with no verdict.
