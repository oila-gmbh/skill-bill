---
status: Complete
- Agent: zcode
---

# SKILL-102 Subtask 2 - Feature-Execution Family Migration

Parent spec: [.feature-specs/SKILL-102-internal-skills/spec.md](./spec.md)
Issue key: SKILL-102

Read the parent spec's **Pinned Decisions** section first. PD3, PD4, PD5, and
PD7 are binding here. This subtask changes frontmatter and prose only — zero
Kotlin changes. If you find yourself editing a `.kt` file, stop: either
subtask 1 is incomplete or you are off-spec.

## Scope

Classify the five feature-execution skills as internal under `bill-feature`
and rewrite every call site in the inventory below from Skill-tool invocation
to the sidecar file-read contract. The listing change and the call-site
rewrite land in this one commit — a state where a skill is unlisted but still
Skill-tool-invoked (or listed but file-read) is broken.

### Step 1 — add frontmatter

Add exactly one line, `internal-for: bill-feature`, to the frontmatter of
each of these five files (PD7). Change nothing else in the frontmatter:

- `skills/bill-feature-task/content.md`
- `skills/bill-feature-task-runtime/content.md`
- `skills/bill-feature-task-prose/content.md`
- `skills/bill-feature-task-subtask-runner/content.md`
- `skills/bill-feature-goal/content.md`

Repo directories do not move or rename (PD3).

### Step 2 — rewrite call sites (complete inventory)

Line numbers are as of 2026-07-04; re-locate by the quoted text if they have
drifted. This inventory is intended to be complete — if you find another
instruction to Skill-tool-invoke one of the five skills, rewrite it the same
way and note it in the run summary.

The file-read contract (PD5), for reference — dispatch text should follow
this shape:

> Read the file `bill-feature-task.md` located in this skill's own installed
> directory (a sibling of this SKILL.md) and execute its instructions in the
> current session with args: `<issue-key> mode:<mode> ...`. Do not use the
> Skill tool for this — `bill-feature-task` is an internal skill and is not
> listed.

1. `skills/bill-feature/content.md` — the `## Dispatch` section (~lines
   45-59): "Run `bill-feature-task` on ..." and "Invoke `bill-feature-goal`
   in the current session ..." become sidecar file-reads of
   `bill-feature-task.md` / `bill-feature-goal.md`, passing issue key, spec
   path, and mode args unchanged. The line "Do not invoke
   `bill-feature-goal`" becomes "Do not dispatch to the goal sidecar" (same
   meaning, no Skill-tool phrasing).
2. `skills/bill-feature/content.md` — the `## Status Requests` section
   (~lines 61-65): route status behavior through the same two sidecar
   file-reads instead of naming skills to invoke.
3. `skills/bill-feature/content.md` — spec preparation (~line 41) is NOT a
   call site to rewrite: `bill-feature-spec` stays listed and stays invoked
   via the Skill tool. Leave it.
4. `skills/bill-feature-task/content.md` — delegation rules (~lines 52-61):
   "invoke the delegated skill via the Skill tool — do not search the
   filesystem", "Invoke `bill-feature-task-runtime` via the Skill tool",
   "Invoke `bill-feature-task-prose` via the Skill tool" become file-reads of
   `bill-feature-task-runtime.md` / `bill-feature-task-prose.md`, sibling
   files in the same installed directory. The anti-filesystem-search warning
   is obsolete here; replace it with the explicit sibling-file instruction.
5. `skills/bill-feature-task/content.md` — opencode refusal text (~line 33):
   "run bill-feature-task-prose for a single feature task, or
   bill-feature-goal mode:prose for a decomposed goal" becomes "use
   bill-feature with mode:prose for a single feature task, or bill-feature
   with mode:prose for a decomposed goal". The refusal's technical rationale
   (120s hard-kill, no output harvest) must remain verbatim.
6. `skills/bill-feature-goal/content.md` — same refusal text (~line 34):
   apply the same rewrite as item 5.
7. `skills/bill-feature-goal/content.md` — the `bill-feature-spec`
   invocation (~line 70) is NOT rewritten (spec stays listed). Leave it.
8. `skills/bill-feature-spec/content.md` — the `single_spec` returned next
   command (~line 133), currently
   `Run bill-feature-task on .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`,
   becomes
   `Run bill-feature on .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`
   (bill-feature's direct-dispatch route, Step 3, handles it from there).
   The `decomposed` next command (`skill-bill goal <issue_key>`) is
   unchanged.

### Step 3 — bill-feature absorbs routing

In `skills/bill-feature/content.md`:

- Extend the frontmatter `description` to carry the trigger phrases
  currently on the hidden skills so agent auto-routing still lands:
  "implement feature", "build feature", "implement spec", "run
  feature-task", "feature from design doc", plus goal/status phrasing
  ("decomposed goal", "goal status", "resume goal"). Keep the existing
  routing-entry-point description; append, don't replace.
- Add a direct-dispatch route to the Dispatch section: when the request
  carries an issue key and `.feature-specs/{KEY}-*/` already contains a
  governed `spec.md` without a `decomposition-manifest.yaml`, skip spec
  preparation and dispatch straight to the `bill-feature-task.md` sidecar;
  when it contains a `decomposition-manifest.yaml`, dispatch straight to the
  `bill-feature-goal.md` sidecar. Only run `bill-feature-spec` when no
  governed artifacts exist for the issue key.

### Mentions that are NOT call sites — do not touch

These reference the five names as identity strings, historical facts, or
descriptions of relationships. Rewriting them breaks contracts or history
(PD4). Leave every one unchanged:

- `skills/bill-feature-task-prose/content.md` — all statements that prose
  persists workflow rows as `bill-feature-task` with `mode=prose`, the
  worker/orchestrator contract wording, and `.agents/skill-overrides.md`
  section names.
- `skills/bill-feature-task-prose/native-agents/agents.yaml` — its
  Skill-tool instructions target `bill-feature-guard`, `bill-code-check`,
  and `bill-pr-description`, all of which stay listed. Do not rewrite them.
  Agent names and return contracts stay as-is.
- `skills/bill-pr-description/content.md` (~lines 3, 89, 97) and
  `skills/bill-pr-review-fix/content.md` (~line 250) — "when invoked from
  `bill-feature-task`" describes an orchestration relationship, not a
  Skill-tool call.
- `skills/bill-feature-verify/content.md` — description and opencode
  comparison prose mention the names descriptively.
- `skills/agent/history.md`, `orchestration/**` playbooks and contract
  schemas — history and identity vocabulary.
- Everything under `runtime-kotlin/` and anything matching the workflow
  name, telemetry constants, MCP tool names, or DB constraint (PD4).

## Acceptance Criteria

1. Exactly the five PD7 skills carry `internal-for: bill-feature`; their repo
   directories and `content.md` paths are unchanged; no Kotlin file changed
   in this subtask's commit.
2. After a scratch install, no configured agent's `skills_dir` contains any
   of the five; `bill-feature`'s installed directory contains all five
   rendered sidecars; `bill-feature` and `bill-feature-spec` remain listed.
3. Every inventory item in Step 2 is rewritten as specified, and the
   opencode refusal rationale text survives verbatim in both files.
4. `bill-feature` direct-dispatches per Step 3 when governed artifacts
   already exist, and only invokes `bill-feature-spec` when none exist.
5. `bill-feature`'s description contains the absorbed trigger phrases from
   Step 3.
6. A grep of the staged install output for Skill-tool-invocation phrasing
   targeting the five internal names returns zero matches (see Validation
   Strategy for the exact command).
7. No file in the "Mentions that are NOT call sites" list changed.
8. `WorkflowEngine.CONTINUATION_CONTENT_PATHS` and `RepoValidationRuntime`
   marker checks pass without modification (proven by the standard command
   set, since no Kotlin changed).
9. The `native-agents/agents.yaml` bundle hosted in
   `skills/bill-feature-task-prose/` installs unchanged, and the prose goal
   orchestrator still spawns `bill-feature-task-subtask-runner` and the
   Level-2 subagents via the Agent tool.

## Non-Goals

- No Kotlin/install-pipeline changes (subtask 1 shipped the mechanism).
- Do not hide `bill-feature-spec`, `bill-feature-verify`, or any other skill.
- Do not restructure phase loops, confirmation gates, or mode semantics —
  invocation plumbing and listing only.
- Do not reword, trim, or "improve" any prose outside the Step 2/Step 3
  inventory.

## Dependency Notes

Depends on subtask 1 (the mechanism must exist and be tested before the
first consumer opts in).

## Validation Strategy

- Scratch install; assert per-agent `skills_dir` contents (criterion 2) and
  sidecar presence/naming with `ls`.
- Grep sweep for criterion 6 over the staged install tree, for example:
  `grep -rniE "(invoke|run) .?bill-feature-(task|goal|task-runtime|task-prose|task-subtask-runner).?( via)? (the )?skill tool" <staged-install-root>`
  plus a manual read of the rendered `bill-feature/SKILL.md` and the five
  sidecars' dispatch sections. Expect zero Skill-tool phrasing targeting the
  five names.
- `git diff --stat` review confirming only `skills/**` markdown changed
  (criterion 1).
- `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
  `npx --yes agnix --strict .`, `scripts/validate_agent_configs`.

## Next Path

Proceed to
[Subtask 3 - Cleanup, Docs, and End-to-End Verification](./spec_subtask_3_cleanup-docs-verification.md).
