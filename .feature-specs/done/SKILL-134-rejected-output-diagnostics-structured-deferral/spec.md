# SKILL-134 Rejected Output Diagnostics and Structured Deferral

Status: ready

## Intended Outcome

Feature-task runtime failures caused by invalid agent output are fully diagnosable from durable, local evidence, and audit-remediation deferral is decided from structured contract fields rather than broad free-text matching.

Operators can retrieve the exact rejected response for a specific workflow, phase, and attempt without exposing it through telemetry, status output, PR content, or installed artifacts. Legitimate evidence such as “validation will verify integration” no longer triggers a false deferral failure, while genuinely missing, deferred, or unresolved repair items still fail loudly.

## Scope

- Persist exact rejected phase responses in a dedicated local diagnostic boundary.
- Associate each rejected response with workflow, phase, attempt, validation rule, JSON path when available, failure reason, agent/model metadata, timestamps, and payload size.
- Provide bounded CLI retrieval and lifecycle controls.
- Keep raw diagnostic content out of telemetry, normal workflow projections, status output, PR descriptions, and generated/install surfaces.
- Replace recursive free-text deferral detection with explicit structured remediation fields and schema validation.
- Preserve exact repair-item result equality, terminal outcome, dependency-order, evidence, and typed unresolvable-item gates.

## Acceptance Criteria

1. Every schema- or reconciliation-rejected phase response is persisted exactly once in a dedicated local diagnostic store before retry or terminal block, keyed by workflow ID, phase ID, attempt number, and a stable record ID.
2. A diagnostic record contains the exact raw response bytes plus validation rule ID, JSON pointer or field path when available, failure reason, executing agent/model metadata, timestamp, byte size, and response digest.
3. Exact raw responses are not embedded in `feature_task_workflows.artifacts_json`, telemetry events, goal/status/watch output, PR descriptions, logs by default, generated skill output, or install staging.
4. The diagnostic store uses restrictive local permissions, a configurable payload ceiling and retention policy, deterministic behavior when the ceiling is exceeded, and explicit deletion/cleanup support.
5. A CLI read surface retrieves rejected output by workflow with optional phase/attempt selectors, distinguishes absent, expired, oversized, and corrupt records through typed errors, and requires an explicit raw-output flag before printing response bodies.
6. Workflow retry, resume, abandonment, and terminal reconciliation preserve diagnostic identity without duplicating the same rejected attempt; cleanup never mutates authoritative workflow state.
7. Audit-gap remediation output has explicit structured representation for deferred or remaining repair work. Completed output requires that representation to be empty; blocked output identifies the exact unresolvable repair item.
8. Deferral validation uses structured fields, exact expected/result identifier equality, terminal outcomes, dependency ordering, and evidence requirements. It does not recursively scan summaries, verification text, result evidence, or other free-form strings for deferral phrases.
9. Legitimate statements including “full validation will verify integration,” “review can discover new defects,” and “a later audit confirms the repaired state” pass when all repair items are terminal and no structured deferred work exists.
10. Missing results, nonterminal outcomes, non-empty structured deferred work, mismatched identifiers, invalid dependency order, or a blocked item also reported terminal continue to fail loudly with stable rule IDs and actionable JSON paths.
11. Existing durable audit-repair state remains readable or fails through a documented contract-version migration boundary; incompatible records never silently default.
12. Tests cover exact persistence, deduplication, size and retention boundaries, permissions abstraction, CLI retrieval, privacy exclusions, structured deferral acceptance/rejection, standalone runtime, goal-child execution, retry, crash/resume, and telemetry/status non-leakage.

## Constraints

- Raw responses may contain source, paths, tool output, or secrets. Exact persistence is local-only and cannot be described as redacted.
- Workflow DB state remains authoritative for execution; rejected-output diagnostics are evidence, not a continuation or completion authority.
- Telemetry and compact workflow projections must remain bounded and raw-content-free.
- Runtime agent differences remain injectable strategies; do not branch on agent identity in the runner.
- New or changed runtime contracts follow schema/version parity, typed-error, classpath-copy, and loud-fail requirements.
- Preserve existing user work and manifest-driven platform behavior.

## Non-Goals

- Uploading raw rejected responses to remote telemetry.
- Treating persisted agent prose as proof that implementation succeeded.
- Removing exact repair-item equality, terminal result, evidence, dependency, or unresolvable-item checks.
- Automatically redacting an artifact that promises exact byte preservation.
- Exposing raw responses in default status or watch commands.

## Validation Strategy

- Add contract, application, persistence, CLI, privacy, and integration tests for the diagnostic store and retrieval surface.
- Add structured remediation schema and runtime-gate tests, including false-positive phrase regressions and genuine deferral rejection.
- Exercise standalone and goal-child retry/resume paths and prove rejected attempts are recorded once.
- Run `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Next Path

Run bill-feature on SKILL-134.
