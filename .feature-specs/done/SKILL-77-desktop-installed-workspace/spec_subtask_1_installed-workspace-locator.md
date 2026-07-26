# SKILL-77 subtask 1 - installed-workspace resolution seam

## Scope

Add an `InstalledWorkspaceLocator` seam in `runtime-desktop`:

- Interface in `core/domain` (alongside `SkillBillServices.kt` conventions);
  JVM implementation in `core/data`.
- Resolves the installed workspace root (`~/.skill-bill`) with injected
  home/env providers following the `JvmRuntimeAssetLocator` pattern
  (`core/data/.../JvmRuntimeAssetLocator.kt:21`) — no process-global
  `System.getProperty`/`System.getenv` reads in the domain layer.
- Reports availability: directory exists and contains `skills/` or
  `platform-packs/`, matching `looksLikeSkillBillRepo` semantics
  (`RuntimeRepoBrowserService.kt:919-920`).
- Home resolution must agree with `JvmDesktopFirstRunGateway.homeProvider`
  (line 55) so first-run install output and default-open later resolve the
  same root.
- Wire into kotlin-inject DI and expose to `feature/skillbill`.

No UI changes in this subtask.

## Acceptance Criteria

1. Locator returns the resolved `~/.skill-bill` path and `availability=true`
   when `skills/` or `platform-packs/` exists under it.
2. Locator returns `availability=false` for missing or empty directories
   without throwing.
3. Home resolution is injectable and tested against a temp dir; no
   process-global env reads inside the domain layer.
4. DI exposes the locator to `feature/skillbill` without breaking the
   existing graph.

## Non-Goals

- Auto-opening the workspace (subtask 2).
- Any git or baseline logic (subtasks 3 and 4).

## Dependency Notes

None — foundation subtask; every later subtask consumes this seam.

## Validation Strategy

- jvmTest for the locator against temp directories (available / missing /
  empty / only-one-subdir cases).
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Run bill-feature-task on .feature-specs/SKILL-77-desktop-installed-workspace/spec_subtask_2_default-open-installed-workspace.md
