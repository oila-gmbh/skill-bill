# SKILL-52.4 · Subtask 3 — Desktop boundary leaks (Phase 2)

Parent overview: [spec.md](./spec.md)

Closes the Tier-1 desktop hexagon leaks: a JVM `Path` in a commonMain port (F3),
hard-coded `Dispatchers.Default` in commonMain UI (F4), and `runBlocking` on the
UI init path (F5). Each lands with the arch test that would have caught it.

Branch: `feat/SKILL-52.4-hexagon-leak-closure-followups` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: [1]
- dependency_reason: Phase 0's per-module dependency test (F9) and import scans
  must cover the desktop leaf modules before reshaping their commonMain ports
  and dispatcher wiring, so a regression is caught immediately. Independent of
  Subtask 2 (application seam) — could run in parallel, but ordered after
  Subtask 1.
- dependencies: [{subtask_id: 1, optional: false, skipped: false}]

## Scope (owns)

- **F3 — JVM Path in commonMain port.** `InstalledWorkspaceBaselineService`
  (`runtime-desktop/core/domain/src/commonMain/.../service/InstalledWorkspaceBaselineService.kt`,
  `import java.nio.file.Path` line 3, `modifiedSkillRelativePaths(workspaceRoot: Path)`
  line 17) must take a common-safe path type (`String` or a desktop-domain
  `WorkspacePath` value type), matching the pattern documented one file over in
  `ScaffoldModels.kt:250-252`. Update `FakeInstalledWorkspaceBaselineService`
  (core/testing commonMain) in lockstep. The jvmMain impl does the
  `java.nio.file.Path` conversion. Per the 2026-05-24 decision, Path is legal as
  inert data — the fix is to make the operation common-safe, not to ban
  Path-as-data globally. Add a test forbidding `java.nio.*` / `java.io.*`
  imports in `runtime-desktop/**/commonMain`.
- **F4 — hard-coded `Dispatchers.Default` in commonMain UI.**
  `SkillBillRoute.kt` (commonMain) has 15 `Dispatchers.` literals (14
  `Dispatchers.Default` + 1 `Dispatchers.IO`) plus peers; route them all through
  the injected `DispatcherProvider` from `core:common`
  (`.../di/Dispatchers.kt`). Note: `DefaultDispatcherProvider.io` currently maps
  to `Dispatchers.Default` — verify whether that is intended before relying on
  it for the one `Dispatchers.IO` site. Add a test forbidding `Dispatchers.`
  literals in desktop commonMain.
- **F5 — `runBlocking` on UI init path.** Make `RecentRepoRepository`
  (`SkillBillServices.kt` commonMain) fully suspend end-to-end (resolves open
  question 2: full-suspend is the cleaner choice). `RoomRecentRepoRepository.kt`
  (jvmMain) currently wraps async DAO calls in `runBlocking` at lines 16/27/39;
  remove. `SkillBillViewModel` construction must no longer block. Add a test
  forbidding `runBlocking` in desktop jvmMain non-test sources (documented
  allow-list for any justified exception).

## Reusable patterns / pitfalls

- SKILL-79 `RuntimeDesktopGatewayPolicyTest` whitelists desktop mappers — update
  in lockstep if the port-shape or service surface changes.
- Making `RecentRepoRepository` suspend ripples to `SkillBillViewModel`
  construction; ensure the read moves into a coroutine/`LaunchedEffect` rather
  than re-introducing a blocking call elsewhere.
- Desktop async change can reorder startup — verify first-run / open-repo flows
  via the `run`/`verify` skills (open repo, edit+save, scaffold wizard,
  first-run).
- Write each import-ban / `runBlocking` / `Dispatchers.` arch test first;
  confirm red against the unfixed tree, then green.
- No explanatory comments.

## Acceptance Criteria

1. AC8: no `runtime-desktop/**/commonMain` imports `java.nio.*`/`java.io.*`;
   `InstalledWorkspaceBaselineService` takes a common-safe path type;
   `FakeInstalledWorkspaceBaselineService` updated in lockstep; jvmMain impl does
   Path conversion; test enforces import ban.
2. AC9: no desktop commonMain references `Dispatchers.` literally; `SkillBillRoute`
   (15 literals) and peers route through injected `DispatcherProvider`; test
   enforces.
3. AC10: no `runBlocking` on desktop UI/init path; `RecentRepoRepository` fully
   suspend end-to-end; `SkillBillViewModel` construction no longer blocks; test
   forbids `runBlocking` in desktop jvmMain non-test (documented allow-list).
4. AC14 (this subtask's slice): all four canonical gates pass.
5. AC15 (this subtask's slice): golden/wire outputs byte-identical to pre-change.

## Non-goals

- No god-object decomposition (SkillBillViewModel / SkillBillFrame /
  RuntimeRepoBrowserService stay intact — that is Subtask 4).
- No collapsing the desktop mini-hexagon into the runtime hexagon.
- No application-seam (Phase 1) work.
- No behavioral change.

## Validation strategy

`bill-code-check` (Kotlin/Gradle → `./gradlew check`). Run all four canonical
gates. Snapshot golden outputs before, assert byte-equality after. Exercise the
desktop app via `run`/`verify` for the async/dispatcher changes.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-52.4-hexagon-leak-closure-followups/spec_subtask_3_desktop-boundary-leaks.md`.
