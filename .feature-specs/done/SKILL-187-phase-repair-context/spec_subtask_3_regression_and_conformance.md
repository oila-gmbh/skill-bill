# SKILL-187 Subtask 3 — Regression and conformance coverage

## Scope

Add boundary-focused tests for the corrective-repair context and the v1
phase-output pipeline. Tests must use synthetic sentinel responses that make
the information flow observable without copying any real rejected payload,
secret, prompt, source body, or local database path.

The suite must separate the one newly authorized surface — the next
corrective-repair prompt — from every public/operator surface that remains
payload-free.

## Acceptance Criteria

1. A synthetic audit response with a missing closing delimiter is accepted by
   the existing syntax-repair engine when the candidate is unambiguous, and a
   subsequent schema rejection receives the exact synthetically captured
   response plus the payload-free root-field constraint.
2. A synthetic audit response with `verdict` nested under `produced_outputs`
   causes the corrective prompt to show the prior response and the required
   root-level shape; a corrected response with root-level `verdict` advances
   through normal validation.
3. A synthetic audit response with an unauthorized observation enum reaches
   corrective retry with the exact prior response and a payload-free enum
   constraint; the corrected enum advances.
4. A synthetic audit response with a semicolon-joined or oversized
   `artifact_ref` reaches corrective retry with the exact prior response and
   bounded-reference guidance; a corrected bounded reference advances.
5. JSON and conservative flow-YAML responses exercise the same context
   contract. Unsupported YAML repair remains a rejection and receives the
   bounded fallback rather than guessed structural edits.
6. The first launch, schema-valid retryable-terminal launch, incomplete-work
   continuation launch, and phase-mismatched launch do not contain the raw
   repair section. Only the matching schema-invalid corrective launch does.
7. Truncated, oversized, unavailable, Unicode, Markdown-fenced,
   instruction-like, delimiter-heavy, and duplicate-key responses each follow
   the documented context state without silent truncation or unsafe prompt
   injection.
8. The private rejected-output diagnostic retains the synthetic raw bytes and
   value-bearing reason for rejected attempts, while blocked reports,
   durable phase rows, status projections, telemetry, and normal logs contain
   neither the sentinel body nor the value-bearing reason.
9. Attempt, phase, repair-turn, evidence-generation, original digest, and
   repaired digest remain correlated when multiple corrections occur in one
   phase and when a later phase retries.
10. Exhausting the bounded correction loop still produces the existing
    payload-free `INVALID_OUTPUT` block and does not include the raw response
    in any operator-facing result.
11. A throwing telemetry/observer path and a diagnostic persistence
    degradation path do not change the context availability classification,
    completion/block outcome, or privacy guarantees.
12. The focused tests are behavior assertions at validator, prompt,
    persistence, and report boundaries; they do not verify mock interaction
    order or duplicate implementation steps.
13. The runtime check suite passes.

## Non-Goals

- Do not duplicate every validator enum or schema field as a separate test
  when one representative behavior test covers the rule.
- Do not assert private implementation call order.
- Do not test provider-specific process details in the phase-output contract
  suite.
- Do not weaken existing privacy assertions for public surfaces merely to make
  the authorized repair prompt test easier.

## Dependency Notes

Depends on subtasks 1 and 2. Update
`FeatureTaskRuntimeRejectionConstraintPrivacyTest` and
`RejectedOutputPrivacyAssertions` to express the new split:

- repair prompt: exact prior response allowed only when the context says it is
  exact and the phase/attempt matches;
- every other surface: raw response and value-bearing reason forbidden.

Reuse existing structural-repair, schema-validator, runner, and diagnostic
fixtures where they already represent the boundary. Add only cases that catch
the information-loss regression or the privacy/size failure.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Run focused suites during development:

```bash
./gradlew :runtime-infra-fs:test --tests '*FeatureTaskRuntimePhaseOutputStructuralRepairTest' \
  --tests '*FeatureTaskRuntimePhaseOutputSchemaValidatorTest'
./gradlew :runtime-application:test --tests '*FeatureTaskRuntimeRunnerTest' \
  --tests '*FeatureTaskRuntimePhasePromptComposerTest' \
  --tests '*FeatureTaskRuntimeRejectionConstraintPrivacyTest'
```

## Next Path

After this subtask passes, execute the end-to-end synthetic SKILL-16
scenario from the parent spec and record the result through the normal feature
verification path. Do not include the real private diagnostic payload in
history, telemetry, or the feature bundle.
