# SKILL-187 — Give corrective phase retries the rejected output and an actionable repair context

## Context

The v1 runtime already has two distinct output-correction mechanisms:

1. `FeatureTaskRuntimePhaseOutputStructuralRepair` performs bounded, syntax-only
   JSON/YAML repair. It may add one missing closing delimiter or remove an
   unambiguous extra closing delimiter, then re-runs strict parsing and the
   phase schema validator.
2. `FeatureTaskRuntimeRunLoop` performs a bounded corrective re-spawn when the
   repaired or original document is still invalid at the phase-specific
   schema/semantic boundary. It records the rejected response in the private
   rejected-output diagnostic store and asks the agent for a corrected response.

The second mechanism does not currently receive the rejected response. The
`PriorAttemptCorrection` carried into
`FeatureTaskRuntimePhasePromptComposer` contains the payload-free retry reason
only. The prompt contains the output contract and, when available, a
payload-free schema constraint, but not the exact malformed or semantically
invalid envelope the agent must repair.

This is an information loss at the corrective-retry boundary:

- the output validator has the original response text;
- `gateOutput` has the decoded response and captured bytes;
- `recordRejectedOutput` retains the response as private diagnostic evidence;
- `schemaInvalidAttempt` discards the response before creating
  `PriorAttemptCorrection`;
- the next foreground launch therefore has to reconstruct the failed output
  from a reason such as a path and rule alone.

The missing context matters for errors that syntax repair must not guess. A
delimiter repair can make a document parse while leaving a field in the wrong
object. Moving `verdict` from `produced_outputs` to the root, correcting an
invalid `artifact_ref`, or changing an unauthorized evidence enum requires the
agent to see what it actually emitted.

### SKILL-16 incident

The v1 runtime blocked the SKILL-16 feature-task workflow
`wftr-20260811-155022-i1sy` in the `audit` phase after its bounded corrective
attempts were exhausted. The private rejected-output diagnostics showed three
different schema failures:

1. One audit repair item used a semicolon-joined `artifact_ref` instead of one
   bounded path or symbol reference.
2. One `blast_radius_inspection.evidence.observation` used the unauthorized
   value `blast_radius_inspected`.
3. One response was missing a final closing delimiter and placed
   `verdict: "satisfied"` inside `produced_outputs` instead of at the required
   root level.

The third response demonstrates the boundary precisely. The existing
structural-repair engine can add the missing delimiter because that is a
deterministic syntax edit. After parsing, the phase schema still rejects the
envelope because the verdict is nested at the wrong level. The corrective
re-spawn was given the rejection rule and path but not the response it needed
to move the field.

The runtime's existing privacy split is intentional and remains necessary:
the private diagnostic may contain the raw response and value-bearing
validator reason, while operator-facing status, telemetry, blocked reasons,
and normal logs remain payload-free. This feature adds a narrowly scoped,
budgeted repair context to the authorized corrective prompt; it does not make
raw output public.

## Intended Outcome

Every schema-invalid phase retry receives a versioned, bounded corrective
context containing:

- the phase and attempt identity;
- the payload-free violated rule/path and actionable expected shape or
  constraint;
- the exact rejected response when it is within the configured repair-context
  budget;
- enough metadata to distinguish an unchanged response from a response
  accepted after syntax repair.

The agent can correct field placement, enum values, bounded references, and
other phase-specific contract violations from the same response it produced.
The runtime still validates the retry from scratch, preserves the bounded
fix-loop cap, and keeps rejected output private everywhere except this
explicitly authorized repair prompt.

## Acceptance Criteria

1. The corrective-retry boundary has a versioned typed context rather than an
   unstructured string. It carries the phase, attempt, rejection rule/path,
   payload-free validator constraint, and repair-context budget metadata.
2. For a schema-invalid attempt whose captured response is not truncated and
   fits the configured repair-context budget, the next corrective prompt
   contains the exact rejected response in a clearly delimited repair section,
   together with the payload-free failure reason and the required output
   contract.
3. The context is populated from the same captured response that is retained
   by `recordRejectedOutput`; it does not re-read chat history, a mutable
   file, or a different attempt's diagnostic row.
4. A response that was accepted after deterministic delimiter repair is
   identified as repaired context when it is later rejected by a phase-specific
   schema or semantic gate. The prompt does not claim that the phase schema
   accepted the response merely because syntax repair succeeded.
5. The corrective prompt explicitly tells the agent to preserve valid content,
   correct the named violation, emit exactly one final JSON object, and place
   phase-required fields at their contract-defined locations. It must not
   ask the agent to copy a raw response unchanged.
6. Raw rejected output is exposed only to the explicitly authorized
   corrective-repair prompt. It remains absent from telemetry, status
   projections, blocked reasons, normal logs, PR descriptions, generated
   files, and public diagnostic summaries.
7. Repair-context inclusion is bounded by explicit UTF-8 byte and prompt
   budgets. The runtime never silently truncates a response while presenting
   it as exact. An oversized or already-truncated response produces a
   payload-free, actionable fallback that names the private diagnostic
   identity/locator and explains that the exact body was not included.
8. Sensitive-output handling is explicit: the context is not persisted as a
   second durable artifact, is not emitted to telemetry, and follows the
   existing local/private diagnostic retention boundary. Any encoding,
   delimiter, or prompt-rendering scheme must prevent the rejected response
   from being interpreted as runtime instructions.
9. A schema-invalid retry cannot accidentally receive the
   retryable-terminal or incomplete-work directive, and a schema-valid
   terminal retry cannot receive the raw-output repair context. The existing
   correction-type separation remains intact.
10. The bounded fix-loop behavior is unchanged: retries remain finite,
    accepted output is normalized and persisted through the existing atomic
    path, and exhaustion still blocks with `INVALID_OUTPUT` without exposing
    the raw response.
11. Regression coverage proves the SKILL-16 cases: a misplaced root verdict,
    an invalid enum value, and an oversized/compound artifact reference each
    produce a repair prompt containing the right rejected response and
    payload-free constraint, followed by validation of the corrected output.
12. Regression coverage proves the privacy boundary by asserting that raw
    response content is absent from telemetry, status, blocked reasons,
    operator-facing reports, and ordinary logs, while the private diagnostic
    retains the original bytes and value-bearing reason.
13. Regression coverage proves malformed/truncated/oversized handling,
    delimiter-safe rendering, attempt correlation, YAML and JSON response
    paths, and that a throwing telemetry or diagnostic observer cannot alter
    workflow correctness.
14. The runtime check suite passes.

## Scope

- `runtime-kotlin/runtime-application` corrective context, prompt composition,
  run-loop threading, and diagnostic correlation.
- `runtime-kotlin/runtime-domain` typed versioned context models and public
  port-facing projections where required.
- `runtime-kotlin/runtime-infra-fs` only where parser/validator result metadata
  must identify structural repair versus schema rejection.
- Focused application, contract, privacy, and integration tests.
- Contract and observability documentation needed to define the new authorized
  prompt projection.

## Constraints

- Keep structural repair deterministic and syntax-only. Do not add heuristic
  field movement, enum invention, prose interpretation, or model calls to the
  validator.
- Do not bypass phase schema validation, semantic gates, required verification,
  transition authority, or the existing fix-loop cap.
- Keep public rejection reasons and telemetry payload-free. Schema constraints
  passed to the repair agent must be the payload-free variant, never a
  value-bearing exception message.
- Treat the rejected response as untrusted data. Delimit and label it as
  reference material; it must not override the repair instructions or contract.
- Use explicit UTF-8 byte and collection limits. Do not introduce unbounded
  prompt growth or duplicate raw retention.
- Preserve existing diagnostic identity, attempt, repair-turn, generation, and
  retention semantics.
- Do not copy any real rejected response, secret, prompt, source body, or
  database path into tests, specs, telemetry fixtures, or generated artifacts.
  Tests use synthetic sentinel payloads.
- Existing accepted-after-repair evidence remains payload-free and
  digest-based.

## Non-Goals

- No general-purpose JSON/YAML semantic repair engine.
- No automatic correction of invalid field values or relocation of fields
  inside the validator.
- No change to which phase failures are retryable, terminal, or operator
  blocking.
- No increase to the bounded fix-loop cap or an unlimited retry path.
- No new public command for viewing raw rejected output.
- No change to provider routing, model selection, platform-pack behavior, or
  chat-history continuation authority.
- No migration of raw diagnostic bodies into workflow state, telemetry, status,
  PRs, or repository files.

## Diagnostic Evidence

The diagnostic basis is the SKILL-16 run above and the v1 code path verified
against:

- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimeRunLoop.kt`,
  where `gateOutput` captures the response, records the private diagnostic,
  and currently constructs schema-invalid retry state without passing the
  response onward.
- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimePhasePromptComposer.kt`,
  where `retryCorrectionDirective` currently renders only the rejection
  context and output skeleton.
- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimePhasePromptDirectives.kt`,
  where `PriorAttemptCorrection` currently stores only one reason plus the
  correction kind.
- `runtime-kotlin/runtime-infra-fs/.../FeatureTaskRuntimePhaseOutputStructuralRepair.kt`,
  which already performs bounded delimiter repair and emits digest/location
  evidence.
- `runtime-kotlin/runtime-infra-fs/.../FeatureTaskRuntimePhaseOutputValidatorAdapter.kt`,
  which applies structural repair before phase-specific schema normalization.
- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimeRejectionConstraintPrivacyTest.kt`
  and `RejectedOutputPrivacyAssertions.kt`, whose current contract deliberately
  forbids raw response spans in retry prompts and therefore must be revised to
  distinguish the authorized repair projection from public surfaces.

Raw payloads and exact private database rows remain diagnostic-only and are not
repeated in this governed spec.

## Subtasks

1. Define the versioned corrective-repair context, strict size/encoding
   boundary, and safe prompt projection.
2. Thread the captured rejected response through the schema-invalid fix loop
   into the next phase launch without crossing public observability boundaries.
3. Add focused regression/conformance coverage for repair usefulness,
   truncation, privacy, correlation, JSON/YAML, and bounded-loop behavior.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused suites should include the existing phase-output structural-repair and
schema-validator tests, `FeatureTaskRuntimePhasePromptComposerTest`,
`FeatureTaskRuntimeRunnerTest`, and
`FeatureTaskRuntimeRejectionConstraintPrivacyTest`, plus new end-to-end
corrective-context coverage.

## Next Path

After all subtasks land, force synthetic SKILL-16-shaped audit responses
through the v1 runtime. Confirm that syntax-only repair handles the missing
delimiter, the following schema rejection supplies the exact rejected
response and payload-free constraint to the next repair launch, the corrected
root-level verdict is accepted, and no operator or telemetry surface contains
the raw sentinel.
