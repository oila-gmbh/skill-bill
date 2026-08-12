# SKILL-187 Subtask 1 — Define the corrective-repair context and safe prompt projection

## Scope

Define the versioned typed boundary used when a schema-invalid phase attempt
is re-launched for correction. The boundary must carry the payload-free failure
information already used by the runtime plus the exact captured response when
the response is safe to include under the repair-context budget.

The context belongs to the application/domain contract seam; Jackson nodes,
SQLDelight rows, and diagnostic-store implementation types must not cross it.
The prompt projection must treat the rejected response as untrusted reference
material rather than executable instructions.

## Acceptance Criteria

1. A versioned typed corrective-repair context exists with phase, attempt,
   repair-turn when applicable, rejection rule/path, payload-free constraint,
   response availability, response byte count, response digest, and an
   explicit inclusion/truncation reason.
2. The context distinguishes `exact response included`, `response already
   truncated`, `response exceeds repair budget`, and `response unavailable`
   without representing a non-exact body as exact.
3. The context validates UTF-8 byte limits and collection limits before prompt
   rendering. Limits are named constants or typed configuration, not scattered
   literals.
4. The prompt projection contains the exact response only in the authorized
   repair section and labels it as untrusted prior output. The projection
   keeps the required output contract and payload-free violation guidance
   outside that section.
5. Prompt rendering remains delimiter-safe when the rejected response contains
   Markdown fences, instruction-like text, braces, YAML markers, Unicode, or
   a trailing delimiter. The response cannot close the repair section and
   change the runtime-authored instructions.
6. An unavailable, truncated, or oversized response produces a payload-free
   fallback that tells the agent to use the private diagnostic locator only
   through the existing authorized mechanism; it does not emit a misleading
   excerpt or silently truncate.
7. The context and projection contain no value-bearing validator reason,
   telemetry field, database path, prompt text, secret, or raw output outside
   the authorized repair projection.
8. Unit/conformance coverage proves all response-availability states, UTF-8
   byte counting, delimiter-safe rendering, and JSON/YAML-shaped synthetic
   responses.

## Non-Goals

- Do not change the structural-repair candidate engine.
- Do not infer or mutate semantic field placement, enum values, or artifact
  references.
- Do not persist a second raw-response artifact or add a public raw-output
  reader.
- Do not change retry caps or terminal outcome classification.

## Dependency Notes

This subtask defines the contract consumed by subtasks 2 and 3. It must
preserve the existing separation between schema correction, retryable terminal
envelopes, and incomplete-work continuation.

The current `PriorAttemptCorrection` carries only a reason and correction kind;
the new context may replace or extend that private application type, but must
not collapse the correction kinds into one nullable payload.

## Validation Strategy

Run focused prompt/directive and model tests first, then:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew :runtime-application:test --tests '*FeatureTaskRuntimePhasePromptComposerTest' \
  --tests '*FeatureTaskRuntimeRejectionConstraintPrivacyTest'
```

## Next Path

Hand the typed context and renderer to subtask 2. Subtask 2 threads the
captured response from `gateOutput` into this context and proves that only a
schema-invalid corrective retry receives it.
