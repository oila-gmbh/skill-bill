# SKILL-82 Subtask 1 — Commit working-tree changes and publish skills bundle

## Scope

Commit the install.sh and README changes already in the working tree on `main`, then extend
the release pipeline to produce and upload a skills bundle asset alongside the runtime zips.

## Acceptance Criteria

1. All working-tree changes on `main` are committed: `--prefer-upstream` flag, no-TTY
   keep-local conflict behaviour, `--clean` flag variable declaration and argument parser
   entry (implementation in subtask 2), README Quickstart one-liner, and install details
   section rewrite.
2. `.github/workflows/release.yml` gains a step (on a single runner leg, not duplicated
   across the matrix) that tars `skills/`, `platform-packs/`, `orchestration/`, and
   `uninstall.sh` into `skill-bill-skills-<version>.tar.gz`, produces a `.sha256`, and
   uploads both as release assets via `actions/upload-artifact`.
3. The publish job's `actions/download-artifact` pattern picks up the skills bundle assets
   alongside the runtime zips so they are attached to the GitHub Release.
4. `./gradlew check` (or equivalent quality gate) passes on the committed state.

## Non-goals

- The `--clean` flag's wipe logic is implemented in subtask 2.
- The install.sh bundle-download bootstrap is implemented in subtask 2.
- The smoke test is implemented in subtask 3.

## Dependencies

None. This is the first subtask.

## Validation strategy

Run `./gradlew check` from `runtime-kotlin/`. Inspect the release workflow diff to confirm
the bundle step runs on exactly one matrix leg and the publish job collects the bundle asset.

## Next path

Subtask 2: implement install.sh headless bootstrap and `--clean` flag body.
