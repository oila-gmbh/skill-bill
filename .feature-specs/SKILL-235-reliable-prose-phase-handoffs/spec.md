# SKILL-235 - reliable-prose-phase-handoffs

## Mode

decomposed

## Intended Outcome

Agents communicate engineering context through durable prose, without hand-authoring JSON envelopes, while the runtime owns serialization, attempt identity, explicit phase decisions, evidence, and resumable delivery. A completed implementation with a malformed or interrupted report publication recovers publication without repeating code changes. Standalone execution, shared goal preplanning, and concurrent subtask planning follow the same admission contract.

## Overview

Replace agent-authored serialization with one durable publication boundary, then integrate explicit phase decisions and runtime-owned evidence through finalization.

## Problem and Evidence

The 6 September 2026 review found an incomplete prose migration: producer prompts still require a final JSON envelope and examples ask for JSON inside value. Initial implement no longer enforces the old inner planning projection, but output admission still rejects outer-shape mistakes and the first rejection reaches the output-gate cap. A completion service exists, yet parent goal planning validates stdout separately, settlement overwrites on conflict, and audit recovery searches raw text for verdict keywords.

The local database held 26 sessions started 24 August–6 September: 11 completed, nine blocked, three paused, two stale, one without a completion state. All nine blocked at implement; eight blocked reasons identified output/schema gates and one identified computer-sleep process failure. Recent diagnostics include optional derived_notes arrays and array/object drift. This spans revisions and retries; it is not a controlled before/after or PostHog-wide rate.

The application is being refactored concurrently. Reconfirm each remaining seam at implementation time; acceptance is final behavior, not preservation of a historical bug or obsolete symbol location.

## Content and Control

Retain the conceptual object PhaseContent(value: String, prompt: String? = null) inside the runtime. The optional prompt is a subordinate handoff hint. It cannot select routing, grant permissions, or weaken validation. Recommended Markdown organization is writing guidance only.

Bind each producer to a runtime-issued attempt capability and artifact destination. Agents write ordinary prose with their normal file/text tool and invoke completion; runtime code serializes internal records. Support MCP/text publication and a bound CLI artifact route through the same admission service. Tool arguments can still fail: return a bounded actionable response and preserve publication state. Avoid requiring a large JSON string, copied IDs, or a structured final stdout response as fallback.

Use explicit phase-appropriate operations for complete, block, audit pass, and audit gaps. Findings are prose records with runtime-generated IDs and incremental dispositions. The runtime calculates outstanding work. The agent is not asked to regenerate a whole exact census after a long execution.

## Durable Lifecycle

Use draft, publication pending, and sealed/accepted states with explicit transition ownership. Resolve attempt identity, slot, repository, input artifact IDs, and worker fence outside model-authored text. Seal immutable content and decision; identical retry is a no-op returning its receipt and conflicting retry is a typed rejection.

If publication happens before child termination, retain a pending result until terminal process/repository reconciliation establishes its final evidence. Further mutations, truncated provider output, missing terminal evidence, or an expired worker cannot masquerade as a fresh accepted checkpoint. A genuine crash retains recovery data rather than inventing approval.

Persist immutable filesystem artifacts before committing references, with orphan recovery if the transaction does not commit. Where records share SQLite, phase settlement, ledger advancement, and outbox emission share a transaction/idempotent transition. Existing Git and PR side effects remain separate recoverable operations with receipts and remote-state reconciliation.

## Delivery and Recovery

Preplan produces a prose digest. Plan receives its exact digest artifact plus the plan phase's own instructions and spec. Implement receives the accepted plan; audit receives plan, implementation report, and repository evidence. Audit-gap remediation retains original planning context and reads the audit report. Consumers receive only declared upstream artifacts; no recursive copying of full phase history.

Full content is retained even when it exceeds a prompt budget. Deliver a bounded preview/index and an allowlisted read handle for the original; report missing or oversized storage separately from prompt delivery. Do not turn optional summaries into a second required gate. Preserve readable prose without embedding it as escaped JSON in the prompt.

Publication repair and engineering repair are different operations. After completed work, request only the absent artifact or decision, with production edits disabled. Retry state and budget survive restart and do not reset indefinitely. Actual semantic gaps return through existing remediation policy. Later edits invalidate proof for affected repository checkpoints; this feature does not require reopening every earlier plan or increasing review round caps.

## Execution Boundaries

| Producer/consumer | Content | Runtime authority |
| --- | --- | --- |
| Preplan to plan | Discovery, risks, decisions, unresolved questions | Run/slot identity and immutable producer reference |
| Plan to implement | Executable plan, constraints, validation intent | Spec and consumer scope |
| Implement to audit | Changes, deviations, unresolved work | Current repository evidence |
| Audit to review/remediation | Evidence and gap/fix narrative | Explicit checkpoint-bound pass/gaps decision |
| Review to verification/fix | Finding bodies and disposition explanations | IDs, census, checkpoint and remaining items |
| Build/validate to history/commit | Optional human explanation | Commands, exit outcomes, measured receipts |
| History/commit to PR | History and PR text | Observed file changes, SHA, branch, push and PR receipt |

## Decomposition

Two independently resumable release increments are justified by different product boundaries, not file count or layer order. Subtask 1 ships actual prose publication for preplan/plan/implement in both standalone and parent planning, plus settlement safety and decision-safety fixes. It leaves other producers on explicitly declared legacy transports and is a complete useful improvement.

Subtask 2 consumes that proven publication contract to migrate audit/review finding operations and narrative finalization, remove remaining agent serialization obligations, and finish evidence/census/telemetry integration. Both commits include their own production integration, migration, tests, and source/install changes. Neither is a test-only or unused-foundation checkpoint.

## Starting Points

Locate the current owners of FeatureTaskPhaseSettlementService, SqliteFeatureTaskPhaseSettlementRepository, FeatureTaskRuntimeRunLoopAttemptSettlement, GoalPlanningPhaseAttemptGate, FeatureTaskRuntimePhasePromptComposer, FeatureTaskRuntimePhaseProjectionShapes, ProsePhaseOutputRecover, provider AgentRun adapters, handoff projection declarations, review/finding receipt settlement, and runtime finalization. Follow their renamed owners after SKILL-233 rather than reintroducing old packages.

## Acceptance Ownership

Parent criteria 1–5, 8, and the shared compatibility/observability/verification parts of 10–12 land in subtask 1. Criterion 6's no-inferred-approval invariant also lands there as an immediate safety fix. Subtask 2 completes criteria 6–7 and 9 and the remaining producer-specific parts of 10–12; it must preserve every earlier criterion.

## Rollout

Pin protocol capabilities per attempt and record negotiated route. Do not migrate an actively owned attempt underneath its worker. New supported runs use the new prose path, while known legacy attempts remain explicit compatibility cases until safely resumed/migrated. An unsupported capability fails preflight with a repair action before expensive work begins. No silent downgrade to model-authored envelopes is allowed.

## Acceptance Criteria

1. Phase content is a nonblank UTF-8 prose/Markdown value with an optional nullable prompt/handoff hint. No JSON/YAML body schema, required headings, fenced-block grammar, receipt-shaped prose, or optional metadata shape gates agent-authored content. Missing, null, and blank hints normalize to absent; hints cannot override the spec, runtime policy, consumer scope, or user mandates.
2. Every agent-authored phase report uses one runtime-owned publication and admission component across standalone phases, goal-shared preplan, and per-subtask planning. Each supported provider has a preflighted transport: a scoped text tool or an ordinary artifact plus bound CLI publication. The model never supplies workflow/slot identity, attempt number, ownership generation, contract version, timestamp, digest, repository fingerprint, or a duplicate summary to complete publication.
3. Publication is scoped to the current run, planning slot/subtask, phase attempt, and worker ownership generation. Unknown, expired, cross-slot, cross-repository, and stale-owner operations reject with typed reasons. Identical retries return the original receipt; conflicting content or decisions cannot overwrite a sealed attempt.
4. Acknowledgement loss and restart around artifact storage, settlement, and workflow advancement preserve one accepted outcome. Artifact references and accepted completion/ledger/outbox changes are committed consistently; downstream phases never read a partial artifact or advance against an unsealed result. Publication followed by further mutations does not attest the earlier tree as the final tree.
5. Output publication and missing-decision repair have a durable budget separate from process retries and semantic remediation. After work finishes, publication failures retain the code, artifact, and input identity and resume only publication; they never automatically rerun implementation. Supported provider sessions resume in place; an unavailable session uses an explicitly scoped report-recovery operation with no authority to edit production code. Exhaustion is labeled publication recovery, not failed implementation.
6. Audit outcome is an explicit pass/report-gaps operation bound to the evaluated checkpoint. No free-text keyword search, status default, exit code, empty finding parse, or missing decision can synthesize approval. Negation such as 'not satisfied' without an explicit decision cannot become pass; missing/ambiguous decisions request decision-only recovery. A pass conflicting with explicitly recorded open-gap state rejects; arbitrary prose is not subjected to a new mandatory semantic-extraction gate.
7. Review findings and verification/fix dispositions retain stable runtime-owned identity and checkpoint provenance, with prose bodies and incremental acknowledgements. The runtime computes the expected census and requests only missing/invalid items. Valid entries survive a rejected batch item; unknown or unresolved items cannot default to addressed, rejected, or approved.
8. Every declared handoff delivers the exact accepted upstream artifact or an authorized retrieval reference with immutable provenance. Full content remains durable. Crossing a prompt budget yields a bounded preview/index plus scoped retrieval or a specific resource failure, never silent truncation, loss of gaps, fabricated summaries, or automatic re-execution of the producer.
9. Repository-derived changes, quality command outcomes, build/validation measurements, commit SHA, pushed state, and PR identity remain runtime-owned typed evidence. History and PR narrative use prose publication; agents do not recreate receipts, hashes, complete changed-path arrays, or reconciliation booleans as proof. Changed repository evidence invalidates affected clearance without blindly discarding reusable planning.
10. Protocol choice and installed/provider capabilities are recorded before execution. Supported legacy records remain explicitly readable or migrate through the repository's governed version/quarantine/recovery rules without silently reinterpreting an in-flight attempt or destroying its raw evidence. Source, rendered prompts, CLI/MCP capabilities, and schema versions agree.
11. Content-free telemetry distinguishes execution, publication, admission, semantic decision, and human-action failures; identifies protocol/runtime version, route, phase, and provider; and records publication retries, recovered outputs, stale/conflicting writes, and budget fallback. Status differentiates retained work awaiting publication from work awaiting implementation.
12. Real-boundary regression coverage proves prose acceptance, explicit decision handling, route parity, slot isolation, fenced/idempotent SQLite settlement, crash recovery, and evidence freshness. No operator block is caused solely by prose formatting or optional hint/notes shape, while unknown semantic results and real policy/store/process failures remain visible.

## Constraints

- Preserve the existing phase ordering, declared consumer edges, audit remediation ownership, bounded review-fix policy, goal-child versus parent PR ownership, and same_branch_commit_per_subtask finalization. Do not broaden the build route beyond its pack-declared compile/buildability command.
- Reuse existing settlement, workflow ownership, artifact, provider adapter, and outbox boundaries; do not add a generic workflow framework, another mandatory AI formatter, or a parallel persistence authority. Runtime-generated structured records remain strict.
- New or changed runtime contracts follow orchestration/contracts first, typed Kotlin versions/errors, parity coverage, and loud-fail parse seams. Treat human prose as content rather than configuration. Implement compatibility as an explicit boundary adapter.
- Read docs/code-principles.md and docs/skill-source-generation.md before affected implementation work. Change authored content.md and governed neutral sources; never commit generated SKILL.md, support pointers, or provider outputs. Run ./install.sh when prompt/source-generation changes require it.
- Integrate with the landed SKILL-233 ownership/package refactor and SKILL-234 cross-machine recovery behavior by responsibility, not obsolete filenames. Do not overwrite ongoing work, reset manifests, start workflows, or choose an implementation branch during preparation.
- Keep prompts, raw reports, artifacts, code, command output, and arbitrary failure text out of remote telemetry. Retrieval is allowlisted to the active consumer and run; artifact paths cannot become arbitrary filesystem-read capabilities.
- Preserve truthful uncertainty. A saved report is not semantic approval, a claimed fix is not validation proof, and process exit is not an audit verdict. Existing explicit operator mandates continue to govern external actions.

## Non-Goals

- Fixing unrelated VS Code polling, broad architecture cleanup, or every maintenance issue from the application review.
- Changing feature decomposition strategy, enabling delegated review by default, adding providers, or redesigning the selected quality gate.
- Replacing deterministic schema validation for manifests, settings, runtime-owned receipts, or durable identity with prose.
- Guaranteeing that model engineering judgment is correct, proving a global historical 80% block rate, or promising a measured reliability percentage from the small local sample.
- Launching implementation, running a goal, creating tracker issues, committing, pushing, or publishing a PR as part of spec preparation.

## Validation Strategy

Use the shared runtime's real manifest/phase contract validators and a small regression set at the affected boundaries. For implementation, follow bill-code-check and the routed pack's declared quality route; a build-selected goal child runs only the pack build gate and its prescribed cache-bypassing confirmation, with no substitute full suite. Tests should reproduce observed formatting failures and real lifecycle races: plain Markdown, optional null/array notes, audit negation, lost acknowledgement, stale takeover, conflicting duplicate, parent-plan concurrency, partial artifact, oversized prompt delivery, and changed evidence. Preserve both successful handoff and truthful non-success outcomes. Run provider-adapter contract fixtures and SQLite transaction tests where the selected validation route permits; report any unexecuted checks explicitly. Run ./install.sh for affected authored/generated prompt changes. Evaluate format-only blocks separately from semantic failures and false approvals; do not use block-rate improvement alone as proof of correctness.
