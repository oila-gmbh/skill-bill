# SKILL-96 · Subtask 4 — Strings: Compose Multiplatform resources

Parent overview: [spec.md](./spec.md)

Introduces the missing localization layer and migrates every user-facing string
literal to `stringResource(Res.string.*)`. This is the highest-risk, highest-value
subtask and runs last, after every numeric literal is already tokenized.

Branch: `feat/SKILL-96-m3-tokenization` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: `[3]` (dimensions)
- dependency_reason: P4 touches the same UI files as P1–P3 and adds
  `stringResource(...)` calls beside the newly-tokenized shapes/type/dims. Running
  it last keeps the diff coherent and means the only literals left in those files
  are strings — making the string migration (and its grep gate) unambiguous.

## Scope (owns)

- **Enable the Compose Multiplatform resources feature.** `org.jetbrains.compose`
  is already applied (`KmpComposeConventionPlugin.kt:13`). Configure it with
  `publicResClass = true` so the generated `Res` object is visible to
  `feature:skillbill` and `core:ui`, not just `designsystem`. In Compose
  Multiplatform 1.10.x the DSL is the `compose.resources { publicResClass.set(true) }`
  block — **verify the exact accessor at implementation time** and place it in the
  designsystem module's `build.gradle.kts` (targeted) rather than the shared
  convention plugin, unless the convention plugin is the cleaner home.

- **Add the resource file** — single source of truth at
  `runtime-desktop/core/designsystem/src/commonMain/composeResources/values/strings.xml`.
  Name keys by surface (`editor_*`, `toolbar_*`, `scaffold_*`, `confirm_deletion_*`,
  `first_run_*`, `command_palette_*`, `inspector_*`, `nav_*`, `status_*`).

- **Migrate every user-facing literal** to `stringResource(Res.string.*)`:
  - `Text("Modified")` → `Text(stringResource(Res.string.editor_modified))`.
  - Templated strings (`"New ${kind.displayLabel}"`) →
    `stringResource(Res.string.scaffold_title_prefix, kind.displayLabel)` with a
    `%1$s` placeholder in the XML.
  - `label: String` / `title: String` / `tooltip` / `contentDescription` params keep
    their signatures — only the literal origin moves into XML.
  - Enum-label `when` helpers that today return string literals become
    `@Composable` functions returning `stringResource(Res.string.*)`.

- **Retire the three ad-hoc string holders:** `ScaffoldWizardStrings`,
  `ConfirmDeletionStrings` (feature/skillbill), `SkillBillAcceleratorLabels`
  (core/domain). Keep genuinely non-display contract values as Kotlin `const val`
  (e.g. `SkillBillStatusBar.READ_ONLY_MODE_LABEL` and any test tags/matchers).

- **Audit `jvmTest`** for hardcoded UI-copy assertions before migration and update
  them to match the resource strings.

- **Migrate feature-by-feature** within the subtask (toolbar → code editor →
  dialogs → tree/palette/inspector/status), compiling after each file.

## Reusable patterns / pitfalls

- `stringResource` is `@Composable`; any non-composable helper that returns a
  display string today must become `@Composable` or receive an already-resolved
  string. Watch the enum-label functions and the `displayText`/`displayLabel`
  helpers.
- `publicResClass = true` is **required** — without it the generated `Res` is
  module-private and `feature:skillbill`/`core:ui` will not compile.
- Keep `Res.string` keys stable and lower-snake-case; group by surface so the file
  stays navigable.
- Do not move compile-time-constant markers (test tags, `READ_ONLY_MODE_LABEL`)
  into resources — they are not localized copy.
- No explanatory comments in code.

## Acceptance Criteria

1. AC1: The Compose Multiplatform resources feature is enabled with
   `publicResClass = true`; `composeResources/values/strings.xml` exists in the
   designsystem module and the generated `Res` is visible to `feature:skillbill`
   and `core:ui`.
2. AC2: No user-facing string literal remains in
   `runtime-desktop/feature/skillbill/src/commonMain/.../ui/` or
   `runtime-desktop/core/ui/src/commonMain/.../ui/` — `Text("…")`,
   `label = "…"`, `title = "…"`, `tooltip`, `contentDescription` all read
   `stringResource(Res.string.*)` (grep gate returns nothing).
3. AC3: `ScaffoldWizardStrings`, `ConfirmDeletionStrings`, and
   `SkillBillAcceleratorLabels` are removed; only non-display `const val` markers
   remain in Kotlin.
4. AC4: Templated strings use `%n$s` placeholders; `label/title` param signatures
   are unchanged; enum-label helpers compile as `@Composable` where needed.
5. AC5: `jvmTest` copy assertions updated; all tests green.
6. AC6 (this subtask's gate): `(cd runtime-kotlin && ./gradlew check)` is green.

## Non-goals

- No shape, typography, or dimension changes (P1–P3 done).
- No additional languages/translations beyond the default `values/strings.xml`
  (localization-ready, not localized).
- No change to non-display contract strings (test tags, mode-label markers).

## Validation strategy

`bill-code-check` (routes Kotlin/Gradle → `(cd runtime-kotlin && ./gradlew check)`).
Then the string grep gate:
`grep -rn 'Text("\|label = "\|title = "\|contentDescription = "' runtime-desktop/feature runtime-desktop/core/ui`
returns nothing. Smoke the app over every dialog/toolbar/palette/editor/tree and
confirm all copy renders correctly and templated strings interpolate properly.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-96-m3-tokenization/spec_subtask_4_strings.md`.
