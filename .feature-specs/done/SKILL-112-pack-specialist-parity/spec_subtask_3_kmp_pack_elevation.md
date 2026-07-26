# SKILL-112 Subtask 3 - KMP Pack Elevation

## Scope

Fix the kmp pack's three structural defects (coverage crack, broken lane
boundaries, Android-only content under a KMP label), bring its specialists to
the subtask 1 standard, and repair add-on reachability. Together with
subtask 2 this completes the kotlin/kmp reference pair the remaining packs
are modeled after. Sources: the 2026-07-09 kmp audit and cross-pack matrix.

### 1. Close the non-UI KMP coverage crack

`expect`/`actual`/`commonMain`/`iosMain` are strong routing signals that pull
a diff into the kmp pack, whose only specialists are ui and ux-accessibility,
while the layered kotlin baseline is told to keep KMP-only concerns out of
scope. Declare the approved area `platform-correctness` in the kmp manifest
and add `bill-kmp-code-review-platform-correctness` covering, at minimum:

- expect/actual contract drift between target implementations
- commonMain dependency hygiene (no JVM-only artifacts leaking into common)
- kotlinx.serialization polymorphic registration parity per target;
  kotlinx-datetime timezone behavior differences
- `Dispatchers.Main` availability and dispatcher choice on iOS
- ObjC export shape and Skie flow bridging; suspend-function cancellation
  across the ObjC boundary

Update the kmp baseline routing table with the signals that select it, and
register its manifest pointers and native-agent entry following the existing
per-area patterns.

### 2. Fix lane boundaries

- `bill-kmp-code-review-ui`: add the standard `## Ignore` section deferring
  accessibility-only findings to ux-accessibility and escaping/secrets
  findings to the kotlin security baseline lane
- `bill-kmp-code-review-ux-accessibility`: delete the `## UI Delegation`
  section (a specialist must not run a sibling specialist); replace with an
  `## Ignore` deferral matching the standard; fix the misplaced closing
  bullet currently jammed under `### Error States`

### 3. Conform the UI specialist to the standard skeleton

Restructure `bill-kmp-code-review-ui/content.md` to the canonical
`## Focus` / `## Ignore` / `## Applicability` / `## Project-Specific Rules`
skeleton. Keep `compose-guidelines.md` as the governed rubric sidecar
(documented by subtask 1) and make it the single source of truth: the
checklist in `content.md` becomes section references into the sidecar
instead of a drift-prone duplicate. Verify after `./install.sh` that the
installed flat sidecar's `compose-guidelines.md` reference resolves (the
current install layout nests the file under `platform-packs/kmp/...` while
the sidecar links it as a sibling; native-agent output already inlines it).

### 4. Multiplatform identity of the UI lane

`compose-guidelines.md` is Android-only (`R.string`, `hiltViewModel`,
`collectAsStateWithLifecycle`). Add a Compose Multiplatform section covering
`Res.string` / `org.jetbrains.compose.resources.stringResource`,
`ComposeUIViewController` and `UIKitView` interop, lifecycle-runtime-compose
availability in commonMain, and CMP ViewModel scoping — and scope the
Android-only rules to Android targets so a `commonMain` diff is not reviewed
against APIs it cannot use. Apply the same target-scoping to the
ux-accessibility localization rules (`stringResource(R.string.xxx)`,
`strings.xml`).

### 5. Compose correctness and semantics depth

- ui (specialist + sidecar): unremembered or needless `derivedStateOf`;
  snapshot state written from background coroutines and `snapshotFlow` vs
  `.value` polling; `rememberUpdatedState` for stale captures in long-lived
  effects; `TextField` async state round-trips dropping characters
- ux-accessibility: rewrite `## Project-Specific Rules` with Compose
  semantics APIs at go-pack density: `Modifier.semantics`,
  `mergeDescendants`, `clearAndSetSemantics`, `contentDescription = null`
  for decorative images, `stateDescription`, `Role`, `liveRegion`,
  `heading()`, `error()` for validation, `traversalIndex` /
  `isTraversalGroup`, `minimumInteractiveComponentSize` / 48dp touch
  targets, `fontScale` layout survival; move the previews/error-state rules
  it currently holds into the ui lane

### 6. Add-on repairs

- make `android-r8-review.md` reachable: add a shrinker-config row
  (`proguard-rules.pro`, `consumer-rules.pro`, minify flags) to the baseline
  routing table
- move the "navigation" signal from the ux-accessibility routing row to the
  ui row, matching where the navigation add-on is registered
- `android-navigation-review.md`: extend beyond Nav3 to androidx.navigation
  (`SavedStateHandle` argument typing, `popUpTo`/`launchSingleTop` back-stack
  hygiene, dialog destination results)
- `android-interop-review.md`: add
  `setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)` and
  `AndroidView` factory/update recomposition semantics
- `android-compose-adaptive-layouts.md`: name the APIs
  (`ListDetailPaneScaffold`, `rememberListDetailPaneScaffoldNavigator`,
  `WindowSizeClass` breakpoints, `PaneScaffoldDirective`)
- `android-compose-edge-to-edge.md`: name
  `WindowInsetsControllerCompat.isAppearanceLightStatusBars` /
  `SystemBarStyle` for system-bar contrast

### 7. Structure conformance

Apply the subtask 1 standard across the pack (severity closers, skeleton,
lane boundaries) and remove `kmp` from the conformance-test exemption list.

## Acceptance Criteria

1. The kmp manifest declares `platform-correctness`, the new specialist
   exists with every Scope section 1 lane as enforceable rules, its pointers
   and native-agent entry are registered, and the baseline routing table
   selects it on multiplatform signals.
2. The ui specialist uses the canonical skeleton with an `## Ignore`
   deferral to ux-accessibility and security; the ux-accessibility
   specialist has no `## UI Delegation` section and defers escaping to
   security.
3. `compose-guidelines.md` remains the single rubric source (no duplicated
   checklist content), contains a Compose Multiplatform section, and its
   Android-only rules are target-scoped; the installed flat sidecar's
   reference to it resolves after `./install.sh`.
4. The ux-accessibility specialist names the Compose semantics APIs listed
   in Scope section 5 as enforceable rules.
5. Every add-on repair in Scope section 6 is applied; an R8-only diff and a
   navigation diff each match a routing-table row that reaches the
   registered add-on.
6. All kmp specialists carry the canonical severity closer; `kmp` is removed
   from the conformance-test exemption list with the test passing.
7. Frontmatter and `contract_version` values are unchanged pack-wide except
   for the new specialist's own files following existing per-area patterns.
8. `skill-bill validate` passes,
   `(cd runtime-kotlin && ./gradlew check)` passes including the kmp pack
   tests and `PlatformPackSchemaValidatesExistingPacksTest`, and
   `./install.sh` completes with the rendered kmp agents reflecting the new
   content.

## Non-Goals

- No changes to `code_review_composition` mechanics or the `kmp-baseline`
  mode pin; the kotlin baseline layering stays exactly as declared.
- No new add-on files; only repairs to existing add-ons and routing.
- No feature-task add-on changes (`feature_addon_usage` stays as-is).
- No ios-pack Swift review of `iosMain` Kotlin (covered by the new
  platform-correctness specialist here, not the ios pack).

## Dependency Notes

Depends on subtask 1 (standard, severity wording) and subtask 2 (the kotlin
baseline this pack layers). Blocks subtasks 4-7, which model their packs on
the completed kotlin/kmp pair.

## Validation Strategy

```bash
skill-bill validate --skill-name bill-kmp-code-review-platform-correctness
skill-bill render --skill-name bill-kmp-code-review-ui
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
./install.sh
grep -n "UI Delegation" platform-packs/kmp -r && exit 1 || true
```

## Next Path

On completion, proceed to subtask 4 (go pack alignment).
