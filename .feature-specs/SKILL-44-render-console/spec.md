# SKILL-44 Render Check and Install Console (Subtask 04)

Status: Complete

## Sources

- Authored subtask spec: `docs/desktop-skill-bill-app/ui-feature-subtasks/04-render-console.md`
- Parent context: `docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`
- Predecessor (load-bearing pattern): `.feature-specs/SKILL-44-validation-workbench/spec.md` and the
  SKILL-44 validation-workbench history entry in
  `runtime-kotlin/runtime-desktop/feature/skillbill/agent/history.md`.

## Context: SKILL-44 Subtask 04

- Working directory: `/mnt/c/Users/User/StudioProjects/skill-bill`.
- Branch: continue on `feat/SKILL-44-runtime-desktop-shell` (do NOT create a new branch).
- Subtask 01 (state/repo/tree) and Subtask 02 (validation workbench) are already shipped on this
  branch. Subtask 03 (authored editor) and Subtask 05 (Changes/History) are NOT shipped — guard any
  references to them.
- Subtask 02 established the canonical pattern this work should mirror: domain model -> gateway
  interface in `core/domain` -> JVM impl on `RuntimeRepoBrowserService` in `core/data` -> fake in
  `core/testing` -> ViewModel `beginRender` / `runRender` / `finishRender` with
  `activeOperationToken` stale-finish-unwind -> Route async wiring via
  `coroutineScope.launch { withContext(Dispatchers.Default) { ... } }` -> Frame UI updates.
- The render slice MUST mirror F-101 (stale-finish unwind via captured previous-summary),
  F-102 (capture session + previousSummary on the caller dispatcher), F-103 (refresh resets to
  UNAVAILABLE), F-104/F-105 (symmetric failure-fallback rows), F-106 (any clipboard / process side
  effect hoisted to route).

## Acceptance Criteria (contract)

1. Render check is disabled unless a renderable target is selected (renderable = governed authored
   skills, add-ons, and native agents — NOT groups, generated artifacts, placeholders, or empty
   selection), the repo is LOADED, and no other op is busy.
2. Running render check activates the Install console dock tab automatically.
3. Console output is structured: ordered phase header lines (`> render <target>`,
   `resolving target...`, `rendering wrapper`, `rendering pointer N`, etc.), the render-result
   block contents in declared order, and a terminal pass/fail line with run duration.
4. Generated outputs are listed as read-only artifacts in the Inspector `Generated artifacts`
   section and in the Install console run summary. Each maps to a path the renderer would have
   written (wrapper file + pointer files for skills; bundle artifacts for native agents; for
   add-ons there are no generated outputs to surface).
5. Runtime failures show exact error text (exception name + message) in the Install console and
   leave authored content + on-disk generated artifacts unchanged. The render is dry-run / preview
   only.
6. Git status refresh after render is a no-op seam (guarded for Subtask 05; provide the seam in
   the route but do not wire it).
7. `RenderGateway` wraps shared runtime render: skills via `renderAuthoringTarget(repoRoot,
   skillName)` (`runtime-core/scaffold/AuthoringRenderOutput.kt`); native agents via
   `parseNativeAgentSourceFile` + `renderNativeAgentSource` (or per-provider rendering via
   `NativeAgentRendering`); add-ons via a pass-through preview that reads the authored add-on file
   content and presents it as a single render block. No SKILL.md / pointer parsing to determine
   status.
8. Refresh and repo-switch reset render state to UNAVAILABLE (on-disk state may have changed).
   Mirrors Subtask 02 F-103.
9. Stale render finish is unwound when a newer op preempts (token-based, mirrors Subtask 02
   F-101: capture session + previous-summary at `beginRender` so the dispatcher work doesn't read
   mutable VM fields off-thread).
10. Pass/fail status appears in three surfaces simultaneously: Install console (terminal line),
    status bar (new `render:` item alongside the existing `validation:` item), and Inspector
    Generated artifacts section (header row showing run state + duration).
11. Render check is dry-run / preview only — does not write SKILL.md, pointer files, or any other
    file on disk.

## Non-goals

- Editing generated output (stays read-only).
- New renderer semantics; only reuse existing renderers.
- Packaging / install distribution.
- Git commit, push, or post-render git refresh wiring (Subtask 05 territory).
- Streaming process output (renderer is in-process and returns a final result).
- Cancellation mid-render.
- Wiring `bill-feature-implement` add-ons; this is a desktop UI feature, not an authored skill.

## Runtime References (verbatim, preserved)

### Shared renderer entry points (in-process, no disk writes)

- `skillbill.scaffold.renderAuthoringTarget(repoRoot: Path, skillName: String): AuthoringRenderResult`
  in `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/AuthoringRenderOutput.kt`.
  Returns `AuthoringRenderResult(repoRoot, skillName, blocks)` where each
  `AuthoringRenderBlock(header, content)` has a header like
  `===== SKILL.md: <relative> =====` or `===== pointer: <relative> =====`. The wrapper block comes
  first; pointer blocks follow in `platform.yaml` declaration order. No filesystem writes.
- `skillbill.nativeagent.parseNativeAgentSourceFile(path: Path): List<NativeAgentSource>` and
  `skillbill.nativeagent.renderNativeAgentSource(agent: NativeAgentSource): String` in
  `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/NativeAgentSource.kt`. For
  per-provider preview, use `NativeAgentProvider.<Provider>.render(source)` in
  `NativeAgentRendering.kt`.

### Validation-workbench pattern (the template to mirror)

`ValidationGateway` in
`runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/service/SkillBillServices.kt`:

```
interface ValidationGateway {
  fun validate(session: RepoSession?): ValidationSummary
  fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String?
}
```

`ValidationSummary`/`ValidationRunState` in
`runtime-kotlin/runtime-desktop/core/domain/src/commonMain/kotlin/skillbill/desktop/core/domain/model/SkillBillModels.kt`
shows the run-state enum + runtimeException fields shape.

ViewModel begin/run/finish + token in
`runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/state/SkillBillViewModel.kt`:

```
fun beginValidate(): ValidationRunRequest { activeOperationToken += 1; busyOperation = VALIDATE;
  val previousValidationSummary = validation; validation = ValidationSummary(state = RUNNING);
  return ValidationRunRequest(token, currentSession, previousValidationSummary) }
fun runValidate(request): ValidationRunResult { return ValidationRunResult(request, gateway.validate(request.session)) }
fun finishValidate(result): SkillBillState {
  if (result.request.token != activeOperationToken) {
    if (validation.state == RUNNING) { validation = result.request.previousValidationSummary; ... }
    return currentState
  }
  busyOperation = null; validation = result.summary; ...
}
```

Route async pattern in
`runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui/SkillBillRoute.kt`:

```
onValidate = {
  if (state.busyOperation == null) {
    val request = viewModel.beginValidate(); state = viewModel.state()
    coroutineScope.launch {
      val result = withContext(Dispatchers.Default) { viewModel.runValidate(request) }
      state = viewModel.finishValidate(result)
    }
  }
}
```

### Wire-up + scoping

DI on JVM in
`runtime-kotlin/runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/di/JvmDataBindings.kt`
adds multi-interface `@Provides` for the same `RuntimeRepoBrowserService` singleton. The new
`RenderGateway` should be added there as another `bindRenderGateway()` provider, with
`RenderGateway` implemented on `RuntimeRepoBrowserService` so it can reuse the existing per-repo
`snapshot.selections` (treeItemId -> `SelectionDetail`) to resolve render targets without
re-parsing.

### Test fakes

`FakeValidationGateway` template in
`runtime-kotlin/runtime-desktop/core/testing/src/commonMain/kotlin/skillbill/desktop/core/testing/FakeSkillBillServices.kt`
(throwOnX + scriptedSummary + call count) is the shape for `FakeRenderGateway`.

### UI surfaces

`SkillBillFrame.kt` toolbar currently has a placeholder `Render check` button with no `onClick`.
`SkillBillBusyOperation` enum (OPEN_REPO, REFRESH, CHOOSE_DIRECTORY, VALIDATE) needs a `RENDER`
value. `BottomDock` holds `activeTab` as Compose-local `remember { mutableStateOf(DockTab.Validation) }`
and must be hoisted to state-driven control so `beginRender` can activate `DockTab.Console`. The
status bar `WorkspaceStatusBar` already renders a `validation:` item via `describeValidationStatus`;
add a parallel `render:` item. The Inspector `Generated artifacts` section (`InspectorPane`)
currently renders only `editor.generatedArtifacts`; it must additionally render a header row when
a render run state exists (PASSED/FAILED/RUNNING/UNAVAILABLE + duration).

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
./gradlew check
```

Generic entry point: `bill-quality-check` (routes to Kotlin/Gradle).
