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
| **OpenAI Codex** | `~/.agents/skills/` | Supported |

The installer auto-skips agents that aren't installed on your machine.

For Codex specifically, `~/.agents/skills/` is the user skill location. `~/.codex/` is Codex's config/state area, not the shared skill directory.

## Installation

### 1. Clone the repo

```bash
git clone <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
```

### 2. Run the installer

```bash
chmod +x install.sh
./install.sh
```

The installer will ask you to enter your agents as a **comma-separated list, primary agent first**:

```
Enter agents (comma-separated, primary first): copilot, claude, glm, codex
```

The **primary agent** holds the direct symlinks to the plugin. All other agents chain through the primary. This means:

```
plugin/skills/bill-code-review/           <-- source of truth (this repo)
        | symlink
~/.copilot/skills/bill-code-review/       <-- primary agent
        | symlink
~/.claude/commands/bill-code-review/      <-- secondary agent
~/.glm/commands/bill-code-review/         <-- secondary agent
~/.agents/skills/bill-code-review/        <-- secondary agent (Codex)
```

That's it. All 20 skills are now available in every agent you selected.

**Re-running the installer is safe.** It only touches `bill-*` skills that belong to this plugin — any custom skills you created independently in your agent's directory are left untouched. Plugin skills are refreshed with updated symlinks.

### Alternative: Claude Code Plugin

If you only use Claude Code, you can install this as a plugin instead:

```bash
claude plugin install ~/Development/skill-bill
```

### Transferring to Another Machine

```bash
git clone <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

No config files to edit — the installer handles everything interactively.

## Skills Included

### Code Review (11 skills)

Run `/bill-code-review` to start a review. The orchestrator classifies the project type conservatively, preserves the full Android/KMP review path when mobile signals are strong, and spawns 2-6 specialist agents in parallel before merging and deduplicating findings, including a real-test-value pass when tests change.

| Skill | Description |
|-------|-------------|
| `/bill-code-review` | Orchestrator: classifies project type, spawns 2-6 specialist reviews, merges results |
| `/bill-code-review-architecture` | Architecture, boundaries, DI, source-of-truth across Android/KMP/backend |
| `/bill-code-review-compose-check` | Jetpack Compose best practices and optimization |
| `/bill-code-review-platform-correctness` | Lifecycle, coroutines, threading, Flow composition, server correctness |
| `/bill-code-review-performance` | Recomposition, hot-path work, blocking I/O, resource usage |
| `/bill-code-review-security` | Secrets, auth, PII, transport/storage across mobile and backend |
| `/bill-code-review-testing` | Coverage gaps, flaky tests, tautological tests, and regression risk |
| `/bill-code-review-ux-accessibility` | UX states, a11y, validation |
| `/bill-code-review-backend-api-contracts` | Backend API contracts, validation, serialization, compatibility |
| `/bill-code-review-backend-persistence` | Backend persistence, transactions, migrations, data consistency |
| `/bill-code-review-backend-reliability` | Backend timeouts, retries, jobs, caching, observability |

### Feature Lifecycle (4 skills)

| Skill | Description |
|-------|-------------|
| `/bill-feature-implement` | End-to-end: design spec, plan, implement, review, auto-select validation, PR |
| `/bill-feature-verify` | Verify a PR against a task spec (reverse of implement) |
| `/bill-feature-guard` | Wrap changes in feature flags for safe rollout |
| `/bill-feature-guard-cleanup` | Remove feature flags after full rollout |

### Utilities (5 skills)

| Skill | Description |
|-------|-------------|
| `/bill-gcheck` | Run `./gradlew check` and fix all issues (no suppressions) |
| `/bill-module-history` | Update module-level agent/history.md with feature history |
| `/bill-unit-test-value-check` | Standalone audit for unit tests with real business value instead of tautological coverage padding |
| `/bill-pr-description` | Generate PR title, description, and QA steps |
| `/bill-new-skill-all-agents` | Create a new skill and sync it to all agents |

## Project Customization

Every review and check skill looks for an **`AGENTS.md`** file in the project root. If found, its rules are applied on top of the built-in defaults. Project rules take precedence when they conflict.

Use this to define project-specific conventions (naming, test framework, architecture rules, etc.) without modifying the plugin itself. Each project can have its own `AGENTS.md`.

## Automatic Validation

`/bill-feature-implement` is the center of gravity for this repo. It now **auto-selects the final validation gate** based on the repository it is changing so the user does not need to decide which checker to run:

- Gradle/Kotlin repos → `bill-gcheck`
- Agent-config / skill repos → inline agent-config validation (`agnix` + repo-native drift checks)
- Mixed repos → both

For this repository, CI enforces the same path with:

- `npx --yes agnix --strict .`
- `python3 scripts/validate_agent_configs.py`

## Skill Naming Strategy

Keep `bill` as the stable namespace prefix. Encode stack or platform in the rest of the skill name only when the skill is stack-specific.

Use these patterns:

- Shared, cross-stack skills: `bill-<capability>`
- Stack-specific skills: `bill-<stack>-<capability>`
- Deeply specialized skills: `bill-<stack>-<area>-<capability>`

Examples:

- Shared: `bill-pr-description`, `bill-module-history`, `bill-feature-verify`
- Kotlin/Android: `bill-kotlin-code-review`, `bill-android-compose-check`, `bill-kmp-feature-implement`
- PHP: `bill-php-code-review`, `bill-php-feature-implement`, `bill-php-laravel-code-review`

Guidelines:

- Keep existing shared utility names unless the skill is truly stack-bound
- Only add a stack label when behavior, heuristics, or tooling are meaningfully different
- Prefer readable slash commands over perfect taxonomy purity
- Avoid renaming existing skills preemptively; add new stack-specific variants as the catalog grows

## Naming Migration Plan

Use an additive migration instead of a flag day rename:

1. Keep existing neutral names such as `/bill-feature-implement` and `/bill-pr-description`
2. Introduce new stack-specific skills with explicit names when adding non-Kotlin variants, such as `/bill-php-feature-implement`
3. Split existing Kotlin-first skills only when there is a real second implementation to justify it
4. When a legacy generic name becomes ambiguous, keep it as the orchestrator or compatibility alias and point users to the stack-specific variants in its description and README

This keeps current workflows stable while making room for PHP and future stacks.

## Adding New Skills

You have two options:

**Option A**: Use the built-in skill

Run `/bill-new-skill-all-agents` from any agent. It will ask for a name, description, and instructions, then create the skill file in this repo and set up symlinks to all your agents automatically. Use the naming strategy above when choosing the skill name.

**Option B**: Manual

1. Create `skills/bill-<capability-or-stack-skill>/SKILL.md` in this repo
2. Run `./install.sh` to sync to all agents

Either way, the new skill becomes available in every connected agent.
