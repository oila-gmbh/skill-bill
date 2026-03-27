# sKill Bill

sKill Bill is a portable AI skill suite for code review, feature implementation, and developer tooling. Today it is strongest for Android, KMP, Kotlin backend/server, and agent-config repositories, with naming conventions designed to expand cleanly to other stacks over time. Install once, use from any AI coding agent.

## What Is This?

This plugin is a collection of 20 AI skills that help with code review, feature development, and project maintenance. Instead of maintaining separate prompts for each AI agent, all skills live in one place and are distributed via symlinks to every agent you use.

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
plugin/skills/kotlin/bill-kotlin-code-review/    <-- source of truth (this repo)
        | symlink
~/.copilot/skills/bill-kotlin-code-review/       <-- primary agent
        | symlink
~/.claude/commands/bill-kotlin-code-review/      <-- secondary agent
~/.glm/commands/bill-kotlin-code-review/         <-- secondary agent
~/.codex/skills/bill-kotlin-code-review/         <-- secondary agent (Codex)
```

That's it. All 20 skills are now available in every agent you selected.

**Re-running the installer is safe by default.** `--mode safe` migrates plugin-managed legacy symlinks, refreshes current symlinks, and skips non-symlink conflicts so local copied/customized skill directories are not overwritten.

Installer modes:

- `--mode safe` — replace symlinks, migrate legacy plugin installs, skip non-symlink conflicts
- `--mode override` — replace any existing target path, including local copies, and prune stale installed `bill-*` skills that are no longer in this repo
- `--mode interactive` — prompt before replacing non-symlink conflicts

### Source Layout

The repository groups source skills by package:

- `skills/shared/` — cross-stack workflows and utilities
- `skills/kotlin/` — Kotlin-focused skills
- `skills/kmp/` — KMP/UI-specific skills

Installed agent commands stay flat, so users still run `/bill-kotlin-code-review` rather than a package-qualified command.

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

### Code Review (11 skills)

Run `/bill-kotlin-code-review` to start a review. The orchestrator classifies the project type conservatively, preserves the full Android/KMP review path when mobile signals are strong, and spawns 2-6 specialist agents in parallel before merging and deduplicating findings, including a real-test-value pass when tests change.

| Skill | Description |
|-------|-------------|
| `/bill-kotlin-code-review` | Orchestrator: classifies project type, spawns 2-6 specialist reviews, merges results |
| `/bill-kotlin-code-review-architecture` | Architecture, boundaries, DI, source-of-truth across Android/KMP/backend |
| `/bill-kmp-code-review-compose-check` | Jetpack Compose best practices and optimization |
| `/bill-kotlin-code-review-platform-correctness` | Lifecycle, coroutines, threading, Flow composition, server correctness |
| `/bill-kotlin-code-review-performance` | Recomposition, hot-path work, blocking I/O, resource usage |
| `/bill-kotlin-code-review-security` | Secrets, auth, PII, transport/storage across mobile and backend |
| `/bill-kotlin-code-review-testing` | Coverage gaps, flaky tests, tautological tests, and regression risk |
| `/bill-kmp-code-review-ux-accessibility` | UX states, a11y, validation |
| `/bill-kotlin-code-review-backend-api-contracts` | Backend API contracts, validation, serialization, compatibility |
| `/bill-kotlin-code-review-backend-persistence` | Backend persistence, transactions, migrations, data consistency |
| `/bill-kotlin-code-review-backend-reliability` | Backend timeouts, retries, jobs, caching, observability |

### Feature Lifecycle (4 skills)

| Skill | Description |
|-------|-------------|
| `/bill-kotlin-feature-implement` | End-to-end: design spec, plan, implement, review, auto-select validation, PR |
| `/bill-kotlin-feature-verify` | Verify a PR against a task spec (reverse of implement) |
| `/bill-feature-guard` | Wrap changes in feature flags for safe rollout |
| `/bill-feature-guard-cleanup` | Remove feature flags after full rollout |

### Utilities (5 skills)

| Skill | Description |
|-------|-------------|
| `/bill-kotlin-quality-check` | Run `./gradlew check` and fix all issues (no suppressions) |
| `/bill-module-history` | Update module-level agent/history.md with feature history |
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

`/bill-kotlin-feature-implement` is the center of gravity for this repo. It now **auto-selects the final validation gate** based on the repository it is changing so the user does not need to decide which checker to run:

- Gradle/Kotlin repos → `bill-kotlin-quality-check`
- Agent-config / skill repos → inline agent-config validation (`agnix` + repo-native drift checks)
- Mixed repos → both

For this repository, CI enforces the same path with:

- `npx --yes agnix --strict .`
- `python3 scripts/validate_agent_configs.py` (catalog drift, cross-skill references, and `skills/<package>/<skill>/SKILL.md` naming/location rules)

## Skill Naming Strategy

Keep `bill` as the stable namespace prefix. Encode stack or platform in the rest of the skill name only when the skill is stack-specific.

Use these patterns:

- Shared, cross-stack skills: `bill-<capability>`
- Stack-specific skills: `bill-<stack>-<capability>`
- Deeply specialized skills: `bill-<stack>-<area>-<capability>`

Examples:

- Shared: `bill-pr-description`, `bill-module-history`, `bill-feature-guard`
- Kotlin/Gradle: `bill-kotlin-code-review`, `bill-kotlin-feature-implement`, `bill-kotlin-quality-check`
- KMP/UI specialists: `bill-kmp-code-review-compose-check`, `bill-kmp-code-review-ux-accessibility`
- PHP: `bill-php-code-review`, `bill-php-feature-implement`, `bill-php-laravel-code-review`

Guidelines:

- Keep shared utility names neutral unless the skill is truly stack-bound
- Only add a stack label when behavior, heuristics, or tooling are meaningfully different
- Prefer readable slash commands over perfect taxonomy purity
- When renaming an existing stack-bound skill, update installer migration rules and docs in the same change

## Naming Migration Plan

Use migration-aware renames instead of one-off manual cleanup:

1. Keep shared skills neutral (`/bill-feature-guard`, `/bill-pr-description`, `/bill-module-history`)
2. Use explicit stack/tool prefixes for stack-bound skills (`/bill-kotlin-feature-implement`, `/bill-kotlin-code-review`, `/bill-kotlin-quality-check`)
3. When a canonical name changes, add the old name to the installer migration map so legacy plugin-managed installs are removed automatically on rerun
4. Let `./install.sh --mode safe` skip non-symlink conflicts so local copied variants are preserved unless the user explicitly chooses `override`

This keeps migrations predictable while making room for PHP and future stacks.

## Adding New Skills

You have two options:

**Option A**: Use the built-in skill

Run `/bill-new-skill-all-agents` from any agent. It will ask for a name, description, and instructions, then create the skill file in this repo and set up symlinks to all your agents automatically. Use the naming strategy above when choosing the skill name.

**Option B**: Manual

1. Create `skills/<package>/bill-<capability-or-stack-skill>/SKILL.md` in this repo
2. Run `./install.sh --mode safe` to sync to all agents

Either way, the new skill becomes available in every connected agent.
