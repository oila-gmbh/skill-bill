# SKILL-167 · Subtask 2 — Latest-release resolution hardening

## Scope

Close the hazard a `plugin-v*` tag stream creates for the two consumers that resolve "the
latest Skill Bill release" from GitHub, before any plugin release can exist:

- `install.sh` — both call sites that use `/releases/latest` and take `tag_name` verbatim:
  `resolve_release_installer_tag()` (`install.sh:178`) and `list_release_asset_names()`
  (`install.sh:455`, the no-`RELEASE_TAG` branch).
- `UpdateCheckService` (`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/updatecheck/UpdateCheckService.kt`)
  — already resilient (it lists `/releases` and takes `maxByOrNull { version }` over
  `Semver::parse`-accepted tags, so `plugin-v0.1.0` fails `Semver.kt:16`'s
  `^v?(\d+)\.(\d+)\.(\d+)...` pattern and is dropped), but that resilience is incidental and
  must be locked in by an explicit test.

### Design decision: list releases, filter to plain runtime semver

Replace both `/releases/latest` uses with one shared helper (e.g.
`resolve_latest_runtime_release_tag()`) that:

1. Fetches `https://api.github.com/repos/$RELEASE_REPO/releases?per_page=100` with the same
   `curl -fsSL` + `Accept: application/vnd.github+json` posture already used at
   `install.sh:459-464`.
2. Extracts `tag_name` values with the repo's established grep/sed JSON scraping style — no
   `jq` dependency (`install.sh:466-471` documents that constraint).
3. Keeps only tags matching plain runtime semver `^v?[0-9]+\.[0-9]+\.[0-9]+$`. This ignores
   `plugin-v*` entirely and also excludes prerelease-suffixed tags, matching the semantics of
   `/releases/latest` (which never returns prereleases).
4. Selects the highest version among the survivors by numeric major.minor.patch comparison —
   not list order (a backported patch release published after a newer minor must not win),
   and not `sort -V` (BSD `sort` on macOS has no `-V`).
5. Fails loudly, as today, when nothing resolves.

`resolve_release_installer_tag()` keeps its `RELEASE_TAG` short-circuit and calls the helper
otherwise. `list_release_asset_names()`'s no-`RELEASE_TAG` branch resolves the tag through the
same helper and then queries `/releases/tags/<tag>`, instead of reading `/releases/latest`
directly.

### Constraint: bash 3.2 compatibility

`install.sh` must remain bash 3.2 compatible (macOS ships 3.2): no bash-4+ features
(associative arrays, `mapfile`, `${var^^}`), and guard any array expansion that can be empty.
The version comparison must use portable tooling (awk or plain arithmetic over split fields).

### Design decision: regression fixtures ride the existing curl-shim harness

`scripts/install_smoke_test.sh` already stubs the GitHub API with a fake `curl` on `PATH`
(`run_piped_bootstrap_latest_release`, `install_smoke_test.sh:278-300`). Extend that harness:

- Update existing shims that answer `/releases/latest` to answer the new `/releases` list
  URL, keeping the scenarios green.
- Add the regression fixture demanded by parent AC 8: a `/releases` response — shaped like a
  real GitHub API payload (array of objects with `tag_name`, `prerelease`, `draft`,
  `assets`) — whose most recent entry is a `plugin-v*` release, with an older plain runtime
  release behind it. Assert the installer resolves the runtime tag, not the plugin tag.
- Keep the fixture faithful to real API responses per the parent constraint ("real GitHub API
  responses (or recorded fixtures of them), not only by reading the code").

### Design decision: UpdateCheckService gets a pinning test, no production change

No change to `UpdateCheckService.kt` is expected. Add a test to
`UpdateCheckServiceTest.kt` using its existing `service(responseBody = ...)` seam and
`releases(...)` fixture builder (`UpdateCheckServiceTest.kt:66-93`): feed a release list where
a `plugin-v*` entry is first/newest (e.g. `plugin-v9.9.9` ahead of `v0.4.0`), and assert the
result selects the newest runtime semver (`v0.4.0`) with a non-`UNKNOWN` status and no
`reason`. Note the fixture builder derives `prerelease` from `tag.contains("-")`; a
`plugin-v9.9.9` tag must land as `prerelease: false` to model a real plugin release, so extend
the builder or inline the entry accordingly.

## Acceptance Criteria

1. `install.sh` no longer contains any `/releases/latest` call: both
   `resolve_release_installer_tag()` and `list_release_asset_names()` resolve through a
   shared helper that lists releases and selects the highest plain runtime semver tag,
   ignoring `plugin-v*` tags entirely.
2. The helper honors the existing `RELEASE_TAG` override short-circuit unchanged, preserves
   the no-`jq` constraint, and stays bash 3.2 compatible.
3. A smoke-test fixture where a `plugin-v*` release is the most recent entry still resolves
   the correct runtime tag — both for installer-tag resolution and for release-asset listing.
4. All pre-existing `install_smoke_test.sh` scenarios pass with the shims updated to the new
   `/releases` URL.
5. `UpdateCheckServiceTest` gains a test feeding a release list where a `plugin-v*` entry is
   newest, asserting the newest runtime semver is the update candidate, the status is not
   `UNKNOWN`, and no `reason` is set.
6. No behavior change for repositories with only runtime releases: the resolved tag equals
   what `/releases/latest` would have returned in every existing scenario.

## Non-Goals

- No change to `UpdateCheckService` production code unless the new test exposes a real gap.
- No plugin release workflow or docs — subtask 3.
- No `jq` or other new runtime dependency for `install.sh`.
- No change to `RELEASE_TAG` / `SKILL_BILL_RELEASE_DIR` / `SKILL_BILL_RELEASE_BASE_URL`
  override semantics.
- No pagination beyond `per_page=100`; this repo's release count is nowhere near that bound.

## Dependency Notes

- Independent of subtask 1.
- Blocks subtask 3: the hazard fix must land before any workflow capable of publishing a
  `plugin-v*` release exists (parent spec, "Why a separate tag stream, and the hazard it
  creates").

## Validation Strategy

- The smoke-test harness is the verification vehicle for `install.sh`: run
  `scripts/install_smoke_test.sh` scenarios including the new plugin-tag-newest fixture.
  Do not exercise the live GitHub API in CI; fixtures mirror recorded API shapes.
- The `UpdateCheckServiceTest` addition runs with the standard runtime-kotlin test suite in
  CI (`validate-agent-configs.yml`); implement/review phases do not run Gradle locally —
  validation phases own test execution.
- `shellcheck` on `install.sh` stays clean (or at parity with current findings).

## Next Path

Subtask 3 introduces the `plugin-v*` tag stream this subtask made safe: the plugin release
workflow, tag-derived versioning, and the release documentation.
