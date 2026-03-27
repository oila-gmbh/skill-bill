# sKill Bill

sKill Bill is a portable AI skill suite for code review, feature implementation, and developer tooling. Today it is strongest for Android, KMP, Kotlin backend/server, and agent-config repositories, with naming conventions designed to expand cleanly to other stacks over time. Install once, use from any AI coding agent.

## What Is This?

This plugin is a collection of 24 AI skills that help with code review, feature development, and project maintenance. Instead of maintaining separate prompts for each AI agent, all skills live in one place and are distributed via symlinks to every agent you use.

sKill Bill started as a mobile-focused plugin, and it now covers Android, KMP, Kotlin backend/server work, shared Kotlin code, and agent-config repositories under one skill suite while leaving room for future stack-specific variants.

**The key idea**: edit a skill once in this repo, and every agent sees the update instantly. No copy-pasting, no drift between agents.

All skills are prefixed with `bill-` to avoid name clashes with your own custom skills.

## Supported Agents

| Agent | Skills directory | Status |
|-------|-----------------|--------|
| **GitHub Copilot** | `~/.copilot/skills/` | Supported |
| **Claude Code** | `~/.claude/commands/` | Supported |
| **GLM** | `~/.glm/commands/` | Supported |
| **OpenAI Codex** | `~/.codex/skills/` or `~/.agents/skills/` | Supported |

The installer auto-skips agents that aren't installed on your machine.

For Codex, newer setups may use `~/.codex/skills/` while older setups may still use `~/.agents/skills/`. The installer prefers `~/.codex/skills/` when it exists and falls back to `~/.agents/skills/` otherwise.

## Installation

### 1. Clone the repo

```bash
git clone <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
```

### 2. Run the installer

```bash
chmod +x install.sh
./install.sh --mode safe
```

The installer will ask you to enter your agents as a **comma-separated list, primary agent first**:

```
Enter agents (comma-separated, primary first): copilot, claude, glm, codex
```

The **primary agent** holds the direct symlinks to the plugin. All other agents chain through the primary. This means:

```
plugin/skills/base/bill-code-review/             <-- source of truth (this repo)
        | symlink
~/.copilot/skills/bill-code-review/              <-- primary agent
        | symlink
~/.claude/commands/bill-code-review/             <-- secondary agent
~/.glm/commands/bill-code-review/                <-- secondary agent
~/.codex/skills/bill-code-review/                <-- secondary agent (Codex)
```

That's it. All 24 skills are now available in every agent you selected.

**Re-running the installer is safe by default.** `--mode safe` migrates plugin-managed legacy symlinks, refreshes current symlinks, and skips non-symlink conflicts so local copied/customized skill directories are not overwritten.

Installer modes:

- `--mode safe` — replace symlinks, migrate legacy plugin installs, skip non-symlink conflicts
- `--mode override` — replace any existing target path, including local copies, and prune stale installed `bill-*` skills that are no longer in this repo
- `--mode interactive` — prompt before replacing non-symlink conflicts

### Source Layout

The repository groups source skills by package:

- `skills/base/` — cross-stack routers, workflows, and utilities
- `skills/kotlin/` — Kotlin review orchestration, quality checks, and generic review specialists
- `skills/kmp/` — KMP/UI-specific skills
- `skills/backend-kotlin/` — Kotlin backend/server review override and specialists
- `orchestration/` — internal playbooks shared by routers and orchestrators; not installed as user-invokable skills

These locations are part of the taxonomy contract, not just an organization preference:

- put a skill in `skills/base/` only when it is truly neutral across stacks or when it is a stable base entry point that delegates elsewhere
- put a skill in a stack package only when its heuristics, commands, or review rules are genuinely stack-specific
- keep reusable decision logic in `orchestration/`, not in installable skills
- follow existing structural patterns before inventing a new one

### Base vs Override Model

This repo treats package layout as a capability model:

- `skills/base/` defines canonical capabilities and stable user-facing entry points
- `skills/<platform>/` provides platform-specific overrides or platform-owned subskills for those capabilities
- `orchestration/` holds shared routing and decision logic that multiple skills reuse

In practice, that means:

- base skills are flexible as long as they use a neutral `bill-<capability>` name
- platform skills are not flexible in the same way; they must play a strict role in the taxonomy
- a platform skill should usually be an override of an existing base capability such as `bill-kotlin-quality-check` or `bill-backend-kotlin-code-review`
- code review is the only capability that currently allows deeper platform subskills, and those subskills must stay inside the approved area list

This is what keeps the repo focused: base can grow new capabilities intentionally, while platform packages can only specialize that base shape instead of inventing unrelated names.

Installed agent commands stay flat, so users still run `/bill-code-review` rather than a package-qualified command.

### Alternative: Claude Code Plugin

If you only use Claude Code, you can install this as a plugin instead:

```bash
claude plugin install ~/Development/skill-bill
```

### Transferring to Another Machine

```bash
git clone <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh --mode safe
```

No config files to edit — the installer handles everything interactively.

## Skills Included

### Code Review (14 skills)

Run `/bill-code-review` to start a review. `bill-code-review` is the stable shared entry point: it reads the shared routing playbook, classifies work using package-aligned stack names such as `kotlin`, `backend-kotlin`, and `kmp`, and delegates to the matching stack-specific orchestrator. In this routing taxonomy, Android work also maps into the `kmp` bucket. For Kotlin-family repos, generic Kotlin routes to `bill-kotlin-code-review`; backend-heavy Kotlin routes to `bill-backend-kotlin-code-review`; and `kmp` routes to `bill-kmp-code-review`, which layers KMP-specific specialists on top of the appropriate Kotlin-family baseline.

| Skill | Description |
|-------|-------------|
| `/bill-code-review` | Shared router: detects stack, delegates to the matching stack-specific reviewer |
| `/bill-kotlin-code-review` | Orchestrator: runs the baseline Kotlin review for shared/generic Kotlin concerns |
| `/bill-backend-kotlin-code-review` | Orchestrator: layers backend/server review on top of `bill-kotlin-code-review` |
| `/bill-kmp-code-review` | Orchestrator: layers Android/KMP-specific review on top of the appropriate Kotlin-family baseline |
| `/bill-kotlin-code-review-architecture` | Shared Kotlin architecture, boundaries, DI, and source-of-truth review |
| `/bill-kmp-code-review-ui` | KMP UI review capability; today implemented with Jetpack Compose best practices and optimization |
| `/bill-kotlin-code-review-platform-correctness` | Shared Kotlin lifecycle, coroutine, threading, and logic correctness review |
| `/bill-kotlin-code-review-performance` | Shared Kotlin hot-path, blocking I/O, latency, and resource-usage review |
| `/bill-kotlin-code-review-security` | Shared Kotlin secrets, auth/session, sensitive-data, and storage/transport review |
| `/bill-kotlin-code-review-testing` | Shared Kotlin test value, regression protection, and reliability review |
| `/bill-kmp-code-review-ux-accessibility` | UX states, a11y, validation |
| `/bill-backend-kotlin-code-review-api-contracts` | Backend API contracts, validation, serialization, compatibility |
| `/bill-backend-kotlin-code-review-persistence` | Backend persistence, transactions, migrations, data consistency |
| `/bill-backend-kotlin-code-review-reliability` | Backend timeouts, retries, jobs, caching, observability |

### Feature Lifecycle (4 skills)

| Skill | Description |
|-------|-------------|
| `/bill-feature-implement` | End-to-end: design spec, plan, implement, review, auto-select validation, PR |
| `/bill-feature-verify` | Verify a PR against a task spec (reverse of implement) |
| `/bill-feature-guard` | Wrap changes in feature flags for safe rollout |
| `/bill-feature-guard-cleanup` | Remove feature flags after full rollout |

### Utilities (6 skills)

| Skill | Description |
|-------|-------------|
| `/bill-quality-check` | Shared router: detects stack and delegates to the matching stack-specific quality-checker |
| `/bill-kotlin-quality-check` | Run `./gradlew check` and fix all issues (no suppressions) |
| `/bill-boundary-history` | Update module/package/area `agent/history.md` with feature history |
| `/bill-unit-test-value-check` | Standalone audit for unit tests with real business value instead of tautological coverage padding |
| `/bill-pr-description` | Generate PR title, description, and QA steps |
| `/bill-new-skill-all-agents` | Create a new skill and sync it to all agents |

## Project Customization

Use **`AGENTS.md`** in the project root for repo-wide conventions that should influence multiple skills.

Use **`.agents/skill-overrides.md`** for per-skill customization without modifying this plugin. Each skill looks for a matching `## bill-...` section and treats that section as the highest-priority instruction for that skill only.

The file is intentionally strict so CI can validate it:

- first line must be `# Skill Overrides`
- each override section must be `## <existing-skill-name>`
- each section body must be a bullet list
- freeform text outside sections is invalid

Precedence is:

1. Matching section in `.agents/skill-overrides.md`
2. `AGENTS.md`
3. Built-in skill defaults

Example `.agents/skill-overrides.md`:

```md
# Skill Overrides

## bill-kotlin-quality-check
- Treat warnings as blocking work.
- Skip formatting-only rewrites unless the user explicitly asks for them.

## bill-pr-description
- Always include ticket links when the branch name contains one.
- Keep QA steps concise unless the user asks for a full matrix.
```

Use `AGENTS.md` for project-wide conventions (naming, test framework, architecture rules, etc.) and `.agents/skill-overrides.md` for targeted skill behavior changes.

## Automatic Validation

`/bill-feature-implement` is the center of gravity for this repo. It now **auto-selects the final validation gate** based on the repository it is changing so the user does not need to decide which checker to run:

- Gradle/Kotlin repos → `bill-quality-check`
- Agent-config / skill repos → inline agent-config validation (`agnix` + repo-native drift checks)
- Mixed repos → both

For this repository, CI enforces the same path with:

- `npx --yes agnix --strict .`
- `python3 scripts/validate_agent_configs.py` (catalog drift, cross-skill references, and `skills/<package>/<skill>/SKILL.md` naming/location rules)

## Skill Naming Strategy

Keep `bill` as the stable namespace prefix. Encode stack or platform in the rest of the skill name only when the skill is stack-specific.

Use these patterns:

- Base, cross-stack skills: `bill-<capability>`
- Stack-specific skills: `bill-<stack>-<capability>`
- Code-review subskills only: `bill-<stack>-code-review-<area>`

Examples:

- Base: `bill-code-review`, `bill-quality-check`, `bill-pr-description`, `bill-boundary-history`, `bill-feature-guard`, `bill-feature-implement`, `bill-feature-verify`
- Kotlin review/quality skills: `bill-kotlin-code-review`, `bill-kotlin-quality-check`
- KMP/UI specialists: `bill-kmp-code-review`, `bill-kmp-code-review-ui`, `bill-kmp-code-review-ux-accessibility`
- Kotlin backend/server override and specialists: `bill-backend-kotlin-code-review`, `bill-backend-kotlin-code-review-api-contracts`, `bill-backend-kotlin-code-review-persistence`
- PHP: `bill-php-code-review`, `bill-php-feature-implement`, `bill-php-quality-check`

Guidelines:

- Keep base utility names neutral unless the skill is truly stack-bound
- Only add a stack label when behavior, heuristics, or tooling are meaningfully different
- Let package location enforce platform relatedness: base skills stay generic; stack packages hold the real stack-specific behavior
- Reuse the existing router/orchestrator/specialist shape instead of inventing one-off structures for similar capabilities
- Platform overrides must reuse an existing base capability name; do not invent new platform-only capability names
- Code-review subskills are the only approved deeper specialization shape, and their `<area>` must be one of: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`
- Any new package or approved name shape requires an intentional validator update in the same change
- Prefer readable slash commands over perfect taxonomy purity
- When renaming an existing stack-bound skill, update installer migration rules and docs in the same change

### Enforcement Model

CI enforces the naming model at the package level:

- `skills/base/` accepts any neutral `bill-<capability>` skill name
- stack packages such as `skills/kotlin/`, `skills/kmp/`, `skills/backend-kotlin/`, and `skills/php/` must use `bill-<platform>-...` names
- a stack skill must either:
  - override an existing base capability with `bill-<platform>-<base-capability>`, or
  - use the approved code-review specialization shape `bill-<platform>-code-review-<area>`

This is intentional. It means a future PHP package can add skills, but only by following the same structure already used in the repo. New random platform-specific capability names are rejected unless the validator and README are intentionally updated together.

## Naming Migration Plan

Use migration-aware renames instead of one-off manual cleanup:

1. Keep base skills neutral with `bill-<capability>` naming
2. Use explicit stack/tool prefixes for stack-bound skills (`/bill-kotlin-code-review`, `/bill-backend-kotlin-code-review`, `/bill-kmp-code-review`, `/bill-kotlin-quality-check`)
3. When a canonical name changes, add the old name to the installer migration map so legacy plugin-managed installs are removed automatically on rerun
4. Let `./install.sh --mode safe` skip non-symlink conflicts so local copied variants are preserved unless the user explicitly chooses `override`

This keeps migrations predictable while making room for PHP and future stacks.

## Base Router Pattern

When a workflow needs one stable cross-stack entry point but the actual implementation differs by stack, use a base router skill in `skills/base/` and keep the real logic in stack-specific skills.

Pattern:

- base router keeps a neutral name such as `bill-code-review` or `bill-quality-check`
- shared decision logic lives in an internal orchestration playbook such as `orchestration/stack-routing/PLAYBOOK.md`
- router detects stack and delegates to `bill-<stack>-...`
- stack-specific skills own heuristics, commands, and deep workflow rules
- generic workflows like feature implement/verify call the base router, not a stack-specific skill directly

Canonical example:

- `bill-quality-check` lives in `skills/base/` because it is the neutral entry point
- `orchestration/stack-routing/PLAYBOOK.md` owns the shared stack-detection rules
- `bill-kotlin-quality-check` lives in `skills/kotlin/` because it owns the real Gradle/Kotlin commands and fix strategy
- `bill-code-review` lives in `skills/base/`, delegates generic Kotlin review to `bill-kotlin-code-review`, and lets `bill-backend-kotlin-code-review` plus `bill-kmp-code-review` extend that baseline for backend/server and Android/KMP-specific coverage
- `bill-backend-kotlin-code-review` lives in `skills/backend-kotlin/` because it extends `bill-kotlin-code-review` with backend-specific review depth and backend-only specialists
- `bill-kmp-code-review-ui` is allowed as a deeper platform subskill because code review is the one approved capability with named specialization areas

This same structure should guide future additions: shared entry point -> orchestration decision logic -> platform-specific implementation.

This keeps taxonomy focused, prevents duplicated routing logic, and lets new stacks like PHP plug in with one new specialist skill plus a small router update.

## Orchestration Playbooks

Internal decision playbooks live under `orchestration/` and are not installed as slash commands.

Use them for shared logic such as:

- stack detection signals
- routing tie-breakers
- cross-router decision rules

Current playbook:

- `orchestration/stack-routing/PLAYBOOK.md` — source of truth for router stack detection and delegation rules

## Adding New Skills

You have two options:

**Option A**: Use the built-in skill

Run `/bill-new-skill-all-agents` from any agent. It will ask for a name, description, and instructions, then create the skill file in this repo and set up symlinks to all your agents automatically. Use the naming strategy above when choosing the skill name.

**Option B**: Manual

1. Create `skills/<package>/<skill-name>/SKILL.md` in this repo, where stack-bound names start with the package prefix (for example `skills/backend-kotlin/bill-backend-kotlin-code-review-api-contracts/SKILL.md`)
2. Run `./install.sh --mode safe` to sync to all agents

Either way, the new skill becomes available in every connected agent.
