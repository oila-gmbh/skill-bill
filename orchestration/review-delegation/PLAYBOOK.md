---
name: review-delegation
description: Maintainer-facing reference snapshot for agent-specific delegated code-review execution.
---

# Shared Review Delegation Snapshot

This maintainer-facing reference snapshot documents the agent-specific delegation contract used when authoring or updating installable code-review skills.

Runtime-facing skills consume this contract through sibling supporting files such as `review-delegation.md` inside each skill directory. Do not reference this repo-relative path directly from installable skills.

## Shared Delegation Rules

- Delegated review layers and specialist review passes must run as separate subagents on supported runtimes; do not collapse them into a single inline review.
- Launch one delegated worker per routed stack-specific review skill or selected specialist review pass unless the current agent-specific section explicitly says otherwise.
- Every delegated worker must receive the exact review scope, changed files or diff source, relevant project guidance, the delegated skill file path, and the shared contract from `review-orchestrator.md`.
- Wait for all delegated workers to finish, then merge and deduplicate findings by root cause, severity, and confidence.
- If a supported runtime refuses or cannot start delegated workers, stop and report a delegation failure instead of silently falling back to inline review.
- If the current runtime is not documented below, stop and say guaranteed delegated review execution is unsupported.

## GitHub Copilot CLI

- Use the `task` tool.
- Launch one `code-review` agent per delegated review skill or specialist review pass.
- Use prompts that tell each subagent to read the delegated skill's `SKILL.md` as the primary rubric and apply `review-orchestrator.md` for shared output structure.
- Use background mode for parallel delegated passes, then read all results and merge them in the parent review.
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
- Tell each subagent to read the delegated skill file and return structured review findings only.
- Wait for all subagents and merge their results in the parent review.
- Do not run delegated review passes inline.

## GLM

- Use the Task/subagent mechanism.
- Spawn one subagent per delegated review skill or specialist review pass.
- Provide the delegated skill file and `review-orchestrator.md` contract to each subagent.
- Run delegated passes in parallel when possible and merge the results in the parent review.
- Do not inline delegated review passes.
