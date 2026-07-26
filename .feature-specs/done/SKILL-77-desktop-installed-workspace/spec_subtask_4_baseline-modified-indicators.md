# SKILL-77 subtask 4 - baseline locally-modified indicators

## Scope

Show per-skill locally-modified-vs-baseline indicators for installed-workspace
sessions, consuming the SKILL-76 baseline manifest read-only:

- Expose a read-only baseline facade in `core/data` via the existing
  `RuntimeComponent` bundle (`DesktopRuntimeApplicationServices.kt:74-98`)
  consuming `BaselineManifestPersistencePort.readBaseline`
  (`runtime-ports/.../BaselineManifestPersistencePort.kt`; a missing manifest
  is not an error). (UNVERIFIED: whether `RuntimeComponent` already exposes
  `baselineManifestPersistencePort` publicly; if not, add a small additive
  read-only DI exposure in `runtime-application`.)
- Compute per-skill modified flags by hashing live files under
  `~/.skill-bill/skills/<skill>` (and platform-pack skills) with the SAME
  hash format the manifest stores. Verify against the SKILL-76 writer
  (`runtime-infra-fs` `InstallStaging` / `FileSystemBaselineManifestWire`;
  entries are skill-relative path → hash,
  `ReconciliationModels.kt:122-139`). Reuse the writer module's hasher if
  visible rather than reimplementing; the wire format must match exactly.
- Per-skill boolean granularity: any file hash mismatch, or a file
  added/removed relative to the manifest entries under that skill's prefix,
  marks the skill modified. Per-file granularity is deferred.
- Decorate `SkillBillTreeItem` (or the tree assembly in
  `RuntimeRepoBrowserService`) with the indicator for installed-workspace
  sessions only (session identity from subtask 2).
- Never call `writeBaseline` — the desktop app is a consumer, not a second
  writer of baselines.

## Acceptance Criteria

1. A skill with all file hashes matching the baseline shows no indicator;
   modifying any file flips the per-skill indicator on the next tree refresh.
2. Added or deleted files under a skill relative to the manifest entries mark
   the skill modified.
3. A missing `baseline-manifest.json` yields no indicators and no errors.
4. Clone sessions never show baseline indicators.
5. No write path to the baseline manifest exists from desktop code (assert by
   API surface: only the read port is consumed).

## Non-Goals

- Per-file indicators.
- Conflict resolution or baseline-adoption actions.
- Triggering any reconcile.

## Dependency Notes

- Depends on subtask 1 (installed-workspace identity) and subtask 2 (the
  default-open tree that renders the indicators).
- Independent of subtask 3; they may land in either order after 2.

## Validation Strategy

- jvmTest: temp workspace + handwritten manifest fixtures matching the
  SKILL-76 wire format (match / mismatch / added / removed / missing-manifest
  cases).
- ViewModel/tree test with fakes asserting installed-only decoration.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Run bill-feature-task on .feature-specs/SKILL-77-desktop-installed-workspace/spec_subtask_5_first-run-handoff.md
