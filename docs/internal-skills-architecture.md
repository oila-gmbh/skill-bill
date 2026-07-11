# Internal Skills Architecture

How dispatch-only skills become one visible entry point: the `internal-for`
classification, the sidecar install mechanism, the file-read dispatch
contract, and where every piece of prose, runtime, and agent machinery lives.
This is the architecture companion to the authored contract in
[skill-source-generation.md](skill-source-generation.md) (Internal Skills
section), which owns the normative rules.

Two families are worked examples here: the feature-execution family (the
SKILL-102 origin case, base-skill internals) and the code-review family
(SKILL-104, the platform-pack extension).

## Part 1 — The feature-execution family (base-skill internals, SKILL-102)

### The idea in one paragraph

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

## Part 2 — The code-review family (platform-pack internals, SKILL-104)

The same mechanism, extended to platform-pack skills. The code-review family
had stack-specific review skills listed to users even though the supported
entry point is `/bill-code-review`, which detects the dominant stack from
`platform.yaml` routing signals and routes automatically. Hiding them removes
dozens of listed skills from every agent's skill list and makes the listed
surface match the actual product surface.

### What changed in the contract (and what did not)

Exactly one rule changed (PD1): platform-pack skills may now declare
`internal-for`. Every other classification rule is byte-for-byte unchanged —
blank value, self parent, unknown parent, parent must be a listed base skill
under `skills/`, no chained `internal-for` (depth is 1). The pack manifest
(`platform.yaml`) is never consulted for classification; there is no
manifest-level internality flag. The review-pack frontmatter additions are the
only authored source change in the family.

### Flatten rule (PD2)

All review-pack skills — the stack entries AND their specialists —
declare `internal-for: bill-code-review`. Stack entry skills do **not** become
parents of their specialists. Nesting (specialists internal to their stack
entry, entries internal to `bill-code-review`) would require depth-2 sidecars —
a sidecar hosting sidecars — which the staging model cannot express (a sidecar
is a file, not a directory). Flattening keeps depth at 1, and sibling
co-location is what the review flow wants: the routed entry sidecar and the
specialist rubrics it reads live in one directory.

### Selection-aware sidecars (PD3) and installed layout

A base-skill internal sidecar stages whenever its parent stages. A
platform-pack internal sidecar stages only when its pack is selected
(`PlatformPackSelection`: `NONE`/`SELECTED`/`ALL`). Sidecar discovery consults
the install plan's selected pack skills (each already carries `sourceDir` and
parsed `internalFor`) rather than re-scanning `platform-packs/` independently
of selection. The parent's content hash folds exactly the selected sidecars.

After a scratch install with all packs selected, `bill-code-review`'s staged
directory contains `SKILL.md` plus 78 sibling sidecars — and no agent
`skills_dir` symlink exists for any of the 78:

```
~/.claude/skills/bill-code-review
  → ~/.skill-bill/installed-skills/bill-code-review-<content-hash>/
      SKILL.md                              rendered governed wrapper — the listed entry
      content.md                            authored source, copied verbatim
      bill-ios-code-review.md               sidecar: iOS stack entry (selected)
      bill-ios-code-review-api-contracts.md sidecar: iOS specialist
      ... (10 iOS specialists total)
      bill-go-code-review.md                sidecar: Go stack entry (selected)
      ... (10 Go specialists total)
      bill-kotlin-code-review.md            sidecar: Kotlin stack entry (selected)
      ... (8 Kotlin specialists total)
      bill-kmp-code-review.md               sidecar: KMP stack entry (selected)
      ... (2 KMP specialists total)
      bill-php-code-review.md               sidecar: PHP stack entry (selected)
      ... (10 PHP specialists total)
      bill-python-code-review.md            sidecar: Python stack entry (selected)
      ... (10 Python specialists total)
      bill-typescript-code-review.md        sidecar: TypeScript stack entry (selected)
      ... (10 TypeScript specialists total)
      platform-packs → …                    symlink for pack pointer resolution
```

With only the Kotlin pack selected, exactly 9 review sidecars stage
(`bill-kotlin-code-review.md` plus its 8 specialists); the other 69 contribute
nothing. With no review packs selected, `bill-code-review` stages
byte-identically to a repo with no internal pack skills (inertness). `ALL`
selection stages every opted-in review sidecar. SKILL-105 applies the same
selection-aware sidecar model to quality-check overrides: selected
`bill-<platform>-code-check` skills stage inside `bill-code-check/` and are not
listed commands.

### Baseline co-presence guard (PD8)

The KMP pack declares `bill-kotlin-code-review` as a required baseline layer
(`platform-packs/kmp/platform.yaml`,
`code_review_composition.baseline_layers`). The KMP orchestrator reads that
baseline as a sibling sidecar at review time. Once both are sidecars, selecting
KMP without Kotlin would leave the baseline sidecar absent. Install planning
loud-fails with `MissingBaselinePlatformSelectionError` when the selection
includes a pack declaring a required baseline layer in an unselected pack;
there is no silent auto-include. `ALL` selection is trivially safe.

### Routing walkthrough

```
user: "/bill-code-review" on a Kotlin diff
  │
  ▼
bill-code-review                                   [listed]
  │  reads platform.yaml routing signals from the diff
  │  (strong signals, then tie-breakers) → dominant pack
  ▼
read sibling bill-kotlin-code-review.md            [internal sidecar]
  │  the routed pack entry sidecar; reads its specialist rubric
  │  selection table (signal → area) and spawns specialists
  │
  ├── read sibling bill-kotlin-code-review-architecture.md   [internal sidecar]
  ├── read sibling bill-kotlin-code-review-security.md       [internal sidecar]
  ├── read sibling bill-kotlin-code-review-testing.md        [internal sidecar]
  └── ... per the signal table
  │
  ▼
  each specialist's rubric executes; findings merge into the
  review summary, risk register, and verdict
```

For a KMP diff, the routed entry is `bill-kmp-code-review.md`, which also reads
`bill-kotlin-code-review.md` as its baseline layer sidecar before its own
specialists. Delegated review workers (parallel lanes, sub-process reviewers)
receive rendered runtime instructions and rubric content/paths from the parent
orchestrator — no worker ever resolves one of the platform-pack sidecars via the Skill tool or a
standalone `skills_dir` path (PD5).

### Selection-shaped variance at a glance

| Selection | Sidecars staged inside `bill-code-review/` |
|---|---|
| `ALL` | 78 (8 stack entries + 70 specialists) |
| Kotlin only | 9 (`bill-kotlin-code-review.md` + 8 specialists) |
| KMP only | fails — Kotlin is a required baseline (PD8) |
| KMP + Kotlin | 12 (3 KMP + 9 Kotlin) |
| None | 0; `bill-code-review` stages inert (byte-identical to no pack internals) |

### File map additions (platform-pack side)

| Concern | Where |
|---|---|
| Relaxed rule (pack skills may carry `internal-for`) | `runtime-kotlin/runtime-infra-fs/.../scaffold/authoring/InternalSkillClassification.kt` |
| Selection-aware sidecar discovery | `.../install/staging/InternalSkillSidecars.kt` (consults `InstallPlanSkill.sourceDir`) |
| Baseline co-presence guard | `.../install/plan/InstallPlanPolicy.kt`, `MissingBaselinePlatformSelectionError` in `ShellContentContractErrors.kt` |
| Pack-internal README catalog exemption | `.../scaffold/runtime/RepoValidationRuntime.kt` (`validateReadme`) |
| Authored pack source (unchanged paths) | `platform-packs/{go,ios,kotlin,kmp,php,python,rust,typescript}/code-review/<skill>/content.md` |
| Tests | `InternalSkillStagingTest`, `InternalSkillClassificationTest`, `InstallPlanInternalSkillDiscoveryTest`, `MissingBaselinePlatformSelectionTest`, `RepoValidationRuntimeTest` |
