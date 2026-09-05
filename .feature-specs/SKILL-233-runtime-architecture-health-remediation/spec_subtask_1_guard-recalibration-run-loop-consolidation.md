# SKILL-233 · Subtask 1: Guard recalibration, run-loop consolidation, DI flattening

## Scope

Change the rules that shaped the fragments, then reassemble the fragments into
units a reader can name. The rules come first because the merged units will
exceed the current ceilings, and a rule is changed by argument, not by baseline
entry.

**Re-tune the four shaping rules.** `PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING`
(500, `runtime-core/src/test/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt:260`),
detekt `TooManyFunctions` (11 in every scope), and
`LongParameterList.constructorThreshold` (7) in `runtime-kotlin/config/detekt/detekt.yml`
are the three rules that produced the split. Decide each one in
`runtime-kotlin/agent/decisions.md` and apply the decision. The expected shape is a
file ceiling high enough that one cohesive collaborator fits (the largest
attributed cluster SKILL-231 measured was 1,069 lines), a `TooManyFunctions`
threshold that tolerates a class with one responsibility and a dozen small
steps, and a constructor threshold that flags real over-injection rather than
forcing bundles. `LongMethod`, `CyclomaticComplexMethod`, `NestedBlockDepth`,
and `ComplexCondition` do not move; they measure functions, and function size is
the limit that still matters.

**Widen the spillover scanner from file names to identifiers.** SKILL-231 made
`RuntimeSpilloverFileNameArchitectureTest` cover `Continued<N>`, `Helpers<N>`,
`Fns<N>`, `Support<N>`, `A1`-style, and bare-digit file names across all ten
modules, and its baseline is empty. The same signature now lives in property
names: `FeatureTaskRuntimeRunLoopCollaborators` exposes `driveContinued1..4`,
`checkpointContinued1..6`, `attemptSettlementContinued1..3`, and
`launchContinued<N>`, 31 identifiers in total. Extend the scan to top-level and
member declaration names in main source, add `Support`, `Helpers`, `Misc`, and
`Extras` as bare suffixes on file and type names (115 production files carry one
today), and keep the exemption list on `PrincipleEnforcementInventory`.

**Consolidate the run loop.** `skillbill.application.featuretask` holds 65
`FeatureTaskRuntimeRunLoop*` files totalling 10,606 lines and 66 `@Inject`
classes in the package. Each fragment is its own `@Inject class` that takes the
run loop session as a method parameter, so no receiver bills the lines and the
logical-type ceiling passes. Reassemble by responsibility. The target set is the
one the fragment names already imply: drive, phase runner, phase attempts,
launch, output verification, output persistence, validation gate, review,
checkpoint, planning branch, backward edge, attempt settlement, record
rejection, repair receipt, subtask commit, transitions. Each becomes one class
or one file-scoped unit whose sub-steps are private functions, not sibling
`@Inject` classes. `FeatureTaskRuntimeRunLoopCollaborators`,
`FeatureTaskRuntimeRunLoopCollaboratorFacets`, and the five
`*ContinuationCollaborators` bundles are deleted because nothing needs to
forward to a fragment any more. `FeatureTaskRuntimeRunState` and its nine
`*Extensions` files become one type with member functions, split only if two
genuinely separate responsibilities emerge.

**Dissolve the other bundles.** Thirty `*Dependencies` / `*Collaborators` /
`*Deps` classes exist across the runtime (`FeatureTaskRuntimeRunnerDependencies`,
`WorkflowServiceDeps`, `WorkflowGoalRunnerManifestStoreDeps`, and siblings).
Where a bundle groups collaborators one class genuinely uses, inline them into
that class's constructor under the re-tuned threshold. Where a bundle groups
collaborators that only some methods use, that is the split point for a real
responsibility boundary. A bundle that survives is one a second consumer
receives as a unit, and the subtask report names each survivor.

**Flatten DI.** `runtime-core/src/main/kotlin/skillbill/di/` holds 28 files:
`RuntimeComponent` implements 13 `*Provides` mixins, each `@Provides` forwards
to a `*Bindings` object, and 35 of the 126 provides are identity binds written
twice. kotlin-inject accepts `@Provides fun port(impl: Adapter): Port = impl`
directly. Replace the pairing-named mixins (`RuntimeTelemetryInstallProvides`,
`RuntimeGoalRunnerScaffoldProvides`, `RuntimeDiagnosticsReviewProvides`,
`RuntimeCompositionMiscProvides`) with area-named ones (`InstallBindings`,
`ReviewBindings`, `GoalRunnerBindings`, `TelemetryBindings`, `WorkflowBindings`,
`ScaffoldBindings`, `DiagnosticsBindings`, `BootstrapBindings`) or with a single
component if the raised ceiling permits. Every `*Bindings` object whose
functions only return their argument is deleted. `RuntimeComponent`'s abstract
property set, which is the runtime's public API, does not change.

**Delete `scripts/split-runloop.py`.** Its input no longer exists.

## Acceptance Criteria

1. `PRODUCTION_LINE_CEILING`, detekt `TooManyFunctions`, and detekt
   `LongParameterList.constructorThreshold` carry new values, and
   `runtime-kotlin/agent/decisions.md` records each value with the reasoning
   and the date. `LongMethod`, `CyclomaticComplexMethod`, `NestedBlockDepth`,
   and `ComplexCondition` are unchanged.
2. The spillover scanner flags `Continued<N>`, `Support`, `Helpers`, `Misc`,
   `Extras`, and letter-plus-digit suffixes on file names, type names, and
   member names across all ten modules. It has an acceptance fixture and a
   rejection fixture for identifiers, and its baseline is empty.
3. No file under `skillbill.application.featuretask` matches
   `FeatureTaskRuntimeRunLoop*Continued*`, `*Collaborators`, or
   `*CollaboratorFacets`. The run loop is at most 20 files, and each file's
   name is the responsibility it holds. The subtask report states the before
   and after counts for files, classes, `@Inject` classes, and lines in the
   package.
4. `FeatureTaskRuntimeRunState` is one type. Its former `*Extensions` files are
   gone or reduced to extensions a second package consumes.
5. No `*Dependencies`, `*Collaborators`, or `*Deps` class exists whose only
   consumer is one constructor. Each surviving bundle is listed in the subtask
   report with its second consumer.
6. `runtime-core` has no `*Bindings` object whose functions return their
   argument, no `*Provides` mixin named by pairing two areas, and every
   `@Provides` is declared once. `RuntimeComponent`'s abstract property set is
   byte-identical before and after.
7. `scripts/split-runloop.py` is deleted.
8. Every run-loop test in `runtime-application/src/test` passes with changes
   limited to constructor wiring and imports. A test whose assertion had to
   change exposed a behaviour difference and is reported, not patched.
9. `runtime-kotlin/gradlew check` and `skill-bill validate` pass with no new
   suppression, no new exemption, and no baseline entry.

## Non-Goals

- Moving the run loop out of `runtime-application`. Subtask 4 does that, once
  the units exist.
- Touching `runtime-ports`, `runtime-domain`, or the null-object family.
  Subtask 2 owns them.
- Changing any port signature or introducing typed identifiers. Subtask 3.
- Reworking `goalrunner` beyond dissolving its `*Deps` bundles. Its 61 files
  are less fragmented than `featuretask` and move as a unit in subtask 4.
- Renaming `RuntimeComponent` or changing what it exposes.

## Dependency Notes

No dependency on another subtask. Subtask 2 may run in parallel; the two touch
disjoint modules (`runtime-application` and `runtime-core` here,
`runtime-ports`, `runtime-domain`, and `runtime-contracts` there), except that
both may edit `PrincipleEnforcementInventory` and `ARCHITECTURE.md`, which
merge textually.

Subtasks 3 and 4 depend on this one: typed identifiers are introduced into
consolidated signatures, and the engine module moves consolidated files.

## Validation Strategy

- The rule change is validated by the decision entry plus the architecture
  tests still passing at the new values with an empty baseline. The bug a wrong
  value would cause is the return of split-by-count files; the widened
  spillover scanner is the guard against that.
- The identifier-level spillover scanner gets a rejection fixture with a
  synthetic `fooContinued2` member and an acceptance fixture with a legitimate
  numbered domain name such as `sha256`.
- Consolidation is validated by the existing run-loop suite: the tests are the
  specification of the loop's behaviour, and they pass unchanged in assertion.
- DI flattening is validated by `runtime-kotlin/gradlew :runtime-core:compileKotlin`
  and a test that reads `RuntimeComponent`'s abstract members by reflection and
  compares them to the pinned list SKILL-231 recorded.
- `runtime-kotlin/gradlew check` in a clean checkout.

## Next Path

```bash
skill-bill goal SKILL-233
```
