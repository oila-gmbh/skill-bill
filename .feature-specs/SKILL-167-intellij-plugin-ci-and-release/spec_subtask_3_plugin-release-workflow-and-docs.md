# SKILL-167 · Subtask 3 — Plugin release workflow and docs

## Scope

Make the plugin downloadable on its own cadence: a dedicated release workflow on the
`plugin-v*.*.*` tag stream that publishes exactly one zip and its checksum sidecar as a
GitHub Release, plus the documentation that governs the new tag stream. `release.yml` is
untouched; a runtime tag never builds the plugin.

### Design decision: dedicated workflow on its own tag stream

New file (e.g. `.github/workflows/plugin-release.yml`) triggered by
`push: tags: ['plugin-v*.*.*']`, running entirely on `ubuntu-latest` (same hosted-only
posture as subtask 1; nothing here may touch the self-hosted runner). Single build-and-publish
flow:

1. Checkout, `setup-java` Temurin JDK 21, Gradle caching — mirroring subtask 1's setup.
2. Derive the version from the tag: `plugin-v0.1.0` → `0.1.0`. Validate the remainder is
   plain semver and fail otherwise.
3. Run `(cd intellij-plugin && ./gradlew check verifyPlugin buildPlugin -Pversion=<version>)`
   with `failureLevel` unchanged from `intellij-plugin/build.gradle.kts:61-66`. This is the
   release-side `verifyPlugin` run parent AC 3 requires.
4. Produce the canonical asset pair: `skill-bill-intellij-plugin-<version>.zip` (renamed from
   `buildPlugin`'s `build/distributions/` output) and a `<name>.zip.sha256` sidecar in the
   same `sha256sum`-style format `release.yml` emits for its assets.
5. Publish a GitHub Release for the tag carrying exactly those two assets.

### Design decision: tag-derived version via `-Pversion`, file untouched

`build.gradle.kts:12` and `pluginConfiguration.version` (`build.gradle.kts:37`) both read
`providers.gradleProperty("version")`, and a `-P` command-line property overrides
`gradle.properties`. So the release build passes `-Pversion=<tag-derived>` and
`intellij-plugin/gradle.properties` keeps `version=0.1.0-SNAPSHOT` for local development —
published zip and `plugin.xml` version agree (parent AC 7) with no sed-patching of tracked
files and no risk of a dirty checkout.

### Design decision: fail-closed asset discipline

Mirror the unexpected-asset posture of `release.yml:288-319`: build the expected-asset list
(`skill-bill-intellij-plugin-<version>.zip` + `.sha256`), fail when an expected asset is
missing, fail when any file staged for upload is not on the list, and only then create the
release with exactly that list. A plugin release must never grow extra assets silently.

### Docs

- `RELEASING.md`: add a plugin-release section — tag format `plugin-vX.Y.Z`, that the plugin
  versions and releases independently of runtime semver, the asset pair the workflow
  publishes, and that plugin tags must never be used for runtime releases (nor runtime tags
  for plugin releases).
- `intellij-plugin/README.md`: document installing from the released zip
  (Settings → Plugins → ⚙ → Install Plugin from Disk…) as the primary installation path,
  pointing at the GitHub Release asset, with building from source (`./gradlew buildPlugin`)
  as the fallback.

## Acceptance Criteria

1. A dedicated workflow triggers only on `plugin-v*.*.*` tags; `release.yml` has zero diff,
   and no runtime tag or runtime release path builds the plugin.
2. The workflow derives the build version from the pushed tag (`plugin-v0.1.0` → `0.1.0`) via
   a command-line Gradle property; `intellij-plugin/gradle.properties` keeps its `-SNAPSHOT`
   version for local development.
3. The workflow runs the plugin's `check`, `verifyPlugin` (with `failureLevel` unchanged from
   `build.gradle.kts:61-66`), and `buildPlugin` using `intellij-plugin/gradlew`, never
   `runtime-kotlin/gradlew`, on `ubuntu-latest`.
4. The published GitHub Release carries exactly `skill-bill-intellij-plugin-<version>.zip`
   and its `.sha256` sidecar; a missing expected asset or any unexpected staged asset fails
   the workflow before the release is created.
5. `RELEASING.md` gains the plugin-release section: tag format, independence from runtime
   versioning, and that plugin tags must never be used for runtime releases.
6. `intellij-plugin/README.md` documents install-from-released-zip as the primary path and
   build-from-source as the fallback.
7. Workflow YAML is syntactically valid (`actionlint` or a successful dry validation).

## Non-Goals

- No JetBrains Marketplace publishing, vendor identity, signing keys, or `publishPlugin`
  wiring — a downloadable zip is the deliverable.
- No bundling of the plugin into the runtime release, the skills tarball, or `install.sh`;
  installing Skill Bill never installs the plugin.
- No auto-update or runtime↔plugin version-compatibility checking.
- No change to plugin behavior, its compatibility range, or its
  `com.intellij.modules.platform`-only dependency.
- No actual `plugin-v0.1.0` tag push within this feature — first release happens after merge
  (parent spec "Next Path").

## Dependency Notes

- Depends on subtask 2: the installer/update-check hardening must be complete before a
  workflow capable of publishing `plugin-v*` releases exists, because a plugin release
  becoming GitHub's `latest` would otherwise break `install.sh` (parent spec hazard).
- Mirrors subtask 1's JDK/caching/hosted-runner setup for its build steps; subtask 1 is not a
  hard dependency but landing it first keeps the two workflows consistent.

## Validation Strategy

- Static validation: `actionlint` on the new workflow; by-inspection diff check that
  `release.yml` is untouched and no runtime workflow gained plugin references.
- The tag→version derivation and the fail-closed asset loop are plain bash; exercise them
  with a direct scripted invocation (fixture directory of staged assets) where practical,
  matching how `release.yml`'s discipline is trusted today.
- End-to-end proof is deliberately post-merge: tagging `plugin-v0.1.0` and installing the
  published zip into a clean IDE is the parent spec's "Next Path", not a gate on this
  subtask.
- Docs changes are review-only; no build execution in implement/review phases.

## Next Path

Feature complete after this subtask. Post-merge: push `plugin-v0.1.0`, verify the release
carries the zip + sidecar pair, and install it from disk in a clean IDE.
