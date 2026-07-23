# SKILL-140 - Runtime Stuck-State Hardening

## Mode

decomposed

## Intended Outcome

A feature-task run never becomes permanently stuck on output its own pipeline produced. Contract violations in agent output are surfaced at the producing phase's schema gate, where the bounded fix loop can hand the error back to a live agent. Durable records a downstream seam rejects are quarantined and regenerated in-band instead of wedging the run until an operator deletes rows. Crashed child processes reconcile to a resumable state automatically on the next startup. Manual database surgery becomes the exception for true corruption, not the routine unblocking ritual.

## Overview

Telemetry from 2026-07-10 through 2026-07-23 shows the recurring stuck-run classes: bounded planning projections rejected at a downstream launch seam after the producing phase already settled completed (including `produced_outputs.projection_kind` missing, and `task_id`/`description` regex failures), bounded fix-loop exhaustion on schema gates, subtasks finishing without a terminal workflow-store outcome after the child process died, and audit-repair non-progress. Fifteen manual `review-metrics.before-*.db` interventions in the same window map onto these classes almost one-to-one. A 2026-07-23 goal run (RDN-29) hit the same class twice on two different edges in one day: the plan launch seam rejected `preplanning_digest.rollout` written as an array instead of an object, and the audit launch seam rejected `implementation_receipt.deviations[0]` written as a free-text string instead of a `{ref, note}` object — each after its producing phase had settled completed, each durably blocking the run.

The structural causes, in dependency order:

1. **Asymmetric validation seams.** The phase-output schema deliberately leaves `produced_outputs` open, so a producing phase's schema gate accepts output that the next phase's launch seam later rejects against the strict planning-projections contract — observed on both the plan launch seam (preplan output) and the audit launch seam (implement output). The rejection lands after the producing phase is settled completed and its agent has exited; the run loop blocks durably and names the record for manual migration or deletion. A recoverable agent-output error becomes a permanent block. Prompt-side shape examples (SKILL-137) reduce how often agents emit the wrong shape, and agent-facing legacy contracts that teach conflicting shapes (the prose-mode implementation contract's free-text `plan_deviation_notes`) make some incidence unavoidable — only the producer gate turns a violation into a recoverable in-loop retry instead of a settled bad record.
2. **Strictness applied to lexical trivia.** Contract regexes such as `taskId` (`^[a-z][a-z0-9-]*$`) and `compactSummary` (no backticks, no tabs) reject agent output for spelling rather than shape, spending the bounded fix loop's three attempts on failures a deterministic canonicalization step would absorb.
3. **Test doubles that null out the enforced contract.** The runner test harness injects `NoopFeatureTaskRuntimePlanningProjectionValidator`, so the real Draft 2020-12 schema strictness never runs against run-loop behavior. The `implement` fixture in `FeatureTaskRuntimeRunnerTest` carries a `changed_files` key the canonical `implementation_receipt` variant rejects — proof the fixtures have drifted from the enforced contract while tests stay green.
4. **No in-band recovery for rejected durable records.** The documented recovery for loud-failed durable state is out-of-band row deletion or migration. The workflow state machine has no edge for "durable record failed validation → invalidate and re-run the phase that produced it."
5. **Terminal-state writes live only in the child.** When a child process dies before its workflow row reaches a terminal state (session limit, API 529, crash), the row wedges as non-terminal until leases are cleared by hand, even though a worker-lease table exists.

This feature closes each cause on the shared runtime surfaces: producer-side gating with a single shared validation function, canonicalization ahead of strict validation, integration tests that exercise the real validator plus fixture-schema parity in CI, a typed quarantine-and-regenerate backward edge for rejected durable records, and startup lease reconciliation for orphaned rows.

## Acceptance Criteria

1. Every bounded planning projection contract enforced at a consumer launch seam is also enforced at the producing phase's schema gate (preplan → `preplanning_digest`, plan → `executable_plan`, implement → `implementation_receipt`), inside the bounded fix loop, so a violating output is returned to the live agent as a schema-gate failure with the projection error text.
2. Producer-side and consumer-side enforcement share one validation function (`featureTaskRuntimePlanningProjectionFromEnvelope` with the same schema validator); no seam carries a second or divergent copy of the contract, and a parity test proves an output accepted by the producer gate is accepted at the launch seam.
3. The launch-seam check remains as defense for legacy durable records, but its rejection no longer terminates the run permanently: it routes through the quarantine-and-regenerate edge defined in criterion 8.
4. A deterministic canonicalization step runs before strict schema validation at the shared validation function: task ids are lowercased and kebab-cased, forbidden lexical characters in compact-summary fields are stripped or replaced, and surrounding whitespace is trimmed. Structural violations (missing required fields, wrong types, unknown keys, budget overflow, dependency cycles) still reject. Canonicalization is loss-bounded, applied identically at every seam, and covered by acceptance and rejection tests.
5. An integration test suite wires the real infra-fs planning-projection schema validator into the run-loop harness and proves: a schema-violating plan output is retried through the bounded fix loop and blocks only at the cap; a canonicalizable output is normalized and advances; a conforming output advances unchanged through plan, implement, and audit consumption.
6. Every canned phase-output fixture used by runner, goal-runner, and projection tests validates against the canonical contract schemas, enforced by a test that fails the build on fixture drift. The existing `implement` fixture drift (undeclared `changed_files` key) is corrected.
7. `NoopFeatureTaskRuntimePlanningProjectionValidator` remains only where projection enforcement is not the behavior under test; every test asserting gate, seam, or projection behavior uses the real validator.
8. When a launch seam rejects a durable upstream record (planning projection, handoff envelope, or workflow-state schema failure), the runtime takes a typed backward edge: the rejected record is preserved as private quarantined evidence, the producing phase's settled status is invalidated, and the producing phase re-enters under a bounded regeneration cap. Only cap exhaustion blocks the run durably, with a reason naming the quarantined record and the regeneration attempts.
9. The quarantine-and-regenerate edge reuses the existing backward-edge and cap machinery (loop ids, edge iterations, watermarks); regeneration attempts survive crash and resume without resetting the cap, mirroring the existing implement-fix cap semantics.
10. On runner startup and on goal-parent supervision, any workflow row that is non-terminal, whose worker lease is expired, and whose process is confirmed dead is transitioned automatically to a typed resumable state carrying the interruption reason. "Finished without a terminal workflow-store outcome" becomes a resumable condition, not a block requiring manual lease or row clearing.
11. Crash reconciliation is idempotent and self-healing in the same sense as the existing startup column ensures: it runs unconditionally, reconciling zero rows is a no-op, and it never touches rows with live leases or running processes.
12. Telemetry counts, per run: producer-gate projection rejections (by phase and reason class), canonicalization applications, quarantine-and-regenerate activations and outcomes, and crash reconciliations — without recording prompt text, plan bodies, or receipt contents.
13. Documentation is reconciled: the runtime-contract recipe and AGENTS.md describe producer-side enforcement, canonicalize-before-validate, the quarantine-and-regenerate edge, and automatic lease reconciliation; out-of-band row deletion is documented as the corruption fallback only.
14. Existing behavior is preserved: audit non-progress detection, stable repair ids, review pass caps, goal-child isolation, immutable review base, decomposition, and the loud-fail stance for genuinely unmigratable records all remain intact, proven by the existing suites.
15. Focused contract, domain, application, persistence, and end-to-end tests pass, followed by:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- One contract, one enforcement function: producer gates, launch seams, and integration tests must call the same shared validation path; no seam-local reimplementation of any projection rule.
- Canonicalization is deterministic and bounded: it may normalize case, separators, whitespace, and forbidden lexical characters; it must never synthesize missing fields, reorder collections, drop entries, or coerce types.
- The quarantine edge preserves evidence: a rejected durable record is retained as private diagnostic state, never deleted by the runtime.
- Regeneration and reconciliation reuse existing machinery (backward edges, caps, watermarks, worker leases); no parallel state machine is introduced.
- Contract YAML changes follow the runtime-contract recipe: Draft 2020-12 schema, pinned Kotlin version constant, parity test, typed invalid-schema error, loud-fail parse seams, configuration-cache-friendly classpath copy.
- Agent-specific runtime behavior stays behind injectable strategies; no agent identity branching is added to the process runner.
- Update authored `content.md`, not generated `SKILL.md` wrappers or support pointers.
- Preserve unrelated working-tree changes; do not run installer or uninstall flows inside goal continuation.

## Non-Goals

- Loosening structural strictness: unknown keys, missing required fields, budget overflows, and dependency cycles keep rejecting loudly.
- Automatic migration of arbitrary legacy schema versions; version bumps still loud-fail records the quarantine edge cannot regenerate (for example, a producing phase that no longer exists).
- Changing the prompt contracts, projection shapes, or budgets introduced by SKILL-137.
- Retrying external failures (session limits, provider 5xx, git divergence) beyond existing policy; those remain legitimate blocks.
- Reworking the goal decomposition architecture or the review pipeline beyond the seams named here.
