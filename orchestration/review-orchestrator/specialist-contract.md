---
name: specialist-contract
description: Compact shared contract for delegated specialist review subagents. Contains only the rules and output format specialists need — orchestrator-only sections (scope, execution mode, learnings, delegation) are omitted to reduce per-subagent token cost.
---

# Shared Specialist Contract

This is the delegated-specialist subset of the full review-orchestrator contract. Orchestrators read the full `review-orchestrator.md`; delegated specialist subagents read this file instead.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

## Shared Contract For Every Specialist

- Review only changed code in the current PR or unit of work
- Surface only meaningful issues such as bugs, logic flaws, security risks, regression risks, or architectural breakage
- Flag newly introduced deprecated APIs or patterns when a supported alternative exists, or when deprecated usage is broad and unjustified
- Flag comments that only restate **what** the code does (paraphrasing adjacent code) as a maintainability finding — this is an explicit contract item, report it at `Minor`. Do not flag comments that explain **why**: a decision or non-obvious constraint the code cannot express is warranted and must be left alone
- Ignore style-only nits, formatting preferences, and naming bikeshedding — a comment that merely restates the code is a maintainability defect under the rule above, not a style nit
- Evidence is mandatory: include `file:line` and a short description
- Include the user-visible or externally observable consequence for each finding
- Report `Minor` findings, or `Medium`/`Low` confidence findings, only when they tie to an explicit contract violation, user-visible bug, regression risk, quality gate failure, or persisted learning
- Always report evidence-backed `Blocker` and `Major` findings. Do not suppress concrete correctness, security, persistence, lifecycle, testing, accessibility, or contract defects because they fall outside the low-value reporting threshold
- Severity is defined by observable consequence. Use `behavior_correctness` as the calibration reference: `Blocker` means the change breaks correctness or safety; `Major` means the change materially worsens behavior for a demonstrated scenario. Stylistic, speculative, or pre-existing observations are `Minor`, `Nit`, or suppressed by the SKILL-115 admission gate. The closed rating enum is `Blocker`, `Major`, `Minor`.
- Absent, thin, or incomplete test coverage is capped at `Minor`, never `Blocker` or `Major`, and is still subject to the reporting threshold above. A test that exists but asserts the wrong behavior, is tautological, or masks a production defect is a defect in the changed code and keeps normal severity calibration.
- Confidence: `High | Medium | Low`
- Keep each specialist review pass to at most 7 findings
- Include a minimal concrete fix for each finding

## Packet Consumer Contract

Every downstream routed layer and delegated specialist consumes the authoritative review packet exactly as launched. The packet is the single source of scope, routing, guidance, and measurement facts; a consumer that re-derives any of them produces a divergent review and breaks digest-backed attribution.

```authoritative-launch-contract
Consume only the immutable lane projection supplied at launch. Do not rediscover, widen, recompute, or read sibling-lane or parent review context.
```

Consumers must not rediscover any of the following:

- `review_status` — the packet already carries the resolved review status
- `review_scope` — the packet already carries the resolved review scope
- `base_head_revision_discovery` — base and head revisions are fixed by the packet
- `diff_recomputation` — never run a broad `git diff`; use the assigned hunks
- `dominant_stack_routing` — the dominant stack is already resolved
- `platform_pack_and_addon_resolution` — pack and add-on composition is already resolved
- `project_guidance_traversal` — do not walk AGENTS/project-guidance files; use the matched rules
- `learnings_resolution` — do not resolve learnings, including through MCP
- `build_test_fact_discovery` — build and test facts are supplied as packet facts
- `telemetry_ownership_determination` — telemetry ownership is decided by the orchestrator
- `broad_repository_search` — searches must stay within assigned paths or named dependencies
- `unrelated_rubric_read` — the single governed rubric in the launch is authoritative
- `rubric_rediscovery` — the launch-supplied rubric must not be reloaded from a native-agent or disk artifact
- `unassigned_file_access` — reads outside the assignment require an authorized expansion
- `unselected_mcp_tool_call` — only tools explicitly projected by the parent may be called
- `unscoped_shell_command` — shell commands outside the measured evidence surface are forbidden
- `diff_artifact_rediscovery` — complete-diff files and references are never part of a lane
- `scratch_path_rediscovery` — scratch review artifacts are outside the governed evidence surface
- `contract_rediscovery` — specialist and consumer contracts are supplied directly at launch
- `rules_rediscovery` — review rules are supplied directly at launch
- `repeated_evidence_read` — a normalized evidence target may be read only once

```evidence-surface-rules
Use only the measured evidence broker. Assigned evidence is limited to projected hunk windows. A complete-file expansion requires a launch-authorized record with a nonblank reachability reason. Each normalized evidence target may be read once.
```

## Shared Report Structure

Section 1 summary must include `Review session ID: <review-session-id>`.
Section 1 summary must include `Review run ID: <review-run-id>`.
Section 1 summary must include `Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>`.
Section 1 summary must include `Execution mode: inline | delegated`.
Section 1 summary must include `Applied learnings: none | <learning references>`.

Generate one review session id per top-level review using the format `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`). If a parent reviewer already passed a `review_session_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same session id across the summary, parent-review handoff, and any learnings-resolution workflow for the current review lifecycle.

Generate one review run id per concrete review output using the format `rvw-YYYYMMDD-HHMMSS-XXXX` where `XXXX` is a random 4-character alphanumeric suffix for uniqueness (e.g. `rvw-20260405-143022-b2e1`). If a parent reviewer already passed a `review_run_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same run id across the summary, the risk register, and any parent-review handoff or follow-up feedback workflow for the current review output.

After Section 1 in a stack-specific review skill, use:

- `### 2. Risk Register`
- `### 3. Action Items (Max 10, prioritized)`
- `### 4. Verdict`

Every finding in `### 2. Risk Register` must use this authoritative machine-readable bullet format:

```report-structure
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings. The bullet format above is required for downstream tooling (triage, telemetry, stats) to parse findings correctly.

- Severity must be one of: `Blocker`, `Major`, `Minor`
- Confidence must be one of: `High`, `Medium`, `Low`
- Finding ids must be unique within the current review run and stable enough for follow-up feedback or fix requests in the same workflow
- Assign finding ids sequentially in risk-register order using `F-001`, `F-002`, `F-003`, and so on
- A worker with no findings must return exactly `NO_FINDINGS`; an empty response is an incomplete result.

## Lane-Specific Consequence Examples

The lanes with the widest observed Major-to-Blocker spread benefit from
explicit examples distinguishing a material defect from an observation:

- **ux-accessibility** (observed 38:1 Major-to-Blocker spread):
  - Material defect (Major): A change that removes semantic markup, breaks
    keyboard navigation flow, or drops an ARIA relationship such that a
    demonstrated assistive-technology user cannot complete the task.
  - Observation (Minor/Nit or admission-gate suppressed): Missing or
    suboptimal ARIA labels where the control remains operable, or color-contrast
    findings below the AAA threshold without a demonstrated user failure.

- **data_persistence** (observed 20:1 Major-to-Blocker spread):
  - Material defect (Major): A change that introduces lost-update windows,
    violates isolation guarantees under a demonstrated concurrent scenario, or
    drops durability constraints such that committed data may be lost.
  - Observation (Minor/Nit or admission-gate suppressed): Cosmetic query-plan
    concerns where correctness and durability guarantees hold, or logging that
    mentions persistence without a concrete failure mode.
