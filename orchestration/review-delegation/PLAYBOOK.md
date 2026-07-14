 ---
name: review-delegation
description: Single source of truth for agent-specific delegated code-review execution. Installed skills link to this via generated support pointers.
---

# Shared Review Delegation Contract

This is the canonical review-delegation contract. Installed skills consume it through generated sibling support pointers (e.g. `review-delegation.md` inside each staged skill directory), so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

## Shared Delegation Rules

- Every delegated specialist starts in a fresh conversation. Codex launches MUST set `fork_turns: "none"`; omission or an inherited-turn value is a governed contract failure.
- Project exactly one compact specialist contract, one applicable rubric, immutable review identifiers and revisions, assigned paths and hunks, relevant criteria references, matched rules, named evidence targets, broker identifiers, and a budget summary into each launch. The parent transcript, full phase briefing, unrelated criteria or rubrics, and unrelated diff are forbidden.
- Specialists use the bounded evidence surface and do not execute status, scope, stack, routing, or broad-diff discovery. Out-of-assignment access requires a nonblank reachability reason and consumes a bounded expansion.
- Payload, evidence, result, and expansion excess terminates the affected lane as `review_context_budget_exceeded`. Never truncate required evidence, skip a required lane, widen repository access, replace a reviewer, or substitute execution mode.
- Use this delegation contract only after the shared execution-mode contract selects `delegated` review.
- Before launching any routed layer or specialist, the parent prepares one compact, in-memory review-context packet. The packet is authoritative for the whole review run and contains the resolved scope and diff source, routing decision, applicable project guidance, relevant build/test facts, changed-file and hunk map, selected add-ons, ordered selected lanes with inclusion or exclusion reasons, immutable session/run identifiers, and one assignment per worker.
- Each worker assignment names the applicable routed skill, rubric, or sidecar; owns specific changed files and hunks; identifies only the direct dependencies that may be read; and states the evidence to verify. Give every worker the shared packet, its assignment, and only its applicable rubric.
- Workers must not repeat repository, scope, stack, routing, or guidance discovery. They may read their assigned changed code and direct dependencies only when needed to establish a reachable finding.
- Keep the packet factual and compact. Do not copy repository dumps, full project documentation, unrelated diffs, or unrelated specialist rubrics into it.
- Select specialist lanes using the routed pack's Diff-Signal Routing Table. Retain required baseline layers, add only signal-relevant specialists, and do not launch empty lanes or fan out to every declared area.
- Delegated review layers and specialist review passes must run as separate subagents on supported runtimes; do not collapse a delegated-required scope into a single inline review.
- Launch one delegated worker per routed stack-specific review skill or selected specialist review pass unless the current agent-specific section explicitly says otherwise.
- The parent review owns only the delegated workers it launched itself. If a delegated child review launches more workers internally, treat those nested workers as opaque implementation detail and consume only the child review's final merged result.
- The parent review that owns the final merged review output also owns `import_review` and `triage_findings`. Delegated workers must not call those telemetry tools themselves.
- When the runtime supports delegated-worker model inheritance, delegated workers should use the same model as the parent thread by default. Do not override the delegated-worker model unless the current runtime-specific section explicitly requires it.
- Every delegated worker must receive the exact review scope, changed files or diff source, relevant project guidance, the delegated skill name and rendered runtime instructions, the current `review_session_id` and `review_run_id` when they already exist, any applicable active learnings when they are available, any already-selected governed add-ons, the shared specialist contract from `specialist-contract.md`, and the rule that delegated workers must return telemetry-relevant metadata to the parent instead of calling telemetry tools directly.
- Wait for all delegated workers to finish, then merge and deduplicate findings by root cause, severity, and confidence.
- Track delegated workers by the ids returned when they are launched. Do not discover or poll delegated workers through broad global listing in the normal review path.
- If delegated review is required for the current scope and a supported runtime refuses or cannot start delegated workers, stop and report that delegated review is required for this scope but unavailable on the current runtime.
- If the current runtime is not documented below, stop and say delegated review is unsupported for delegated-required scopes.

Governed add-ons may narrow or enrich delegated review instructions only after the parent review has already resolved the dominant stack and selected the applicable add-ons.

## GitHub Copilot CLI

- Use the `task` tool.
- Launch one `code-review` agent per delegated review skill or specialist review pass.
- Use prompts that tell each subagent to follow the delegated skill's rendered runtime instructions as the primary rubric and apply `review-orchestrator.md` for shared output structure.
- Tell each delegated worker to return structured review output plus telemetry-relevant metadata to the parent and not to call `import_review` or `triage_findings`.
- Use background mode for parallel delegated passes, capture every returned `agent_id`, then wait on and read only those tracked ids before merging results in the parent review.
- Do not use `list_agents` to discover delegated workers during normal review execution. Reserve it for explicit recovery/debugging only.
- Do not call `read_agent` on nested workers launched by a delegated child review. Read only the child review agent you launched and let that child return its own merged result.
- For a single delegated pass, still use a subagent instead of reviewing inline.

## Claude Code

- Use the `Task` tool / subagent mechanism.
- Launch one subagent per delegated review skill or specialist review pass.
- Tell each subagent to read the sibling sidecar file `<delegated-skill-name>.md` co-located with `bill-code-review`'s `SKILL.md` as the primary rubric and return only meaningful findings. Do not use the Skill tool for the delegated review skill — it is an internal skill and is not listed.
- Tell each delegated worker to return structured review output plus telemetry-relevant metadata to the parent and not to call `import_review` or `triage_findings`.
- Run eligible delegated passes in parallel and merge the results in the parent review.
- Do not inline delegated review logic on Claude when Task/subagents are available.

## OpenAI Codex

- Explicitly request subagents.
- Spawn one subagent per delegated review skill or specialist review pass.
- Use the same model as the parent thread by default.
- Tell each subagent to read the sibling sidecar file `<delegated-skill-name>.md` co-located with `bill-code-review`'s `SKILL.md` and return structured review findings only. Do not use the Skill tool for the delegated review skill — it is an internal skill and is not listed.
- Tell each delegated worker to return structured review output plus telemetry-relevant metadata to the parent and not to call `import_review` or `triage_findings`.
- Wait for all subagents and merge their results in the parent review.
- Do not run delegated review passes inline.

## Opencode

Opencode delegated review is intentionally unsupported.

## Junie

Junie delegated review is intentionally unsupported.
