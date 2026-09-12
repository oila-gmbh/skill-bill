# SKILL-239 subtask 3: Give the feature run loop one state owner

## Scope

Fix F-004 in the feature-task run-loop family. The current twenty files contain 280 whole-run-loop parameters. Helper objects write fields on FeatureTaskRuntimeRunLoopSession, and report() selects among independently nullable terminal reports. The module split therefore leaves a large shared dependency and state object inside the engine.

Own the FeatureTaskRuntimeRunLoop family, FeatureTaskRuntimeRunState and reconstruction helpers, the small recovery-command formatting dependency, and affected engine tests. Preserve the existing phase contracts and public engine entry points.

## Acceptance criteria

1. One per-run owner controls report outcome, branch/checkpoint ownership, pending and active reentry, operator retry consumption, and record-rejection settlement. Helper objects request named transitions or return decisions; they cannot assign these fields directly.
2. Terminal report state uses the existing report hierarchy or a minimal sealed state so blocked, paused, and decomposed outcomes cannot all be present. Preserve resolved-branch enrichment and the existing externally visible reports.
3. Completed phases, phase outputs, attempt counts, and related collections no longer expose mutable collections to arbitrary consumers. Transition methods update coupled state together, and read APIs expose immutable views or copies as appropriate. Reconstruction uses the same invariants as live execution.
4. Helpers no longer accept the entire FeatureTaskRuntimeRunLoop. Give pure decisions their required values and effectful collaborators their actual ports. The coordinator sequences those operations. Do not replace the run-loop parameter with an equivalent all-access context, interface, or callback collection.
5. Resume from a persisted review invalidation, consumed/unconsumed audit retry grant, interrupted reentry, and completed phase reconstruction yields the same durable phase order and report as before. A stale report cannot survive a later transition that replaces it.
6. Preserve one bounded review-fix round, producer projection repair, build-only goal-child behavior, single-subtask commit ownership, and validation receipt semantics from 347. No artifact version bump is needed solely to privatize in-memory state.
7. Move recovery-command formatting to an existing inward-owned location or another minimal dependency-correct home. Remove the only featuretask-to-goalrunner import and empty the engine package-cycle baseline. Keep the command text and quoting semantics unchanged in this structural change.
8. Report the before/after whole-run-loop parameter count, helper responsibilities, mutable state access, and public API changes. New public engine API count is zero. The result must be easier to trace without adding speculative ports or lowering per-function complexity rules.

## Dependency notes

This structural commit is independently shippable. It follows the persistence work in the manifest to reduce concurrent edits to settlement consumers. SKILL-238 owns role-interface and forwarding-service deletion. Reuse its new constructors if it has landed; otherwise adapt existing signatures only as required. Do not require SKILL-238's IDE cleanup or repeat that goal's acceptance.

## Validation strategy

Use existing engine boundary and resume tests. From runtime-kotlin, run `./gradlew :runtime-engine:test --tests 'skillbill.engine.FeatureTaskRuntime*' --tests 'skillbill.engine.GoalRunner*' :runtime-core:test --tests 'skillbill.architecture.RuntimeEngineInboundApiTest' --tests 'skillbill.architecture.ProductionLogicalTypeLineCeilingArchitectureTest' --console=plain`. Include the existing package-cycle check by its current owning test class. Add only missing transition sequences that demonstrate contradictory session state or different behavior after reconstruction.

## Non-goals

Another Gradle module, a generic state-machine DSL, event sourcing, replacing all maps or String identifiers, a coroutine rewrite, or merging the 9,779 lines into one class. Numeric size reductions are evidence, not the design.

## Next path

Continue to subtask 4, which records and checks the resulting architecture.
