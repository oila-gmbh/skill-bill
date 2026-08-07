# SKILL-166: Runtime JDK 21 toolchain and runtime-image bump

Status: Prepared

## Intended Outcome

The Kotlin runtime compiles, tests, links, and ships on **JDK 21** instead of 17. The
shipped self-contained jlink images embed a 21 runtime, every CI workflow provisions 21,
and the release documentation states 21 as the pinned build toolchain. End users are
unaffected — they never supply a JDK; the runtime image carries its own.

## Background

Three constants pin 17 today, plus four workflow steps and the release docs:

- `runtime-kotlin/build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Jvm.kt:13`
  — `JDK_VERSION = 17`, driving `jvmToolchain`, the Java toolchain `languageVersion`, and
  `jvmTarget = JvmTarget.JVM_17`.
- `runtime-kotlin/build-logic/convention/src/main/kotlin/RuntimeImageConventionPlugin.kt:22`
  — `LINK_JDK_VERSION = 17`, the JDK jlink links the distributed runtime image against,
  together with the hand-pinned additive `IMAGE_MODULES` set (kotlin-inject and
  kotlinx.serialization are automatic modules, so jdeps cannot derive the set reliably).
- `runtime-kotlin/build-logic/convention/build.gradle.kts:13-19` — source/target 17 for
  build-logic itself.
- `.github/workflows/validate-agent-configs.yml:55`, `install-smoke-test.yml:32`,
  `release.yml:67` and `release.yml:185` — `setup-java` pinned to temurin 17.
- `RELEASING.md:55` — documents the JDK 17 pin as release policy.

This is **not** motivated by CI toolchain friction. Both Gradle builds already apply the
foojay resolver (`runtime-kotlin/settings.gradle.kts:13`), so Gradle auto-provisions any
declared toolchain regardless of the runner's JDK; a 17 runtime build and a 21 plugin build
coexist today without conflict. The motivation is access to JDK 21 language and library
features in the runtime itself (virtual threads being the obvious candidate for the process
wait loops) and moving off a major that is two LTS releases behind.

## Acceptance Criteria

1. `JDK_VERSION` is 21 and `jvmTarget` is `JvmTarget.JVM_21` in `Jvm.kt`; every
   `runtime-kotlin` module compiles and `./gradlew check` passes with
   `allWarningsAsErrors = true` still enabled — no warning suppression added to get there.
2. `LINK_JDK_VERSION` is 21 and `IMAGE_MODULES` is re-validated against the 21 module
   graph: `:runtime-cli:runtimeZip` and `:runtime-mcp:runtimeZip` link successfully, and
   the produced images run the CLI and MCP entry points. Any module added or removed from
   `IMAGE_MODULES` carries a comment saying why, matching the existing F-002 style.
3. build-logic's own `sourceCompatibility`/`targetCompatibility`/`jvmTarget` move to 21 and
   the included build still configures cleanly.
4. All four `setup-java` steps across the three workflows pin temurin 21; the comment at
   `validate-agent-configs.yml:53` explaining the pin is updated rather than left stale.
5. `RELEASING.md` states JDK 21 as the pinned release toolchain wherever it states 17.
6. A staging release (`workflow_dispatch` with a `staging_version`, per RELEASING.md
   "Staging a release for downstream testing") produces linkable, runnable images on all
   three release hosts — macos-arm64, windows-x64, linux-x64 — before the change is
   considered done. jlink cannot cross-compile, so a green Linux build is not evidence for
   the other two.
7. `install-smoke-test.yml` stays green: the installed CLI and MCP images start and respond
   on a clean machine.

## Non-Goals

- No adoption of JDK 21 features in this change. This is a toolchain bump only; virtual
  threads, pattern matching, and any other 21 feature land in separate work so a
  regression here is unambiguously a toolchain regression.
- No change to `intellij-plugin`, which already targets 21 on its own (dictated by the IDE
  it loads into, not by this repo's choice).
- No Kotlin, Gradle, or dependency version upgrades bundled in — keep the diff attributable.
- No change to the runtime image's module-set *strategy* (explicit additive pinning stays;
  only its contents may be corrected).

## Constraints

- `allWarningsAsErrors = true` means any new deprecation the 21 compiler surfaces is a hard
  build failure. Fix the code; do not weaken the flag.
- The release matrix builds natively per host because jlink cannot cross-compile, and the
  macos-arm64 leg runs on the self-hosted Mac mini — that host must have JDK 21 available
  (via `setup-java` provisioning) for a release to complete.
- Runtime image artifact names and the `<os>-<arch>` host-token contract are unchanged;
  `install.sh` and `resolveHostRuntimeToken` resolve the same filenames as before.
- The bump must be a single reviewable change — if `IMAGE_MODULES` needs correcting, that
  correction ships in the same commit as the version constants, never as a follow-up fix
  after a broken release.

## Dependencies

None. Independent of SKILL-167 (plugin CI and release), which touches only
`intellij-plugin/` and its own workflow.

## Next Path

After merge, cut a staging release first and install from it on each supported host before
tagging a real version.
