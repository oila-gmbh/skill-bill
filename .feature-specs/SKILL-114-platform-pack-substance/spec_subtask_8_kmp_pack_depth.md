# SKILL-114 Subtask 8 - KMP Pack Depth

## Scope

Keep KMP as a compositional overlay, but make the combined route complete and
unambiguously multiplatform. Consume the elevated ten-area Kotlin baseline,
then apply KMP-specific platform-correctness, UI, and UX/accessibility depth
without double-reporting generic Kotlin concerns.

Required KMP depth includes source-set dependency and visibility boundaries,
expect/actual parity, target capability differences, serialization/time/locale
behavior, coroutine dispatch and cancellation across JVM/Native, freezing and
native interop where applicable, ObjC/Swift export and bridge semantics,
resource generation, Compose Multiplatform state/effects/navigation/rendering,
Android/iOS/desktop/web target differences, semantics/focus/input/localization,
and build/packaging/shrinker/toolchain behavior.

Audit all twelve add-ons for clear activation, non-overlap, current platform
guidance, and correct review-versus-implementation ownership. Add a declared
KMP quality checker that discovers Gradle tasks and validates common and target
compilation, Android lint/tests, iOS/native checks when available, Compose
resources, dependency alignment, and shrinker/release paths. Retire the
KMP-to-Kotlin quality fallback only after the new checker is installed and
covered.

## Acceptance Criteria

1. KMP's effective coverage resolves to all ten areas through a required Kotlin
   baseline plus its three declared delta specialists, with tests proving the
   inherited and overriding lanes run and deduplicate correctly.
2. All three KMP specialists meet the depth gate using effective core rubric
   content; conditional add-ons do not substitute for the ten-rule core
   minimum.
3. Platform correctness covers source sets, expect/actual, target capabilities,
   serialization/time, dispatch/cancellation, Native and Swift/ObjC interop,
   resources, build and packaging failure modes.
4. UI and UX/accessibility cover Compose Multiplatform and meaningful Android,
   iOS, desktop and web target differences in state/effects/navigation,
   rendering, semantics, focus/input, feedback, localization and adaptation.
5. Every KMP add-on has tested activation/exclusion signals, non-duplicative
   ownership, current substantive guidance, and reachable pointers for every
   declared consumer.
6. `platform.yaml` declares a KMP quality-check skill; its content and tests
   cover discovered Gradle tasks, common/target compilation, Android and
   available native checks, Compose resources, dependency alignment, and
   release/shrinker paths.
7. The historical KMP-to-Kotlin quality fallback and its docs/telemetry paths
   are removed or migrated without affecting Kotlin-only routing.
8. KMP passes both duplication thresholds and its history records effective
   coverage, add-on ownership, and the quality-check migration.
9. KMP/Kotlin composition, pack, render, install and telemetry tests pass with
   `skill-bill validate` and relevant Gradle checks.

## Non-Goals

- No physical duplication of all ten Kotlin specialists under KMP.
- No guarantee that every target toolchain is installed in every repository;
  unavailable target checks are reported, not fabricated.
- No generic Android-only redefinition of KMP.

## Dependency Notes

Depends on subtasks 1 and 7. Blocks final subtask 10.

## Validation Strategy

Run KMP/Kotlin maintained-pack audits, composition and quality-routing tests,
render/install snapshots, `skill-bill validate`, and relevant Gradle checks.

## Next Path

Proceed independently to subtask 10 after all pack subtasks finish.
