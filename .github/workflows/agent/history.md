# .github/workflows — Boundary History

## [2026-06-13] SKILL-82 subtask 1 skills-bundle-step
Areas: .github/workflows/release.yml
- Bundle step guarded by `if: matrix.host_token == 'linux-x64'` on the build job; tars skills/, platform-packs/, orchestration/, uninstall.sh into skill-bill-skills-${RELEASE_VERSION}.tar.gz + .sha256 (sha256sum available on ubuntu-latest). reusable
- Artifact name `release-assets-skills` is auto-covered by publish job's `pattern: release-assets-*` download — any new build-leg artifact named `release-assets-*` is picked up without changing the publish job. reusable
- `RELEASE_VERSION` is set via `$GITHUB_ENV` in 'Set release version' step; available as env var in subsequent steps on the same runner leg. reusable
Feature flag: N/A
Acceptance criteria: 2/4 parent ACs (CI pipeline only; bootstrap in subtask 2, smoke test in subtask 3)

## [2026-05-29] ci-release-pipeline (SKILL-55 subtask 3)
Areas: .github/workflows, RELEASING.md, .github/actionlint.yaml
- `release.yml` now runs a `build` matrix (fail-fast:false) that produces per-OS artifacts on host-matched runners, then a `publish` job (`needs: build`) attaches them. jlink/jpackage cannot cross-compile, so one OS per matching runner.
- Runner→token map (canonical, matches subtasks 1-2 filenames): macos-14→macos-arm64, macos-15-intel→macos-x64, windows-latest→windows-x64, ubuntu-latest→linux-x64. Linux leg must `apt-get install fakeroot rpm` for .deb/.rpm.
- Build installers via host-native `:runtime-desktop:canonicalRename<Format>Installer` tasks, NOT the `packageDesktopInstallers` aggregate (the aggregate pulls all four formats and fails jpackage on the wrong host). Build images via `:runtime-cli:runtimeZip :runtime-mcp:runtimeZip`.
- Validation gate (gradlew check, agnix Found-0-errors, validate_agent_configs, validate_release_ref) is preserved verbatim and runs fail-closed in `publish` before any download/attach. Do not weaken it to make the matrix pass.
- Staging (reusable for downstream asset testing): push a `-rc.N` prerelease tag OR run `workflow_dispatch` with `staging_version`; staging path skips validate_release_ref, always publishes a prerelease, and omits `--verify-tag` (no pushed tag). Publish is idempotent: `gh release upload --clobber` if the release exists else `gh release create`.
- `.github/actionlint.yaml` (reusable) registers `macos-15-intel` so actionlint's stale runner-label DB stops false-failing; add new hosted labels here, not as suppressions.
- Known limit: macOS x64 depends on the `macos-15-intel` hosted image; if retired, drop that leg (fail-fast:false keeps the rest publishing). Installers are unsigned for v1.
Feature flag: N/A
Acceptance criteria: 7/7 implemented
