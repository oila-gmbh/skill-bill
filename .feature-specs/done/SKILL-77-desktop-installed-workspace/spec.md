---
status: Complete
---

# SKILL-77 - desktop installed workspace

## Mode

decomposed

## Intended Outcome

The Compose desktop app browses, edits, and versions the SKILL-76 installed
source of truth (`~/.skill-bill/skills` + `~/.skill-bill/platform-packs`) by
default, with no dependency on a maintainer repo clone. Users land in the
installed workspace after first-run, see which skills they have locally
modified versus the installed baseline, and get a working git change-tracking
flow over `~/.skill-bill` via init-on-first-open, while the existing
open-a-clone picker flow keeps working unchanged.

## Overview

SKILL-76 made `~/.skill-bill` self-contained: install copies `skills/` +
`platform-packs/` in as the authoritative, editable source of truth and
reconciles reinstalls per-skill against
`$HOME/.skill-bill/baseline-manifest.json` (read/written via
`reconcilePorts.baselineManifestPersistencePort`,
`runtime-application/src/main/kotlin/skillbill/application/InstallService.kt:100-135`).
Edits under `~/.skill-bill` survive reinstalls. The desktop app is the natural
front-end for those edits, but today it still models a single user-picked repo:

- `RepoSession{repoPath, isRecognizedSkillBillRepo}`
  (`core/domain/model/SkillBillModels.kt:3`), picker at
  `feature/skillbill/ui/SkillBillRoute.kt:227`, recent path persisted via
  `RecentRepoRepository` (SQLite, `core/database/RecentRepoEntity.kt`).
- Root validation (`looksLikeSkillBillRepo`,
  `RuntimeRepoBrowserService.kt:919-920`) requires `skills/` OR
  `platform-packs/` — the installed `~/.skill-bill` tree already passes it
  unchanged (verified: both dirs plus `baseline-manifest.json` present).
- Editor read/write is root-relative (`loadDocument`
  `RuntimeRepoBrowserService.kt:139-151`; `authoringSaver` →
  `scaffoldService.saveExactContent` line 168; add-on `Files.writeString` line
  76), so repointing is a session-source change, not an editor change.
- `RuntimeGitGateway` (`sessionRoot` lines 225-230, `runGit` shell-outs for
  status/diff/log/stage/commit/push) assumes the opened directory IS a git
  working tree. `~/.skill-bill` is not a git repo after install, so the
  installed workspace needs a guarded provisioning step (git init + initial
  as-installed commit + scoped `.gitignore`), graceful degradation when the
  git binary is missing, and push hidden when
  `GitPublishingStatus.pushTarget` is null (`SkillBillModels.kt:592-601`).
- Desktop `core/data` already builds a `RuntimeComponent` per home
  (`DesktopRuntimeApplicationServices.kt:93-98`), so the baseline read port is
  architecturally reachable for per-skill modified-vs-baseline indicators.
- `finishFirstRunSetup` (`SkillBillViewModel.kt:862`) currently only clears
  wizard state — it does not open any workspace, so the first-run handoff is a
  real gap.

The feature is decomposed along those seams into five small,
dependency-ordered subtasks on one branch: (1) an env-threaded
installed-workspace locator, (2) default-open + picker coexistence, (3) git
provisioning + degradation, (4) baseline modified indicators, (5) first-run
handoff + end-to-end validation.

## Acceptance Criteria

1. On launch with an existing valid `~/.skill-bill` (`skills/` or
   `platform-packs/` present), the app opens the installed workspace
   automatically without showing the directory picker.
2. Editing and saving a skill document in the installed workspace writes to
   the file under `~/.skill-bill` via the existing
   `RuntimeRepoBrowserService`/`authoringSaver` path.
3. The existing "choose repo directory" picker still opens arbitrary clones,
   and clone sessions behave exactly as before (including recent-path memory
   and push when a remote exists).
4. First open of the installed workspace provisions git: init when
   `~/.skill-bill` is not itself a git repo root (a `$HOME`-rooted dotfiles
   worktree must not suppress provisioning), write a `.gitignore` tracking
   `skills/` and `platform-packs/` content only, and create an initial commit
   of the as-installed state; subsequent opens do not re-provision.
5. With no remote configured, push UI is hidden or disabled with no error;
   with the git binary unavailable, the app shows an explanatory message in
   the changes surface and does not crash.
6. Skills whose live content differs from `baseline-manifest.json` hashes show
   a locally-modified indicator in the tree for installed-workspace sessions
   only; the app never writes the baseline manifest.
7. Completing the first-run wizard lands the user in the opened installed
   workspace.
8. `./gradlew check` passes; new logic is covered by jvmTest/commonTest in the
   owning modules.

## Constraints

- Reuse `RuntimeRepoBrowserService`, `authoringSaver`, `GitGateway`,
  `RepoSession` plumbing — repoint, don't fork parallel services.
- Do not change SKILL-76 install/reconciliation semantics; the baseline
  manifest is read-only from the desktop app.
- kotlin-inject DI, domain/data layering, env/home threading via injected
  providers (`JvmRuntimeAssetLocator` pattern,
  `JvmRuntimeAssetLocator.kt:21`); no process-global env reads in the
  application layer.
- Do not break the open-a-clone maintainer flow.

## Non-Goals

- Conflict-resolution UI for baseline divergence (future follow-up).
- Push-to-upstream, fork/PR creation, or any remote sync for the installed
  workspace.
- Portable export/import of the workspace (separate deferred feature).
- Changes to `install.sh` or the runtime CLI.
- Multi-workspace management beyond default-installed + existing picker.

## Open Questions

1. **Should opening the installed workspace update `RecentRepoRepository`?**
   RECOMMENDED: no — the installed workspace is the implicit default;
   `rememberRepoPath` stays clone-only so the picker's remembered path is
   never clobbered.
2. **What should the initial commit include beyond `skills/` and
   `platform-packs/`?** RECOMMENDED: nothing — `.gitignore` excludes
   `runtime/`, `installed-skills/`, `native-agents/`, `orchestration/`,
   `review-metrics.db`, `config.json`, `install-selection.json`,
   `baseline-manifest.json`, `desktop.properties`; only authored content is
   versioned.
3. **git-init vs hiding the git surface for the installed workspace?**
   RESOLVED in planning: git-init-on-first-open — the whole change-tracking
   GUI is `GitGateway`-backed, so hiding git removes the feature's core
   value, while init costs one guarded provisioning step. Must guard against
   `$HOME`-as-dotfiles-worktree by checking `git rev-parse --show-toplevel`
   equals `~/.skill-bill` before trusting an existing repo.
4. **Granularity of modified indicators?** RECOMMENDED: per-skill boolean
   (any file hash mismatch, or file added/removed vs manifest entries for
   that skill prefix); per-file granularity deferred.

## Validation Strategy

- Per-subtask: module-local jvmTest/commonTest (locator, provisioning,
  indicator computation, ViewModel state transitions) using the existing
  fake-service patterns (`core/testing` `FakeSkillBillServices`), plus
  `(cd runtime-kotlin && ./gradlew check)`.
- Subtask 5 adds integration-style ViewModel tests covering fresh install →
  wizard completes → installed session open → git provisioned → editor save →
  change visible in the snapshot.
- Manual: launch the app with a real SKILL-76 install; confirm default-open,
  edit/save, commit history, modified indicator after an edit, and the
  picker still opening a clone.

```bash
skill-bill goal SKILL-77
```
