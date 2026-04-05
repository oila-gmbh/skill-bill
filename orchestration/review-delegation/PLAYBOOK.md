---
name: review-delegation
description: Maintainer-facing reference snapshot for agent-specific delegated code-review execution.
---

# Shared Review Delegation Snapshot

This maintainer-facing reference snapshot documents the agent-specific delegation contract used when authoring or updating installable code-review skills.

Runtime-facing skills consume this contract through sibling supporting files such as `review-delegation.md` inside each skill directory. Do not reference this repo-relative path directly from installable skills.

## Shared Delegation Rules

- Use this delegation contract only after the shared execution-mode contract selects `delegated` review.
- Delegated review layers and specialist review passes must run as separate subagents on supported runtimes; do not collapse a delegated-required scope into a single inline review.
- Launch one delegated worker per routed stack-specific review skill or selected specialist review pass unless the current agent-specific section explicitly says otherwise.
- The parent review owns only the delegated workers it launched itself. If a delegated child review launches more workers internally, treat those nested workers as opaque implementation detail and consume only the child review's final merged result.
- When the runtime supports delegated-worker model inheritance, delegated workers should use the same model as the parent thread by default. Do not override the delegated-worker model unless the current runtime-specific section explicitly requires it.
- Every delegated worker must receive the exact review scope, changed files or diff source, relevant project guidance, the delegated skill file path, the current `review_session_id` and `review_run_id` when they already exist, any applicable active learnings when they are available, and the shared specialist contract from `specialist-contract.md`.
- Wait for all delegated workers to finish, then merge and deduplicate findings by root cause, severity, and confidence.
- Track delegated workers by the ids returned when they are launched. Do not discover or poll delegated workers through broad global listing in the normal review path.
- If delegated review is required for the current scope and a supported runtime refuses or cannot start delegated workers, stop and report that delegated review is required for this scope but unavailable on the current runtime.
- If the current runtime is not documented below, stop and say delegated review is unsupported for delegated-required scopes.

## GitHub Copilot CLI

- Use the `task` tool.
- Launch one `code-review` agent per delegated review skill or specialist review pass.
- Use prompts that tell each subagent to read the delegated skill's `SKILL.md` as the primary rubric and apply `review-orchestrator.md` for shared output structure.
- Use background mode for parallel delegated passes, capture every returned `agent_id`, then wait on and read only those tracked ids before merging results in the parent review.
- Do not use `list_agents` to discover delegated workers during normal review execution. Reserve it for explicit recovery/debugging only.
- Do not call `read_agent` on nested workers launched by a delegated child review. Read only the child review agent you launched and let that child return its own merged result.
- For a single delegated pass, still use a subagent instead of reviewing inline.

## Claude Code

- Use the `Task` tool / subagent mechanism.
- Launch one subagent per delegated review skill or specialist review pass.
- Tell each subagent to read the delegated skill file as the primary rubric and return only meaningful findings.
- Run eligible delegated passes in parallel and merge the results in the parent review.
- Do not inline delegated review logic on Claude when Task/subagents are available.

## OpenAI Codex

- Explicitly request subagents.
- Spawn one subagent per delegated review skill or specialist review pass.
- Use the same model as the parent thread by default.
- Tell each subagent to read the delegated skill file and return structured review findings only.
- Wait for all subagents and merge their results in the parent review.
- Do not run delegated review passes inline.

## GLM

- Use the Task/subagent mechanism.
- Spawn one subagent per delegated review skill or specialist review pass.
- Provide the delegated skill file and `review-orchestrator.md` contract to each subagent.
- Run delegated passes in parallel when possible and merge the results in the parent review.
- Do not inline delegated review passes.
