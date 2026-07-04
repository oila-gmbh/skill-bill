---
status: Pending
---

# SKILL-102 Subtask 3 - Cleanup, Docs, and End-to-End Verification

Parent spec: [.feature-specs/SKILL-102-internal-skills/spec.md](./spec.md)
Issue key: SKILL-102

Read the parent spec's **Pinned Decisions** section first. This subtask adds
documentation and evidence; it must not change mechanism code or skill
routing prose. If verification below fails, fix the defect in the spirit of
subtask 1 or 2 (and say so in the run summary) — do not invent new mechanism.

## Scope

### Documentation

- `AGENTS.md`: document the internal-skill concept in the skill-authoring
  section — the `internal-for` frontmatter key, the parent rules and
  loud-fail behavior (unknown/internal/self parent, missing value, sidecar
  name collision), the installed layout (`<parent>/<skill-name>.md` sidecar,
  no `skills_dir` entry), and the file-read invocation contract (PD5), using
  the feature-execution family as the worked example.
- Any other contributor-facing skill-authoring doc that lists frontmatter
  keys or explains how skills install: locate with
  `grep -rl "content.md" docs/ CONTRIBUTING.md README.md` and update the
  ones that enumerate frontmatter or install layout. List in the run summary
  which files were checked and which were updated.

### User-facing surface reconciliation

- `README.md`: if it enumerates skills as user-invocable entry points,
  remove or reclassify the five internal skills so they are not advertised
  as directly invocable. Locate with
  `grep -n "bill-feature-task\|bill-feature-goal" README.md`.
- Desktop nav / CLI listings: these are expected to derive from install
  discovery and therefore self-correct once the five stop being listed.
  Verify rather than assume: confirm the desktop nav source (see PR #206
  "surface external add-ons in desktop nav") and any `skill-bill` listing
  command show only listed skills post-install. If one has its own
  hardcoded enumeration containing the five names, fix that enumeration.

### End-to-end verification (Claude Code and Codex only)

Run each check on both agents and capture evidence (command output or
transcript excerpt) in the run notes:

1. Fresh install; list the installed skills dir (e.g.
   `ls ~/.claude/skills/`). Expect: `bill-feature` and `bill-feature-spec`
   present, none of the five internal skills present, and
   `ls ~/.claude/skills/bill-feature/` shows the five sidecar `.md` files
   next to `SKILL.md`.
2. Fresh feature request through `bill-feature` with no existing spec:
   confirm it invokes `bill-feature-spec` via the Skill tool, then
   dispatches by reading the `bill-feature-task.md` sidecar (visible in the
   session transcript as a file read, not a Skill-tool call).
3. Direct-dispatch: with a governed spec already present for an issue key,
   confirm `bill-feature` skips spec preparation and dispatches straight to
   the sidecar.
4. Runtime: one `skill-bill feature-task` run launches, is interrupted, and
   resumes successfully post-migration.
5. Prose goal: one `mode:prose` goal run spawns
   `bill-feature-task-subtask-runner` via the Agent tool successfully.

Do not run or claim verification on other agents (junie, copilot, opencode,
glm): they receive the same install output, but per the repo's support-tier
rule no new e2e claims are made for them.

### Records

- Record the boundary decision in the appropriate `agent/decisions.md` via
  the `bill-boundary-decisions` flow: why internal skills are file-read
  sidecars (Skill tool cannot resolve unlisted skills), and why repo paths
  did not move (runtime path bindings, PD3).
- Add the feature history entry via the `bill-boundary-history` flow.
- Reconcile the parent spec: set Status to Complete, resolve or explicitly
  defer its open question, and fold in any corrections discovered during
  implementation.

## Acceptance Criteria

1. `AGENTS.md` documents the internal-skill frontmatter, parent rules,
   install layout, and invocation contract with the feature-execution family
   as the example.
2. The run summary lists every contributor-facing doc checked for
   frontmatter/install-layout content and shows each was updated or needed
   no change.
3. `README.md` and any skill-enumerating UI/CLI surface do not present the
   five internal skills as directly invocable entry points; discovery-driven
   surfaces were verified, not assumed.
4. E2e checks 1-5 pass on Claude Code and on Codex, with captured evidence
   for each check on each agent.
5. Boundary decision and feature history entries are recorded.
6. The parent spec is reconciled to its final state (status, open question,
   corrections).
7. Maintainer validation passes: `skill-bill validate`,
   `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`,
   `scripts/validate_agent_configs`.

## Non-Goals

- No new mechanism, no additional skills migrated, no routing prose changes
  beyond defects found by verification (which must be called out).
- No e2e claims for agents beyond Claude Code and Codex.

## Dependency Notes

Depends on subtask 2 (verifies the migrated state).

## Validation Strategy

- The e2e checklist above is the validation; evidence capture is part of the
  deliverable, not optional.
- Full maintainer command set (criterion 7).

## Next Path

Feature complete once the parent spec is reconciled (criterion 6).
