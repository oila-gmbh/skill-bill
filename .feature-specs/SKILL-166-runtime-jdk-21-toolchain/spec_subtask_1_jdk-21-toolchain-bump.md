# SKILL-166 Subtask 1 - JDK 21 toolchain and runtime-image bump

Parent spec: [.feature-specs/SKILL-166-runtime-jdk-21-toolchain/spec.md](spec.md)
Issue key: SKILL-166

## Scope

Bump the Kotlin runtime build and ship toolchain from JDK 17 to JDK 21 in one reviewable
change. Update the version constants that drive compilation and jlink, re-validate the
hand-pinned `IMAGE_MODULES` set against the 21 module graph, move build-logic's own
source/target settings to 21, retarget the four CI `setup-java` pins and their stale
comments, and update `RELEASING.md` so release policy matches the code.

Primary files:

- `runtime-kotlin/build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Jvm.kt`
- `runtime-kotlin/build-logic/convention/src/main/kotlin/RuntimeImageConventionPlugin.kt`
- `runtime-kotlin/build-logic/convention/build.gradle.kts`
- `.github/workflows/validate-agent-configs.yml`
- `.github/workflows/install-smoke-test.yml`
- `.github/workflows/release.yml`
- `RELEASING.md`

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

- No adoption of JDK 21 language or library features (virtual threads, pattern matching,
  etc.) in this change.
- No change to `intellij-plugin` (already on 21).
- No Kotlin, Gradle, or dependency version upgrades bundled with this bump.
- No change to the runtime image module-set strategy (explicit additive pinning stays;
  only contents may be corrected if 21 requires it).

## Dependency Notes

Standalone. No upstream subtasks. Independent of SKILL-167.

## Validation Strategy

1. `(cd runtime-kotlin && ./gradlew check)` — must pass with `allWarningsAsErrors` intact.
2. `(cd runtime-kotlin && ./gradlew :runtime-cli:runtimeZip :runtime-mcp:runtimeZip)` —
   both link; run the produced CLI and MCP entry points locally.
3. Diff the four workflow `setup-java` pins and the RELEASING.md toolchain statements
   against 21.
4. After merge (or via staging `workflow_dispatch`), confirm the release matrix produces
   runnable images on macos-arm64, windows-x64, and linux-x64; confirm
   `install-smoke-test.yml` stays green.

## Next Path

After this subtask lands, cut a staging release and install from it on each supported host
before tagging a real version.
