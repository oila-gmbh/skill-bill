# SKILL-96 · Subtask 3 — Dimensions tokenization

Parent overview: [spec.md](./spec.md)

Replaces ~306 inline `.dp` literals (padding/border/size/height) with a new
`SkillBillDimens` spacing/sizing object and the extended `SkillBillMetrics`, and
drops the `@file:Suppress("MagicNumber")` annotations that exist only to hide
these literals.

Branch: `feat/SKILL-96-m3-tokenization` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: `[2]` (typography)
- dependency_reason: P1 and P2 have already cleaned shapes and type in these
  files. P3 now resolves the remaining `.dp` literals; doing it last among the
  numeric phases lets the MagicNumber suppressions be removed cleanly (the
  suppressions hide all magic numbers, so they must stay until every numeric
  literal — shape/type/dim — is gone).

## Scope (owns)

- **New `SkillBillDimens.kt`** — a direct-access `object` (matching
  `SkillBillMetrics`) of 4dp-grid spacing/padding/border/size primitives. Target
  (tiers chosen so every recurring literal maps losslessly):

  ```kotlin
  object SkillBillDimens {
    val hairline = 1.dp
    val divider  = 1.4.dp

    val spacingXs  = 2.dp
    val spacingSm  = 4.dp
    val spacingMd  = 6.dp
    val spacingLg  = 8.dp
    val spacingXl  = 10.dp
    val spacing2xl = 12.dp
    val spacing3xl = 14.dp
    val spacing4xl = 16.dp
    val spacing5xl = 18.dp

    val padXs  = 2.dp
    val padSm  = 4.dp
    val padMd  = 6.dp
    val padLg  = 8.dp
    val padXl  = 10.dp
    val pad2xl = 12.dp
    val pad3xl = 14.dp
    val pad4xl = 16.dp

    val controlHeightSm = 26.dp
    val controlHeightMd = 28.dp
    val controlHeightLg = 30.dp

    val rowHeightSm = 30.dp
    val rowHeightMd = 38.dp
    val rowHeightLg = 44.dp
    val rowHeightXl = 52.dp

    val iconSm = 14.dp
    val iconMd = 15.dp
    val iconLg = 18.dp

    val checkboxSize      = 18.dp
    val lineNumberWidth   = 50.dp
    val dialogMinWidth    = 560.dp
    val dialogMaxWidth    = 760.dp
    val dialogMaxHeight   = 640.dp
    val footerButtonMinWidth = 60.dp
  }
  ```

  (Finalize tier names in planning from the actual literal distribution; keep the
   4dp grid and ensure every recurring value maps to a token.)

- **Extend `SkillBillMetrics.kt`** — keep the existing six fields verbatim
  (`toolbarHeight`, `treePaneWidth`, `inspectorPaneWidth`, `bottomDockHeight`,
  `statusPaneHeight = 28.dp`, `treeIndent`) so current call sites at
  `SkillBillNavigationPane.kt`/`SkillBillInspector.kt` keep working. Reuse
  `statusPaneHeight` for any 28dp status bar; add structural sizes:
  `editorCommandBarHeight = 38.dp`, `footerHeight = 52.dp`,
  `dialogHeaderHeight = 44.dp` (verify values against the literals they replace).

- **Migration map (deterministic):** map every `padding/border/size/height/width`
  literal to the nearest `SkillBillDimens.*` (spacing/padding/sizing) or
  `SkillBillMetrics.*` (structural). Examples:
  - `SkillBillToolbar.kt` `.height(40.dp)` → `.height(SkillBillMetrics.toolbarHeight)`.
  - borders `1.dp`/`1.4.dp` → `SkillBillDimens.hairline`/`.divider`.
  - paddings 6/8/10/12/14/16 → `padMd/padLg/padXl/pad2xl/pad3xl/pad4xl`.
  - row/control heights 28/30/44/52 → `controlHeightMd/rowHeightSm/rowHeightLg/rowHeightXl`.
  - icon sizes 14/15/18 → `iconSm/iconMd/iconLg`.

- **Migrate every inline `.dp` site** across the UI consumers (representative:
  `SkillBillToolbar.kt`, `ScaffoldWizardDialog.kt`, `SkillBillFrameShared.kt`,
  `SkillBillStatusBar.kt`, `FirstRunSetupDialog.kt`, `ConfirmDeletionDialog.kt`,
  `SkillBillCodeEditor.kt`, `SkillBillInspector.kt`, `SkillBillNavigationPane.kt`,
  `SkillBillNavTree.kt`, `SkillBillEditorTabs.kt`, `SkillBillCommandPaletteOverlay.kt`,
  `SkillBillToolbarMenus.kt`).

- **Remove `@file:Suppress("MagicNumber")`** from `SkillBillToolbar.kt`,
  `ScaffoldWizardDialog.kt`, and any other UI file that carried it — this is the
  gating signal that P1–P3 are complete.

- **Extend `SkillBillFrameTokenWiringTest.kt`** with assertions pinning the pane
  widths/heights (`SkillBillMetrics.toolbarHeight == 40.dp`, etc.).

## Reusable patterns / pitfalls

- Do not invent new numeric values — every token equals an existing literal, so
  pixel layout is preserved.
- Keep `SkillBillMetrics` for structural layout sizes (pane widths, bar heights)
  and `SkillBillDimens` for spacing/padding/border/control sizing primitives; do
  not blur the two.
- Compile after each file; `allWarningsAsErrors` + detekt will flag any leftover
  magic number once the suppression is gone, so remove suppressions last in the
  subtask and fix every report rather than re-suppressing.
- No explanatory comments in code.

## Acceptance Criteria

1. AC1: `SkillBillDimens.kt` defines the spacing/padding/sizing primitives as a
   direct-access `object`; `SkillBillMetrics.kt` is extended with the structural
   sizes and keeps its existing six fields.
2. AC2: No `N.dp` literal remains in
   `runtime-desktop/feature/skillbill/src/commonMain/.../ui/` or
   `runtime-desktop/core/ui/src/commonMain/.../ui/` for padding/border/size/height
   (grep gate returns nothing).
3. AC3: `@file:Suppress("MagicNumber")` is removed from every UI file; detekt
   reports no MagicNumber finding in UI.
4. AC4: `SkillBillFrameTokenWiringTest.kt` pins the structural metric values.
5. AC5: Pixel layout preserved — token values equal the literals they replace;
   the app smokes clean.
6. AC6 (this subtask's gate): `(cd runtime-kotlin && ./gradlew check)` is green.

## Non-goals

- No shape, typography, or string changes (P1/P2 done; P4 later).
- No layout redesign — only the source of each dimension value changes.

## Validation strategy

`bill-code-check` (routes Kotlin/Gradle → `(cd runtime-kotlin && ./gradlew check)`,
which runs detekt via `skillbill.quality`). Then the dimension grep gate:
`grep -rn "MagicNumber" runtime-desktop/feature runtime-desktop/core/ui` returns
nothing, and `grep -rnE "[0-9]+\.dp|[0-9]+\.[0-9]+\.dp" runtime-desktop/feature runtime-desktop/core/ui`
returns nothing outside the designsystem module. Smoke the app over every surface
to confirm spacing/sizing is unchanged.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-96-m3-tokenization/spec_subtask_3_dimensions.md`.
