# runtime-kotlin

Standalone Kotlin runtime for the local `skill-bill` CLI, MCP server, install
planner/apply path, desktop app backend, telemetry, workflow state, scaffolding,
and governed validation.

## Build

```bash
./gradlew check
```

## Self-contained runtime images (jlink)

`runtime-cli` and `runtime-mcp` can be packaged as self-contained images that
bundle a trimmed Java runtime, so they run on a machine with **no system JDK**.
These are produced with the Badass Runtime plugin (`org.beryx.runtime`, jlink
under the hood) and are independent of the existing `installDist` outputs.

Build the host images and their versioned archives:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:runtimeZip :runtime-mcp:runtimeZip)
```

The image name and archive name derive from `project.version` (no hardcoded
version). The canonical per-OS/arch target tokens are defined once in the
`skillbill.runtime-image` convention plugin
(`build-logic/convention/.../RuntimeTargets.kt`), consumed as a typed contract:

- `macos-arm64`
- `macos-x64`
- `windows-x64`
- `linux-x64`

**Cross-compilation is not supported** — each target's image must be built on a
matching OS/arch. Locally only the current host's image is reproducible (on this
repo's reference host, `linux-x64`); `macos-*` and `windows-x64` are built on
matching CI runners (subtask 3).

### Outputs and unpacked layout

The archive lands under each module's build directory (gitignored):

```
runtime-cli/build/runtime-image/runtime-cli-<version>-<os>-<arch>.zip
runtime-cli/build/runtime-image/runtime-cli-<version>-<os>-<arch>.zip.sha256
runtime-mcp/build/runtime-image/runtime-mcp-<version>-<os>-<arch>.zip
runtime-mcp/build/runtime-image/runtime-mcp-<version>-<os>-<arch>.zip.sha256
```

Each archive carries an adjacent `.sha256` checksum file. Unzipping yields a
stable `image/` layout with a `bin/` launcher and the bundled `bin/java`:

```
image/
  bin/
    runtime-cli        # CLI launcher (subtask 4 symlinks this to `skill-bill`)
    java               # bundled trimmed JRE — no system JDK required
  lib/
  conf/
  legal/
```

```
image/
  bin/
    runtime-mcp        # MCP launcher (subtask 4 symlinks this to `skill-bill-mcp`)
    java
  lib/                 # includes the generated telemetry-event schema resource
  ...
```

Run the unpacked launcher directly — it uses the bundled `bin/java`:

```bash
unzip runtime-cli-<version>-<os>-<arch>.zip -d /tmp/skill-bill-image
/tmp/skill-bill-image/image/bin/runtime-cli version
```

### Verification matrix

The no-JDK run (`skill-bill version` via `bin/runtime-cli`) and the `runtime-mcp`
stdio smoke are exercised locally on **`linux-x64` only**. `macos-arm64`,
`macos-x64`, and `windows-x64` are built and verified on their matching CI
runners (subtask 3); they cannot be reproduced from a Linux host.

The local smoke test only exercises `version` / stdio-init; full per-module path
verification (e.g. every jlink module the telemetry HTTP path needs) happens in CI
(subtask 3).

> The existing `:runtime-cli:installDist` / `:runtime-mcp:installDist` outputs and
> the desktop bundling that consumes them are **unchanged** — the runtime images
> are an additive, separate distribution path.

## Desktop App installers

The Compose desktop app (`runtime-desktop`) packages into native installers via
jpackage, **each bundling its own JRE** — end users need **no system JDK** to run
the installed app. The staged Skill Bill runtime resources
(`skill-bill-runtime/{runtime-cli,runtime-mcp,skills,platform-packs,orchestration}`)
are bundled inside the app as well.

Build the Linux installers and produce their canonical, checksummed artifacts:

```bash
(cd runtime-kotlin && ./gradlew :runtime-desktop:packageDeb :runtime-desktop:packageRpm)
(cd runtime-kotlin && ./gradlew :runtime-desktop:packageDesktopInstallers)
```

`packageDesktopInstallers` is the convenience aggregate: it depends on the per-format
package tasks (`packageDeb`, `packageRpm`, and on their respective OS, `packageDmg` /
`packageMsi`), then renames each produced installer to the canonical name and writes a
`.sha256` sidecar next to it.

> **Host tooling.** jpackage shells out to the OS's native packaging tools, so
> `packageDeb` needs `dpkg-deb` (+ `fakeroot`) and `packageRpm` needs `rpmbuild` on the
> build host. Without them jpackage fails with `Invalid or unsupported type: [deb]`; install
> the tool (e.g. `dpkg`/`rpm-build`) or run on a CI runner that has it. The app image and
> the canonical rename/`.sha256` step do not need these tools.

### Artifact naming

Each installer is renamed to:

```
SkillBill-<version>-<os>-<arch>.<ext>
SkillBill-<version>-<os>-<arch>.<ext>.sha256
```

`<version>` is the full `project.version` (e.g. `0.1.0-SNAPSHOT`). `<os>-<arch>` is the
canonical token from the same contract subtask 1 exposes
(`resolveHostRuntimeToken`: `macos-arm64` / `macos-x64` / `windows-x64` / `linux-x64`).
The embedded jpackage `--app-version` is a strict numeric `MAJOR.MINOR.PATCH` derived
from `project.version` (`0.1.0-SNAPSHOT` -> `0.1.0`), since jpackage rejects qualifiers.
Renamed artifacts land under each format's Compose output dir:

```
runtime-desktop/build/compose/binaries/main/deb/SkillBill-<version>-linux-x64.deb
runtime-desktop/build/compose/binaries/main/deb/SkillBill-<version>-linux-x64.deb.sha256
runtime-desktop/build/compose/binaries/main/rpm/SkillBill-<version>-linux-x64.rpm
runtime-desktop/build/compose/binaries/main/rpm/SkillBill-<version>-linux-x64.rpm.sha256
```

Verify a checksum from inside the format dir:

```bash
(cd runtime-desktop/build/compose/binaries/main/deb && sha256sum -c SkillBill-*-linux-x64.deb.sha256)
```

**Cross-compilation is not supported.** Locally only the Linux host's `.deb` / `.rpm`
are reproducible; `.dmg` (macOS) and `.msi` (Windows) build on matching CI runners
(subtask 3). The `packageDmg` / `packageMsi` rename tasks are wired identically and run
there.

### Signing

Installers ship **unsigned for v1** (see `agent/decisions.md`,
"Ship desktop installers UNSIGNED for v1"). End users open the app through the OS
escape hatch — macOS Gatekeeper: right-click the app -> **Open** -> **Open**; Windows
SmartScreen: **More info** -> **Run anyway**.

### Maintainer from-source path (unchanged)

The existing from-source desktop path still works for maintainers:

```bash
(cd runtime-kotlin && ./gradlew :runtime-desktop:prepareDesktopAppDistributable)
# or via the repo installer:
./install.sh --with-desktop-app
```

`prepareDesktopAppDistributable` produces an executable app image under
`runtime-desktop/build/compose/binaries/main/app` with the bundled runtime scripts
marked executable. This path is untouched by the installer work.

### Verified-OS matrix

| OS / arch     | Installer(s) | `.deb`/`.rpm` built | App image + bundled JRE + staged resources | GUI launch | Notes |
|---------------|--------------|---------------------|---------------------------------------------|------------|-------|
| `linux-x64`   | `.deb`, `.rpm` | **not on this host** — `dpkg-deb` / `rpmbuild` absent, jpackage reports "Invalid or unsupported type: [deb]"; build on a host with those tools (CI) | **verified present** — `createDistributable` / `prepareDesktopAppDistributable` produced `build/compose/binaries/main/app/SkillBill` with the `bin/SkillBill` launcher, the bundled JRE under `lib/runtime` (`libjvm.so`/`libjava.so`), and the staged `skill-bill-runtime/{runtime-cli,runtime-mcp,skills,platform-packs,orchestration}` | not exercised (headless) | canonical rename + `.sha256` task verified end-to-end against a `.deb`-shaped artifact (`sha256sum -c` OK); a real `.deb`/`.rpm` install + GUI launch is deferred to a host with the packaging tools and a display |
| `macos-arm64` | `.dmg`       | CI (subtask 3)      | CI (subtask 3)                              | CI (subtask 3) | cannot reproduce from a Linux host |
| `macos-x64`   | `.dmg`       | CI (subtask 3)      | CI (subtask 3)                              | CI (subtask 3) | cannot reproduce from a Linux host |
| `windows-x64` | `.msi`       | CI (subtask 3)      | CI (subtask 3)                              | CI (subtask 3) | cannot reproduce from a Linux host |

## IntelliJ / Android Studio

`runtime-kotlin` is a nested standalone Gradle project inside the larger `skill-bill` repo.

If you open only the outer `skill-bill` repository as a plain IntelliJ project,
Kotlin source files under module paths such as
`runtime-kotlin/runtime-core/src/main/kotlin` and
`runtime-kotlin/runtime-mcp/src/main/kotlin` may show unresolved `skillbill.*`
imports even though Gradle builds successfully.

Use one of these setups instead:

1. Open `runtime-kotlin/` as its own IntelliJ project.
2. If you keep the outer repo open, attach `runtime-kotlin/build.gradle.kts` as a Gradle project and run a Gradle sync.

If imports stay red after attaching the build, reload the Gradle project or restart the IDE.
