# sKill Bill

Treat your AI skills like software — with stable interfaces, platform overrides, and validation that prevents the repo from rotting.

sKill Bill is a portable collection of 44 AI skills for code review, feature implementation, and developer tooling. One repo, synced to every supported agent. Currently strongest for Kotlin, Android/KMP, Kotlin backend/server, PHP backends, and Go backends/services.

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

Detected stack: kotlin
Routed to: bill-kotlin-code-review
Specialist reviews: architecture, platform-correctness, testing

[ARCHITECTURE]
P0: Shared state mutation not protected by synchronization

[PLATFORM CORRECTNESS]
P1: ViewModel scope used outside main thread context

[TESTING]
Minor: Test coverage for error path incomplete
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
│       ├── architecture (subagent)
│       ├── platform-correctness (subagent)
│       ├── security (subagent, if applicable)
│       └── testing (subagent, if applicable)
├── /bill-quality-check (auto-routed)
│   └── e.g. bill-kotlin-quality-check
├── completeness audit
└── /bill-pr-description
```

Base entry points stay stable for users:

- `/bill-code-review` routes to `bill-kotlin-code-review` | `bill-backend-kotlin-code-review` | `bill-kmp-code-review` | `bill-php-code-review` | `bill-go-code-review`
- `/bill-quality-check` routes to the matching stack-specific quality checker
- `/bill-feature-implement` orchestrates the full workflow

## Supported agents

| Agent | Install path |
|-------|--------------|
| GitHub Copilot | `~/.copilot/skills/` |
| Claude Code | `~/.claude/commands/` |
| GLM | `~/.glm/commands/` |
| OpenAI Codex | `~/.codex/skills/` or `~/.agents/skills/` |

The installer links all selected agents to the same repo so updates stay in sync.

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

It then shows the available platform packages and asks which ones to install. Base skills in `skills/base/` are always installed; platform packages are installed only when selected.

Available options are shown as separate entries:

```text
Kotlin backend
Kotlin
KMP
PHP
Go
all
```

Example platform selections:

```text
Kotlin backend, Kotlin, KMP
PHP
Go
all
```

Each installer run replaces the existing Skill Bill links and reinstalls only the agent and platform selections from that run.

The installer always removes existing Skill Bill links before reinstalling the selected agents and platforms.

If you only use Claude Code, you can also install this repo as a Claude plugin:

```bash
claude plugin install ~/Development/skill-bill
```

## Uninstallation

To remove Skill Bill skill symlinks from the supported agent install paths:

```bash
chmod +x uninstall.sh
./uninstall.sh
```

The uninstaller is idempotent. It removes current Skill Bill skill names plus known legacy install names when they are present as symlinks, and skips non-symlink paths.

## Skills Included

### Code Review (32 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-code-review` | Shared review router |
| `/bill-kotlin-code-review` | Kotlin baseline review orchestrator |
| `/bill-backend-kotlin-code-review` | Backend Kotlin review override |
| `/bill-kmp-code-review` | Android/KMP review override |
| `/bill-kotlin-code-review-architecture` | Kotlin architecture and boundaries review |
| `/bill-kotlin-code-review-platform-correctness` | Kotlin lifecycle, coroutine, threading, and logic review |
| `/bill-kotlin-code-review-performance` | Kotlin performance review |
| `/bill-kotlin-code-review-security` | Kotlin security review |
| `/bill-kotlin-code-review-testing` | Kotlin test quality review |
| `/bill-kmp-code-review-ui` | KMP UI review |
| `/bill-kmp-code-review-ux-accessibility` | KMP UX and accessibility review |
| `/bill-backend-kotlin-code-review-api-contracts` | Backend API contract review |
| `/bill-backend-kotlin-code-review-persistence` | Backend persistence and migration review |
| `/bill-backend-kotlin-code-review-reliability` | Backend reliability and observability review |
| `/bill-php-code-review` | PHP backend review orchestrator |
| `/bill-php-code-review-architecture` | PHP architecture and boundary review |
| `/bill-php-code-review-platform-correctness` | PHP correctness, ordering, retry, and stale-state review |
| `/bill-php-code-review-api-contracts` | PHP API contract and serialization review |
| `/bill-php-code-review-persistence` | PHP persistence, transaction, and migration review |
| `/bill-php-code-review-reliability` | PHP reliability, retry, and observability review |
| `/bill-php-code-review-security` | PHP security review |
| `/bill-php-code-review-performance` | PHP performance review |
| `/bill-php-code-review-testing` | PHP test quality review |
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
| `/bill-feature-implement` | Spec-to-verified implementation workflow |
| `/bill-feature-verify` | Verify a PR against a task spec |
| `/bill-feature-guard` | Add feature-flag rollout safety |
| `/bill-feature-guard-cleanup` | Remove feature flags after rollout |

### Utilities (8 skills)

| Skill | Purpose |
|-------|---------|
| `/bill-quality-check` | Shared quality-check router |
| `/bill-kotlin-quality-check` | Gradle/Kotlin quality-check implementation |
| `/bill-php-quality-check` | PHP quality-check implementation |
| `/bill-go-quality-check` | Go quality-check implementation |
| `/bill-boundary-history` | Maintain `agent/history.md` at module/package/area boundaries |
| `/bill-unit-test-value-check` | Audit unit tests for real value |
| `/bill-pr-description` | Generate PR title, description, and QA steps |
| `/bill-new-skill-all-agents` | Create a new skill and sync it to all agents |

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

The repo is organized around a strict three-layer model:

- `skills/base/` — canonical, user-facing capabilities such as `bill-code-review`, `bill-quality-check`, and `bill-feature-implement`
- `skills/<platform>/` — platform-specific overrides and approved subskills
- `orchestration/` — maintainer-facing reference snapshots for shared routing, review, and delegation contracts

Think of it as markdown with inheritance:

- base skills define the stable contracts
- platform skills specialize them
- orchestration snapshots document the shared routing, review, and delegation logic that runtime-facing skills can reference via sibling supporting files in the same skill directory

### Fast mental model

If you only remember four things, remember these:

1. Users enter through stable skills in `skills/base/`.
2. Platform depth lives in `skills/<platform>/`.
3. Shared logic is documented in `orchestration/`, but runtimes consume it through sibling sidecars such as `stack-routing.md`, `review-orchestrator.md`, and `review-delegation.md`.
4. Topology changes should start in `scripts/skill_repo_contracts.py`, then flow into skills, tests, and docs.

That last file is the canonical map for:

- which shared playbook snapshots exist
- which runtime-facing skills require which sidecars
- which review skills are governed by the shared review/delegation contract

Current platform packages:

- `kotlin`
- `backend-kotlin`
- `kmp`
- `php`
- `go`

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
python3 -m unittest discover -s tests
npx --yes agnix --strict .
python3 scripts/validate_agent_configs.py
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

- run `/bill-new-skill-all-agents`

Manual path:

1. create `skills/<package>/<skill-name>/SKILL.md`
2. follow the naming rules above
3. run `./install.sh`
4. update docs and validation if you intentionally add a new package or naming shape

## License

MIT — free to use, copy, modify, merge, publish, distribute, sublicense, and sell, provided the license notice is retained.
