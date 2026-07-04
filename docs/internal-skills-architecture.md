# Internal Skills and the Feature-Execution Family

How six skills became one visible entry point: the `internal-for`
classification, the sidecar install mechanism, the file-read dispatch
contract, and where every piece of prose, runtime, and agent machinery lives.
This is the architecture companion to the authored contract in
[skill-source-generation.md](skill-source-generation.md) (Internal Skills
section), which owns the normative rules.

## The idea in one paragraph

Before SKILL-102, the feature-execution family put six entries in every
agent's skill list, but five of them were dispatch targets that only
`bill-feature` should ever select — users invoking them directly was never
the intent, and six near-identical descriptions diluted trigger-phrase
matching. The Skill tool on every supported agent can only resolve *listed*
skills; there is no invocable-but-hidden state. So hiding a skill forces a
different invocation contract: the hidden skill's governed content installs
as a plain markdown file — a **sidecar** — inside its parent's installed
directory, and the parent invokes it by **reading that sibling file and
executing its instructions in the current session**. This reuses a pattern
the install pipeline already had (support pointers like `shell-ceremony.md`)
and works identically on every agent, because reading a file is universal
where Skill-tool mechanics are not.

## What install produces

The agent skill list shows `bill-feature` and `bill-feature-spec` but none of
the five execution skills. The symlink in the agent's skills dir points into
the content-addressed staging cache, where the five sidecars sit next to the
parent's `SKILL.md`:

```
~/.claude/skills/bill-feature
  → ~/.skill-bill/installed-skills/bill-feature-<content-hash>/
      SKILL.md                            rendered governed wrapper — what the agent lists
      content.md                          authored source, copied verbatim
      bill-feature-task.md                sidecar: mode router
      bill-feature-task-runtime.md        sidecar: runtime-mode executor
      bill-feature-task-prose.md          sidecar: prose-mode orchestrator
      bill-feature-goal.md                sidecar: decomposed-goal executor
      bill-feature-task-subtask-runner.md sidecar: docs for the level-1 subtask agent
      platform-packs → …                  symlink for pack pointer resolution
```

Each sidecar carries the *full governed wrapper* — the same frontmatter,
descriptor, class sections, `## Execution` body, and ceremony a listed
skill's `SKILL.md` would carry (pinned decision PD6: behavior parity over
token savings). Executing a sidecar behaves exactly like the skill did when
it was listed; only the way you reach it changed.

The phase-loop subagents are a separate mechanism entirely — **native
agents**, installed outside the skills directory (e.g. `~/.claude/agents/`
for Claude Code):

```
bill-feature-task-pre-planning        digest producer
bill-feature-task-planning            ordered plan / decomposition
bill-feature-task-implementation      executes the plan atomically
bill-feature-task-implementation-fix  review fix-loop respawn
bill-feature-task-completeness-audit  criteria audit
bill-feature-task-quality-check       final validation gate
bill-feature-task-pr-description      PR creation
bill-feature-task-subtask-runner      level-1 goal subtask agent
```

## Where the source lives (nothing moved)

Pinned decision PD3 froze the repo layout. Every internal skill still lives
exactly where it did, as a normal skill directory with a `content.md` — the
*only* authored change that classifies it is one frontmatter line:

```
skills/
  bill-feature/content.md                     listed — the single entry point
  bill-feature-spec/content.md                listed — spec preparation, still Skill-tool invoked
  bill-feature-task/content.md                internal-for: bill-feature
  bill-feature-task-runtime/content.md        internal-for: bill-feature
  bill-feature-task-prose/content.md          internal-for: bill-feature
  bill-feature-task-prose/native-agents/agents.yaml   the phase-agent bundle
  bill-feature-task-subtask-runner/content.md internal-for: bill-feature
  bill-feature-goal/content.md                internal-for: bill-feature
```

Keeping the paths frozen is load-bearing, not cosmetic. The Kotlin runtime
binds to these files by repo path: `WorkflowEngine.CONTINUATION_CONTENT_PATHS`
reads `skills/bill-feature-task/content.md` when it builds a resume payload,
and `RepoValidationRuntime` asserts workflow-step markers inside
`bill-feature-task-prose/content.md`. Likewise every identity string is
byte-for-byte unchanged (PD4): workflow rows are still named
`bill-feature-task`, and the DB `workflow_name` CHECK constraint, telemetry
constants, and MCP tool names are untouched. The feature changed *listing and
invocation plumbing* — never identity or runtime behavior.

Internal skills are deliberately **not** nested under their parent's source
directory (e.g. `skills/bill-feature/internal/…`). Everything inside a
skill's source dir is authored content that copies verbatim into staging and
into the content hash, discovery keys on top-level skill dirs, and the
runtime path bindings above would all churn — for a purely cosmetic benefit.
The frontmatter key is the single source of truth for classification; the
path never encodes it.

## How routing works, end to end

Everything funnels through `bill-feature`. Three decisions happen in order:
*do governed artifacts already exist?* → *single spec or decomposed?* →
*runtime or prose mode?*

```
user: "implement feature …" / "goal status" / …
  │
  ▼
bill-feature                                     [listed]
  │  update check, then artifact detection:
  │    .feature-specs/{KEY}-*/spec.md only          → task sidecar (direct dispatch)
  │    .feature-specs/{KEY}-*/decomposition-manifest → goal sidecar (direct dispatch)
  │    nothing                                      → prepare a spec first
  ▼
bill-feature-spec                                [listed, Skill tool]
  │  produces governed artifacts + mode verdict
  │
  ├── single_spec ──► read sibling bill-feature-task.md        [internal]
  │                     │  mode router: mode:runtime (default) / mode:prose,
  │                     │  parallel-review:<agent>, confirmation gate,
  │                     │  opencode prose-only rule
  │                     ├── mode:runtime ──► read bill-feature-task-runtime.md [internal]
  │                     │                      └─► launches `skill-bill feature-task`
  │                     │                          (foreground Kotlin runtime: durable
  │                     │                           workflow state, per-phase agent
  │                     │                           subprocesses, interrupt/resume)
  │                     └── mode:prose ────► read bill-feature-task-prose.md   [internal]
  │                                            └─► phase loop in-session; heavy phases
  │                                                (preplan, plan, implement, audit,
  │                                                quality check, PR description) run as
  │                                                sequential native-agent subagents
  │
  └── decomposed ──► read sibling bill-feature-goal.md          [internal]
                        │  one confirmation gate; status requests land here too
                        ├── mode:runtime ──► launches `skill-bill goal`
                        │                     (durable goal loop: scheduling, dependency
                        │                      order, limit-pause + resume)
                        └── mode:prose ────► spawns bill-feature-task-subtask-runner
                                              per subtask via the Agent tool — fresh
                                              context per subtask, curated history.md /
                                              decisions.md handoff between them
```

The dispatch sentence is the whole contract. Every hop above (except
`bill-feature-spec`) is literally the parent executing prose of the form:

> Read the file `bill-feature-task.md` located in this skill's own installed
> directory (a sibling of this `SKILL.md`) and execute its instructions in
> the current session with args: `<issue-key> mode:<mode> …`. Do not use the
> Skill tool for this — `bill-feature-task` is an internal skill and is not
> listed.

Arguments flow through unchanged — issue key, spec path, `mode:`,
`parallel-review:`, `--agent-override` — so downstream behavior is identical
to the Skill-tool era.

## The five sidecars at a glance

| Sidecar | Role | Terminal action |
|---|---|---|
| `bill-feature-task.md` | Mode router for one implementation unit; confirmation gate; opencode refusal | Reads the runtime or prose sibling |
| `bill-feature-task-runtime.md` | Runtime-backed single-spec execution | Launches `skill-bill feature-task` |
| `bill-feature-task-prose.md` | In-session phase-loop orchestrator | Spawns the phase native agents |
| `bill-feature-goal.md` | Decomposed-goal gate, both modes, status behavior | Launches `skill-bill goal` or runs the prose subtask loop |
| `bill-feature-task-subtask-runner.md` | Documentation only — its real artifact is the native agent of the same name | — |

The subtask-runner is the one deliberate oddity: its skill directory exists
so the agent contract is authored and governed like everything else, but
nothing ever reads its sidecar to execute it. The executable artifact is
registered in `skills/bill-feature-task-prose/native-agents/agents.yaml` and
installed through the native-agent pipeline — which is why the install code
deliberately keeps internal skills enumerated as native-agent source roots
even though it excludes them from staging and linking.

## How the install pipeline produces this

One classification, read through one parser, validated by one rule evaluator,
consumed at three seams:

- **Classification.** `parseInternalForFrontmatter` is the single place that
  reads the key (first occurrence wins; a blank value is preserved so it can
  fail loudly, never "treat as listed"). `InternalSkillClassification.kt`
  holds the one rule evaluator: parent must exist, must be a listed *base*
  skill, must not be the skill itself, no chaining (depth 1), and
  platform-pack skills cannot carry the key at all. Authoring discovery and
  install-plan building throw the first violation as a typed
  `InvalidInternalSkillClassificationError`; `skill-bill validate` reports
  every violation as an issue.
- **Planning.** `discoverBaseSkills` carries `internalFor` onto each
  `InstallPlanSkill`. Staging intents are only emitted for skills that
  actually stage standalone — internal skills get none.
- **Staging.** When a parent stages, `discoverInternalSidecarTargets` finds
  its children under the plan's skills root, renders each child's governed
  wrapper once (one authoring-discovery walk per staging operation), writes
  them as `<skill-name>.md` into the parent's staging dir, and folds the
  rendered bytes into the parent's **content hash**. That hash fold makes the
  system self-maintaining: editing a child's `content.md` invalidates the
  parent's cache entry and re-renders the sidecar on the next install. The
  hash section is appended only when children exist, so a repo with no
  internal skills produces byte-identical output to the pre-SKILL-102
  pipeline.
- **Linking.** Agent skills-dir symlinks are created only for non-internal
  skills. The direct path is guarded too: `skill-bill link-skill` against an
  internal skill's directory refuses with the same typed error.
- **Reuse and repair.** A cached staging dir is reused only if its hash
  marker, `SKILL.md`, *and every expected sidecar* are intact; anything
  pruned or half-written triggers a clean re-render. Uninstalling the parent
  removes its one symlink — the sidecars live inside the parent's staging
  dir, so there is nothing separate to clean up.

### Guardrails enforced at `skill-bill validate` time

- All classification rules above, as repo-validation issues.
- **Collision guard:** an authored file in the parent's source dir occupying
  a would-be sidecar name (e.g. an authored `bill-feature/bill-feature-task.md`)
  fails validation and staging (`InternalSkillSidecarCollisionError`).
- **Reference co-location:** every `` `<skill-name>.md` `` sidecar reference
  inside any skill's prose must resolve to an internal skill sharing the
  referencing skill's effective parent — so re-parenting or de-listing one
  family member breaks validate, not a live session with a file-not-found.
- **README catalog:** internal skills are exempt from the "every skill
  appears in the README catalog" rule, since they are intentionally not
  user-invocable.

## Why it is shaped this way

- **File-read over Skill tool** — forced by reality (no agent has an
  invocable-but-hidden state) and better anyway: sibling file reads are the
  most portable primitive across supported agents.
- **Sidecars inside the parent, not a shared hidden folder** — the parent's
  installed directory is the one location the parent can always resolve
  ("a sibling of this SKILL.md"), needs no per-agent path knowledge, and
  inherits the parent's install/uninstall/cache lifecycle for free.
- **Full wrapper, no trimmed format** (PD6) — the executed behavior had to be
  provably identical to the listed era.
- **Repo paths and identity frozen** (PD3/PD4) — runtime resume, the DB CHECK
  constraint, telemetry history, and MCP dispatch all bind to the old names
  and paths; the blast radius stayed inside the install pipeline and the
  skill prose.
- **Direct dispatch** — hiding `bill-feature-task` removed the old shortcut
  of invoking it directly when a spec already exists; the artifact-detection
  route in `bill-feature` restores that with zero user-facing ceremony.
- **`bill-feature-spec` stayed listed** — it is a different kind of skill
  (preparation without implementation) with a legitimate standalone life, so
  it keeps its Skill-tool contract.

## File map

| Concern | Where |
|---|---|
| Classification rules + parser (single source) | `runtime-kotlin/runtime-infra-fs/…/scaffold/authoring/InternalSkillClassification.kt` |
| Authoring discovery (reads the key, validates) | `…/scaffold/authoring/AuthoringDiscovery.kt` |
| Install-plan discovery + plan-time validation | `…/install/plan/InstallPlanSkillDiscovery.kt`, `InstallPlanBuilder.kt` |
| Sidecar discovery + render-once carrier | `…/install/staging/InternalSkillSidecars.kt` |
| Hash folding, staging, reuse checks | `…/install/staging/InstallStaging.kt`, `InstallStagingIO.kt` |
| Standalone-install filter, native-agent roots | `…/install/apply/InstallApply.kt`, `InstallApplyNativeAgents.kt` |
| Direct link-skill guard | `…/install/plan/InstallPrimitives.kt` |
| Validate-time rules incl. sidecar references | `…/scaffold/runtime/RepoValidationRuntime.kt` |
| Typed errors | `runtime-kotlin/runtime-contracts/…/error/ShellContentContractErrors.kt` |
| Routing prose (the actual dispatch sentences) | `skills/bill-feature/content.md`, `skills/bill-feature-task/content.md` |
| Authored contract (normative) | `docs/skill-source-generation.md` → Internal Skills |
| Tests | `InternalSkillStagingTest`, `InternalSkillClassificationTest`, `InstallPlanInternalSkillDiscoveryTest`, `RepoValidationRuntimeTest` |
