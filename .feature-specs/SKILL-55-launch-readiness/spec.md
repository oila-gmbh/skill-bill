# SKILL-55 - Launch Readiness: Prebuilt Distribution + Front Door

Created: 2026-05-28
Status: Draft
Issue key: SKILL-55
Parent: none (top-level feature)

## Decomposition

This feature is decomposed because launch readiness spans five distinct
boundaries with strict sequencing — each later stage consumes the artifact the
previous one produces: the build toolchain (self-contained per-OS images), the
desktop packaging toolchain, the release/CI pipeline, the installer, and the
documentation/launch surface. It is too large for one reliable execution and
the foundations (prebuilt artifacts) are independently verifiable before the
consumers (installer, docs) are touched.

Implement on one branch with a commit per subtask:

1. [Self-Contained Runtime Images (CLI + MCP), per-OS, no JDK](./spec_subtask_1_self-contained-runtime-images.md)
2. [Desktop App Installers, per-OS](./spec_subtask_2_desktop-app-installers.md)
3. [CI Release Pipeline → GitHub Releases](./spec_subtask_3_ci-release-pipeline.md)
4. [install.sh: Prebuilt-First Fetch + Install UX Hardening](./spec_subtask_4_install-prebuilt-first.md)
5. [Front Door: README Hero, Demo Asset, 60-Second Quickstart](./spec_subtask_5_front-door-readme-and-demo.md)
6. [Launch Kit: Reddit / Product Hunt Assets](./spec_subtask_6_launch-kit.md)

## Sources

- Product discussion 2026-05-28 (launch-readiness assessment). Key conclusions:
  - The install friction that a cold Reddit / Product Hunt visitor judges is
    **not** step count (two commands is fine); it is the hidden JDK requirement
    and the multi-minute from-source build on the default path.
  - The desktop app is the most graspable / differentiated surface and the
    Product Hunt "hero"; it must be a **prebuilt download**, not a from-source
    Compose build.
  - "App install default-yes" is only safe **once the app ships prebuilt**, and
    should be gated on a real display so headless installs don't get a useless
    GUI artifact.
- Default build path that this feature replaces for end users:
  - `install.sh:274` — `./gradlew :runtime-cli:installDist :runtime-mcp:installDist`
  - `install.sh:337` — `./gradlew :runtime-desktop:prepareDesktopAppDistributable`
- Prebuilt-install plumbing already in the tree (currently unfed and non-default):
  - `install.sh:234` `install_packaged_runtime_distributions`
  - `install.sh:254` `SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1`
- Desktop packaging already wired:
  - `runtime-kotlin/runtime-desktop/build.gradle.kts:163` `nativeDistributions`
  - `runtime-kotlin/runtime-desktop/build.gradle.kts:193-206`
    `packageDmg`/`packageMsi`/`packageDeb`/`packageRpm`
  - desktop bundles `runtime-cli`/`runtime-mcp` installDist (lines 95, 102)
- Release contract: `RELEASING.md` (tag-driven GitHub Releases, pre-1.0 SemVer,
  `Release` workflow).
- Existing CI: `.github/workflows/release.yml`,
  `.github/workflows/validate-agent-configs.yml`.
- Per-user desktop install locations + launchers: `README.md` "Desktop App".

## Context

Skill Bill works and is deep, but its first-time install experience is the weak
point a cold visitor judges in under a minute. The product is technically
ready; the front door is not. `git clone` + `./install.sh` is good by
step-count, but the default path silently requires a JDK and runs a
multi-minute Gradle build for `runtime-cli` + `runtime-mcp`, and — when the
desktop app is selected — a heavier, host-constrained Compose packaging build.
For the maintainer and existing power users, Java is already present and this is
invisible. For a random visitor in a Node/Python world, "just run install.sh"
becomes "install a JDK, set JAVA_HOME, wait for Gradle," and if Java is missing
the build fails with an error that reads like Skill Bill is broken.

The prebuilt-install fallback path already exists in `install.sh`
(`SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD` →
`install_packaged_runtime_distributions`) but there are no published artifacts
for it to consume, and it is not the default.

## Problem

The end-user install path leaks the build toolchain:

- **CLI + MCP build from source by default**, requiring a JDK and a
  multi-minute first build. No published self-contained images exist.
- **Desktop app builds from source by default when selected** (the 3rd install
  prompt defaults to yes), which is the slowest, most host/toolchain-fragile
  step — and is exactly the step a launch visitor is most likely to trip on.
- **No prebuilt release artifacts exist** for the existing fast-install path to
  consume; the `Release` workflow reruns validation and publishes notes but does
  not produce per-OS binaries.
- **The README front door** leads with abstract framing and 25KB of detail
  rather than a 5-second hook, a demo, and a ≤60-second try path.
- **No launch assets exist** (post drafts, demo GIF/video, PH gallery), and
  nothing pre-empts the predictable "it won't open" (unsigned binary) and "this
  is overengineered" objections.

## Goals

1. Publish self-contained, per-OS runtime images for `runtime-cli` and
   `runtime-mcp` that run without a system JDK (macOS arm64 + x64, Windows x64,
   Linux x64).
2. Publish per-OS desktop app installers as release artifacts (download-and-open).
3. Extend the tag-driven `Release` workflow to build the above on matching host
   runners and attach them — with checksums — to the GitHub Release.
4. Make `./install.sh` fetch and verify the prebuilt artifact for the host
   OS/arch **by default**, falling back to a from-source build only for
   maintainers / an explicit `--from-source`.
5. Eliminate the hidden JDK + multi-minute build from the end-user path; target
   time-to-first-`skill-bill version` under ~1 minute on a clean machine.
6. Make the desktop app a prebuilt download and gate its install default on a
   real display.
7. Rewrite the README front door: one-sentence hook, demo asset, ≤60-second
   quickstart reflecting the prebuilt install.
8. Produce the launch kit (subreddit-targeted drafts, the personal-story hook,
   the objection-preempt framing, PH gallery/assets) pointing at the polished
   front door.
9. Keep the from-source build fully supported for maintainers and contributors.

## Non-Goals

- Do not change skill behavior, routing, MCP tool names, telemetry, workflow
  state, scaffold semantics, or any `bill-*` skill content.
- Do not remove or break the from-source build; `--from-source` / the maintainer
  Gradle path stays fully supported and is the fallback when no artifact matches.
- Do not change the tag-driven release contract or SemVer policy in
  `RELEASING.md`; only extend what the `Release` workflow produces.
- Do not make code signing / notarization a blocking requirement for this
  feature. Signing is tracked as a decision in subtask 2 and a risk the launch
  kit pre-empts; unsigned artifacts with explicit "how to open" instructions are
  acceptable for the first launch.
- Do not publish to Homebrew / winget / apt / other package managers here
  (follow-up). GitHub Releases is the launch distribution channel.
- Do not press "post." Subtask 6 produces the assets and the plan; the actual
  launch stays a human decision.

## Target Outcome

On a clean machine with no JDK:

```text
git clone <repo> && cd skill-bill && ./install.sh
# → detects OS/arch, downloads + verifies the prebuilt runtime, symlinks agents,
#   registers MCP, optionally installs the prebuilt desktop app (default yes on a
#   desktop session, no on headless). No JDK, no Gradle, under ~1 minute.
skill-bill version   # works immediately
```

The from-source path remains available:

```text
./install.sh --from-source   # maintainers / unsupported arch: builds via Gradle
```

## Acceptance Criteria (parent-level; satisfied collectively by subtasks)

1. A tagged release publishes, as downloadable assets with checksums:
   self-contained `runtime-cli` + `runtime-mcp` images for macOS arm64, macOS
   x64, Windows x64, Linux x64; and desktop installers for macOS, Windows, and
   Linux.
2. On a clean machine with no JDK, `./install.sh` (pointed at a published or
   staged release) completes without a Gradle build and `skill-bill version`
   and `skill-bill doctor` succeed.
3. `./install.sh --from-source` still builds and installs via Gradle exactly as
   today.
4. The desktop app installs from a prebuilt artifact; its install default is yes
   on a desktop session and no on headless; `skillbill-desktop` launches.
5. The README opens with a one-sentence hook, an embedded demo asset, and a
   quickstart that a newcomer can complete in ≤60 seconds, with no JDK mentioned
   as a prerequisite for the prebuilt path.
6. A launch kit exists under the spec directory: at least one subreddit-targeted
   post draft, the demo asset (or its storyboard + capture instructions if the
   binary asset is produced out-of-band), a PH gallery plan, and an
   objection-preempt FAQ covering "won't open / unsigned" and "overengineered."
7. The maintainer validation gate passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Validation Strategy

Per-subtask validation lives in each subtask spec. The end-to-end acceptance
beyond the maintainer gate is a **clean-machine install test**: a host (or
container) with no JDK on `PATH` and no `JAVA_HOME`, where `./install.sh`
against a staged release succeeds and `skill-bill version` runs. Where a real
clean host is unavailable, document the manual matrix that was exercised
(macOS, Windows, Linux) instead of silently claiming coverage.

## Recommended Next Prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-55-launch-readiness/spec.md
```
