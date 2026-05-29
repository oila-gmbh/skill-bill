# SKILL-55 Subtask 4 - install.sh: Prebuilt-First Fetch + Install UX Hardening

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 4 of 6
Depends on: subtask 3
Branch model: same-branch, commit per subtask

## Purpose

Make `./install.sh` fetch and verify the prebuilt artifacts published by
subtask 3 **by default**, eliminating the hidden JDK requirement and the
multi-minute Gradle build from the end-user path. Keep the from-source build as
an explicit fallback for maintainers and unsupported architectures. Then harden
the install UX per the launch-readiness discussion: gate the desktop-app default
on a real display, state what the script touches, and add a one-liner to install
the app later.

## Scope

In scope:

- **OS/arch detection** mapping the host to the canonical `<os>-<arch>` token
  defined in subtasks 1-2 (`macos-arm64`, `macos-x64`, `windows-x64`,
  `linux-x64`).
- **Prebuilt-first runtime install**: by default, download the matching
  `runtime-cli` + `runtime-mcp` self-contained images for the install's target
  version from the GitHub Release, verify the `.sha256`, unpack, and feed them
  into the **existing** `install_packaged_runtime_distributions` path
  (`install.sh:234`) — reusing its copy + symlink-launcher model. No Gradle, no
  JDK.
- **From-source fallback**: a `--from-source` flag (and automatic fallback when
  no artifact matches the host, e.g. an unsupported arch) runs the current
  `build_kotlin_runtime_distributions` path (`install.sh:249`). The current
  Gradle build behavior is preserved byte-for-byte behind this flag.
- **Version selection**: default to the latest stable release; allow
  `--release <tag>` / an env override; `--from-source` ignores releases.
- **Desktop app, prebuilt**: step 3 of the install ("install app?") downloads
  the prebuilt installer/app from the release (subtask 2) instead of building it.
  Place it in the documented per-user location (`README.md` "Desktop App") and
  install the `skillbill-desktop` launcher as today.
- **Display gating of the app default**: default the app prompt to **yes** only
  when a desktop session is detected (`$DISPLAY` / `$WAYLAND_DISPLAY` on Linux,
  always-on macOS/Windows interactive, interactive TTY); default to **no** on
  headless/SSH/CI. Non-interactive runs honor `--with-desktop-app` /
  `--no-desktop-app` as today.
- **Transparency + reversibility**: before mutating the system, print a concise
  summary of what will change (agent symlinks, `~/.local/bin` launchers, MCP
  registration, the "clean slate" reset noted at `install.sh:182`) and point at
  `uninstall.sh`. If the desktop artifact is unsigned (subtask 2 decision), print
  the OS-specific "how to open" hint on success.
- **`skill-bill desktop install`** (or documented equivalent): a post-install
  one-liner that downloads + installs the prebuilt desktop app later, so
  declining at install time is not a re-clone-and-rebuild chore.
- Update `uninstall.sh` if new install locations/artifacts are introduced.

Out of scope:

- Producing the artifacts (subtasks 1-3).
- README / quickstart copy (subtask 5) — but the install flags and messages this
  subtask introduces are the source of truth subtask 5 documents.
- Auto-update of installed binaries (follow-up).

## Acceptance Criteria

1. On a clean machine with no JDK on `PATH` and no `JAVA_HOME`, `./install.sh`
   (against a published or staged release) installs the runtime without a Gradle
   build; `skill-bill version` and `skill-bill doctor` succeed.
2. Downloaded artifacts are checksum-verified against their `.sha256`; a
   mismatch or failed download fails loudly and does not install a partial state.
3. `./install.sh --from-source` reproduces today's Gradle build-and-install
   behavior; the same path is used automatically when no artifact matches the
   host arch, with an explicit message saying why.
4. The desktop app installs from the prebuilt artifact (no Compose build); its
   prompt defaults to yes on a desktop session and no on headless; `--with-desktop-app`
   / `--no-desktop-app` still force the choice in non-interactive runs.
5. `skillbill-desktop` launches the prebuilt app; if unsigned, the success
   message includes the OS-specific open instructions.
6. Before any system mutation, the installer prints what it will change and how
   to reverse it (`uninstall.sh`).
7. A documented post-install command installs the desktop app later without
   re-running the full installer.
8. `uninstall.sh` cleanly removes everything this subtask installs.

## Validation

```bash
# Clean-machine prebuilt path (no JDK): in a container/VM without Java —
./install.sh --release <staged-tag>
skill-bill version && skill-bill doctor

# From-source fallback still works (maintainer host with JDK):
./install.sh --from-source
skill-bill version

# Headless default check (no DISPLAY): app prompt defaults to no / is skipped.
# Desktop session check: app prompt defaults to yes.

# Maintainer gate:
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Implementation Notes

- The plumbing already exists: `install_packaged_runtime_distributions`
  (`install.sh:234`) consumes a `source_dir`; this subtask's download step just
  produces that `source_dir` from a release asset instead of from a Gradle
  `build/install/...` directory. The `SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD`
  branch (`install.sh:254`) is the seam to generalize into "prebuilt by default,
  build on `--from-source`."
- Keep `curl`/`tar`/`unzip`/`shasum`(`sha256sum`) as the only new hard deps and
  detect them up front with a clear message — these are near-universal, unlike a
  JDK.
- Windows: the repo installer is bash (`install.sh`) and already handles
  `cygpath` (`install.sh:99,378`); keep the prebuilt path working under
  Git-Bash/MSYS, or document the Windows install entry point explicitly. Do not
  silently assume POSIX.
- **Sequencing guardrail (from the parent discussion):** the desktop default-yes
  is only safe once prebuilt artifacts exist. Since this subtask depends on
  subtask 3, that ordering is satisfied — but do not merge the default-yes change
  ahead of artifact availability.
- Preserve the "clean slate" reset semantics (`install.sh:182`) and idempotent
  re-runs; the prebuilt path must be as re-runnable as the build path.
