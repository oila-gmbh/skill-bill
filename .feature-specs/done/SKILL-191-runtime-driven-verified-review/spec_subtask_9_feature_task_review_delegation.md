# SKILL-191 · Subtask 9 — Feature-task review phase delegation

## Scope

Make the feature-task runtime's review phase delegate to the same driver, so both
entry points share one implementation and one set of stage records.

Today `PHASE_REVIEW` is an agent phase carrying a prose directive
(`FeatureTaskRuntimePhasePromptDirectives.kt:348`) that tells an agent to review
against the acceptance criteria and emit `produced_outputs.findings`. Replace that
with runtime-owned execution, following the precedent already set by the validate
phase: the runtime runs the gate and hands the agent a bounded projection, rather
than instructing the agent to run it.

The phase becomes: the runtime invokes the review driver with the phase's resolved
review scope, the durable child-owned diff, the resolved `code_review_mode`, and the
governed spec path — which feature-task always has, so adjudication always runs here.
The driver returns the assembled register with verdicts, and the runtime records
`produced_outputs.findings` and `review_run_id` itself.

**The supplied diff stays authoritative.** The driver must not resolve `--scope branch`,
`origin/main...HEAD`, a merge base, or a rediscovered baseline when the caller supplied
a child-owned delta. This rule exists today and must survive the move.

**Selected agent add-ons stay immutable.** The labelled `Selected agent add-ons`
section a governed feature caller supplies is copied verbatim into every stage launch,
preserving order, provenance, digest, delimiters, and the precedence guard.

**Acceptance-criteria delivery changes shape, not availability.** The review phase no
longer needs `ACCEPTANCE_CRITERIA` rendered into a prompt directive, because criteria
now reach adjudication through the spec intent projection. Adjust
`FeatureTaskRuntimeRunInvariantPromptAllowlist` only if the phase stops rendering them,
and keep policy mandates reaching the phases they govern.

**Audit stays the criterion-gap authority.** This subtask must not let review start
reporting unsatisfied criteria. Review reports defects and `spec_deviation`; audit
reports criterion gaps. Two authorities over criterion status would reopen the
audit-loop evidence-bar problem.

## Acceptance Criteria

1. The feature-task review phase executes through the same driver as the standalone entry, and the prose review directive no longer instructs an agent to perform the review.
2. The runtime records `produced_outputs.findings` and `review_run_id` from the driver's result rather than from an agent's reported output.
3. Both entry points produce identical stage records — verdicts, boundaries, and spec projection reference — for the same delta and the same spec.
4. A caller-supplied child-owned diff stays authoritative: no stage resolves `--scope branch`, `origin/main...HEAD`, a merge base, or a rediscovered baseline.
5. Adjudication always runs for a feature-task review, since the governed spec path is always supplied.
6. A supplied `Selected agent add-ons` section is copied verbatim into every stage launch, preserving order, provenance, digest, delimiters, and the precedence guard.
7. Review does not report unsatisfied acceptance criteria; criterion-gap detection remains exclusive to the audit phase.
8. `context:feature-remediation` passes continue to bound scope to the remediation delta and emit `blocker_dispositions` informed by stage verdicts.
9. The resolved `code_review_mode` continues to flow from run preparation and goal continuation unchanged, and a continuation with a conflicting mode still fails as it does today.

## Non-Goals

- Changing phase ordering, the audit phase, the validate gate, or the remediation loop's
  control flow.
- Changing `code_review_mode` resolution, its continuation-conflict rules, or its
  durable storage.
- Changing the goal decomposition or subtask review-state schemas beyond the verdict
  fields subtask 6 introduced.
- Removing the review phase's durable identity or its checkpoint semantics.

## Dependency Notes

Depends on subtask 6 for verdict-aware assembly and subtask 8 for the driver entry.
This is the last subtask; it closes parity between the two entry points.

## Validation Strategy

- One parity test that both entry points produce the same stage records for the same
  delta and spec. This is the criterion that proves the two paths did not silently
  fork, which is the main risk of the whole feature.
- One test that a supplied child-owned diff is not replaced by a rediscovered scope,
  since scope substitution here silently reviews the wrong code.
- One test that review reports no criterion gaps, guarding the audit boundary.
- One test that add-on sections survive verbatim into a stage launch.

## Next Path

Feature complete. Run `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
`npx --yes agnix --strict .`, and `scripts/validate_agent_configs`, then reconcile this
spec set to its final state.
