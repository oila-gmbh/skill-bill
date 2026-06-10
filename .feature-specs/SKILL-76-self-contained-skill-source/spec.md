---
status: Complete
---

# SKILL-76 - self-contained skill source of truth

## Mode

single_spec

## Intended Outcome

`./install.sh` copies the authored skills and platform packs into `~/.skill-bill`
as the **authoritative, editable source of truth** — the same way the runtime is
already a self-contained copy under `~/.skill-bill/runtime/` — so an installation
no longer depends on the fetched repo clone. After install a user can delete the
clone and everything still works; a user can edit a skill in place under
`~/.skill-bill` and have that edit **survive reinstalls** instead of being wiped
by the disposable staging cache. This breaks the tight coupling to the
maintainer's repository: local modifications no longer require a fork (which
drifts) or an upstream PR to persist.

## Overview

Today the runtime is fully decoupled from the repo: install copies the jlink
runtime distribution into `~/.skill-bill/runtime/` via `cp -R` + atomic `mv`
(`install.sh:636-652`), and nothing at run time reads the repo — the only symlink
is the launcher `~/.local/bin/skill-bill -> ~/.skill-bill/runtime/runtime-cli/bin/runtime-cli`
(`install.sh:855,862`). "Installed" for the runtime means a self-contained copy in
the state dir; the repo is only the build/fetch source.

Skills do **not** follow that model. The authored source of truth stays in the
repo clone (`skills/` + `platform-packs/`). On every install the runtime CLI
renders skills from the clone into a hash-keyed staging cache
(`~/.skill-bill/installed-skills/{slug}-{hash}/`) and points the agent symlinks
(`~/.claude/commands/`, etc.) at that staging dir; non-content-managed skills fall
back to a **direct symlink into the clone**. The staging cache is treated as
disposable and is regenerated / pruned each install. Two consequences:

1. The installation depends on the clone (de facto source of truth). Delete the
   clone and non-content-managed skills break; the next install needs it to
   re-render everything.
2. There is nowhere durable for a user to edit a skill. The staging cache is
   blown away and regenerated from the clone on every reinstall, so user edits do
   not persist, and forking the whole repo is the only way to keep changes — which
   drifts out of sync fast.

The runtime CLI's skill plan-and-apply is handed `--repo-root` pointing at the
clone (`install.sh:1778-1798`); at run time the runtime does **not** read skill
markdown (agents read it from their own dirs), so the clone dependency is purely
install-time wiring. Symlink creation is `InstallPrimitives.installSkill()`
(`Files.createSymbolicLink`); the target is resolved by
`InstallStaging.resolveStagedSymlinkTarget()` / `stageInstalledSkill()`, gated by
`isContentManagedSkill()`.

This feature makes `~/.skill-bill` the authoritative home for skills:

### Copied source of truth

On install, copy `skills/` and `platform-packs/` (plus the closure of repo-root
files the plan/apply step reads — manifests, `platform.yaml`, etc.) into
`~/.skill-bill` (e.g. `~/.skill-bill/skills`, `~/.skill-bill/platform-packs`),
mirroring the runtime copy pattern. This copied tree becomes the installation's
source of truth.

### Repointed wiring

The runtime's `--repo-root` and every skill symlink target resolve against the
copied tree under `~/.skill-bill`, never the clone. Content-managed skills stage
from the copy; non-content-managed skills fall back to a direct symlink into the
copy. After install, deleting the clone leaves a fully functional installation.

### Reconcile-on-reinstall (the crux)

The disposable-cache model is what loses user edits. Replace it with a
reconciliation pass that preserves local modifications, modeled on package-manager
config-file handling (dpkg "conffile" semantics):

- Record a per-skill **baseline**: the content hash of each upstream skill as last
  copied in (stored in a baseline manifest under `~/.skill-bill`).
- On reinstall, for each skill compare the current local copy against its stored
  baseline:
  - **Unmodified locally** (local == baseline): adopt the new upstream content,
    refresh the baseline.
  - **Modified locally, upstream unchanged** (local != baseline, upstream ==
    baseline): keep the local edit, no conflict.
  - **Modified locally AND upstream changed** (local != baseline, upstream !=
    baseline): **conflict** — keep the local edit, do **not** overwrite, surface
    the upstream version for manual reconciliation and report it in the summary.
- New upstream skills are copied in and baselined. Skills the user authored locally
  but that have no upstream counterpart are kept (never deleted) and reported.

The exact conflict-surfacing mechanism (sidecar file vs. report-only) is an Open
Question with a recommended default below.

## Acceptance Criteria

1. `./install.sh` copies `skills/` and `platform-packs/` (and the full closure of
   repo-root files the runtime plan/apply reads) into `~/.skill-bill` as a
   self-contained tree before skill linking runs.
2. The runtime receives a `--repo-root` (or equivalent) that points at the copied
   `~/.skill-bill` tree, not the clone; all skill resolution, staging, and symlink
   targets resolve against the copy.
3. After a successful install, deleting the source clone leaves a fully functional
   installation: every installed skill (content-managed and non-content-managed)
   still resolves, and a subsequent `./install.sh` run from the same copied tree
   succeeds without the clone present. (If a reinstall still requires the clone,
   that requirement is explicit and documented, not accidental.)
4. Non-content-managed skills fall back to a direct symlink into the copied tree
   under `~/.skill-bill`, never into the clone.
5. A user edit to a skill under `~/.skill-bill` survives a subsequent reinstall:
   when the local content differs from its recorded baseline and upstream is
   unchanged, the reinstall keeps the local content.
6. An unmodified local skill (local == baseline) adopts the new upstream content on
   reinstall, and its baseline is refreshed.
7. (REVISED — interactive; supersedes the original sidecar/report-only wording.)
   When BOTH the local copy and upstream have diverged from the recorded baseline,
   the reinstall WARNS the user and PROMPTS interactively: "accept" = overwrite the
   local edit with the new upstream version and refresh the baseline; "abort" = stop
   the whole install and change nothing. When running WITHOUT a TTY (CI, piped
   install), any conflict ABORTS the install with a clear message (no silent choice).
   All conflicts are reported in the install summary. CRITICAL: conflict detection
   must happen BEFORE the atomic swap commits, so an abort leaves the existing
   installation fully intact (no half-applied state). Open Question 1 (sidecar vs
   report-only) is SUPERSEDED by this interactive design.
8. New upstream skills are copied in and baselined on reinstall; locally authored
   skills with no upstream counterpart are preserved (not deleted) and reported.
9. Reconciliation is idempotent: a reinstall with no upstream or local changes
   produces no diffs, no spurious conflicts, and no baseline churn.
10. A first install onto a machine with a pre-existing repo-symlinked installation
    (current model) migrates cleanly to the copied-source model without manual
    cleanup and without leaving dangling symlinks into the clone.
11. Multi-profile fan-out (SKILL-74) and per-profile MCP registration (SKILL-75)
    continue to work unchanged against the copied source; `CLAUDE_CONFIG_DIR` is
    still honored.
12. Tests cover: copy-in populates `~/.skill-bill/skills` + `platform-packs`;
    `--repo-root` points at the copy; symlinks resolve into the copy not the clone;
    clone deleted → skills still resolve; reinstall preserves a user-edited skill;
    reinstall adopts upstream for an untouched skill; both-changed conflict keeps
    local + reports; locally authored skill preserved; non-content-managed fallback
    targets the copy; idempotent reinstall.

## Constraints

- Mirror the existing runtime copy pattern (`cp -R` into a temp dir + atomic
  `mv`/swap) for the skill copy; do not invent a divergent copy mechanism.
- Reuse the existing staging + symlink plumbing (`InstallPrimitives.installSkill`,
  `InstallStaging` staging/hashing). The change is the *source location* and the
  *reconciliation policy*, not the link mechanism — do not fork the link/stage code.
- Reuse the existing content-hashing utility for baseline detection rather than
  introducing a second hashing scheme; keep the baseline manifest under
  `~/.skill-bill`.
- Do not change the runtime distribution install — it is already self-contained.
- Do not change the `--runtime-*-build-dir` / runtime binary wiring; only the skill
  source root (`--repo-root`) moves.
- Preserve the `isContentManagedSkill` content-managed vs. non-content-managed
  distinction; only the resolved source/target location changes.
- Keep install/uninstall idempotent and keep the SKILL-74/75 multi-profile fan-out
  intact.

## Non-Goals

- **Portable installation / export-import** across machines — captured as a future
  sketch in *Future / Not Now*, not specified here.
- Pushing local modifications back upstream, any git integration, or a fork/sync/PR
  flow.
- A versioned per-skill registry or independent per-skill distribution/packaging.
- Three-way **automatic merge** of conflicting skills. On conflict we keep-local and
  surface upstream; we do not attempt to merge.
- Changing skill rendering/templating semantics, skill content, or the runtime image
  install.
- Deleting or "repairing" locally authored skills that have no upstream counterpart.

## Future / Not Now

**Portable installation (deferred).** Because `~/.skill-bill` will hold the
authoritative, editable skill source plus `install-selection.json`, a natural
follow-up is a `skill-bill export` command that snapshots the user's modified
content + selections into a single movable package, and a `skill-bill import` that
re-materializes the installation on a different machine. The hard part is the
runtime: it is a per-host-token jlink image (host-specific binaries), so the
package cannot simply carry the runtime binaries across OS/arch — import must
re-acquire the right runtime for the target host. **Deferred decision:** whether the
package is (a) content-only and fetches/builds the runtime on import, (b) content +
an embedded host-tagged runtime used offline only when the target host-token
matches (else fetch/build), or (c) content + all host runtimes for fully offline
import on any platform. This spec deliberately does not design it; SKILL-76 only
establishes the self-contained, editable source-of-truth that makes such a package
possible.

**Desktop app over the installed source (deferred).** Once `~/.skill-bill` holds
the authoritative, editable authored source (`skills/*/content.md`, platform
packs) and serves as a valid `--repo-root`, the existing `runtime-desktop` app —
today a graphical client over a *repo checkout* — can be re-pointed at the
**installed** copy instead. Editing `~/.skill-bill/skills/<slug>/content.md` in
the app, then triggering the app's existing validate + render action (equivalent
to `runtime-cli apply --repo-root ~/.skill-bill`), gives an in-place edit →
refresh loop without hunting through a hidden directory or running a full
`./install.sh`. SKILL-76 only establishes the self-contained editable source that
makes this coherent; the desktop re-targeting, a dedicated `skill-bill refresh`
ergonomic, and reconciliation-aware editing are out of scope here and tracked as a
future issue.

## Open Questions

1. **Conflict-surfacing mechanism.** RESOLVED / SUPERSEDED — see revised AC-7. No
   sidecar file. On a both-changed conflict the reinstall warns and prompts
   interactively (accept = overwrite local with upstream + refresh baseline; abort =
   stop the whole install, change nothing). Without a TTY any conflict aborts with a
   clear message. Conflict detection happens BEFORE the atomic swap so an abort leaves
   the existing installation fully intact. All conflicts are reported in the summary.
2. **Closure of copied repo-root files.** RESOLVED — subtask 1 copies the full
   read-closure into `~/.skill-bill`: `skills/`, `platform-packs/`, and the whole
   `orchestration/` tree (carrying `skill-classes/*.yaml`, every `PLAYBOOK.md` /
   `specialist-contract.md` / `shell-ceremony.md`, and the `android-*.md` support
   targets inlined at render time) as REAL files; `orchestration/contracts/*.yaml`
   is loaded as a classpath resource, not copied. The copy serves as `--repo-root`
   with the clone absent.
3. **Reinstall-without-clone.** RESOLVED — a *running* installation never needs the
   clone (all skills/staging/agent links resolve under `~/.skill-bill`). The one
   operation still using a source checkout is re-running `./install.sh` itself (the
   script and the upstream side of the reconcile live in the checkout) — documented
   explicitly in the README per AC-3, by design, not accidental.
4. **Baseline storage + algorithm.** RESOLVED — `~/.skill-bill/baseline-manifest.json`
   (`{contract_version, baselines: {sorted skill-rel-path → 16-hex}}`), written via a
   `runtime-infra-fs` adapter behind a domain port (atomic temp + ATOMIC_MOVE,
   sorted keys for byte-stable idempotent writes). Reuses `computeInstallContentHash`
   — the SAME hash that keys the `{slug}-{hash}` staging leaf — never a second scheme.
   Survives the pre-install wipe via the uninstall.sh preserve-mode allowlist.
5. **Migration trigger.** RESOLVED — no new detection code needed: the existing
   `--replace-existing-skill-bill-links` flag (already passed by install.sh) drives
   `InstallSymlinkReplacement.createManagedSymlinkWithGuidance(replaceExisting=true)`,
   which reads the old target and repoints any clone-pointing managed link onto the
   copy with no dangling clone link left. Asserted by `InstallApplyReplacementCleanupTest`.
6. **Default vs. opt-in.** RESOLVED — copied-source is the default immediately. NO
   transition flag / escape hatch. (Runtime already proves the pattern; AC-10 covers
   migration.)
7. **Disk duplication.** RESOLVED — duplicating `skills/` + `platform-packs/` into
   `~/.skill-bill` is accepted: (1) authored source vs (2) rendered staging cache is
   a source-vs-derived relationship, not wasteful duplication. Constraint: ensure the
   staging cache does not ADDITIONALLY redundantly duplicate the same content.

## Validation Strategy

- Add runtime tests in the install test sources covering AC-12: copy-in, `--repo-root`
  repointing, symlink-resolves-into-copy, clone-deleted-still-resolves, the three
  reconciliation outcomes (adopt / keep-local / conflict), locally-authored
  preservation, non-content-managed fallback target, and idempotent reinstall. Reuse
  the SKILL-74 multi-root fixtures for the fan-out parity check (AC-11).
- `(cd runtime-kotlin && ./gradlew check)`.
- `skill-bill validate`.
- `scripts/validate_agent_configs`.
- Manual: run `./install.sh`; edit a skill under `~/.skill-bill/skills`; reinstall and
  confirm the edit survives. Separately, `rm -rf` the clone after install and confirm a
  content-managed and a non-content-managed skill both still run. Introduce an upstream
  change to an untouched skill and confirm it is adopted; change both sides and confirm
  the conflict is kept-local and reported.

Run bill-feature-task on .feature-specs/SKILL-76-self-contained-skill-source/spec.md
