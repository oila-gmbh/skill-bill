---
status: Superseded
issue_key: SKILL-125
source: inline user request informed by the SKILL-120 live review
superseded_by: SKILL-129
---

> **Superseded by SKILL-129** (2026-07-22): SKILL-129 ("super-optimized-code-review",
> merged via PR #234) names this spec as its direct motivation and implements every
> claim below. Verified against the current codebase:
>
> - Isolated Codex launch — `fork_turns: "none"` enforced and tested
>   (`AgentRunCommandBuilders.kt`, `requireProcessLaunch`).
> - Bounded specialist launch payload — `GovernedReviewLaunch` validates a launch
>   contains only owned paths/criteria/rules (`ReviewContextModels.kt`).
> - One-time parent discovery — single `ReviewContextPacket` with a stable digest;
>   `ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY` rendered into every launch.
> - Bounded evidence surface — `ReviewEvidenceBroker` is the sole measured,
>   budget-checked read path; expansions require authorization and digest match.
> - Token attribution — `ProviderTokenUsage`/`ReviewTreeUsage` support fresh/cached
>   separation, ownership, and tree aggregation without double-counting.
> - Named budget policy and typed failures — `ReviewContextBudgetPolicy` plus
>   `ReviewContextBudgetExceeded`/`ReviewBudgetRegression`, validated and evaluated.
> - Explicit `mode:inline`/`mode:delegated` authority over `mode:auto` eligibility —
>   `ReviewExecutionModePolicy.resolve()`, covered by a test proving inline overrides
>   a risky auto-delegate profile.
>
> All 5 SKILL-129 subtasks are `status: complete` in its decomposition manifest, with
> a further goal-wide remediation commit (`f1c6f229`) closing residual findings. No
> further preparation or implementation against this spec is needed. Kept for
> historical record of the original SKILL-120 incident and problem statement.

# SKILL-125: Enforce bounded context and token use for delegated code review

## Intended Outcome

Delegated code review keeps independent specialist reasoning without multiplying
the parent transcript, full feature briefing, repository discovery, or broad diff
reads across every lane. The review parent prepares one compact, measurable
context packet, gives each selected specialist only its assignment, and enforces
budgets that prevent a normal review from silently consuming hundreds of
thousands of fresh tokens.

Execution-mode selection also remains under caller control. Automatic mode may
use size and risk eligibility rules, but an explicit inline request is an
instruction to perform the complete routed review in the current agent context,
not an assertion that the scope satisfies automatic inline thresholds.

The change closes the end-to-end native-agent gap left by SKILL-121. SKILL-121
improved lane selection and the CLI parallel-runner prompt, but the observed
SKILL-120 Codex review still forked parent turns into every child, copied the full
18-criterion phase briefing, and allowed specialists to repeat `git status`, diff
discovery, and overlapping repository reads. The live review exceeded roughly
800,000 non-cached input-plus-output tokens before completion.

## Problem Statement

The current contract describes a shared review-context packet but does not make
that packet the only native specialist input or enforce its boundaries. In the
observed Codex path:

- the parent spawned specialists with inherited turns instead of isolated child
  context;
- every child received the full feature-task review briefing and acceptance
  criteria, regardless of lane ownership;
- each child rediscovered the base revision, changed files, and diff;
- specialists issued repeated broad `git diff`, `sed`, and `rg` reads with large
  output limits; and
- provider token data existed in session logs but was not attributed to the
  review parent and lanes as a review budget or regression signal.

Prompt guidance alone is insufficient. The runtime and generated native-agent
contracts need enforceable payload, discovery, evidence, and accounting seams.

## Decided Behaviour

### Explicit execution mode is authoritative

`mode:auto` owns automatic execution-mode selection and may use diff size,
changed-file count, stack layering, and high-risk signals to choose delegated
review. Those eligibility rules do not apply when the caller explicitly passes
`mode:inline`.

An explicit `mode:inline` request always runs the full routed review inline in
the current agent context, regardless of scope size, changed-line count,
mixed-risk signals, persistence or schema changes, concurrency or lifecycle
changes, or the number of signal-relevant specialist rubrics. The reviewer must
apply every required baseline and signal-relevant specialist rubric in-session.
It must not reject the request as inline-ineligible, require delegated review,
spawn specialist subagents, silently substitute `mode:auto` or
`mode:delegated`, or weaken finding, evidence, merge, and telemetry contracts.

`mode:delegated` remains an explicit instruction to use delegated execution.
Automatic thresholds remain relevant only to `mode:auto`. Bounded delegated
context and per-lane budgets apply whenever delegated execution is selected;
inline execution reports parent-session token usage without inventing delegated
lane accounting.

### Isolated specialist launch

Every delegated specialist starts with an isolated conversation. Native-agent
launch instructions must explicitly disable parent-turn inheritance where the
provider supports that control. For Codex this means spawning with
`fork_turns: "none"`; a non-empty inherited-turn selection is a contract failure
for governed delegated review.

The specialist receives exactly:

- the compact specialist contract;
- its one applicable lane rubric;
- the immutable review/run identifiers and resolved base/head revisions;
- its assigned changed files and hunks;
- only the acceptance-criteria references relevant to that assignment;
- applicable project rules reduced to the exact matched rules; and
- named direct dependencies or evidence targets the parent has already selected.

The full feature-task phase prompt, parent transcript, unrelated acceptance
criteria, complete repository guidance, other lane rubrics, and unrelated diff
hunks are excluded.

### Prepared evidence instead of repeated discovery

The parent resolves repository identity, base/head revisions, status, changed
paths, diff hunks, stack, pack, add-ons, and lane selection once. It produces an
immutable packet with a stable digest and one assignment per lane.

Specialists consume that packet through a bounded review-context/evidence
surface. They do not run their own scope-discovery commands or request the full
branch diff. A specialist may request an assigned file, assigned hunk, or named
direct dependency through the bounded surface. Requests outside the assignment
are rejected unless the specialist supplies a concrete reachability reason; an
approved expansion is recorded against the lane and becomes part of its budget.

The parent must not ask an LLM to recreate the packet from prose. Packet
construction, lane projection, digesting, size measurement, and assignment
validation are deterministic runtime behavior with typed failures.

### Budgets and loud-fail behaviour

Delegated review has explicit default budgets for:

- serialized parent packet bytes;
- serialized per-lane launch bytes;
- cumulative evidence bytes served to each lane;
- per-tool-result bytes returned to a lane;
- maximum assignment expansions; and
- provider-reported input, cached-input, output, and reasoning tokens when the
  provider exposes them.

Budget values live in one named policy object, are reported in help/status, and
may be overridden only through a validated repository configuration contract.
Exceeding a byte/read/expansion budget stops that lane with a typed
`review_context_budget_exceeded` result. It must not silently truncate evidence,
drop a required lane, widen to an unrestricted repository scan, or convert to a
different review mode.

Provider token totals are recorded even when they arrive only after a child
finishes. Cached input is reported separately and is never presented as fresh
work. A completed review that exceeds its configured token threshold is marked
as a budget regression in telemetry and the merged result; enforcement may be
pre-launch/live only for providers that expose a reliable live cancellation
seam. The contract must distinguish enforceable context/evidence budgets from
post-run provider accounting.

### Review result and observability

Status and terminal output report, for the parent and every selected lane:

- assignment and packet digest;
- launch payload bytes;
- evidence bytes and expansion count;
- input, cached-input, output, reasoning, and total tokens when available;
- fresh-token approximation (`input - cached_input + output`);
- terminal result and any budget exceeded; and
- aggregate review-tree totals without double-counting child sessions.

Telemetry and durable feature-task review artifacts preserve the same bounded
summary. Raw prompts, diffs, source contents, and sensitive repository text are
not persisted as telemetry.

## Scope

- Shared delegated-review packet and specialist-assignment models, validators,
  and typed budget errors.
- Code-review shell, review-delegation contract, generated native-agent prompts,
  and Codex launch instructions.
- Execution-mode parsing and routing so explicit `inline`, `delegated`, and
  automatic selection have distinct, testable semantics.
- The normal routed Kotlin review path used by feature-task runtime, plus the
  provider-neutral seam used by other maintained platform packs.
- Bounded repository evidence access for delegated specialists.
- Agent-run token extraction and parent/lane aggregation for review telemetry,
  status, and durable feature-task review artifacts.
- Focused contract tests and an end-to-end Codex fixture proving isolated launch,
  no repeated discovery, bounded lane reads, and correct token accounting.
- Documentation describing fresh versus cached token totals and operator budget
  configuration.

## Non-Goals

- Collapsing all specialist reasoning into the parent reviewer.
- Removing required baseline lanes or weakening specialist rubrics, severity,
  evidence, or merge rules.
- Treating provider cache accounting as a portable pre-launch enforcement seam.
- Persisting full review packets, source code, diffs, prompts, or tool output in
  telemetry.
- Optimizing feature-task plan, implementation, audit, or validation phases.
- Replacing the explicit parallel-review lane or changing its confirmation and
  provenance contract beyond applying the same per-lane budgets.

## Acceptance Criteria

1. Every governed delegated specialist receives an isolated child conversation. Codex launches use `fork_turns: "none"`, and tests fail if a governed review spawn inherits parent turns.
2. A specialist launch contains only the compact specialist contract, its applicable rubric, immutable review identifiers, assigned files/hunks, relevant criteria references, matched project rules, and named evidence targets. The full feature-task phase briefing, parent transcript, unrelated criteria, other rubrics, and unrelated diff are absent.
3. The review parent deterministically resolves repository identity, base/head revisions, status, changed paths/hunks, stack, pack, add-ons, selected lanes, and lane assignments once. The packet and every assignment have stable digests and loud-fail schema validation.
4. Delegated specialists do not execute repository/scope rediscovery or request the full branch diff. Assigned code and direct dependencies are served through a bounded evidence surface; out-of-assignment access requires a recorded reachability reason and consumes a bounded expansion.
5. One named review-context budget policy defines defaults for parent-packet bytes, per-lane launch bytes, cumulative lane evidence bytes, per-result bytes, assignment expansions, and provider token thresholds. Validated repository configuration may override those values; malformed, negative, or internally inconsistent values loud-fail before specialist launch.
6. Exceeding an enforceable payload, evidence, result-size, or expansion budget terminates the affected lane with typed `review_context_budget_exceeded` evidence. The runtime does not silently truncate required evidence, skip a required lane, widen repository access, or substitute another review mode.
7. Agent-run results capture provider-reported input, cached-input, output, reasoning, and total tokens when available. Review accounting reports fresh-token approximation separately, attributes usage to the parent and each lane, and aggregates the review tree without double-counting.
8. Providers without reliable live token cancellation use byte/read/expansion budgets for enforcement and mark post-run token-threshold excess as a budget regression. Help, status, merged review output, durable feature-task artifacts, and telemetry clearly distinguish enforced budgets from post-run accounting.
9. Review telemetry persists only bounded numeric/accounting metadata, lane identifiers, packet digests, and terminal budget outcomes. It does not persist raw prompts, diffs, source contents, project guidance, or tool output.
10. An end-to-end Codex review fixture for a medium Kotlin branch diff launches only routed lanes, proves `fork_turns: "none"`, proves no child repeats status/diff discovery, proves each child sees only its assignment, and verifies per-lane plus aggregate token/accounting output.
11. Regression tests reproduce the SKILL-120 failure shape: a long feature briefing and many acceptance criteria must not appear in specialist sessions, overlapping specialists must not independently fetch the same broad diff, and an intentionally excessive evidence request must terminate with the typed budget result.
12. Existing review correctness remains intact: required baseline lanes run, relevant specialists retain independent reasoning, findings preserve concrete evidence and provenance, merge/dedup semantics are unchanged, and delegated-worker failure remains loud.
13. Source skill and orchestration changes follow governed `content.md` and sidecar rules, generated outputs remain uncommitted, and `./install.sh` refreshes local staging after source-contract changes.
14. Maintainer validation passes: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.
15. Execution-mode contract tests prove that `mode:auto` alone applies inline eligibility thresholds; an explicit `mode:inline` runs the complete routed review in the current agent context for oversized and high-risk fixtures without refusing, delegating, or spawning specialists, while an explicit `mode:delegated` still requires delegated execution. Inline results preserve all required baseline and signal-relevant rubrics and report parent-session token usage without fabricated lane totals.

## Validation Strategy

Start with unit tests for packet projection, digest stability, assignment
validation, budget policy parsing, bounded evidence access, token aggregation,
and redaction. Add contract tests for rendered Codex/native-agent launch
instructions and provider-neutral specialist prompts. Add an integration fixture
that records child spawn arguments and repository commands, then assert isolated
launch, one-time parent discovery, no broad child diff commands, bounded evidence
reads, and exact parent/lane accounting.

Add execution-mode fixtures covering small, oversized, and high-risk diffs.
Assert that automatic mode retains threshold-based selection, explicit inline
always remains in-session while applying all routed rubrics, and explicit
delegated mode never falls back inline.

Run the complete maintainer gate after focused review/runtime tests and inspect a
real delegated Kotlin review status payload to confirm that fresh and cached
usage are separately understandable.

## Next Path

Closed — superseded by SKILL-129. Do not run `bill-feature` on this spec.
