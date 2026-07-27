# SKILL-146: Least-context handoffs

## Intended Outcome

Every Skill Bill feature-task, feature-verification, and delegated code-review agent receives only the bounded, typed facts it needs. Complete upstream phase responses, raw agent output, complete diffs, telemetry payloads, prompts, logs, and unrelated run invariants remain durable private evidence and are not inherited by downstream model context. Repository state at an exact checkpoint remains authoritative for code, diff, and validation evidence.

## Overview

Replace phase-level inheritance with closed-world, consumer-specific handoff declarations. Each declaration identifies a source, a named versioned projection contract, visibility, collection and UTF-8 byte limits, and repository-checkpoint policy. The runtime validates source artifacts, projects allowlisted fields, rejects invalid or stale inputs with typed errors, and persists the exact projection delivered to each phase while retaining complete evidence only in private diagnostic storage.

Apply the same receipt and projection semantics to runtime and prose feature-task execution, feature verification, and delegated code review. Delegated specialists receive only their assigned hunk content and governed launch guidance; no provider route exposes a shared whole diff or permits diff rediscovery. Fresh, resumed, retried, remediating, goal-child, and standalone launches use the same semantic boundaries.

## Acceptance Criteria

1. A versioned phase-handoff contract declares consumer-specific inputs by source, projection contract id/version, prompt visibility, maximum item and byte budgets, and repository-checkpoint policy; consumers cannot select arbitrary upstream artifacts or fields at runtime.
2. Full producer phase envelopes, raw agent responses, prompts, tool-output bodies, command logs, source bodies, complete diffs, and telemetry payloads are never included in downstream prompt context unless a named contract explicitly defines a bounded field for that consumer.
3. Durable storage separates private phase evidence from prompt-visible handoff projections. A persisted phase launch records the exact bounded projection delivered to the agent and does not duplicate complete upstream payloads in `upstream_outputs_by_phase_id` or equivalent prompt-facing fields.
4. Missing, malformed, unsupported-version, oversized, unprojectable, or stale handoff inputs fail loudly through typed `ShellContentContractException` descendants or the established typed workflow-contract hierarchy, naming the consumer phase and offending projection.
5. Repository-derived evidence uses an explicit immutable checkpoint or deterministic comparison scope. A phase that requires current repository evidence rejects or refreshes a stale receipt according to its declared policy rather than silently trusting producer claims.
6. Run identity remains durable runtime state, while spec reference, feature size, acceptance criteria, mandates, review policy, and add-on content are projected only to phases that declare them. Commit/push, history, and other finalization phases do not receive unrelated planning or review context.
7. `preplan -> plan` supplies a bounded pre-planning digest containing affected boundaries, relevant patterns and decisions, risks, rollout information, validation strategy, and unresolved planning questions without forwarding the complete preplan envelope.
8. `plan -> implement` supplies a versioned executable plan containing stable ordered task ids, dependencies, criterion references, target paths or symbols, test obligations, constraints, and validation strategy without forwarding planning narration, decomposition presentation, or generic notes.
9. `plan + implement -> audit` supplies a bounded plan commitment, implementation receipt, and authoritative scoped repository diff/state. The receipt distinguishes completed task ids, changed paths, tests added or updated, tests executed, deviations, and unresolved items; it is treated as a claim rather than proof.
10. `audit -> review` supplies only acceptance criteria, exact review scope/checkpoint, and a compact audit clearance. Review does not receive the implementation response, implementation receipt, audit report, audit reasoning, or repair history unless a separately named field is required by review policy.
11. `review -> implement_fix` supplies only unresolved actionable Blocker findings, their stable ids and locations, expected outcomes, relevant criterion/task references, and the exact reviewed checkpoint. Approved findings, non-blocking findings, specialist narratives, raw review output, and telemetry are excluded.
12. `audit -> implement` remediation supplies the immutable executable plan, typed audit repair plan, prior terminal repair outcomes needed for idempotency, unresolved gap ids, and current repository checkpoint. It does not resend general preplanning narrative, the full audit response, or settled criteria.
13. `implement/audit -> validate` is replaced by a bounded validation request containing validation strategy, exact changed-path scope/checkpoint, and required checks. Validation independently inspects the repository and emits a validation receipt separate from telemetry.
14. `implement/validate -> write_history` supplies a compact change receipt, validation receipt, and diff-derived boundary candidates. It excludes implementation narration, complete validation output, raw test logs, and unrelated acceptance criteria.
15. `implement/validate/write_history -> commit_push` is replaced by a commit request containing explicit path inventory, required inclusions/exclusions, branch identity, and runtime-owned gate attestations. Gate completion is enforced by runtime state and is not represented by forwarding prior phase reports.
16. `implement/commit_push -> pr` is replaced by a PR request containing acceptance criteria, change receipt, validation summary, commit receipt, branch/base identity, and authoritative diff reference. It excludes raw implementation, validation, history, commit, review, and audit outputs.
17. Every backward edge and repeated phase selects the latest valid projection for the exact producing iteration/checkpoint. Resume, crash recovery, retry, and audit/review remediation cannot accidentally inherit an older full artifact or sibling-subtask context.
18. Prose feature-task guidance and durable workflow dependencies use the same receipt and projection semantics as runtime mode. The stale prose workflow definition is corrected to `implement -> audit -> review -> validate`, and audit no longer depends on `review_result`.
19. Prose continuation returns the same current-step projected context as a fresh launch. The default continuation path never asks the model to retrieve the complete durable artifact map; explicit private diagnostic inspection remains an operator/debug action rather than phase context.
20. Telemetry payloads and progress-write diagnostics are persisted through telemetry/progress stores and are not nested in domain artifacts such as review results or validation receipts that later phases consume.
21. `bill-feature-verify` keeps code review, unit-test value checking, completeness audit, and feature-flag audit independent: each receives criteria and the authoritative diff projection it needs, while the consolidated verdict receives compact typed evaluator receipts instead of complete evaluator outputs.
22. Context budgets are enforced before agent launch using UTF-8 byte counts and collection limits. Projection overflow fails or uses a contract-declared lossless reference; it never silently truncates JSON, drops required fields, or falls back to a full source artifact.
23. Durable compatibility is explicit: new contract versions intentionally loud-fail incompatible legacy phase/briefing records with an actionable migration or restart message; no legacy record is silently interpreted under the new least-context contract.
24. Telemetry measures projected input bytes and estimated tokens by phase, projection-contract failures, stale-checkpoint rejections, and private-versus-delivered byte counts without recording prompt text, diff bodies, source, or receipt contents.
25. Acceptance and rejection tests prove both presence and absence: every phase receives all required projected fields and none of the forbidden upstream envelopes, invariant fields, telemetry, raw outputs, or unrelated artifacts.
26. Runtime and prose documentation, architecture notes, governed `content.md`, schemas, constants, validators, persistence mappings, MCP/CLI continuation surfaces, fixtures, and golden files agree on the new boundaries. Generated wrappers and installed staging artifacts remain uncommitted.
27. A delegated review specialist receives its own assigned hunk content in the governed launch and never acquires a diff itself. Reading a diff file, diff artifact, scratch diff path, or complete-diff body is a forbidden rediscovery with a typed outcome, and no lane launch carries hunks outside its own assignment.
28. Brokered specialist evidence is hunk-window scoped. Complete file bodies reach a lane only through an authorized expansion carrying a nonblank reachability reason, and an already-admitted evidence target is not re-read through offsets, limits, or pagination.
29. Every provider delivery path — Claude Code prompt route, Codex native subagent route, and CLI route — produces the same lane projection. None passes a shared whole-diff path or artifact that resolves to the complete diff, and per-provider golden launch envelopes prove it.
30. A specialist does not read its rubric, specialist contract, or consumer contract from disk; the launch supplies them. The forbidden-rediscovery list, evidence-surface rules, and report structure have one authoritative copy, with any remaining restatement proven byte-identical by parity test.
31. Focused contract, domain, application, persistence, runtime, prose continuation, verification, delegated review, goal-child, standalone, retry, resume, and end-to-end tests pass, followed by `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Constraints

- Repository diff and file state are authoritative; receipts are bounded claims and gate attestations, not proof.
- Contract YAML follows the runtime-contract recipe: Draft 2020-12 schema, pinned Kotlin constant, parity test, typed invalid-schema error, loud-fail parse seams, and configuration-cache-friendly classpath copy.
- Handoff projections are closed-world. Open maps exist only at annotated serialization boundaries and originate from typed domain models.
- The runtime owns dependency selection, checkpoint validation, and projection; agents cannot request or widen prior context.
- Compact references are stable repository-relative paths, artifact/workflow/iteration ids, or content digests and cannot conceal unbounded prose.
- Delegated lane content derives from the already-parsed authoritative `ReviewDiffEvidence`; no lane recomputes or widens the diff.
- Preserve audit non-progress, stable repair ids, review two-pass cap, goal-child isolation, immutable review base, decomposition, pack routing, add-on verification, and injectable agent runtime strategies.
- Add-on content reaches only manifest-declared consumers.
- Update authored `content.md`, never generated `SKILL.md` wrappers or support pointers.
- Preserve unrelated working-tree changes. Do not run installer or uninstall flows during goal continuation.

## Non-Goals

- Deleting private full phase evidence needed for diagnostics.
- Removing acceptance criteria from phases that directly need them.
- Combining independent audit, review, validation, or verification evaluators.
- Embedding full diffs in durable workflow state.
- Adding agent-controlled context retrieval.
- Changing review severity, audit exclusions, repair caps, decomposition, or pack selection.
- Reducing review lane count or rubric substance.
- Controlling provider host-harness context.
- Migrating arbitrary historical terminal workflows in place.

## Validation Strategy

- Add schema/version parity, projection matrix, checkpoint, retry/resume, persistence, prompt snapshot, UTF-8 budget, prose parity, evaluator independence, delegated lane, provider golden, telemetry privacy, compatibility, and end-to-end tests.
- Run focused Gradle tests per subtask.
- Finish with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

