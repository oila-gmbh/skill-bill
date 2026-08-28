# SKILL-218 — Make Cursor delegated review actually launch specialists

## Philosophy

SKILL-182 documented Cursor as an in-harness delegated runtime and installed
named specialists under `~/.cursor/agents/`. The runtime still launches one
generic `agent --print` parent whose prompt never names those specialists, and
whose `--workspace` is an isolated MCP temp dir with no `.cursor/agents/`.
Cursor CLI has no `--agent` flag. Invocation is `/name` in the prompt, with
project agents loaded from `{workspace}/.cursor/agents/`.

Claude keeps `Agent`/`Task` on `reviewFanOut`. This skill only changes the
Cursor parent launch so an explicit `mode:delegated` can see and name the
routed specialists.

## Context

`ParallelCodeReviewRunner` compiles specialist lanes, preflights native
agents, then launches one parent with `reviewFanOut = true`. Claude's builder
adds `--tools Agent,Task`. Cursor's builder ignores that flag, sets
`--workspace` to the evidence-endpoint directory, and writes
`.cursor/mcp.json` there. The delegated parent prompt says "launch one
specialist worker per resolved rubric" without `/bill-kotlin-code-review-architecture`
(or any other logical worker name).

The playbook currently tells a Cursor CLI parent that named specialist lanes
cannot run. That matches today's launch, not Cursor's `/name` mechanism.

## Intended Outcome

An explicit Cursor `mode:delegated` parent launched by `skill-bill code-review`
names every selected specialist with `/<logicalWorkerName>` in one instruction
and finds those agent files as project subagents in the isolated launch
workspace. Claude and Codex launch commands and prompts stay unchanged.
Unavailable specialists still fail loud. Nothing falls back to inline.

## Acceptance Criteria

1. A Cursor delegated parent prompt contains one `/<logicalWorkerName>` line
   per selected specialist, in a single instruction that names every selected
   lane.
2. Before that parent starts, each selected specialist's installed Cursor
   agent file is present at
   `{reviewLaunchDirectory}/.cursor/agents/<logicalWorkerName>.md`.
3. A Claude delegated parent prompt does not gain `/name` invocation lines,
   and its command still enables `Agent` and `Task` when `reviewFanOut` is
   true.
4. A Codex delegated parent launch is unchanged by this skill.
5. Cursor inline still launches one `bill-code-review-inline` parent with no
   specialist `/name` fan-out and without copying specialist agent files for
   unused lanes.
6. Missing, dangling, or undeclared selected specialists still fail
   `MissingInstalledNativeAgentError` before the parent starts. The run does
   not resolve to inline.
7. Junie remains unsupported for delegated review.
8. Tests cover criteria 1–6 at the runner and Cursor command-builder seams.
   One strong test per rule.

## Constraints

- Stay inside the invoking agent's harness. Do not restore the SKILL-145
  lifecycle store, wave dispatcher, or provider capability registry.
- Do not add a Cursor `--agent` flag. The CLI does not have one.
- Do not change Claude Code, Codex, or Junie launch behaviour.
- `../../../orchestration/review-orchestrator/specialist-contract.md` stays the worker
  rules source. Playbook edits describe Cursor launch mechanics only.
- Cursor delegated review stays experimental and explicit opt-in. `auto` and
  an omitted mode still resolve to inline.

## Non-Goals

- Making `delegated` the default or auto-resolved mode.
- Runtime-owned sibling `agent --print` processes, one per specialist.
- End-to-end verification against a live Cursor CLI as a CI gate.
- Changing specialist rubric bodies, native-agent frontmatter vocabulary, or
  install linking of `~/.cursor/agents/`.
- Copilot or Junie support.

## Decomposition Rationale

One subtask. Prompt `/name` lines and workspace agent copies are one Cursor
parent launch. Splitting them leaves a commit that still cannot invoke the
files it copied, or names agents the isolated workspace cannot see.

## Next Path

If a live Cursor CLI parent still cannot spawn `/name` lanes after this
lands, a follow-up may decide whether sibling AgentRun processes are allowed
for Cursor. That is out of scope here.
