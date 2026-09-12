# SKILL-238 · Subtask 1 — Delete speculative leftovers

## Scope

Remove confirmed dead or undeclared speculative surfaces that add no shipped
product behavior. This subtask must not change runtime control flow.

Work includes:

- Delete `scripts/split-runloop.py` (one-off splitter for a run-loop file that
  no longer exists; prior history already claimed deletion).
- Delete undeclared TypeScript SKILL-116 scaffolds:
  `platform-packs/typescript/addons/offline-first-sync-review.md`,
  `di-backend-review.md`, `ai-llm-backend-review.md` (marked “Do not ship
  as-is”; not referenced from `addon_usage` / pointers).
- Remove or reduce `docs/delegated-review/` to a single pointer at
  `orchestration/review-delegation/` (SKILL-159-removed archive).
- Trim `docs/team-control-plane-roadmap.md` unbuilt CLI / hosted-org sections
  to match shipped team surfaces, or replace with an explicit “not shipped”
  stub — do not implement the CLI.
- Delete unused `jna` entry from `runtime-kotlin/gradle/libs.versions.toml`
  (and any unused catalog alias) when no module depends on it.
- Delete empty `app/src/main/java/com/amplitude/ampli/` package tree if still
  source-less.
- Remove stale GLM uninstall cleanup past the 2026-08-02 window in
  `uninstall.sh` only after confirming no remaining seeds; do not remove
  Copilot historical sweep paths.
- Fix any stale comments that still claim “seven agents” or point at deleted
  capture recipes only when those files are touched for the above cuts.
- Update affected area `agent/history.md` if docs/scripts boundaries require
  it.

## Acceptance Criteria

1. `scripts/split-runloop.py` is absent from the tree.
2. The three undeclared TypeScript add-on scaffolds listed in Scope are absent, and no pack manifest still references them.
3. `docs/delegated-review/` is gone or contains only a pointer to the live review-delegation contract; no archived capability bodies remain as if current.
4. Unused `jna` catalog version/library entries are removed from `runtime-kotlin/gradle/libs.versions.toml`.
5. Empty Amplitude `ampli` package tree is absent when it still has no sources.
6. GLM-only uninstall cleanup past the closed window is removed when no seeds remain; Copilot deprecation sweep paths stay.
7. No runtime-kotlin production behavior changes in this subtask beyond catalog/doc/script deletions.

## Non-Goals

- Role-port / forwarder collapses (subtask 2).
- IDE pause/stop / StatusClock work (subtask 3).
- Implementing team-control-plane CLI or filling TS add-on scaffolds.
- Hexagonal port redesign.

## Dependency Notes

- None. First executable unit; base branch is `main`.

## Validation Strategy

```bash
test ! -e scripts/split-runloop.py
test ! -e platform-packs/typescript/addons/offline-first-sync-review.md
rg -n 'jna' runtime-kotlin/gradle/libs.versions.toml && exit 1 || true
# keep Copilot sweep present
rg -n 'copilot' uninstall.sh
```

Confirm pack manifests and install/orphan scripts do not expect the deleted
add-ons. Prefer existing maintainer smokes that already cover uninstall /
install paths when available.

## Next Path

`.feature-specs/SKILL-238-yagni-port-necessity-alignment/spec_subtask_2_collapse-runtime-kotlin-yagni.md`
