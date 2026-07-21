 ---
name: review-delegation
description: Single source of truth for agent-specific delegated code-review execution. Installed skills link to this via generated support pointers.
---

# Shared Review Delegation Contract

This is the canonical review-delegation contract. Installed skills consume it through generated sibling support pointers (e.g. `review-delegation.md` inside each staged skill directory), so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

## Shared Delegation Rules

- Every delegated specialist starts in a fresh conversation. Native Codex launches MUST set `fork_turns: "none"`; Codex CLI launches MUST use a fresh process receiving only the governed compact assignment. Other providers retain their existing launch behavior.
- Project exactly one compact specialist contract, one applicable rubric, immutable review identifiers and revisions, assigned paths and hunks, relevant criteria references, matched rules, named evidence targets, broker identifiers, and a budget summary into each launch. The parent transcript, full phase briefing, unrelated criteria or rubrics, and unrelated diff are forbidden.
- Specialists use the bounded evidence surface and do not execute status, scope, stack, routing, or broad-diff discovery. Out-of-assignment access requires a nonblank reachability reason and consumes a bounded expansion.
- Payload, evidence, result, and expansion excess terminates the affected lane as `review_context_budget_exceeded`. Never truncate required evidence, skip a required lane, widen repository access, replace a reviewer, or substitute execution mode.
- Use this delegation contract only after the shared execution-mode contract selects `delegated` review.
- Before launching any routed layer or specialist, the parent prepares one compact, in-memory review-context packet. The packet is authoritative for the whole review run and contains the resolved scope and diff source, routing decision, applicable project guidance, relevant build/test facts, changed-file and hunk map, selected add-ons, ordered selected lanes with inclusion or exclusion reasons, immutable session/run identifiers, and one assignment per worker.
- Each worker assignment names its applicable embedded rubric, owns specific changed files and hunks, identifies only the direct dependencies that may be read, and states the evidence to verify. The validated assignment is the launch authority; do not give a worker the shared packet.
- Workers must not repeat repository, scope, stack, routing, or guidance discovery. They may read their assigned changed code and direct dependencies only when needed to establish a reachable finding.
- Keep the packet factual and compact. Do not copy repository dumps, full project documentation, unrelated diffs, or unrelated specialist rubrics into it.
- Build one deterministic launch plan before starting workers. Recursively flatten required baseline layers into direct specialist lanes, apply the nearest pack's area override, retain signal-relevant lanes and add-ons, and drop empty or duplicate assignments.
- Launch only the specialists in that flattened plan. Never launch a routed baseline orchestrator as a nested worker.
- Launch one delegated worker per routed stack-specific review skill or selected specialist review pass unless the current agent-specific section explicitly says otherwise.
- The parent review owns every worker in the flattened launch plan and preserves each lane's composition-chain attribution through merge and deduplication.
- The parent review that owns the final merged review output also owns `import_review` and `triage_findings`. Delegated workers must not call those telemetry tools themselves.
- When the runtime supports delegated-worker model inheritance, delegated workers should use the same model as the parent thread by default. Do not override the delegated-worker model unless the current runtime-specific section explicitly requires it.
- Every delegated worker receives only the broker projection from its validated assignment. Scope, raw diff, guidance bodies, learnings, add-ons, runtime ceremony, and telemetry ownership stay in the authoritative parent packet and are not projected.
- Wait for all delegated workers to finish, then merge and deduplicate findings by root cause, severity, and confidence.
- Track delegated workers by the ids returned when they are launched. Do not discover or poll delegated workers through broad global listing in the normal review path.
- If delegated review is required for the current scope and a supported runtime refuses or cannot start delegated workers, stop and report that delegated review is required for this scope but unavailable on the current runtime.
- If the current runtime is not documented below, stop and say delegated review is unsupported for delegated-required scopes.

Governed add-ons may narrow or enrich delegated review instructions only after the parent review has already resolved the dominant stack and selected the applicable add-ons.

## GitHub Copilot CLI

- Use the `task` tool.
- Launch one `code-review` agent per delegated review skill or specialist review pass.
- Use prompts that tell each subagent to follow the delegated skill's rendered runtime instructions as the primary rubric and apply `review-orchestrator.md` for shared output structure.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry and metadata are not part of the worker projection.
- Use background mode for parallel delegated passes, capture every returned `agent_id`, then wait on and read only those tracked ids before merging results in the parent review.
- Do not use `list_agents` to discover delegated workers during normal review execution. Reserve it for explicit recovery/debugging only.
- Do not call `read_agent` on nested workers launched by a delegated child review. Read only the child review agent you launched and let that child return its own merged result.
- For a single delegated pass, still use a subagent instead of reviewing inline.

## Claude Code

- Use the `Task` tool / subagent mechanism.
- Launch one subagent per delegated review skill or specialist review pass.
- The installed native agent's embedded governed rubric is authoritative. Do not tell the worker to read a sibling rubric sidecar.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry and metadata are not part of the worker projection.
- Run eligible delegated passes in parallel and merge the results in the parent review.
- Do not inline delegated review logic on Claude when Task/subagents are available.

## OpenAI Codex

- Explicitly request subagents.
- Spawn one subagent per delegated review skill or specialist review pass.
- Use the same model as the parent thread by default.
- The installed native agent's embedded governed rubric is authoritative. Do not tell the worker to read a sibling rubric sidecar.
- Tell each delegated worker to return only its structured findings. Parent-owned telemetry and metadata are not part of the worker projection.
- Wait for all subagents and merge their results in the parent review.
- Do not run delegated review passes inline.

## Opencode

Opencode delegated review is intentionally unsupported.

## Junie

Junie delegated review is intentionally unsupported.
