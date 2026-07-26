# SKILL-84 — Update-check slash command

status: Draft

## Problem

Skill Bill can be installed from GitHub Releases, but users have no lightweight
way from an agent session to check whether their installed runtime and skill
content are behind the latest published release. Today they must remember the
installed version, inspect GitHub manually, and decide whether to rerun the
installer.

That gap is especially visible for slash-command users: the common workflows are
available as `/bill-*` commands, but update awareness lives outside the same
surface. A stale install can silently miss new skills, platform packs, runtime
fixes, or renderer behavior until the user happens to reinstall.

## Intended Outcome

Add a user-facing `/bill-update-check` slash command, backed by a non-mutating
runtime command, that reports whether a newer Skill Bill release is available.
The command should compare the installed version with the latest suitable GitHub
release and give a concrete next command when an update exists.

## Acceptance Criteria

1. A new installed slash command `/bill-update-check` is rendered and linked by
   the existing install flow alongside the other `bill-*` commands.

2. The slash command delegates to a runtime CLI command, for example
   `skill-bill update-check`, instead of embedding release-checking logic in the
   authored skill content.

3. The runtime command reads the locally installed Skill Bill version from the
   same source used by `skill-bill version`, so the comparison reflects the
   running installed runtime rather than the checkout's Gradle metadata.

4. The runtime command queries GitHub Releases for `Sermilion/skill-bill`,
   selects the latest stable semver release by default, and supports an explicit
   `--include-prereleases` option that includes prerelease tags in the
   comparison.

5. The command reports one of these states with clear terminal output and
   machine-readable JSON support: `up_to_date`, `update_available`,
   `ahead_of_release`, and `unknown`.

6. When an update is available, the output includes the installed version, latest
   release version, release URL, and the recommended install command:
   `curl -fsSL https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh | bash`.

7. Network failures, GitHub API rate limits, malformed release payloads, missing
   local version metadata, or non-semver local versions fail softly into
   `unknown` with a concise reason and a non-zero exit only for programmer or
   usage errors.

8. The check is read-only. It must not run `install.sh`, mutate installed skills,
   rewrite agent command links, touch workflow state, or update local repo files.

9. The installer includes `/bill-update-check` in the rendered command catalog
   without hand-authored generated wrappers under `skills/`; the source remains
   governed `content.md` only.

10. When only an issue key is provided, `bill-feature`, `bill-feature-task`, and
    `bill-feature-goal` search `.feature-specs` for a matching governed spec
    path and use the single clear match instead of immediately requiring the
    user to provide the path.

11. Tests cover version comparison ordering, stable-vs-prerelease selection,
    update/no-update/ahead/unknown result mapping, CLI text and JSON output, and
    installer slash-command registration, plus `.feature-specs` lookup for
    key-only feature workflow invocations.

## Non-goals

- Automatically installing updates.
- Background or scheduled update checks.
- Desktop-app update UX.
- Checking for unreleased commits on `main`.
- Supporting package managers beyond the existing install script.

## Constraints

- Keep release lookup behind a testable port so CLI tests can run without live
  network access.
- Reuse existing version and CLI result-mapping patterns where possible.
- Do not add generated `SKILL.md` wrappers or support pointer files to source.
- If GitHub credentials are absent, the public unauthenticated API path must
  still work until it is rate-limited.

## Validation Strategy

- Kotlin: `(cd runtime-kotlin && ./gradlew check)`.
- Repo validation: `skill-bill validate`.
- Installer smoke: run the install/link path against a temporary agent directory
  and confirm `/bill-update-check` is present.
- Manual CLI checks with a mocked or fixture-backed release source:
  `skill-bill update-check`, `skill-bill update-check --include-prereleases`,
  and `skill-bill update-check --format json`.

## Size

SMALL — one new runtime utility command, one governed slash-command skill, and
installer/catalog coverage.
