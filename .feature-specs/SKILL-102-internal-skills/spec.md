# SKILL-102 - internal skills hidden from the agent skill list

Created: 2026-07-04
Status: Draft
Issue key: SKILL-102
Parent: follow-up to SKILL-40 (hide generated skill artifacts) and SKILL-41 (native-agent composition)

## Decomposition

This feature is decomposed because it spans three separate boundaries:

1. a new install-pipeline capability (internal-skill classification and sidecar
   staging) that must exist and be inert before any consumer migrates;
2. the feature-execution skill family migration, which rewrites cross-skill
   invocation from the Skill tool to sidecar file-reads and must land atomically
   with the listing change;
3. cleanup, documentation, and end-to-end verification across agents and
   runtime resume paths.

Implement on one branch with a commit per subtask:

1. [Internal-Skill Install Mechanism](./spec_subtask_1_internal-skill-install-mechanism.md)
2. [Feature-Execution Family Migration](./spec_subtask_2_feature-execution-family-migration.md)
3. [Cleanup, Docs, and End-to-End Verification](./spec_subtask_3_cleanup-docs-verification.md)

## Pinned Decisions (binding for all subtasks)

These decisions are already made. Do not re-decide, redesign, or "improve"
them during implementation. If one turns out to be impossible, stop and
surface the conflict instead of substituting a different design.

- **PD1 — frontmatter contract.** An internal skill is declared by exactly one
  new optional `content.md` frontmatter key: `internal-for: <parent-skill-name>`.
  Presence of the key makes the skill internal; absence means listed. No other
  new frontmatter keys, no config.yaml switches, no per-agent overrides.
- **PD2 — sidecar naming.** The rendered sidecar file is named
  `<skill-name>.md` using the full skill name (`bill-feature-task.md`, never
  `task.md`) and is placed at the top level of the parent skill's installed
  directory, next to the parent's `SKILL.md`.
- **PD3 — repo layout is frozen.** Repo source directories do not move or
  rename. `skills/bill-feature-task/content.md`,
  `skills/bill-feature-task-prose/content.md`, etc. stay exactly where they
  are. Only frontmatter and prose inside them change.
- **PD4 — identity strings are frozen.** Workflow names, telemetry constants,
  MCP tool names, and the DB `workflow_name` CHECK constraint are
  byte-for-byte unchanged. `bill-feature-task` remains the workflow identity
  even though it is no longer a listed skill.
- **PD5 — invocation contract.** A listed skill invokes an internal skill by
  reading the sidecar file from its own installed directory (a sibling file)
  and executing its instructions in the current session, passing the same
  argument conventions as before (`mode:runtime` / `mode:prose`,
  `parallel-review:<agent>`, issue key, spec path). The Skill tool is never
  used to invoke an internal skill — no supported agent can resolve an
  unlisted skill by name.
- **PD6 — sidecar content.** The sidecar carries the same governed wrapper a
  listed skill's `SKILL.md` would carry (frontmatter, descriptor, class
  sections, `## Execution` body, ceremony). Behavior parity over token
  savings; do not invent a trimmed format.
- **PD7 — hidden set.** Exactly five skills become internal, all with parent
  `bill-feature`: `bill-feature-task`, `bill-feature-task-runtime`,
  `bill-feature-task-prose`, `bill-feature-task-subtask-runner`,
  `bill-feature-goal`. `bill-feature-spec` stays listed and standalone; so
  does every other skill in the repo.

## Sources

- Session investigation on 2026-07-04 mapping the skill render/install
  pipeline (file paths verified against the repo on that date):
  - a listed skill is exactly a directory with `content.md`; discovery,
    install staging, and the agent-visible list are all keyed on that fact.
    Discovery:
    `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPlanSkillDiscovery.kt`
    and
    `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/authoring/AuthoringDiscovery.kt`.
    No hidden/unlisted concept exists today;
  - the Skill tool on every supported agent resolves only listed skills;
    there is no invocable-but-hidden state, so hiding a skill requires
    converting its call sites to file-reads;
  - authored non-skill sidecar files inside a skill dir already install
    verbatim alongside `SKILL.md`
    (`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStaging.kt`)
    and are consumed by relative-path file read (precedent:
    `compose-guidelines.md` in the KMP review pack, the generated
    `shell-ceremony.md` pointer);
  - the Kotlin runtime binds to feature skills by repo path:
    `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
    (`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowEngine.kt`)
    reads `skills/bill-feature-task/content.md` on resume, and
    `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/RepoValidationRuntime.kt`
    asserts workflow-step markers inside
    `skills/bill-feature-task-prose/content.md`. PD3 exists so neither needs
    to change;
  - `bill-feature-task-subtask-runner`'s real artifact is a native agent
    registered in `skills/bill-feature-task-prose/native-agents/agents.yaml`
    and spawned via the Agent tool; its skill-dir `content.md` is
    documentation only. Native agents install outside the skills dir
    (`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/NativeAgentOperations.kt`).
- User scope decision on 2026-07-04: hide the feature-execution skills only;
  `bill-feature-spec` is a different kind of skill (spec preparation without
  implementation) and stays listed and standalone.
- Repo contracts from `AGENTS.md`: generated artifacts are not committed;
  runtime contracts fail loudly on drift; skills install identically for all
  supported agents.

## Problem

The feature-execution family currently puts six entries in every agent's skill
list: `bill-feature`, `bill-feature-task`, `bill-feature-task-runtime`,
`bill-feature-task-prose`, `bill-feature-task-subtask-runner`, and
`bill-feature-goal`. Five of them are internal routing or execution targets
that users should never invoke directly — `bill-feature` owns routing, and the
rest are dispatch targets it selects. The crowded list confuses users,
dilutes trigger-phrase matching across near-identical descriptions, and
misrepresents the product surface: one entry point, not six.

The install pipeline has no way to ship a skill's governed content without
also listing it, because listing is derived from the same `content.md`
discovery that drives staging.

## Goals

1. Introduce a first-class internal-skill concept: a governed skill whose
   content installs as a sidecar file inside its parent skill's installed
   directory instead of as its own listed skill.
2. Reduce the feature-execution family to a single user-visible entry,
   `bill-feature`, on every supported agent.
3. Preserve all runtime behavior: workflow identity strings, DB constraints,
   telemetry, MCP dispatch, runtime resume paths, and native-agent bundle
   installation are unchanged (PD3, PD4).
4. Keep the mechanism agent-agnostic: sidecar file-reads work identically on
   every agent in `config.yaml`, with no per-agent forks beyond what the
   install pipeline already does.

## Non-Goals

- Do not hide `bill-feature-spec`; it remains a listed, standalone skill.
- Do not change any other listed skill (`bill-feature-verify`,
  `bill-code-review`, `bill-pr-description`, etc.) beyond what subtask 2's
  explicit call-site inventory names.
- Do not move or rename repo source directories under `skills/` (PD3).
- Do not change workflow identity strings, the DB `workflow_name` CHECK
  constraint, telemetry constants, or MCP tool names (PD4).
- Do not build a general skill-visibility configuration system (per-user
  show/hide preferences); this is an authored, repo-level classification.
- Do not change how native agents are composed, installed, or spawned.
- Do not restructure phase loops, confirmation gates, or mode semantics of
  any migrated skill — this feature changes invocation plumbing and listing
  only.

## Target User Experience

After install, the agent skill list shows `bill-feature` (and
`bill-feature-spec`) but none of the five execution-family skills. "Implement
feature", "build feature", "run feature-task", and goal/status phrasing all
route to `bill-feature`, which dispatches internally:

- spec preparation is delegated to the listed `bill-feature-spec` skill via
  the Skill tool, exactly as today;
- task and goal execution are dispatched by reading the rendered sidecar
  files installed inside `bill-feature`'s own directory
  (`bill-feature-task.md`, `bill-feature-goal.md`) and executing them with
  the same argument conventions the Skill-tool invocations carry today (PD5);
- a user who already has a governed spec and asks to implement it lands in
  `bill-feature`, which detects the existing
  `.feature-specs/{KEY}-*/spec.md` (or decomposition manifest) and dispatches
  straight to the task or goal sidecar without re-running spec preparation.

Runtime flows are indistinguishable from today: `skill-bill feature-task` and
`skill-bill goal` launch, resume, and record workflows exactly as before.

## Acceptance Criteria

1. `content.md` frontmatter supports the `internal-for: <parent-skill-name>`
   classification (PD1), and the authoring/install pipeline loud-fails with a
   typed, actionable error on an internal skill whose parent is missing,
   unknown, or itself internal.
2. Install renders each internal skill's governed content as a markdown
   sidecar named `<skill-name>.md` at the top level of the parent skill's
   installed directory (PD2), and creates no `SKILL.md` directory entry for
   the internal skill in any agent's `skills_dir`.
3. After a clean install, no supported agent lists `bill-feature-task`,
   `bill-feature-task-runtime`, `bill-feature-task-prose`,
   `bill-feature-task-subtask-runner`, or `bill-feature-goal`; `bill-feature`
   and `bill-feature-spec` remain listed.
4. The rendered sidecar preserves the governed wrapper (descriptor, class
   sections, execution body, ceremony) so the executed behavior is identical
   to the previously listed skill's `SKILL.md` (PD6).
5. Native-agent bundles hosted in an internal skill's directory (the
   `skills/bill-feature-task-prose/native-agents/agents.yaml` bundle) install
   exactly as before, and the prose goal orchestrator can still spawn
   `bill-feature-task-subtask-runner` and the Level-2 feature-task subagents
   via the Agent tool.
6. `bill-feature` dispatches to task and goal execution by reading the
   installed sidecar files, absorbs the trigger phrases currently carried by
   the hidden skills' descriptions, and gains a direct-dispatch route: when a
   governed spec or decomposition manifest already exists for the issue key,
   it skips spec preparation and dispatches directly.
7. Every call site in subtask 2's inventory is rewritten to the file-read
   contract, and a grep of the staged install output finds no instruction to
   invoke any of the five internal skills via the Skill tool.
8. Repo source directories for the internal skills remain at their current
   paths with `content.md` intact; `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
   and `RepoValidationRuntime` content-marker checks pass without path
   changes (PD3).
9. Workflow identity strings, the DB `workflow_name` CHECK constraint,
   telemetry constants, and MCP dispatch are byte-for-byte unchanged (PD4).
10. Uninstall, reinstall, `skill-bill doctor`, and the install overlay treat
    internal-skill sidecars correctly: no orphaned files, no false doctor
    failures, and repeated installs are idempotent.
11. Runtime execution still works end-to-end: `skill-bill feature-task`
    launches and resumes a workflow, and `skill-bill goal` runs a decomposed
    goal, with no behavior change attributable to listing.
12. Tests cover: internal-skill classification and every loud-fail case,
    sidecar staging and naming, absence of `skills_dir` entries for internal
    skills, native-agent bundle installation from an internal skill dir, and
    install/uninstall idempotency.
13. Documentation (`AGENTS.md` and any contributor-facing skill-authoring
    docs) explains the internal-skill concept, its frontmatter, and the
    file-read invocation contract.
14. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- The load-bearing insight is that only the install/listing layer changes.
  Repo layout, runtime path bindings, and identity strings all stay put,
  which keeps the blast radius inside the install pipeline and the skill
  prose.
- The Skill tool cannot invoke unlisted skills on any supported agent, so the
  invocation contract for internal skills is necessarily a file read. This is
  already an established pattern (`shell-ceremony.md`,
  `compose-guidelines.md`) and is more portable across agents than
  Skill-tool mechanics.
- Distinguish call sites from identity strings everywhere: a call site is an
  instruction telling an agent to invoke a skill (those are rewritten); an
  identity string names a workflow, telemetry event, DB value, or historical
  fact (those are never touched). Subtask 2 enumerates both lists explicitly.
- Trigger-phrase absorption matters: today a user saying "implement feature"
  may match the hidden skills' descriptions. After migration those phrases
  must live on `bill-feature`'s description or routing silently degrades.
- The direct-dispatch route replaces the lost ability to invoke
  `bill-feature-task` directly to skip spec preparation when a spec already
  exists.

## Validation Strategy

- Kotlin unit tests for classification parsing, loud-fail cases, staging
  layout, and agent-link planning.
- Install integration tests (existing install/uninstall test harness) proving
  listed-vs-internal placement, idempotency, and native-agent bundle parity.
- Mechanical grep sweeps of staged install output (exact commands are in the
  subtask specs) so "no Skill-tool call sites remain" is verified, not
  eyeballed.
- Manual end-to-end verification on Claude Code and Codex (the two
  e2e-verified agents): skill list contents, `bill-feature` dispatch through
  a sidecar, one runtime feature-task run, one prose-mode goal subtask spawn.
- Standard maintainer command set (see acceptance criterion 14).

## Open Questions

- Should internal skills' descriptions be surfaced anywhere user-visible
  (e.g. a `skill-bill list --all` maintainer view), or is repo source the
  only place they appear? (Not blocking: default is repo-source-only; a
  maintainer view can be a follow-up.)
