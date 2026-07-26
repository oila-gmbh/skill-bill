# SKILL-82 — True one-liner install via release skills bundle

## Context

The `curl -fsSL https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh | bash`
one-liner added to the README does not work for fresh installs. `install.sh` derives
`PLUGIN_DIR` from `$(dirname "$0")`, which resolves to the current working directory when bash
reads from a pipe — not the repo root. On a fresh machine with no local clone, `SKILLS_DIR`,
`PLATFORM_PACKS_DIR`, and `ORCHESTRATION_DIR` do not exist and the install aborts with
"Missing authored skills source."

The runtime binaries (CLI, MCP) are already fetched from GitHub Releases. Skill content has no
equivalent: it is always sourced from the local `PLUGIN_DIR` tree. Every user must therefore
clone the repo before installing — hidden friction that undercuts the promise of a one-command
install.

Two additional gaps surfaced alongside this:

- **No clean-install path.** Users who want to reset their locally-customised skills to
  canonical upstream have no explicit mechanism; they must manually delete
  `~/.skill-bill/skills/` and reinstall.
- **Uncommitted working-tree improvements.** The no-TTY conflict abort was replaced with
  keep-local-and-warn, and `--prefer-upstream` was added — but these changes were never
  committed to main.

## Intended outcome

`curl -fsSL https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh | bash`
works end-to-end on a fresh machine with no prior clone. The release pipeline publishes a
minimal skills bundle alongside the runtime zips; `install.sh` downloads it automatically when
no local clone is present. Users who want a clean slate have an explicit `--clean` flag.

## Acceptance Criteria

1. The release pipeline publishes `skill-bill-skills-<version>.tar.gz` (and its `.sha256`) as
   a release asset containing exactly `skills/`, `platform-packs/`, `orchestration/`, and
   `uninstall.sh`.
2. `install.sh` detects when `SKILLS_DIR` does not exist, resolves the matching release,
   downloads and checksum-verifies the skills bundle, extracts it to a temp directory, and
   sets `PLUGIN_DIR` to the extracted root before proceeding normally. A local clone always
   wins: when `SKILLS_DIR` exists no bundle is downloaded.
3. `--clean` flag wipes `~/.skill-bill/skills/`, `~/.skill-bill/platform-packs/`, and
   `~/.skill-bill/orchestration/` before staging so reconcile finds no conflicts. Documented
   in `--help`. Composable with `--prefer-upstream`.
4. An offline smoke test (using the `SKILL_BILL_RELEASE_DIR` seam) verifies that running
   `install.sh` against a temp home dir with no prior clone exits 0 and installs at least one
   skill into the target agent directory.
5. The uncommitted working-tree changes (`--prefer-upstream` flag, no-TTY keep-local
   behaviour, README one-liner, install details update) are committed to main.
6. The README Quickstart one-liner is accurate: it works on a fresh machine without a
   separate clone step.

## Non-goals

- The skills bundle is not a standalone distribution. Users do not interact with it directly.
- Skill content changes require a new release to be picked up by the one-liner. This is
  intentional: versioned runtime + skills pairings are simpler to reason about.
- Windows native support for the `curl | bash` flow is out of scope. Windows users install
  via the `.msi` desktop installer or WSL.
