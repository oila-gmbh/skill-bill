# SKILL-197 — Subtask 1: Own `architecture` in the `kmp` pack with Android-appropriate content

## Scope

Give the `kmp` pack ownership of the `architecture` review area so an Android/KMP diff is no longer
reviewed against `kotlin` architecture content written for backend and desktop JVM.

In scope:

- `platform-packs/kmp/platform.yaml`
  - add `architecture` to `declared_code_review_areas`
  - add `architecture: code-review/bill-kmp-code-review-architecture/content.md` to
    `declared_files.areas`
  - add an `area_metadata.architecture.focus` line naming the Android/KMP concerns this pack owns
  - add `lane_conditions.architecture` with `required: true`, matching the other required `kmp` area
  - add a `pointers.code-review/bill-kmp-code-review-architecture` entry carrying
    `specialist-contract.md`, consistent with the other specialist pointer blocks
- new `platform-packs/kmp/code-review/bill-kmp-code-review-architecture/content.md` following the
  house style of `bill-kmp-code-review-platform-correctness/content.md`: frontmatter with `name`,
  `description`, `internal-for: bill-code-review`, then `# ...`, `## Focus`, `## Ignore`,
  `## Applicability`, `## Project-Specific Rules` with named rule subsections
- `platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/agents.yaml` — register
  `bill-kmp-code-review-architecture` so native-agent parity holds
- pinned expectations that enumerate the `kmp` area set:
  - `KMP_CODE_REVIEW_AREAS` in
    `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/KmpPlatformPackTest.kt`
  - `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/ComposedReviewLaunchPlanTest.kt`
  - `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/infrastructure/fs/FileSystemDeclaredReviewSpecialistsTest.kt`
  - `runtime-kotlin/runtime-infra-fs/src/test/resources/snapshots/native-agents/bill-kmp-code-review.agents.yaml`
  - `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/system/UninstallCommand.kt`
  - the specialist count in `docs/internal-skills-architecture.md`
- the ownership regression test and the F-001-class replay assertion described in the acceptance
  criteria below

Out of scope: the four remaining KMP-uncovered areas (subtask 2), and any change to the `kotlin`
pack.

## Acceptance Criteria

1. `platform-packs/kmp/platform.yaml` declares `architecture` in `declared_code_review_areas`, in
   `declared_files.areas`, in `area_metadata`, and in `lane_conditions` with `required: true`, and
   `skill-bill validate` plus the platform-pack schema and loud-fail contract checks pass.
2. On an Android/KMP diff that routes `kmp`, the composed launch plan yields exactly one
   `architecture` lane and its owning pack slug is `kmp`.
3. The new `kmp` architecture content contains no rule whose only trigger is a technology absent from
   an Android/KMP runtime: none of Exposed, Spring, Hibernate, JDBC, R2DBC, `@Transactional`,
   `DataSource`, servlet request scope, or published-ABI / `api(project(...))` reasoning. A test
   asserts this using the existing `BACKEND_RUBRIC_TERMS` drift check extended to the architecture
   rubric.
4. The new content covers at minimum: Gradle module boundary and dependency direction for Android
   modules; DI graph and scope ownership; `ViewModel` and lifecycle ownership of long-lived work; the
   boundary authority between repository, use case, and sync engine; worker and background-task
   ownership; and single-source-of-truth for offline writes.
5. The new content carries a rule reaching the F-001 class: two documents, fragments, or model trees
   describing one payload, where a field added to one selection set is not added to the other, so the
   same response decodes differently depending on which document a runtime predicate selected. A test
   asserts the rule's presence by its named subsection and its trigger vocabulary.
6. A regression test pins ownership on both routes: an Android/KMP diff plans `architecture` owned by
   `kmp`, and a backend-dominant Kotlin diff plans `architecture` owned by `kotlin`.
7. The `kotlin` pack's `declared_code_review_areas`, area content, `area_metadata`, and
   `lane_conditions` are byte-unchanged.
8. The set of distinct areas planned for an Android/KMP diff is identical before and after this
   change; only the owning pack and its content differ for `architecture`.
9. `bill-kmp-code-review-architecture` is registered in the `kmp` native-agent bundle and the
   native-agent parity test passes for all six declared areas.
10. `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.
11. No comments are added to any changed file.

## Non-Goals

- Changing routing, lane selection, or fallback semantics. That is SKILL-196.
- Declaring `performance`, `security`, `testing`, or `api-contracts` on `kmp`. Subtask 2 audits those
  and decides.
- Weakening or restructuring the `kotlin` pack for its backend consumers.
- Re-running or re-scoring the observed review `rvw-20260817-183143-ilvx`.

## Dependency Notes

No dependencies. This subtask establishes the ownership pattern and the drift-check shape that
subtask 2 reuses for any area it decides to declare.

SKILL-196 AC 4 must not ship before this subtask lands. Nothing in this subtask depends on SKILL-196
code; the ordering constraint runs the other way.

## Validation Strategy

- `skill-bill validate` for pack schema, declared-file existence, required sections, and
  `contract_version` parity.
- `(cd runtime-kotlin && ./gradlew check)` for the pinned area-set tests, the native-agent parity
  test, the backend-drift test, and the new ownership regression test.
- The ownership regression test drives `ReviewStackRouting` and `ReviewLaunchPlanPolicy` over the
  existing `PLAIN_ANDROID_DIFF` and backend-dominant fixtures already present in
  `KmpPlatformPackTest`, so it asserts planning behaviour rather than manifest structure.

## Next Path

Subtask 2: audit `performance`, `security`, `testing`, and `api-contracts` against an Android/KMP
diff and record a per-area disposition.
