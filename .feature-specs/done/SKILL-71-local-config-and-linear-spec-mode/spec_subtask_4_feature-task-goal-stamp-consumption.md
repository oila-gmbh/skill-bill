---
status: Complete
---

# SKILL-71 Subtask 4 - feature-task and goal Stamp Consumption

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Make implementation, commit, cleanup, resume, and verify honor the
`spec_source: linear` stamp. Everything reads the stamp from the artifact, never
the config.

- **Commit exclusion.** When the stamp is `linear`, the commit step omits
  `.feature-specs/{KEY}/` (parent spec, subtask specs, manifest) from the staged
  set. Preserve the existing contract: stage by explicit enumerated path, never
  `git add -A` (`bill-feature-task-prose/content.md:269` and the runtime commit
  handoff). Nothing spec-related enters git history in linear mode.
- **Delete on terminal success.** On successful completion of a linear-mode
  feature, delete the local spec scratch:
  - decomposed: delete each subtask spec file after that subtask completes, and
    the parent spec + manifest only after the final subtask completes (the
    manifest is live runtime state until then);
  - single_spec: delete the spec dir on terminal success.
  Deletion happens only on success; an aborted/blocked/incomplete run leaves the
  scratch intact so it remains resumable.
- **Rehydrate for resume/verify.** When a linear-mode feature's local spec is
  missing (post-deletion), rehydrate before any read: fetch the parent issue (by
  `issue_key`) and each subtask (by `linear_issue_id`) via the Linear MCP and
  rewrite the local spec files, then let the runtime read the working tree as
  usual. The runtime is unchanged — it still reads `spec_path` once and freezes
  invariants; rehydrate just ensures the file exists first.
- Apply consistently across `bill-feature-task` and `skill-bill goal`, and to
  `bill-feature-verify` (which must rehydrate before extracting acceptance
  criteria from a deleted linear spec).
- Reading the stamp: decomposed from the manifest `spec_source`; single_spec from
  the `spec.md` line (subtask 2). Default `local` -> today's behavior exactly.

## Acceptance Criteria

1. With `spec_source: linear`, the commit step excludes `.feature-specs/{KEY}/`
   from staging while still enumerating paths and never using `git add -A`; the
   committed tree contains no spec, subtask spec, or manifest file.
2. On terminal success of a linear-mode feature, the local spec dir is deleted —
   incrementally for decomposed (subtask spec after its subtask, parent spec +
   manifest after the final subtask) — and only on success.
3. An aborted, blocked, or otherwise non-terminal-success run leaves the local
   spec scratch intact and resumable.
4. Resume and `bill-feature-verify` rehydrate a missing linear-mode spec from
   Linear (`issue_key` + per-subtask `linear_issue_id`) via MCP before any read;
   the runtime read path is otherwise unchanged.
5. All stamp reads come from the artifact; config is never consulted at
   task/goal/verify time.
6. Local-mode features (default `local`) keep today's behavior: specs are staged
   and committed as before, nothing is deleted, and no rehydrate is attempted.

## Non-Goals

- No runtime Linear client; rehydrate is agent-side MCP.
- No change to the phase loop, `spec_path` resolution, or invariant freezing.
- No config consultation downstream (the stamp is authoritative).
- No deletion on non-success paths.

## Dependency Notes

Depends on subtask 2 (stamp contract to read) and subtask 3 (which produces
linear-mode artifacts and the Linear ids to rehydrate from).

## Validation Strategy

- Decomposed linear run: assert each subtask spec is removed after its commit,
  the manifest survives until the final subtask, and the dir is gone on success;
  assert no spec/manifest path appears in any commit.
- Abort mid-run: assert the scratch and manifest remain and the run resumes
  (rehydrating only if the local copy was already deleted).
- Verify-after-deletion: assert rehydrate restores the spec and acceptance
  criteria extraction succeeds.
- Local-mode regression: specs still committed, nothing deleted, no MCP calls.
- `(cd runtime-kotlin && ./gradlew check)`, `skill-bill validate`,
  `scripts/validate_agent_configs`.

## Next Path

Proceed to subtask 5:
`.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec_subtask_5_code-review-config-fallback.md`
