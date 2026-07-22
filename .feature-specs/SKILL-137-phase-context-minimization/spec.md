# SKILL-137 - Phase Context Minimization

## Mode

decomposed

## Intended Outcome

Every Skill Bill feature-task, feature-verification, and delegated code-review agent receives only the bounded, typed facts it needs. Complete upstream phase responses, raw agent output, complete diffs, telemetry payloads, prompts, logs, and unrelated run invariants remain durable private evidence and are not inherited by downstream model context. Repository state at an exact checkpoint remains authoritative for code, diff, and validation evidence.

## Overview

The runtime feature-task pipeline currently declares dependencies by producer phase and forwards each producer's complete validated output envelope. Its 64 KiB serialized briefing ceiling bounds prompt size but does not enforce least-context semantics, and the durable launch briefing retains the unprojected payload. Prose feature-task continuation has the same artifact-level pattern, a stale review-before-audit workflow definition, and artifacts that mix domain results with telemetry. The adjacent feature-verification workflow also passes whole evaluator results into otherwise independent evaluators.

Replace phase-level inheritance with manifest-like, consumer-specific handoff declarations. Each declaration identifies a source, a named versioned projection contract, visibility, size limits, and repository-checkpoint policy. The runtime validates the source artifact, projects only allowlisted fields, rejects missing, malformed, oversized, or stale inputs with typed errors, and persists the exact projected briefing delivered to the phase. Full phase outputs may remain in private diagnostic storage but are never prompt-visible by default.

Delegated code review has the same defect in its own shape. It fans one authoritative diff out to many specialist lanes, but `GovernedReviewLaunch.toLaunchEnvelope()` projects `assigned_hunks` as content-free hunk ids while only the parent packet carries `changed_hunks` with `content`. A specialist receives the identity of the change it must review without the change itself, so each delivery path improvises: the native broker inlines complete file bodies for every assigned path, and the prompt-driven path leaves acquisition undefined so every lane reads the same complete diff. Observed Kotlin specialist runs open near 21 KTok and close between 78 and 105 KTok, with the same diff admitted by five lanes and re-read with overlapping offsets inside single lanes. Each lane must receive its assigned hunk content and nothing else.

The target contracts include a pre-planning digest, executable plan, plan commitment, implementation receipt, audit clearance and repair request, review repair request, validation receipt, change receipt, commit request/receipt, PR request, and delegated-review lane launch. Fresh launches and resumed launches must assemble the same projection. Runtime, prose, feature-verification, and delegated-review surfaces must share the same semantic boundaries even when their execution mechanisms differ.

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
31. Focused contract, domain, application, persistence, runtime, prose continuation, verification, delegated review, goal-child, standalone, retry, resume, and end-to-end tests pass, followed by:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Repository diff and file state are authoritative for what changed; receipts are bounded producer claims and gate attestations, not substitutes for inspection.
- Contract YAML follows the runtime-contract recipe: Draft 2020-12 schema, pinned Kotlin version constant, parity test, typed invalid-schema error, loud-fail parse seams, and configuration-cache-friendly classpath copy.
- Handoff projections are closed-world. Open maps are allowed only at explicitly annotated serialization boundaries and must be produced by typed domain models rather than arbitrary agent fields.
- The runtime owns dependency selection, checkpoint validation, and projection. A phase agent cannot request extra prior artifacts or expand its prompt-visible scope.
- Compact references must be repository-relative paths, stable artifact ids, workflow ids, iteration ids, or content digests. They must not conceal raw prose or unbounded content.
- Delegated-review lane content is derived from the already-parsed authoritative `ReviewDiffEvidence`. No lane, provider path, or projection step recomputes a diff, resolves a replacement baseline, or widens the review revision.
- Existing audit non-progress, stable repair ids, review two-pass cap, goal-child isolation, immutable review base, feature decomposition, platform-pack routing, and add-on verification behavior remain intact.
- Add-on content is delivered only to manifest-declared receiving phase/agent assignments. Add-on identities may remain run-level state, but hydrated content is not unconditional prompt context.
- Agent-specific runtime behavior stays behind injectable strategies; no agent identity branching is added to the process runner.
- Update authored `content.md`, not generated `SKILL.md` wrappers or support pointers.
- Preserve unrelated working-tree changes. Do not run installer or uninstall flows inside goal continuation; the operator refreshes installs after the complete goal.

## Non-Goals

- Deleting private full phase evidence needed for diagnostics, schema retry analysis, or operator inspection.
- Removing acceptance criteria from phases that directly plan, implement, audit, review, or describe the delivered feature.
- Replacing code review, audit, validation, or feature-verification policy with a single combined evaluator.
- Embedding full diffs in durable workflow state; checkpoints and regenerated deterministic scopes are preferred.
- Adding a generic agent-controlled context retrieval API.
- Changing review severity taxonomy, audit test-exclusion policy, remediation pass caps, feature decomposition semantics, or platform-pack selection.
- Reducing review lane count, dropping required baseline lanes, or thinning specialist rubric substance to save context.
- Host-harness context that Skill Bill does not own, such as provider system prompts, tool schemas, project instruction files, and user memory injected into every subagent.
- Migrating arbitrary historical terminal workflows in place. Loud failure with an actionable restart or out-of-band migration path is acceptable where contract versions change.

## Validation Strategy

- Add schema and parity tests for every new or version-bumped contract.
- Add projection matrix tests for every forward and backward edge, including exact required and forbidden fields.
- Add checkpoint acceptance, staleness, retry, crash-resume, latest-iteration, and sibling-isolation tests.
- Add durable serialization tests proving private phase evidence and delivered projections remain separate across database round trips.
- Add prompt snapshots and byte-budget tests proving no full upstream envelope survives projection and no required structured value is truncated.
- Add prose fresh-launch/resume parity tests and stale-order rejection coverage.
- Add feature-verification evaluator independence and consolidated-receipt tests.
- Add delegated-review lane projection tests: assigned hunk content presence, cross-lane hunk absence, hunk-window evidence admission, complete-diff and unexpanded whole-file rejection, repeat-read rejection, and per-provider golden launch envelopes.
- Add telemetry privacy tests and golden-file updates.
- Run focused Gradle tests per subtask, then the complete repository gates in acceptance criterion 31.

## Next Path

Run `skill-bill goal SKILL-137`.
