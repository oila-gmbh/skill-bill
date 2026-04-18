# sKill Bill

A framework for governed, portable AI-agent behavior across every major coding agent — stable user-facing commands, platform depth behind a router, and validator-backed contracts that keep things from drifting as the repo grows.

sKill Bill is a framework, not a prompt library. It ships a governed *shell + content* architecture, a scaffolder for authoring new skills, and a cross-agent installer that syncs one source of truth to Claude Code, Copilot, Codex, OpenCode, and GLM. The shell+content contract is versioned at `orchestration/shell-content-contract/PLAYBOOK.md`.

The repository also ships with a reference collection of 48 AI skills — code review, quality check, feature implementation, PR description — across Kotlin, KMP, backend-Kotlin, PHP, Go, and agent-config. Use them as-is, fork them, or ignore them and author your own. The governance model is the product; the packs are examples. Platform depth varies — see [reference platform packs](#reference-platform-packs) for what each stack gets today.

Rolling out to a team? Start with [Getting Started for Teams](docs/getting-started-for-teams.md) — it covers customization, expectations, and when to trust vs. verify output.

## Why this exists

Most prompt or skill repos degrade over time:

- names drift
- overlapping skills appear
- stack-specific behavior leaks into generic prompts
- different agents get different copies

sKill Bill treats skills more like software:

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
│       └── testing (inline pass or subagent, if applicable)
├── /bill-quality-check (auto-routed)
│   └── e.g. bill-kotlin-quality-check
├── completeness audit
└── /bill-pr-description
```

Small, low-risk review scopes may stay inline in one thread. Larger or higher-risk scopes use delegated review passes and report the chosen execution mode explicitly.

After stack routing, a platform package may apply governed add-ons from `skills/<platform>/addons/`. These remain stack-owned metadata such as `Selected add-ons: android-compose, android-navigation, android-interop, android-design-system, android-r8` for KMP Android work. They are not extra slash commands and are not counted in the skill catalog.

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

The intent is for these stack-owned add-ons to be the apex Android reference layer inside Skill Bill for transferable Android development guidance: Compose edge-to-edge and adaptive surfaces, Android navigation/state patterns, host-boundary interoperability, design-system/theming work, and Android shrinker/R8 behavior. Android-specific upgrade playbooks such as AGP migrations or Play Billing version bumps stay out of runtime add-ons unless they are intentionally modeled as their own governed assets.

Base entry points stay stable for users:

- `/bill-code-review` routes to `bill-agent-config-code-review` | `bill-kotlin-code-review` | `bill-backend-kotlin-code-review` | `bill-kmp-code-review` | `bill-php-code-review` | `bill-go-code-review`
- `/bill-quality-check` routes to the matching stack-specific quality checker
- `/bill-feature-implement` orchestrates the full workflow

## Reference platform packs

sKill Bill ships with reference packs under `platform-packs/<slug>/`. These are examples — real, validated, ready to install, and meant to be forked or replaced. Not all packs are at the same depth. The table below shows what each stack gets today so you know what to expect — and where to contribute.

| Tier | Platforms | What you get | Skill count |
|------|-----------|-------------|-------------|
| **Deep** | Kotlin, KMP | Multi-layer specialist routing (KMP → Kotlin baseline), 12 governed Android add-ons (Compose, navigation, interop, design-system, R8), inline/delegated execution modes, quality-check | 10 skills + 12 add-ons |
| **Deep** | Backend-Kotlin | Layers 3 backend-specific specialists (api-contracts, persistence, reliability) on the Kotlin baseline | 4 skills (+ Kotlin baseline) |
| **Solid** | PHP, Go | Full code-review orchestrator with 8 specialist areas each, quality-check, no governed add-ons or framework-specific depth | 10 skills each |
| **Meta** | Agent-config | Self-referential: reviews and validates this skill repo itself | 2 skills |

**What "Deep" means vs "Solid":**

- Deep platforms have multi-layer routing, governed add-ons for framework-specific guidance, and specialist areas that compose across packages.
- Solid platforms have a full specialist roster but no governed add-ons or multi-layer routing. PHP and Go each cover 8 code-review areas — the gap is framework-specific depth (e.g., no Laravel add-ons for PHP, no Chi/Gin add-ons for Go).

The PHP/Go gap is a visible backlog item, not a missing feature. Governed add-ons can be added under `skills/php/addons/` or `skills/go/addons/` when framework-specific guidance is needed.

## Review telemetry

Skill Bill records review acceptance metrics locally in SQLite and can optionally sync anonymized analytics to a hosted relay or custom proxy. The `skill-bill` MCP server exposes review import, triage, learnings, and stats as native agent tools — no bash commands needed. See [docs/review-telemetry.md](docs/review-telemetry.md) for the full workflow, CLI reference, learnings management, telemetry events, and proxy configuration.

## Supported agents

| Agent | Install path |
|-------|--------------|
| GitHub Copilot | `~/.copilot/skills/` |
| Claude Code | `~/.claude/commands/` |
| GLM | `~/.glm/commands/` |
| OpenAI Codex | `~/.codex/skills/` or `~/.agents/skills/` |
| OpenCode | `~/.config/opencode/skills/` |

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

It then shows the available **optional** platform packages and asks which ones to install. Base skills in `skills/base/` and the governed `agent-config` package are always installed; the remaining platform packages are installed only when selected. Governed add-ons under `skills/<platform>/addons/` ship with their owning platform package and do not appear as separate install targets or slash commands. The primary input path is **comma-separated numbers**, though platform names still work too.

Available options are shown as separate entries:

```text
1. Kotlin backend
2. Kotlin
3. KMP
4. PHP
5. Go
6. all
```

Example platform selections:

```text
1,2,3
4
5
6
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

The skills below ship in this repo as reference examples. Install them via `./install.sh`, extend them in your fork, or author your own via `/bill-skill-scaffold`. These are not the product — the governance framework is. They are what a team starts from.

### Code Review (1 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-code-review` | Shell-owned code-review router; routes to the matching platform pack based on manifest-declared signals |

### Platform Packs — Agent config (1 skills)

Shipped example pack at `platform-packs/agent-config/`. Reviews and validates skill/agent-config repositories (self-referential meta pack).

| Skill | Purpose |
|-------|---------|
| `/bill-agent-config-code-review` | Review skill/agent-config repositories |

### Platform Packs — Kotlin (6 skills)

Shipped example pack at `platform-packs/kotlin/`. Covers shared Kotlin code and acts as the baseline layer for KMP and backend-Kotlin packs.

| Skill | Purpose |
|-------|---------|
| `/bill-kotlin-code-review` | Kotlin baseline review orchestrator |
| `/bill-kotlin-code-review-architecture` | Kotlin architecture and boundaries review |
| `/bill-kotlin-code-review-platform-correctness` | Kotlin lifecycle, coroutine, threading, and logic review |
| `/bill-kotlin-code-review-performance` | Kotlin performance review |
| `/bill-kotlin-code-review-security` | Kotlin security review |
| `/bill-kotlin-code-review-testing` | Kotlin test quality review |

### Platform Packs — KMP (3 skills)

Shipped example pack at `platform-packs/kmp/`. Layers Android/KMP-specific reviewers on the Kotlin baseline. Also owns governed Android add-ons.

| Skill | Purpose |
|-------|---------|
| `/bill-kmp-code-review` | Android/KMP review override |
| `/bill-kmp-code-review-ui` | KMP UI review |
| `/bill-kmp-code-review-ux-accessibility` | KMP UX and accessibility review |

### Platform Packs — Backend Kotlin (4 skills)

Shipped example pack at `platform-packs/backend-kotlin/`. Layers backend-specific reviewers on the Kotlin baseline.

| Skill | Purpose |
|-------|---------|
| `/bill-backend-kotlin-code-review` | Backend Kotlin review override |
| `/bill-backend-kotlin-code-review-api-contracts` | Backend API contract review |
| `/bill-backend-kotlin-code-review-persistence` | Backend persistence and migration review |
| `/bill-backend-kotlin-code-review-reliability` | Backend reliability and observability review |

### Platform Packs — PHP (9 skills)

Shipped example pack at `platform-packs/php/`. Covers the PHP ecosystem end-to-end.

| Skill | Purpose |
|-------|---------|
| `/bill-php-code-review` | PHP backend review orchestrator |
| `/bill-php-code-review-architecture` | PHP architecture and boundary review |
| `/bill-php-code-review-platform-correctness` | PHP correctness, ordering, retry, and stale-state review |
| `/bill-php-code-review-api-contracts` | PHP API contract and serialization review |
| `/bill-php-code-review-persistence` | PHP persistence, transaction, and migration review |
| `/bill-php-code-review-reliability` | PHP reliability, retry, and observability review |
| `/bill-php-code-review-security` | PHP security review |
| `/bill-php-code-review-performance` | PHP performance review |
| `/bill-php-code-review-testing` | PHP test quality review |

### Platform Packs — Go (9 skills)

Shipped example pack at `platform-packs/go/`. Covers the Go ecosystem end-to-end.

| Skill | Purpose |
|-------|---------|
| `/bill-go-code-review` | Go backend/service review orchestrator |
| `/bill-go-code-review-architecture` | Go architecture and package-boundary review |
| `/bill-go-code-review-platform-correctness` | Go correctness, goroutine safety, and context review |
| `/bill-go-code-review-api-contracts` | Go API contract and serialization review |
| `/bill-go-code-review-persistence` | Go persistence, transaction, and migration review |
| `/bill-go-code-review-reliability` | Go reliability, timeout, and observability review |
| `/bill-go-code-review-security` | Go security review |
| `/bill-go-code-review-performance` | Go performance review |
| `/bill-go-code-review-testing` | Go test quality review |

### Feature Lifecycle (4 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-feature-implement` | Spec-to-verified implementation workflow — heavy phases run in subagents to keep the orchestrator context small |
| `/bill-feature-verify` | Verify a PR against a task spec |
| `/bill-feature-guard` | Add feature-flag rollout safety |
| `/bill-feature-guard-cleanup` | Remove feature flags after rollout |

### Utilities (11 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-quality-check` | Shared quality-check router |
| `/bill-agent-config-quality-check` | Agent-config repository quality-check implementation |
| `/bill-kotlin-quality-check` | Gradle/Kotlin quality-check implementation |
| `/bill-php-quality-check` | PHP quality-check implementation |
| `/bill-go-quality-check` | Go quality-check implementation |
| `/bill-boundary-history` | Maintain `agent/history.md` at module/package/area boundaries |
| `/bill-boundary-decisions` | Record architectural/implementation decisions in `agent/decisions.md` |
| `/bill-unit-test-value-check` | Audit unit tests for real value |
| `/bill-pr-description` | Generate PR title, description, and QA steps, preferring repo PR templates when present |
| `/bill-grill-plan` | Stress-test a plan or design by walking every decision branch |
| `/bill-skill-scaffold` | Scaffold a new skill or platform skill set and sync it to all agents |

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

- `skills/base/` — canonical, user-facing capabilities such as `bill-code-review` (a governed shell), `bill-quality-check` (also a governed shell), and `bill-feature-implement`
- `skills/<platform>/` — platform-specific overrides for skills that have not been piloted onto the shell+content contract yet (today: `bill-feature-implement` and `bill-feature-verify` only; code-review and quality-check are shelled)
- `platform-packs/<platform>/` — user-owned platform packs consumed by the `bill-code-review` shell via the shell+content contract. Each pack ships a `platform.yaml` manifest plus per-area reviewer content
- `orchestration/` — single source of truth for shared routing, review, delegation, telemetry, and shell+content contracts

Think of it as markdown with inheritance:

- base skills define the stable contracts
- platform skills specialize them
- orchestration files are the canonical shared contracts for routing, review, delegation, and telemetry; skills link to them via sibling symlinks, so changes propagate to every linked skill immediately

### Fast mental model

If you only remember four things, remember these:

1. Users enter through stable skills in `skills/base/`.
2. Platform depth lives in `skills/<platform>/`.
3. Governed add-ons live under `skills/<platform>/addons/` and apply only after stack routing.
4. Shared logic is documented in `orchestration/`, but runtimes consume it through sibling sidecars such as `stack-routing.md`, `review-orchestrator.md`, `review-delegation.md`, and `telemetry-contract.md`.
5. Topology changes should start in `scripts/skill_repo_contracts.py`, then flow into skills, tests, and docs.

That last file is the canonical map for:

- which shared playbook snapshots exist
- which runtime-facing skills require which sidecars
- which review skills are governed by the shared review/delegation contract

Current shipped platform packs (under `platform-packs/`):

- `kotlin` — Deep (6 code-review skills in the pack, baseline for KMP and backend-kotlin) + `bill-kotlin-quality-check` (in-pack under `quality-check/`)
- `kmp` — Deep (3 code-review skills in the pack + 12 governed add-ons, layers on kotlin). Quality-check falls back to kotlin.
- `backend-kotlin` — Deep (4 code-review skills in the pack, layers on kotlin). Quality-check falls back to kotlin.
- `php` — Solid (9 code-review skills in the pack, no add-ons) + `bill-php-quality-check` (in-pack under `quality-check/`)
- `go` — Solid (9 code-review skills in the pack, no add-ons) + `bill-go-quality-check` (in-pack under `quality-check/`)
- `agent-config` — Meta (1 code-review skill in the pack) + `bill-agent-config-quality-check` (in-pack under `quality-check/`)

### Naming and enforcement

Naming is intentionally strict:

- base skills may use any neutral `bill-<capability>` name
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

## Adding skills

Preferred path:

- from inside an AI agent, run `/bill-skill-scaffold`. The skill collects intent with a decision tree over the five supported kinds (horizontal, platform-override-piloted, platform-pack, code-review-area, add-on), previews the scaffolded output with synthesized markers, and subprocess-calls `skill-bill new-skill --payload <tempfile>` to materialize the skill.
- outside an agent (scripts, CI, teams piloting a new platform), run `skill-bill new-skill --interactive` for a kind-specific no-LLM flow, or pass a JSON payload file with `skill-bill new-skill --payload ./payload.json`.

New platform packs are scaffolded as a pack root plus baseline quality-check content, so adding a fresh platform no longer requires manual manifest assembly or README platform catalog maintenance. Known platforms such as `java` use built-in routing presets; only unknown or custom platforms need manual `routing_signals`. `platform-pack` also supports `skeleton_mode=full` to generate a full bare-bones review-area skill set up front. Add specialist areas later with the `code-review-area` flow when you choose the lighter `starter` path.

The payload schema, the loud-fail exception catalog, and one worked example per kind live in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

The scaffolder is atomic: it creates files, edits manifests with best-effort comment preservation, wires sibling supporting-file symlinks, runs `scripts/validate_agent_configs.py`, and auto-installs into detected agents. Any failure rolls back every staged change and prints the validator's error verbatim.

Manual path (discouraged — prefer the scaffolder):

1. create `skills/<package>/<skill-name>/SKILL.md`
2. follow the naming rules above
3. run `./install.sh`
4. update docs and validation if you intentionally add a new package or naming shape

## License

MIT — free to use, copy, modify, merge, publish, distribute, sublicense, and sell, provided the license notice is retained.
