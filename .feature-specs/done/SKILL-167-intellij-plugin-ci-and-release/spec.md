# SKILL-167: IntelliJ plugin CI build and independent release artifact

Status: Prepared

## Intended Outcome

The IntelliJ status plugin is (a) built and tested in CI on every change that touches it,
and (b) downloadable as its own GitHub Release asset on its own tag stream and its own
cadence — never bundled into the Skill Bill runtime release, because the plugin is useful
only to IntelliJ users while the runtime serves everyone.

## Background

`intellij-plugin/` is a fully separate Gradle build: its own `settings.gradle.kts`, own
wrapper, JDK 21 toolchain, IntelliJ Platform Gradle Plugin 2.16.0, compiling against IDEA
2025.2.6.2 with a declared compatibility range of `sinceBuild=252` .. `untilBuild=261.*`.
`docs/getting-started.md:470` states the deliberate rule: do not fold IntelliJ Platform
tasks into `runtime-kotlin`.

Today **no CI workflow touches it at all**:

- `validate-agent-configs.yml:62` runs `cd runtime-kotlin && ./gradlew check` only.
- `install-smoke-test.yml` builds `runtime-cli`/`runtime-mcp` `installDist` only.
- `release.yml` contains zero plugin references.

So plugin tests run only on a maintainer's machine, and SKILL-165's acceptance depends on
those suites staying green with nothing enforcing it. The plugin is also undistributed:
`intellij-plugin/gradle.properties` says `version=0.1.0-SNAPSHOT` and no release has ever
carried a plugin zip, so the only way to obtain it is to build it yourself.

### Why a separate tag stream, and the hazard it creates

`release.yml`'s publish job enforces a strict asset allow-list and **fails on any
unexpected asset** (`release.yml:300-314`), so a plugin zip cannot simply be dropped into
the runtime release without editing that list — and doing so would also force a plugin
build on every runtime release and chain the plugin's version to the runtime's, which is
wrong: the plugin's compatibility is dictated by IDE builds, not by runtime semver.

A separate tag stream introduces one real hazard that this feature must close.
`install.sh:178` and `install.sh:455` resolve the installer's target via
`https://api.github.com/repos/$RELEASE_REPO/releases/latest` and take `tag_name` verbatim.
GitHub's `latest` is the most recent non-draft, non-prerelease release **by date**, so a
plugin release published after a runtime release would become `latest` and the installer
would try to install the plugin as the runtime. The runtime's own `UpdateCheckService`
does not share this bug — it lists releases and selects `maxByOrNull { version }` over
tags that `Semver::parse` accepts — but that resilience is incidental and must be
covered by an explicit test, not assumed.

## Acceptance Criteria

1. A new workflow (separate file, not folded into `validate-agent-configs.yml`) runs
   `cd intellij-plugin && ./gradlew check` on pull requests and pushes to `main`, gated on
   `paths: ['intellij-plugin/**']` plus the workflow's own file, with `setup-java`
   provisioning JDK 21 and Gradle caching enabled.
2. The workflow uses the same fork-safety posture as `validate-agent-configs.yml`: fork
   PRs never execute on the self-hosted runner. Plugin `check` on `ubuntu-latest` alone is
   sufficient — the plugin is pure JVM and has no macOS-specific behavior.
3. `verifyPlugin` does **not** run on pull requests (it downloads IC 2025.2.5 and IDEA
   2026.1 on top of the compile baseline). It runs on a scheduled nightly job and on the
   plugin release workflow, with `failureLevel` unchanged from `build.gradle.kts:61-66`.
4. A deliberately broken plugin test fails the new workflow, and a change touching only
   `runtime-kotlin/**` does not trigger it — both demonstrated before the feature is done.
5. Plugin releases run on their own tag stream, `plugin-v*.*.*`, in a dedicated workflow.
   The existing `release.yml` is untouched, and a runtime tag never builds the plugin.
6. The workflow publishes a GitHub Release for that tag carrying exactly
   `skill-bill-intellij-plugin-<version>.zip` and its `.sha256` sidecar, produced by
   `./gradlew buildPlugin`, with the same unexpected-asset fail-closed discipline
   `release.yml` uses.
7. `intellij-plugin/gradle.properties` `version` is derived from the pushed tag for release
   builds (`plugin-v0.1.0` → `0.1.0`) so the published zip and the `plugin.xml` version
   agree; local development builds keep a `-SNAPSHOT` version.
8. `install.sh` no longer resolves its target through `/releases/latest`. Both call sites
   (`install.sh:178`, `install.sh:455`) select the newest release whose tag is a plain
   runtime semver, ignoring `plugin-v*` tags entirely. Regression-covered: a fixture where
   a `plugin-v*` release is the most recent must still resolve the correct runtime tag.
9. `UpdateCheckService` ignores `plugin-v*` releases — asserted by a test feeding a release
   list where a `plugin-v*` entry is newest, expecting the newest runtime semver as the
   update candidate and no `unknown` reason.
10. `RELEASING.md` gains a plugin-release section: the tag format, that it is independent of
    runtime versioning, and that plugin tags must never be used for runtime releases.
11. `intellij-plugin/README.md` documents installing from the released zip
    (Settings → Plugins → Install Plugin from Disk) as the primary path, with building from
    source as the fallback.

## Non-Goals

- No JetBrains Marketplace publishing, signing keys, or `publishPlugin` wiring. The
  Marketplace is the eventual destination but needs vendor identity and signing secrets
  decided separately; a downloadable zip is the deliverable here.
- No bundling of the plugin into the runtime release, the skills tarball, or `install.sh`.
  Installing Skill Bill never installs the plugin.
- No change to plugin behavior, its compatibility range, or its dependency on
  `com.intellij.modules.platform` only.
- No move of `intellij-plugin/` into `runtime-kotlin`'s Gradle build, and no separate
  repository — the plugin stays in this repo with its own build and its own tag stream.
- No auto-update or version-compatibility check between the installed plugin and the
  installed runtime CLI.

## Constraints

- The plugin build must stay a standalone Gradle build; the new workflow invokes
  `intellij-plugin/gradlew`, never `runtime-kotlin/gradlew`.
- Plugin CI must not slow down runtime PRs — hence the path filter; a runtime-only PR must
  not pay for an IntelliJ Platform download.
- The `install.sh` change in AC 8 is a correctness fix to a live installer path. It must be
  verified against real GitHub API responses (or recorded fixtures of them), not only by
  reading the code.
- The plugin's compatibility range (IDEA 2025.2 .. 2026.1) means the published zip goes
  stale as new IDEs ship; the nightly `verifyPlugin` job is what surfaces that, so it must
  report failures somewhere a maintainer sees them.

## Dependencies

None on SKILL-166 — the plugin already builds on JDK 21 independently of the runtime's
toolchain. Ordering between the two is free.

## Next Path

After merge, tag `plugin-v0.1.0` to produce the first downloadable plugin release, then
verify a clean IDE installs it from the published zip.
