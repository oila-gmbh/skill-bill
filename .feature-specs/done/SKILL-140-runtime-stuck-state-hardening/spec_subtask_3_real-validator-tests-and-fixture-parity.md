# SKILL-140 Subtask 3 - Real-Validator Integration Tests And Fixture Parity

## Scope

Close the coverage gap that let asymmetric-seam bugs ship green: run-loop contract behavior must be proven against the real Draft 2020-12 schema validator, and every canned fixture must validate against the canonical contract schemas in CI.

- Add an integration test suite (in `runtime-cli` or another module where `runtime-infra-fs` is already on the test classpath, matching existing module dependency direction) that wires `FeatureTaskRuntimePlanningProjectionValidatorAdapter` into the run-loop harness and exercises the producer gate and launch seam end-to-end.
- Add a fixture-parity test that validates every canned phase-output fixture used by runner, goal-runner, and projection tests (`validProducedOutputs` and friends) against the canonical schemas, failing the build on drift.
- Correct the existing `implement`/`implement_fix` fixture drift: the undeclared `changed_files` key is removed or the schema's governed co-resident list is consciously extended — resolved against the SKILL-137 contract intent, not silently widened.
- Constrain `NoopFeatureTaskRuntimePlanningProjectionValidator` to tests where projection enforcement is not the behavior under test; tests asserting gate, seam, or projection behavior use the real validator.

## Acceptance Criteria (this subtask)

1. An integration test proves, with the real schema validator: a schema-violating plan output retries through the bounded fix loop and blocks only at the cap; a canonicalizable output is normalized and advances; a conforming output advances unchanged through plan, implement, and audit consumption.
2. A build-failing parity test validates every canned phase-output fixture against the canonical contract schemas; introducing an undeclared key or pattern violation into any fixture fails the build with a message naming the fixture and the violation.
3. The `implement` fixture drift (`changed_files`) is resolved with an explicit decision recorded in the test or schema comment, and the fixture corpus validates cleanly.
4. Remaining uses of the Noop validator are limited to tests not asserting projection behavior; each gate/seam-asserting test uses the real validator.
5. Module dependency direction is preserved: `runtime-application` gains no production dependency on `runtime-infra-fs`; the real-validator wiring lives in test scope of a module already depending on both.

## Non-Goals

- Rewriting the runner test harness beyond validator injection and fixture validation.
- Adding new contract schemas or changing existing schema shapes.
- Testing provider agents end-to-end against real LLM output.

## Dependency Notes

Depends on Subtasks 1 and 2: the producer gate and canonicalization must exist so the integration suite locks in their combined final behavior.

## Validation Strategy

- Run the new integration suite and fixture-parity test in isolation, then `(cd runtime-kotlin && ./gradlew check)`.
- Mutation check: temporarily corrupt one fixture locally to confirm the parity test fails loudly, then revert.

## Next Path

Subtask 4 gives the launch seam an in-band recovery edge for the legacy durable records the producer gate cannot reach.
