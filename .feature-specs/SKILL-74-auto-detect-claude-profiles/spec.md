---
status: Complete
---

# SKILL-74 - auto-detect claude profiles

## Mode

single_spec

## Intended Outcome

A single `./install.sh` run installs (and `./uninstall.sh` removes) Skill Bill
skills and native subagents for **every** Claude Code profile present on the
machine — the default `~/.claude` and every named `~/.claude-<name>` profile
directory (for example `~/.claude-work`) — without the maintainer setting
`CLAUDE_CONFIG_DIR` or maintaining a profile list. Personal and work Claude
accounts stay in sync from one plain install run.

## Overview

Claude Code resolves its config root from `CLAUDE_CONFIG_DIR`, falling back to
`~/.claude`. Maintainers commonly run multiple profiles through a launcher (for
example a `cc` fish function: `cc` -> `~/.claude`, `cc work` ->
`CLAUDE_CONFIG_DIR=~/.claude-work`), which creates sibling config directories
like `~/.claude-work` under `$HOME`.

Commit `40be2e8b` (honor `CLAUDE_CONFIG_DIR` on install) made the runtime resolve
the claude root through `claudeConfigRoot(home, environment)` so an install lands
in the *active* profile rather than always `~/.claude`. But that still depends on
`CLAUDE_CONFIG_DIR` being set at run time and targets exactly **one** root:
`agentPaths` returns one `Path` per agent, `AgentTarget` carries one path, and the
plan/link/detect/cleanup pipeline assumes one claude target. There is no
persistent record of which profiles exist, so a plain `./install.sh` cannot know
`~/.claude-work` exists and leaves it empty.

This feature **auto-discovers** claude profiles by scanning `$HOME` for the
default `~/.claude` and any `~/.claude-<name>` directory, unions in the active
`CLAUDE_CONFIG_DIR` (which may live outside `$HOME` or under a non-standard name),
deduplicates, and fans the existing skill-link, native-subagent, and cleanup
operations out across every discovered root. No environment variable and no
configuration file are required: the filesystem is the source of truth.

## Acceptance Criteria

1. A new runtime resolver returns the **ordered, deduplicated set** of claude
   config roots, composed of:
   - the default `~/.claude` (always included);
   - every existing top-level `$HOME/.claude-<name>` directory discovered by
     scanning `$HOME`;
   - the `CLAUDE_CONFIG_DIR` root when set, non-blank, and resolved to a distinct
     absolute path (covers roots outside `$HOME` or with non-standard names).
   Ordering is stable (default first, then discovered names, then any extra
   `CLAUDE_CONFIG_DIR` root) and entries are deduplicated by normalized absolute
   path. It reuses the existing `claudeConfigRoot`/`CLAUDE_CONFIG_DIR` env
   semantics for the env contribution.
2. Discovery matches only directories named exactly `.claude` or beginning with
   `.claude-`; it never matches files (for example `~/.claude.json`) and never
   recurses below `$HOME` top level.
3. A discovered `~/.claude-<name>` directory is treated as a profile only when it
   looks like a real Claude Code config root — it contains at least one
   recognizable marker (for example `.claude.json`, `.credentials.json`,
   `commands/`, or `agents/`) — so unrelated `~/.claude-*` directories are not
   clobbered. The default `~/.claude` and any explicit `CLAUDE_CONFIG_DIR` root
   are always included regardless of markers.
4. `install apply` links Skill Bill skills into `<root>/commands` for **every**
   resolved claude root in one run, using the existing staging-cache symlink
   target — one shared `~/.skill-bill/installed-skills`, no duplicate staging per
   root.
5. Claude native subagents are linked into `<root>/agents` for every resolved
   claude root, mirroring the skill-link fan-out.
6. `./uninstall.sh` removes Skill Bill skill links and native-subagent links from
   `<root>/commands` and `<root>/agents` for every resolved claude root, using the
   same discovery so no profile is left with orphaned links.
7. The orphan-link ownership sweep (links pointing into
   `~/.skill-bill/installed-skills`) runs against every resolved claude root.
8. Detection mode (`--agent-mode detected`) reports claude as present when any
   resolved claude root exists and targets all existing resolved roots.
9. The existing single-path seams remain backward compatible: `install agent-path
   claude` continues to print one path (the active root — `CLAUDE_CONFIG_DIR` if
   set, else `~/.claude`); callers needing the full set use the new multi-root
   seam rather than a changed return type on the existing command.
10. Profile discovery is owned by the runtime (a single resolver/command), and
    both `install.sh` and `uninstall.sh` consume it rather than re-globbing
    `$HOME` in shell, so install and uninstall always agree on the root set.
11. `install.sh` populates every discovered claude root in a single plain
    invocation (no env var required), and the final summary lists each claude
    root that was installed into.
12. Re-running install is idempotent per root: existing correct links are left in
    place, links are never created outside a resolved claude root, and a profile
    created after a prior install is picked up on the next run.
13. When only the default `~/.claude` exists (no named profiles, no
    `CLAUDE_CONFIG_DIR`), behavior is identical to the post-`40be2e8b` baseline.
14. Tests cover:
    - resolver returns only `~/.claude` when no named profiles exist;
    - resolver discovers and orders multiple `~/.claude-<name>` profiles;
    - resolver excludes files (`~/.claude.json`) and unmarked `~/.claude-*` dirs;
    - resolver unions a distinct `CLAUDE_CONFIG_DIR` root (including outside
      `$HOME`) and deduplicates one that equals a discovered root;
    - apply links skills into every discovered root's `commands` dir in one run;
    - uninstall removes links from every discovered root's `commands` and
      `agents` dirs;
    - detection reports/targets all existing resolved roots;
    - single-profile path is unchanged from baseline.

## Constraints

- Reuse the `claudeConfigRoot`/`CLAUDE_CONFIG_DIR` env precedence as the single
  source of truth for the env contribution; do not re-parse the env var ad hoc in
  shell or Kotlin, and do not glob `$HOME` in more than one place.
- Thread the environment through existing seams the way the codebase already does
  (defaulted `environment: Map<String,String> = System.getenv()`); keep discovery
  testable by accepting an injectable `$HOME`/environment rather than reading
  process globals deep inside pure planning logic.
- Do not change the install-selection JSON schema, the staging-cache layout, or
  the `~/.skill-bill/installed-skills` content-hash scheme.
- Keep one staging cache shared across roots; multiple roots symlink to the same
  staging dirs, they do not get independent copies.
- Do not change non-claude agent path resolution.
- Keep generated runtime artifacts and install staging output out of source.

## Non-Goals

- A configuration file listing claude profiles to fan out to; discovery is
  filesystem-driven, not config-driven.
- Creating `~/.claude-<name>` profile directories that do not already exist
  (other than the canonical default `~/.claude`, which install may create as
  today).
- Per-profile divergent skill/platform selections; all resolved roots receive the
  same selection in a given run.
- Multi-root support for non-claude agents (copilot/codex/opencode/junie).
- Discovering Claude profiles outside `$HOME` except via an explicit
  `CLAUDE_CONFIG_DIR`.
- Desktop app or first-run wizard changes.

## Open Questions (Resolved)

1. **Marker strictness.** Resolved: the marker set is `.claude.json`,
   `.credentials.json`, `commands/`, `agents/`, and `history.jsonl` (a
   `~/.claude-<name>` dir qualifies if it contains ANY of these). The default
   `~/.claude` and any explicit `CLAUDE_CONFIG_DIR` root bypass the marker test
   entirely. Implemented in `ClaudeConfigPaths.claudeConfigRoots`.
2. **Fan-out seam.** Resolved: apply-internal fan-out. The runtime resolver
   (`claudeConfigRoots`) expands the single claude target into one
   `<root>/commands` (and `<root>/agents`) target per resolved root inside the
   plan-builder/policy and native-agent operations. `install.sh` drops the pinned
   `--agent-target claude=<path>` so the runtime fans out; both shells consume the
   `install claude-roots` command for summary/uninstall rather than re-globbing
   `$HOME`.

## Validation Strategy

- Add focused runtime tests for the root-set resolver (discovery, marker
  filtering, env union/dedup) and for apply/uninstall fan-out across multiple
  roots (extend `ClaudeConfigDirAgentPathTest` and the install/cleanup CLI tests).
- Add an installer shell or architecture test asserting every discovered root is
  populated in one plain run and cleaned by uninstall.
- `(cd runtime-kotlin && ./gradlew check)` for runtime changes.
- `skill-bill validate`.
- `scripts/validate_agent_configs`.

Run bill-feature-task on .feature-specs/SKILL-74-auto-detect-claude-profiles/spec.md
