# SKILL-82 Subtask 2 — install.sh headless bootstrap and --clean flag

## Scope

Make `install.sh` self-sufficient when no local clone is present: detect the absence of
`SKILLS_DIR` at startup, resolve the matching release, download and verify the skills bundle,
extract it to a temp directory, and re-point `PLUGIN_DIR` before the normal install flow
begins. Also implement the body of the `--clean` flag (the variable and parser entry landed
in subtask 1; this subtask adds the wipe logic that fires when the flag is set).

## Acceptance Criteria

1. At startup, when `SKILLS_DIR` does not exist, `install.sh` prints an info line indicating
   it will fetch the skills bundle, resolves the release tag (from `--release TAG` if
   supplied, otherwise the latest stable release via the GitHub API), downloads
   `skill-bill-skills-<version>.tar.gz` and its `.sha256` using the existing
   `fetch_release_asset` / `verify_sha256` helpers, extracts the archive to a temp dir under
   `PREBUILT_WORK_DIR`, and sets `PLUGIN_DIR` to the extracted root. The rest of the install
   proceeds unchanged.
2. When `SKILLS_DIR` exists (local clone or prior install), no bundle download occurs. The
   local tree always wins.
3. When `--clean` is set, `install.sh` wipes `~/.skill-bill/skills/`,
   `~/.skill-bill/platform-packs/`, and `~/.skill-bill/orchestration/` before staging the
   candidate tree. The wipe runs after the bundle bootstrap (if applicable) and before
   `stage_authored_candidate` so reconcile finds no prior state.
4. `--clean` is documented in the `usage()` output.
5. `--clean` and `--prefer-upstream` are composable: passing both is valid (on a clean slate
   `--prefer-upstream` is a no-op since there is nothing to conflict with).
6. The offline test seam (`SKILL_BILL_RELEASE_DIR`) works unchanged: when set, the bundle
   asset is copied from that directory rather than fetched from GitHub.
7. `shellcheck` (or the existing install.sh quality gate) passes on the modified script.

## Non-goals

- The smoke test asserting end-to-end behaviour is in subtask 3.
- Asset naming or release pipeline changes are in subtask 1.

## Dependencies

- Subtask 1 must be complete (skills bundle asset exists in releases and `--clean` variable +
  parser entry are committed).

## Validation strategy

Run the install against a temp home dir using `SKILL_BILL_RELEASE_DIR` pointing at a local
directory containing a pre-built skills bundle tarball and runtime zips. Assert exit 0 and
that skills appear under the temp agent directory.

## Next path

Subtask 3: write the automated smoke test and verify README accuracy end-to-end.
