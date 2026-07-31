# SKILL-152 Subtask 1 — Actionable rejection feedback and deterministic absorption

Parent: `.feature-specs/SKILL-152-handoff-bookkeeping-reliability/spec.md` (unit 1)

## Scope

Split the schema-rejection reason into two values with different audiences, and stop spending fix-loop attempts on rejection classes that carry no governed meaning.

Today one string serves both audiences. `payloadFreeRejectionReason` reduces every rejection to a rule name and a JSON pointer, and that value is simultaneously the durable operator-facing blocked reason, the diagnostic row's `reason`, and the `priorSchemaFailure` echoed into the retry prompt. Satisfying the strictest audience stripped the one audience that needed the detail: the agent that authored the payload and is about to retry.

The work:

- Separate the retry-path reason from the operator-facing blocked reason at the rejection seams (`FeatureTaskRuntimeRunLoop.kt:2244` and `:2318`), so `priorSchemaFailure` carries the validator's constraint text while the blocked reason keeps its current payload-free form. The existing `assertPrivateDiagnosticRejection` assertions pin the blocked reason and must stay green untouched.
- Route the carried detail through `boundedSchemaGateDetail`, and correct its KDoc, which already describes the retry-prompt path it no longer serves.
- Record the constraint text on the private diagnostic row alongside the raw response, so an operator sees the violated rule without reconstructing it.
- Cover every fix-loop rejection path — producer-projection, consumer-projection, phase-output, audit-repair-plan — so none is left echoing a bare pointer.
- Prune unknown keys from closed projection objects in `FeatureTaskRuntimeProjectionCanonicalizer` before validation, scoped to objects whose schema is `additionalProperties: false` and whose governed fields are fully enumerated. State the discard explicitly in the canonicalizer contract KDoc, which currently promises it never drops.
- Fix `formatReason` in `FeatureTaskRuntimePlanningProjectionSchemaValidator.kt:64`, which prepends an instance location the networknt message already carries.

Verified failure classes to satisfy, from running the real validator:

| payload | reported location | fix path |
| --- | --- | --- |
| extra key | `$.reconciliation_evidence` | absorbed by canonicalization |
| missing `evidence` | `$.reconciliation_evidence` | agent retry, needs constraint text |
| string in place of object | `$.reconciliation_evidence` | agent retry, needs constraint text |

Canonicalization alone covers one of three, so the feedback channel is the primary fix and the prune is the narrow optimization.

## Acceptance Criteria

1. `priorSchemaFailure` carries the validator's constraint text — violated rule, expected shape, offending field — into the retry prompt for every fix-loop rejection path.
2. The operator-facing blocked reason, telemetry, and status surfaces keep their current payload-free text, and every existing `assertPrivateDiagnosticRejection` assertion passes unmodified.
3. Carried constraint text contains no field value, body fragment, or span of the agent's raw response, and passes through `boundedSchemaGateDetail`.
4. The private diagnostic row records the constraint text alongside the raw response bytes.
5. A closed projection object rejected solely for unknown keys is canonicalized before validation and never consumes a fix-loop attempt; the canonicalizer still never synthesizes a missing field, coerces a type, or drops a governed field.
6. The canonicalizer's contract documentation states the unknown-key discard.
7. A rejection reason reports each violated instance location exactly once.
8. A producer rejected for asserting `reconciliation_evidence.reconciled: false` on a `completed` envelope is told in its retry prompt that a completed receipt asserts a reconciled tree, and that genuinely incomplete work leaves the phase through a `blocked` or `failed` envelope instead. The directive names the path only; continuation semantics are SKILL-150 subtask 2.
9. Tests cover extra-key, missing-required, and wrong-type rejections on a closed projection object and assert the retry prompt names the violated constraint in each case.
10. A regression fixture drives a malformed `implementation_receipt` through a real fix-loop transition for each of the three observed classes and proves convergence rather than attempt exhaustion.
11. Privacy tests prove no raw-response span reaches a retry prompt, blocked reason, telemetry event, or status surface.

## Non-Goals

- Changing `reconciliation_evidence.reconciled` `const: true` semantics, or implementing the continuation path an honestly-unreconciled phase takes. The `const` correctly enforces that a `completed` receipt asserts a reconciled tree, and the producer gate already returns early for any non-`completed` envelope, so the escape valve exists. Routing incomplete work through `blocked`/`failed` instead of the schema-invalid path is SKILL-150 subtask 2, AC-2 and AC-4. This subtask adds the pointing directive and nothing behind it.
- Changing fix-loop attempt caps or the bounded retry policy.
- Moving which validator runs at which seam.
- Adding any new store for raw responses.

## Dependency Notes

Independent of subtask 2; the two touch different seams and may land in either order.

Adjacent in-flight work to stay clear of: SKILL-142 subtask 2 owns validator parity across the gate and hydration seams; SKILL-150 owns durable convergence state and `blocked`/`failed` continuation. This subtask changes only what a rejected agent is told and which rejections are worth telling it about.

## Validation Strategy

- Unit-test the reason split directly: one rejection, two output strings, opposite assertions.
- Exercise each malformed class against the real planning-projection validator rather than a stub, so the asserted text is the text agents receive.
- Drive the regression fixture through a real phase transition, not a direct gate call, since the seam that blocked is the loop, not the gate.
- Extend the existing privacy assertion helper to the retry-prompt path.
- Then run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Continue with subtask 2 to stop retained producer evidence from colliding when a review generation restarts.
