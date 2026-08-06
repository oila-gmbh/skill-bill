---
issue_key: SKILL-136
subtask_id: 1
name: Alias Android to the KMP pack
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 1 — Alias Android to the KMP pack

## Intended Outcome

A plain, single-module Android application diff routes to the `kmp` platform
pack, and its Compose UI lanes fire on a non-multiplatform `src/main` layout.
A backend-dominant Kotlin diff continues to route to the generic Kotlin pack.

## Scope

- `platform-packs/kmp/platform.yaml`:
  - `display_name` widens from "Kotlin Multiplatform" to cover Android and
    Kotlin Multiplatform.
  - Remove the contradicting tie-breaker: *"Do not prefer this pack when
    adjacent Kotlin or Android signals dominate without multiplatform source
    sets."* Every other tie-breaker is retained, including those that exclude
    backend-dominant diffs and exclude generated/vendored files from
    dominance.
  - `lane_conditions.ui` widens beyond `path: ["androidMain", "iosMain"]` so it
    also fires on a non-multiplatform Android layout under `src/main`.
    `ux-accessibility` is content-only and is not changed.
- `KmpPlatformPackTest` and `KotlinPlatformPackTest`: add routing fixtures.

## Acceptance Criteria

1. A plain single-module Android diff — Android Gradle plugin markers,
   `AndroidManifest.xml`, Room/DataStore/DI changes, no multiplatform source
   sets, no dominant backend-framework markers — routes to the `kmp` pack.
2. An actual multiplatform diff touching `commonMain`/`androidMain` continues
   to route to the `kmp` pack.
3. A mixed-monorepo fixture where a backend Kotlin/Exposed service dominates
   the changed product surface, with an incidental unrelated Android module,
   still routes to the generic Kotlin pack.
4. A Jetpack Compose Navigation plus ViewModel/StateFlow fixture under
   plain-Android routing resolves `ui` to `bill-kmp-code-review-ui`, not
   `bill-kotlin-code-review-ui`, and the `ui` lane condition fires on a
   `src/main` layout with no `androidMain` directory.
5. The `kmp` platform slug and every `bill-kmp-code-review*` skill name are
   unchanged.
6. `platform-packs/kotlin/` and `platform-packs/generic/` specialists, rubric
   text, lane conditions, and routing signals are byte-unmodified by this
   subtask.
7. `platform-packs/kmp/platform.yaml` validates against
   `orchestration/contracts/platform-pack-schema.yaml`.
8. `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)` pass.

## Non-Goals

- Declaring the new `persistence` and `reliability` areas (Subtask 3).
- Authoring specialist rubric content (Subtask 2).
- Retuning the Kotlin pack's keyword gates.
- Renaming the `kmp` slug or creating a separate `android` pack.

## Dependencies

None. Runs in parallel with Subtask 2.

## Validation Strategy

Extend `KmpPlatformPackTest` and `KotlinPlatformPackTest` with the four
fixtures above, asserting the selected pack for each and the resolved `ui`
specialist for the Compose fixture. Then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

Continue the goal; Subtask 3 consumes this routing change.
