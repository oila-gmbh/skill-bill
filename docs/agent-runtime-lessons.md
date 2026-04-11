# Agent Runtime Lessons

Hard-won behavioral knowledge about AI agent runtimes collected while building and debugging cross-agent skills. These are not documented in official docs — they come from production observation.

## Copilot CLI

### Custom agent name matching interferes with ad-hoc tasks

When predefined custom agent templates exist in `~/.copilot/agents/`, the `task` tool may match spawned tasks against agent names by keyword. A custom agent named `bill-code-review-resolve` caused ad-hoc `code-review` tasks to route incorrectly or hang — even though the task prompt never referenced that agent by name.

**Rule:** Only create custom agent templates for workflows that explicitly need them (e.g., feature-implement pipeline stages). Do not create custom agents for capabilities that should run as generic ad-hoc tasks (e.g., code review specialists).

### Background task agents cannot reliably call MCP tools

Delegated background agents spawned via `task` may not have access to MCP server tools. When a skill instructs a subagent to call an MCP tool (e.g., `import_review`, `triage_findings`), the agent starts, reads files, but never completes — it gets stuck at the MCP call with 0 completed turns.

**Rule:** Subagents should only read files and return structured text. The parent orchestrator owns the MCP lifecycle (import, triage, learnings resolution). Mark MCP-calling sections in skills as "top-level only" so delegated workers skip them.

### Heavy background agents may stall on certain CLI versions

Simple background tasks (e.g., fetch a URL, read a single file) complete reliably. Complex tasks that require multiple file reads, structured rubric application, and large output (e.g., code review specialists) can stall indefinitely — showing "Running" with 0 completed turns.

This was observed starting with Copilot CLI 1.0.24 (2026-04-10), coinciding with GitHub's [enforcement of new service reliability limits](https://github.blog/changelog/2026-04-10-enforcing-new-limits-and-retiring-opus-4-6-fast-from-copilot-pro/) targeting "high concurrency and intense usage" patterns. Parallel background agents are exactly that pattern — multiple concurrent model requests from the same session. When rate-limited (429), agents enter retry loops and show "Running" with 0 completed turns indefinitely.

A related bug was filed as [github/copilot-cli#2569](https://github.com/github/copilot-cli/issues/2569) on 2026-04-08, reporting unsolicited duplicate background agents from a single `task` call.

**Rule:** Run Copilot delegated review passes sequentially (one at a time), not in parallel. This keeps concurrent model requests to 2 (parent + 1 subagent) and avoids triggering rate-limit retry loops. Parallel delegation can be re-enabled if Copilot raises concurrency limits or fixes the retry behavior.

### Background agent failures are silent — no error propagation to parent

When a background agent hits a rate limit, crashes, or stalls, the failure is not reliably reported back to the parent thread. There is no structured error, no callback, and no `rate_limited` event. The parent just sees "still running" / "no result" indefinitely. This was confirmed by Copilot's own documentation review.

**Rule:** Never assume a delegated background agent will return — either with results or with an error. Use a watchdog pattern: poll status, detect lack of progress (e.g., 120 seconds with no new output), retry once, then fall back to inline. Treat "no progress" as an error condition, because you cannot rely on the runtime to tell you something went wrong.

### Abandoned background agents are not automatically cleaned up

When the parent times out waiting for delegated background agents and falls back to inline execution, the orphaned agents remain in Copilot's task list as "Running" indefinitely. They show "0 completed turns" and consume background task slots.

**Rule:** There is currently no programmatic way to cancel orphaned background agents from the parent. Users must manually cancel them via `/tasks` UI. Account for this when designing fallback behavior.

### Copilot context window is smaller than Claude Code

Copilot with GPT-5.4 has a smaller effective context window. Loading multiple supporting files (stack-routing, review-delegation, review-orchestrator, AGENTS.md) plus a large diff can fill the context before the review starts, causing a read-compact-reread loop.

**Rule:** Keep the number of supporting files a skill reads upfront minimal. Use the specialist-contract.md pattern (compressed subset of review-orchestrator.md) to reduce token overhead for delegated workers.

## General Cross-Agent

### Symlinks vs copies for skill installation

Symlinked skills propagate repo changes instantly to all agents. Copied skills (needed for prefix rewriting or content transformation) require re-running the installer after every change. During active development, the delay between editing a skill and testing it on an agent that uses copies is a significant friction point.

**Rule:** Prefer symlinks for skill installation. Avoid features that require copying and transforming skill files (e.g., custom prefixes) unless the tradeoff is explicitly worth it.

### Subagent delegation contract must be runtime-aware

Different runtimes handle subagent spawning differently:
- **Claude Code:** Task/subagent mechanism loads skill files directly — no predefined templates needed. MCP tools available in subagents.
- **Copilot CLI:** `task` tool spawns background agents. Predefined custom agents may interfere with ad-hoc tasks. MCP tools unreliable in background agents.
- **Codex / GLM:** Generic subagent mechanisms, behavior less tested.

**Rule:** Delegation contracts must have per-runtime sections. Do not assume capabilities that work on one runtime (e.g., MCP in subagents on Claude) work on another. When a runtime lacks a capability, the skill should degrade gracefully (e.g., run inline instead of delegating).

### Skill complexity scales differently across runtimes

A skill that works well on Claude Code (large context, reliable subagents, MCP access everywhere) may fail on Copilot (smaller context, unreliable background agents, no MCP in subagents). The same 50-skill taxonomy that produces excellent results on Claude can hit runtime limits on Copilot.

**Rule:** Test skills on the least capable target runtime, not just the most capable one. Design the skill contract so the happy path works everywhere, with progressive enhancement for runtimes that support it.
