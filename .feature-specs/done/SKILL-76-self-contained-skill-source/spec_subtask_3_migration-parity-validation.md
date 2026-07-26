---
status: Complete
parent_spec: ./spec.md
subtask_id: 3
---

# SKILL-76 · Subtask 3 — Migration cleanliness + SKILL-74/75 parity + coverage closeout

Parent overview: [spec.md](./spec.md). Builds on subtasks 1 and 2. This subtask
proves clean migration from the old repo-symlinked model and confirms the
multi-profile fan-out + per-profile MCP wiring still works against the copy, then
closes out any remaining AC-12 coverage gaps. Mostly verification + targeted fixes
+ tests; expected to need minimal new production code.

## Scope

1. **Migration cleanliness (AC-10).** A first install onto a machine with a
   PRE-EXISTING repo-symlinked install (agent symlinks like
   `~/.claude/commands/<skill>` pointing into the clone/staging) must migrate to the
   copied-source model with NO dangling symlinks into the clone. The
   `--replace-existing-skill-bill-links` flag
   (`InstallCliCommands.kt:280` → `InstallSymlinkReplacement.createManagedSymlinkWithGuidance`
   `replaceExisting=true`, `readSymlinkTargetOrNull`) already repoints managed
   links and is already passed (`install.sh:1791`); migration is largely automatic
   once subtask 1's wipe-exemption holds. Verify end-to-end and fix any gap where a
   clone-pointing link could survive. Then ASSERT it: extend
   `InstallApplyReplacementCleanupTest` so a clone-pointing managed symlink is
   replaced by a copy-pointing one with NO dangling links left.

2. **SKILL-74 multi-profile + SKILL-75 MCP parity (AC-11).** The
   `claudeMultiRootDefaultTargets` fan-out (`InstallPlanBuilder` +
   `InstallPlanPolicy`), `CLAUDE_CONFIG_DIR` threading (via the `environment`
   param), and per-profile MCP registration (`install.sh --mcp`, line 1790) are
   source-location-agnostic — moving `--repo-root` to the copy does not touch them.
   Verify and lock with a parity test reusing the SKILL-74 multi-root fixtures
   (`InstallApplyClaudeMultiRootTest`): fan-out + per-profile MCP + `CLAUDE_CONFIG_DIR`
   still resolve correctly against the copied repoRoot. Touch
   `ClaudeConfigRootsTest` / `ClaudeConfigDirAgentPathTest` only if a gap surfaces.

3. **AC-12 coverage closeout.** Audit the full AC-12 checklist against tests
   delivered in subtasks 1, 2, and here; add any missing assertions so every listed
   case is covered exactly once (copy-in; `--repo-root` at copy; symlinks into copy
   not clone; clone-deleted still resolves; reinstall preserves user edit; reinstall
   adopts upstream for untouched; both-changed conflict accept/abort + report;
   locally-authored preserved; non-content-managed fallback targets copy; idempotent
   reinstall). Do not duplicate coverage already added upstream — fill gaps only.

4. **Docs (AC-3 explicit-requirement clause).** If a reinstall still requires the
   clone for any reason, document that requirement explicitly (spec says the
   requirement must be explicit + documented, not accidental). Otherwise document
   the clone-deletable guarantee where install behavior is described.

## Acceptance Criteria (this subtask)

- AC-10: first install over a pre-existing repo-symlinked install migrates cleanly,
  no dangling symlinks into the clone (asserted).
- AC-11: SKILL-74 multi-profile fan-out + SKILL-75 per-profile MCP registration keep
  working against the copy; `CLAUDE_CONFIG_DIR` honored (asserted).
- AC-12: the complete coverage checklist is satisfied across the three subtasks
  with no gaps.

## Validation (this subtask)

- `bill-code-check`; `cd runtime-kotlin && ./gradlew check`.
- New/updated tests:
  - `InstallApplyReplacementCleanupTest`: clone-pointing managed symlink → replaced
    by copy-pointing, no dangling links (AC-10).
  - `InstallApplyClaudeMultiRootTest` (reuse SKILL-74 fixtures): fan-out + MCP +
    `CLAUDE_CONFIG_DIR` parity against the copied repoRoot (AC-11).
  - Any remaining AC-12 gap assertions.
- `npx --yes agnix --strict .`; `scripts/validate_agent_configs`.

## Non-goals (this subtask)

- Copy-in / repoint / wipe-exemption (subtask 1).
- Baseline manifest / reconciliation policy / interactive conflict (subtask 2).
- Changing SKILL-74/75 behavior; desktop re-pointing; `skill-bill refresh`;
  portable export/import; versioned registry; three-way merge.

## Dependencies

- Subtask 1 (copy-in + repoint + wipe-exemption) — required for migration to land
  on the copy.
- Subtask 2 (reconciliation + baseline) — required so the AC-12 closeout audits a
  complete feature, not a partial one.

## Handoff

Run `bill-feature-task` on
`.feature-specs/SKILL-76-self-contained-skill-source/spec_subtask_3_migration-parity-validation.md`.
