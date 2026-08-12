# SKILL-187 Subtask 2 — Thread the rejected response into corrective re-spawn

## Scope

Integrate the subtask-1 context through the v1 phase-output rejection path:

```text
captured stdout/bytes
→ output validator and syntax repair
→ private rejected-output diagnostic
→ schema-invalid attempt result
→ PriorAttemptCorrection
→ next phase prompt
```

The integration must preserve the current distinction between:

- deterministic syntax repair accepted by
  `FeatureTaskRuntimePhaseOutputStructuralRepair`;
- schema/semantic rejection requiring a corrective re-spawn;
- schema-valid retryable terminal output;
- schema-valid incomplete work.

The captured response used for the repair prompt must be the same response
whose bytes, digest, attempt, phase, and repair turn are sent to
`recordRejectedOutput`.

## Acceptance Criteria

1. `gateOutput` passes the captured rejected response and its availability
   metadata into the schema-invalid attempt result instead of discarding it
   after diagnostic persistence.
2. `settleMalformedOutput` creates a corrective context containing the previous
   attempt's response, and the next `attemptOnce` launch renders that context
   through `FeatureTaskRuntimePhasePromptComposer`.
3. A semantic rejection after successful delimiter repair carries the
   post-capture response that was actually rejected by the phase schema, while
   repair evidence remains digest/location metadata and no raw body is added to
   durable phase artifacts.
4. A schema-invalid response with a misplaced root verdict, invalid enum, or
   compound artifact reference is corrected on the next synthetic agent launch
   and then passes the normal validator and phase completion path.
5. The first launch has no prior-repair context. A subsequent schema-invalid
   launch has the matching prior attempt context, and a later retry does not
   receive a stale context from an older phase, attempt, repair turn, or
   continuation segment.
6. Retryable terminal and incomplete-work attempts never receive the raw-output
   repair section or the schema-rejection directive. Schema-invalid attempts
   never receive those other retry directives.
7. Response inclusion respects truncation and budget metadata from the capture
   boundary. The integration does not reconstruct a response from a lossy
   public reason or from chat history.
8. Fix-loop exhaustion still persists the payload-free operator block reason
   and `INVALID_OUTPUT` disposition. The exact rejected response is available
   only through the private diagnostic store and the authorized in-flight
   repair prompt.
9. Existing diagnostic identity, attempt, repair-turn, evidence-generation,
   digest, and retention behavior remains unchanged for accepted, rejected,
   and exhausted attempts.
10. Integration tests prove that throwing telemetry, status projection, or
    diagnostic observer code cannot leak the response or change the retry,
    block, or completion outcome.

## Non-Goals

- Do not make the validator perform semantic repair.
- Do not add an additional retry after the configured cap.
- Do not expose raw output through status, telemetry, blocked reasons,
  operator reports, PRs, or logs.
- Do not change provider-specific launch behavior or model/effort routing.
- Do not alter existing accepted-after-repair persistence semantics.

## Dependency Notes

Depends on subtask 1's versioned context and prompt projection. The existing
run loop already owns captured stdout, private diagnostic recording, and fix
loop transitions; keep those responsibilities in the run loop rather than
moving them into the validator or harness adapter.

The implementation must account for all schema-invalid construction paths,
including the normal phase-output schema error and the audit-repair-plan
schema error. A path that still constructs `AttemptResult.SchemaInvalid` with
no response is incomplete unless the response was genuinely unavailable.

## Validation Strategy

Run focused runner and prompt tests, then:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew :runtime-application:test --tests '*FeatureTaskRuntimeRunnerTest' \
  --tests '*FeatureTaskRuntimePhasePromptComposerTest' \
  --tests '*FeatureTaskRuntimeRejectionConstraintPrivacyTest'
```

Inspect synthetic diagnostic rows after each correction test to ensure the
prompt and private evidence refer to the same attempt and response digest.

## Next Path

Subtask 3 adds the complete regression/conformance matrix and updates the
privacy assertions so the authorized repair prompt is tested separately from
all operator-facing surfaces.
