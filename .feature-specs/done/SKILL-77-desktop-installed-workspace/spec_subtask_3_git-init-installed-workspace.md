# SKILL-77 subtask 3 - git provisioning and graceful degradation

## Scope

Provision git over the installed workspace on first open, and make the git
surface degrade gracefully instead of assuming a working tree:

- Add a provisioning step in `core/data`, invoked from the
  installed-workspace open path (subtask 2's flow), scoped by the locator to
  the installed root only — never clones:
  - Check `git rev-parse --show-toplevel`; init only when `~/.skill-bill` is
    not already **its own** repo root. A `$HOME`-rooted dotfiles worktree
    resolves to `$HOME`, not `~/.skill-bill`, and must NOT suppress
    provisioning — `~/.skill-bill` still gets its own repo.
  - Write a `.gitignore` excluding `runtime/`, `installed-skills/`,
    `native-agents/`, `orchestration/`, `review-metrics.db`, `config.json`,
    `install-selection.json`, `baseline-manifest.json`,
    `desktop.properties` — only authored content (`skills/`,
    `platform-packs/`) is versioned.
  - Create an initial commit of the as-installed `skills/` +
    `platform-packs/` state.
  - Reuse `RuntimeGitGateway`'s `runGit` conventions
    (`RuntimeGitGateway.kt:234` etc.); do not fork a second git runner.
- Surface git-binary-unavailable as a `ChangesSnapshot.errorMessage`-style
  message (the `GitGateway` contract in `SkillBillServices.kt:56-96` already
  mandates non-throwing error surfacing); the session must still open and the
  editor must still work.
- Verify `publishingStatus` yields a null `pushTarget` with zero remotes and
  that the push UI hides/disables on null `pushTarget` (`GitPublishingStatus`,
  `SkillBillModels.kt:592-601`); add the guard if absent. (UNVERIFIED: the
  jvmMain `publishingStatus` implementation's exact zero-remote behavior —
  confirm during implementation.)

## Acceptance Criteria

1. First open of a non-git `~/.skill-bill` results in a git repo rooted
   there, with the scoped `.gitignore` and one initial commit containing
   `skills/` and `platform-packs/` only.
2. Re-open does not re-init or create additional commits; an existing
   `~/.skill-bill`-rooted repo is left untouched.
3. A `$HOME`-rooted parent git worktree does not suppress provisioning:
   `~/.skill-bill` still gets its own repo.
4. Missing git binary: session opens, editor works, changes surface shows an
   explanatory message, no crash.
5. No remote: push affordance hidden or disabled with no error; clone
   sessions with remotes keep push working.
6. Stage/unstage/discard/commit/history all function over the provisioned
   workspace via the existing `GitGateway`.

## Non-Goals

- Any remote/push/PR setup for the installed workspace.
- Baseline awareness (subtask 4).

## Dependency Notes

- Depends on subtask 1 (locator scopes provisioning to the installed root)
  and subtask 2 (provisioning triggers on the installed-workspace open path).
- Independent of subtask 4; they may land in either order after 2.

## Validation Strategy

- jvmTest with temp dirs and real git (pattern exists in
  `RuntimeGitGatewayTest`): init-on-first-open, idempotent re-open,
  `$HOME`-worktree guard, `.gitignore` scope, initial-commit contents.
- Binary-missing path tested via injected runner/PATH.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Run bill-feature-task on .feature-specs/SKILL-77-desktop-installed-workspace/spec_subtask_4_baseline-modified-indicators.md
