# Skill Bill

A governed system for portable AI-agent behavior: stable base commands, shared orchestration, validator-backed contracts, cross-agent installers, scaffolding, and local-first telemetry that keep one source of truth from drifting as the repo grows.

Skill Bill is a governance product, not a prompt dump. This repo ships the shared orchestration playbooks under `orchestration/`, validators and CLI/MCP runtime under `skill_bill/` and `scripts/`, cross-agent installers, the `bill-create-skill` authoring path, SQLite-backed telemetry, and stable base shells such as `bill-code-review` and `bill-quality-check`. Governed pack skills now use a thin `SKILL.md` wrapper plus sibling `content.md` and `shell-ceremony.md` sidecars. `bill-feature-implement` now follows the same split as a top-level workflow shell: the workflow contract and telemetry ownership stay in `skills/bill-feature-implement/SKILL.md`, while the detailed execution body lives in `skills/bill-feature-implement/content.md`. For humans, `content.md` is the real working surface where authored skill behavior lives; `SKILL.md` is scaffold-managed wiring that points the runtime at the right authored content and shared ceremony. The shell+content contract is versioned at `orchestration/shell-content-contract/PLAYBOOK.md`, and top-level orchestrators can additionally adopt the workflow contract at `orchestration/workflow-contract/PLAYBOOK.md`.

Shipped platform packs live under `platform-packs/`. Routing, validation, and installation are manifest-driven, so any conforming platform pack can live here without changing the shell.

Rolling out to a team? Start with [Getting Started for Teams](docs/getting-started-for-teams.md) — it covers customization, expectations, and when to trust vs. verify output.

## Why this exists

Most prompt or skill repos degrade over time:

- names drift
- overlapping skills appear
- stack-specific behavior leaks into generic prompts
- different agents get different copies

Skill Bill treats skills more like software:

- stable base capabilities
- platform-specific overrides
- shared routing logic
- CI-enforced naming and structure
- one repo synced to every supported agent

## What it looks like

You interact through a handful of stable base commands. They auto-detect your stack and route to the right specialists.

**Code review** — one command, stack-aware specialist reviews:

```
/bill-code-review

Review session ID: rvs-20260402-221530
Review run ID: rvw-20260402-221530
Detected stack: kotlin
Routed to: bill-kotlin-code-review
Execution mode: inline
Applied learnings: none
Specialist reviews: architecture, platform-correctness, testing

### 2. Risk Register
- [F-001] Major | High | app/src/main/java/...:42 | Shared state mutation is not protected by synchronization.
- [F-002] Major | Medium | app/src/main/java/...:88 | ViewModel scope is used from the wrong thread context.
- [F-003] Minor | High | app/src/test/...:17 | Error-path coverage is missing for the new branch.
```

**Feature implementation** — end-to-end from design doc to PR:

```
/bill-feature-implement

1. Collects design doc, creates acceptance criteria
2. Creates branch, plans implementation tasks
3. Implements each task atomically
4. Runs /bill-code-review (auto-routed to your stack)
5. Completeness audit against acceptance criteria
6. Runs /bill-quality-check (auto-routed)
7. Generates PR description
```

**Quality check** — auto-routed to your stack's toolchain:

```
/bill-quality-check

Detected stack: kotlin
Routed to: bill-kotlin-quality-check

Running ./gradlew check...
Build: PASS
Tests: 247 passed
Lint: PASS
```

## How routing works

A single `feature-implement` run chains 10-12 skill invocations:

```
/bill-feature-implement
├── plan + acceptance criteria
├── implementation (atomic tasks)
├── /bill-code-review (auto-routed)
│   └── e.g. bill-kotlin-code-review
│       ├── execution mode: inline or delegated
│       ├── architecture (inline pass or subagent)
│       ├── platform-correctness (inline pass or subagent)
│       ├── security (inline pass or subagent, if applicable)
│       ├── testing (inline pass or subagent, if applicable)
│       └── api-contracts / persistence / reliability (inline pass or subagent, when backend signals are present)
├── /bill-quality-check (auto-routed)
│   └── e.g. bill-kotlin-quality-check
├── completeness audit
└── /bill-pr-description
```

Small, low-risk review scopes may stay inline in one thread. Larger or higher-risk scopes use delegated review passes and report the chosen execution mode explicitly.

After stack routing, a platform pack may apply governed add-ons from `platform-packs/<platform>/addons/`. These are pack-owned supporting files, not standalone skills or extra slash commands. Routed platform skills in that same pack may reference them from their sibling `content.md` files to enrich the already-selected skill, and they continue to surface as metadata such as `Selected add-ons: android-compose, android-navigation, android-interop, android-design-system, android-r8` for KMP Android work.

The current `kmp` pilot uses:
- `android-compose-implementation.md`
- `android-compose-review.md`
- `android-compose-edge-to-edge.md`
- `android-compose-adaptive-layouts.md`
- `android-navigation-implementation.md`
- `android-navigation-review.md`
- `android-interop-implementation.md`
- `android-interop-review.md`
- `android-design-system-implementation.md`
- `android-design-system-review.md`
- `android-r8-implementation.md`
- `android-r8-review.md`

Runtime skills scan the add-on index first, then open only the linked topic files whose cues match the current work so Android-specific depth stays available without paying the token cost on every KMP run.

The intent is for these pack-owned add-ons to be the apex Android reference layer inside Skill Bill for transferable Android development guidance: Compose edge-to-edge and adaptive surfaces, Android navigation/state patterns, host-boundary interoperability, design-system/theming work, and Android shrinker/R8 behavior. Android-specific upgrade playbooks such as AGP migrations or Play Billing version bumps stay out of runtime add-ons unless they are intentionally modeled as their own governed assets.

Base entry points stay stable for users:

- `/bill-code-review` routes to the matching `bill-<platform>-code-review` discovered from `platform-packs/`
- `/bill-quality-check` routes to the matching stack-specific quality checker
- `/bill-feature-implement` orchestrates the full workflow

## Skills vs workflows

Skill Bill keeps **skills** and **workflows** separate on purpose:

- skills are the reusable user-facing units: routing, rubrics, stack depth, and standalone execution
- workflows are the small set of top-level orchestrators that need durable step state, explicit artifact handoff, retry/resume rules, and parent-owned telemetry

Current top-level workflow adopters:

- `bill-feature-implement`
- `bill-feature-verify`

Both still present as stable user-facing skills, but their internal step graphs now have a governed home under `orchestration/workflow-contract/PLAYBOOK.md` instead of living only in skill prose.

The workflow runtime now exposes discovery, resume, and continue surfaces through:

- `skill-bill workflow ...` for `bill-feature-implement`
- `skill-bill verify-workflow ...` for `bill-feature-verify`
- matching MCP tools for each workflow family

Interrupted runs can be reactivated from persisted state instead of being reconstructed from chat history, and each `continue` surface now returns a step-specific continuation contract for the owning top-level skill.

## Reference platform packs

Skill Bill's governed architecture lives in `platform-packs/<slug>/`. Any platform is allowed as long as it follows the governed pack contract.

| Tier     | Platforms   | What you get                                                                                                                                                                           | Skill count            |
|----------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| **Deep** | Kotlin, KMP | Multi-layer specialist routing (KMP → Kotlin baseline), 12 governed Android add-ons (Compose, navigation, interop, design-system, R8), inline/delegated execution modes, quality-check | 13 skills + 12 add-ons |

The point of the shipped inventory is to demonstrate the governance model with real maintained packs.

**What "Deep" means here:**

- Deep platforms have multi-layer routing, governed add-ons for framework-specific guidance, and specialist areas that compose across packages.
- Kotlin provides the baseline review and quality-check path.
- KMP layers Android/KMP-specific review depth and governed Android add-ons on top of the Kotlin baseline.

## Review telemetry

Skill Bill records review acceptance metrics locally in SQLite and can optionally sync anonymized analytics to a hosted relay or custom proxy. The `skill-bill` MCP server exposes review import, triage, learnings, and stats as native agent tools — no bash commands needed. See [docs/review-telemetry.md](docs/review-telemetry.md) for the full workflow, CLI reference, learnings management, telemetry events, and proxy configuration.

## Supported agents

| Agent          | Install path                              |
|----------------|-------------------------------------------|
| GitHub Copilot | `~/.copilot/skills/`                      |
| Claude Code    | `~/.claude/commands/`                     |
| GLM            | `~/.glm/commands/`                        |
| OpenAI Codex   | `~/.codex/skills/` or `~/.agents/skills/` |
| OpenCode       | `~/.config/opencode/skills/`              |

The installer links all selected agents to the same repo so updates stay in sync, and registers the local Skill Bill MCP server for agents with config-based MCP support.

## Installation

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
chmod +x install.sh
./install.sh
```

If you want a stable install target instead of tracking `main`, clone a release tag and install from that checkout:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

The installer first asks which agent targets to install to. You can choose one or more entries, including `all`:

```text
all
```

It then shows the available platform packs discovered under `platform-packs/` and asks which ones to install. Canonical skills in `skills/` are always installed. Governed add-ons under `platform-packs/<platform>/addons/` ship with their owning platform pack and do not appear as separate install targets or slash commands. The primary input path is **comma-separated numbers**, though platform names still work too.

Available options depend on the packs present in your checkout. For example, a checkout with the Kotlin and KMP reference packs shows:

```text
1. Kotlin
2. KMP
3. all
```

Example platform selections:

```text
1
1,2
2
3
```

Each installer run replaces the existing Skill Bill installs and reinstalls only the agent and platform selections from that run.

The installer always removes existing Skill Bill installs before reinstalling the selected agents and platforms. Installed skills are symlinks back to `skills/` in this repo, so edits to skill files are picked up immediately without re-running `./install.sh`.

## Uninstallation

To remove Skill Bill skill installs from the supported agent install paths:

```bash
chmod +x uninstall.sh
./uninstall.sh
```

The uninstaller is idempotent. It removes current Skill Bill installs, generated alias installs, and known legacy install names when they are present, and skips unrelated non-symlink paths.

## Reference skill catalog

The skills below ship in this repo as the built-in governance system plus the currently maintained platform packs. Install them via `./install.sh` or extend them via `/bill-create-skill`.

### Code Review (1 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-code-review` | Shell-owned code-review router; routes to the matching platform pack based on manifest-declared signals |

### Platform Packs — Kotlin (9 skills)

Reference pack at `platform-packs/kotlin/`. Covers shared Kotlin plus backend/server Kotlin code and acts as the baseline layer for the KMP pack.

| Skill | Purpose |
|-------|---------|
| `/bill-kotlin-code-review` | Kotlin baseline review orchestrator |
| `/bill-kotlin-code-review-architecture` | Kotlin architecture and boundaries review |
| `/bill-kotlin-code-review-platform-correctness` | Kotlin lifecycle, coroutine, threading, and logic review |
| `/bill-kotlin-code-review-performance` | Kotlin performance review |
| `/bill-kotlin-code-review-security` | Kotlin security review |
| `/bill-kotlin-code-review-testing` | Kotlin test quality review |
| `/bill-kotlin-code-review-api-contracts` | Kotlin backend API contract review |
| `/bill-kotlin-code-review-persistence` | Kotlin backend persistence review |
| `/bill-kotlin-code-review-reliability` | Kotlin backend reliability review |

### Platform Packs — KMP (3 skills)

Reference pack at `platform-packs/kmp/`. Layers Android/KMP-specific reviewers on the Kotlin baseline. Also owns governed Android add-ons.

| Skill | Purpose |
|-------|---------|
| `/bill-kmp-code-review` | Android/KMP review override |
| `/bill-kmp-code-review-ui` | KMP UI review |
| `/bill-kmp-code-review-ux-accessibility` | KMP UX and accessibility review |

### Feature Lifecycle (4 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-feature-implement` | Spec-to-verified implementation workflow shell - heavy phases run in subagents, with authored execution guidance in sibling `content.md` |
| `/bill-feature-verify` | Spec-to-PR verification workflow with durable workflow-state support |
| `/bill-feature-guard` | Add feature-flag rollout safety |
| `/bill-feature-guard-cleanup` | Remove feature flags after rollout |

### Utilities (9 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-quality-check` | Shared quality-check router |
| `/bill-kotlin-quality-check` | Gradle/Kotlin quality-check implementation |
| `/bill-boundary-history` | Maintain `agent/history.md` at module/package/area boundaries |
| `/bill-boundary-decisions` | Record architectural/implementation decisions in `agent/decisions.md` |
| `/bill-unit-test-value-check` | Audit unit tests for real value |
| `/bill-pr-description` | Generate PR title, description, and QA steps, preferring repo PR templates when present |
| `/bill-grill-plan` | Stress-test a plan or design by walking every decision branch |
| `/bill-create-skill` | Scaffold a new skill or platform skill set and sync it to all agents |
| `/bill-skill-remove` | Remove an existing skill or platform skill set and clean up installs and wiring |

## Project customization

Use `AGENTS.md` for repo-wide guidance.

Use `.agents/skill-overrides.md` for per-skill customization without editing this plugin. The file is intentionally strict:

- first line must be `# Skill Overrides`
- each section must be `## <existing-skill-name>`
- each section body must be a bullet list
- freeform text outside sections is invalid

Precedence:

1. matching `.agents/skill-overrides.md` section
2. `AGENTS.md`
3. built-in skill defaults

Example:

```md
# Skill Overrides

## bill-kotlin-quality-check
- Treat warnings as blocking work.

## bill-pr-description
- Keep QA steps concise.
```

## Architecture

### Core model

The repo is organized around a strict four-layer model:

- `skills/` — canonical, user-facing capabilities such as `bill-code-review` (a governed shell), `bill-quality-check` (also a governed shell), and `bill-feature-implement` (a top-level workflow shell with sibling `content.md`)
- `skills/<platform>/` — platform-specific overrides for skills that have not been piloted onto the shell+content contract yet (today: `bill-feature-implement` and `bill-feature-verify` only; code-review and quality-check are shelled)
- `platform-packs/<platform>/` — user-owned platform packs consumed by the `bill-code-review` shell via the shell+content contract. Each pack ships a `platform.yaml` manifest plus per-area reviewer content
- `orchestration/` — single source of truth for shared routing, review, delegation, telemetry, and shell+content contracts

Think of it as markdown with inheritance:

- canonical skills define the stable contracts
- platform skills specialize them
- orchestration files are the canonical shared contracts for routing, review, delegation, and telemetry; skills link to them via sibling symlinks, so changes propagate to every linked skill immediately

### Fast mental model

If you only remember four things, remember these:

1. Users enter through stable skills in `skills/`.
2. Platform depth lives in `skills/<platform>/`.
3. Governed add-ons live under `platform-packs/<platform>/addons/` and apply only after stack routing.
4. Shared logic is documented in `orchestration/`, but runtimes consume it through sibling sidecars such as `stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`, and `telemetry-contract.md`.
5. Topology changes should start in `scripts/skill_repo_contracts.py`, then flow into skills, tests, and docs.

That last file is the canonical map for:

- which shared playbook snapshots exist
- which runtime-facing skills require which sidecars
- which review skills are governed by the shared review/delegation contract

Current shipped platform packs (under `platform-packs/`):

- `kotlin` — reference pack with 9 code-review skills plus `bill-kotlin-quality-check`
- `kmp` — reference pack with 3 code-review skills and 12 governed Android add-ons; quality-check currently falls back to `kotlin`

Additional stacks can be added as conforming platform packs under `platform-packs/`.

### Naming and enforcement

Naming is intentionally strict:

- canonical skills may use any neutral `bill-<capability>` name
- platform overrides must use `bill-<platform>-<base-capability>`
- deeper specialization is only allowed for code review:
  - `bill-<platform>-code-review-<area>`

Approved `code-review` areas:

- `architecture`
- `performance`
- `platform-correctness`
- `security`
- `testing`
- `api-contracts`
- `persistence`
- `reliability`
- `ui`
- `ux-accessibility`

That means new stacks can extend the system, but they cannot invent random new naming shapes without intentionally updating the validator and docs.

## Validation

This repo validates both content quality and taxonomy rules.

Local checks:

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

## Test runs

For everyday development, use the smallest test slice that proves the change:

- full repo suite: `.venv/bin/python3 -m unittest discover -s tests`
- focused workflow/runtime coverage: run the specific `tests.test_<area>` modules you touched
- opt-in subprocess workflow E2E coverage: set `SKILL_BILL_RUN_WORKFLOW_E2E=1`

Example focused run for the verify workflow runtime:

```bash
.venv/bin/python3 -m unittest \
  tests.test_feature_verify_workflow_state \
  tests.test_feature_verify_agent_resume \
  tests.test_feature_verify_workflow_contract \
  tests.test_feature_verify_telemetry \
  tests.test_cli \
  tests.test_mcp_stdio
```

Example opt-in workflow E2E run:

```bash
SKILL_BILL_RUN_WORKFLOW_E2E=1 .venv/bin/python3 -m unittest tests.test_feature_verify_workflow_e2e
```

CI runs the same checks.

## Versioning and releases

Skill Bill uses tag-driven GitHub Releases.

- stable releases use SemVer tags such as `v0.4.0`
- prereleases use SemVer prerelease tags such as `v0.5.0-rc.1`
- pushing a release tag reruns validation and publishes a GitHub Release with generated notes

See `RELEASING.md` for the maintainer checklist and versioning policy.

The validator enforces:

- package location rules
- naming rules
- README catalog drift
- cross-skill references
- required routing playbook references
- plugin metadata

## Skill taxonomy

The repo now has three distinct layers, and it helps to read them in that order:

- `content.md` is the authored working surface for a governed skill. This is where maintainers put the actual execution guidance, rubrics, routing cues, add-on selection rules, and specialist-specific judgment.
- `SKILL.md` is the scaffold-managed entry wrapper. Agents enter through it, but it is intentionally thin: descriptor metadata, a pointer to `content.md`, and pointers to governed sidecars such as `shell-ceremony.md`.
- sibling supporting files such as `shell-ceremony.md`, `stack-routing.md`, `review-orchestrator.md`, and `telemetry-contract.md` provide shared behavior when that family needs it. These are contract surfaces, not the place for day-to-day authored skill logic.

In practice: if you are changing what a skill tells an agent to do, you almost always want `content.md`. If you are changing shared runtime ceremony, reporting, routing, or telemetry behavior, you are usually in a governed sidecar or orchestration playbook instead.

That now includes `skills/bill-feature-implement/`: keep workflow-state markers, continuation behavior, stable artifact names, and telemetry ownership in `SKILL.md`, but edit the detailed execution flow in sibling `content.md`.

## Authoring a skill

Skill Bill v1.1 splits every governed skill into two sibling files:

- **`content.md`** — the actual authoring surface. Put the skill's real
  behavior here: execution guidance, rubrics, routing cues,
  project-specific rules, classification signals, add-on selection
  rules, and specialist heuristics. When maintainers say "edit the
  skill", this is usually the file they mean. Open it with
  `skill-bill edit <skill-name>`; by default it walks each authored H2
  section in the terminal with replace / append / clear / skip actions.
  Pass `--section <heading>` to target one section, `--editor` to hand
  off to `$VISUAL` or `$EDITOR`, or `--body-file` to replace the full
  body (or one targeted section) from stdin or another path. For scripted
  or agent-authored writes, use `skill-bill fill <skill-name> --body-file`
  or `--body`; it writes `content.md`, regenerates the wrapper, and
  reruns validation without manual file editing.
- **`SKILL.md`** — generated governance shell. Agents execute through
  this file, but authors normally should not edit it directly. It carries
  frontmatter (`name`, `description`), the required governed H2 set, a
  template-owned `## Execution` pointer to the sibling `content.md`, and
  the shell-owned ceremony such as overrides precedence
  (`.agents/skill-overrides.md` > `AGENTS.md` > built-in defaults).
- **shared sidecars** — files such as `shell-ceremony.md`,
  `stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`,
  and `telemetry-contract.md`. These hold governed cross-skill behavior
  when needed. They are part of the execution contract, but they are not
  the normal authoring surface for skill-specific logic.

The shell owns output contracts (session/run IDs, severity/confidence
scales, risk-register format), orchestration (delegation/inline mode,
scope-determination bullet lists), telemetry and learnings pointers,
sidecar-file references, and `## Project Overrides` — none of that
belongs in `content.md`. See
`orchestration/shell-content-contract/PLAYBOOK.md` for the full taxonomy
and ceremony blacklist.

Example shape:

```
platform-packs/kotlin/code-review/bill-kotlin-code-review/
├── SKILL.md            # generated, contract-enforced shell
├── content.md          # author-owned skill body
├── stack-routing.md    # sibling supporting files (sym-linked)
├── review-orchestrator.md
├── review-delegation.md
└── telemetry-contract.md
```

When the shared wrapper template changes, regenerate scaffold-managed
wrappers with `skill-bill render` (alias: `skill-bill upgrade`). Use
`--skill-name <skill>` to target a single skill; omit it to refresh the
full repo. Wrapper regeneration leaves the authored `content.md` working
surface alone and reruns `scripts/validate_agent_configs.py`.

Legacy v1.0 skills migrate through the one-shot script
`.venv/bin/python3 scripts/migrate_to_content_md.py`. The migration is
idempotent, writes `_migration_backup/<timestamp>/` before the first
rewrite, rolls back per-skill on validator failure, and never commits.
This is a maintainer-only bulk workflow; normal skill edits should go
through `skill-bill edit`.

## Adding skills

Preferred path:

- from inside an AI agent, run `/bill-create-skill`. The skill starts with plain-language intake, gathers only the missing authoring details, previews the inferred scaffold, then subprocess-calls `skill-bill new --payload <tempfile>` to materialize it.
- outside an agent (scripts, CI, teams piloting a new platform), run `skill-bill new --interactive` for the same plain-language bootstrap flow, or pass a JSON payload file with `skill-bill new --payload ./payload.json`.
- when you want one command that scaffolds and drops directly into authoring, use `skill-bill create-and-fill --interactive` (or `--payload`). It is only for one content-managed skill at a time; use `skill-bill new` for platform-pack bootstrap flows.

Terminal-first loop for one concrete skill:

1. `skill-bill new --interactive`
2. `skill-bill show <skill-name>`
3. `skill-bill edit <skill-name>` or `skill-bill fill <skill-name> --body-file -`
4. `skill-bill doctor skill <skill-name>`
5. `skill-bill validate --skill-name <skill-name>`
6. `skill-bill render --skill-name <skill-name>` when wrapper templates change

`skill-bill new-skill` and `skill-bill upgrade` remain supported as the
lower-level command names behind `new` and `render`.

Additional authoring helpers:

- `skill-bill show <skill-name>` gives a stable read path for humans and agents: section headings, completion status, drift status, and recommended next commands.
- `skill-bill explain [<skill-name>]` explains the governed boundary between `content.md`, generated `SKILL.md`, and shared sidecars.
- `skill-bill doctor skill <skill-name>` runs isolated validation and returns a plain-language diagnosis plus the next commands to run.

For governed skills, the interactive flow can seed an initial `content.md`
body so the authored behavior is captured at scaffold time instead of being
backfilled by editing `SKILL.md`. New platform packs are scaffolded as a
bootstrap set: pack root, baseline `code-review`, and baseline
`quality-check`. Known platforms such as `java` use built-in routing
presets; only unknown or custom platforms need manual `routing_signals`.
`platform-pack` supports `skeleton_mode=full` for approved specialist stubs
or `starter` for the baseline pair only.

The payload schema, the loud-fail exception catalog, and one worked example per kind live in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

The scaffolder is atomic: it creates files, edits manifests with best-effort comment preservation, wires sibling supporting-file symlinks, runs `scripts/validate_agent_configs.py`, and auto-installs into detected agents. Any failure rolls back every staged change and prints the validator's error verbatim.

When the shared wrapper template changes, run `skill-bill render` from the
repo root. It regenerates scaffold-managed `SKILL.md` wrappers in place,
then reruns `scripts/validate_agent_configs.py`. Authored `content.md`
files and the shared `shell-ceremony.md` sidecar stay untouched.

Manual path (discouraged — prefer the scaffolder):

1. create the governed skill directory in the correct taxonomy location
2. add the scaffold-managed `SKILL.md`, the authored `content.md`, and any required governed sidecars or symlinks
3. follow the naming rules above
4. run `./install.sh`
5. update docs and validation if you intentionally add a new package or naming shape

## License

MIT — free to use, copy, modify, merge, publish, distribute, sublicense, and sell, provided the license notice is retained.
