# Mobile Development Plugin

A portable AI skill suite for Android/Kotlin projects — code review, feature implementation, and developer tooling. Install once, use from any AI coding agent.

## What Is This?

This plugin is a collection of 16 AI skills (slash commands) that help with code review, feature development, and project maintenance. Instead of maintaining separate prompts for each AI agent, all skills live in one place and are distributed via symlinks to every agent you use.

**The key idea**: edit a skill once in this repo, and every agent sees the update instantly. No copy-pasting, no drift between agents.

All skills are prefixed with `mdp-` (mobile development plugin) to avoid name clashes with your own custom skills.

## Supported Agents

| Agent | Skills directory | Status |
|-------|-----------------|--------|
| **GitHub Copilot** | `~/.copilot/skills/` | Supported |
| **Claude Code** | `~/.claude/commands/` | Supported |
| **GLM** | `~/.glm/commands/` | Supported |

The installer auto-skips agents that aren't installed on your machine.

## Installation

### 1. Clone the repo

```bash
git clone <this-repo> ~/Development/mobile-development-plugin
cd ~/Development/mobile-development-plugin
```

### 2. Run the installer

```bash
chmod +x install.sh
./install.sh
```

The installer will ask you to enter your agents as a **comma-separated list, primary agent first**:

```
Enter agents (comma-separated, primary first): copilot, claude, glm
```

The **primary agent** holds the direct symlinks to the plugin. All other agents chain through the primary. This means:

```
plugin/skills/mdp-code-review/           <-- source of truth (this repo)
        | symlink
~/.copilot/skills/mdp-code-review/       <-- primary agent
        | symlink
~/.claude/commands/mdp-code-review/      <-- secondary agent
~/.glm/commands/mdp-code-review/         <-- secondary agent
```

That's it. All 16 skills are now available as slash commands in every agent you selected.

**Re-running the installer is safe.** It only touches `mdp-*` skills that belong to this plugin — any custom skills you created independently in your agent's directory are left untouched. Plugin skills are refreshed with updated symlinks.

### Alternative: Claude Code Plugin

If you only use Claude Code, you can install this as a plugin instead:

```bash
claude plugin install ~/Development/mobile-development-plugin
```

### Transferring to Another Machine

```bash
git clone <this-repo> ~/Development/mobile-development-plugin
cd ~/Development/mobile-development-plugin
./install.sh
```

No config files to edit — the installer handles everything interactively.

## Skills Included

### Code Review (8 skills)

Run `/mdp-code-review` to start a review. The orchestrator analyzes the diff and spawns 2-6 specialist agents in parallel, then merges and deduplicates findings.

| Skill | Description |
|-------|-------------|
| `/mdp-code-review` | Orchestrator: spawns 2-6 specialist reviews, merges results |
| `/mdp-code-review-architecture` | Architecture, boundaries, DI, source-of-truth |
| `/mdp-code-review-compose-check` | Jetpack Compose best practices and optimization |
| `/mdp-code-review-platform-correctness` | Lifecycle, coroutines, threading, Flow composition |
| `/mdp-code-review-performance` | Recomposition, main-thread work, resource usage |
| `/mdp-code-review-security` | Secrets, auth, PII, transport/storage |
| `/mdp-code-review-testing` | Coverage gaps, flaky tests, regression risk |
| `/mdp-code-review-ux-accessibility` | UX states, a11y, validation |

### Feature Lifecycle (4 skills)

| Skill | Description |
|-------|-------------|
| `/mdp-feature-implement` | End-to-end: design spec, plan, implement, review, PR |
| `/mdp-feature-verify` | Verify a PR against a task spec (reverse of implement) |
| `/mdp-feature-guard` | Wrap changes in feature flags for safe rollout |
| `/mdp-feature-guard-cleanup` | Remove feature flags after full rollout |

### Utilities (4 skills)

| Skill | Description |
|-------|-------------|
| `/mdp-gcheck` | Run `./gradlew check` and fix all issues (no suppressions) |
| `/mdp-module-history` | Update module-level agent/history.md with feature history |
| `/mdp-pr-description` | Generate PR title, description, and QA steps |
| `/mdp-new-skill-all-agents` | Create a new skill and sync it to all agents |

## Project Customization

Every review and check skill looks for an **`AGENTS.md`** file in the project root. If found, its rules are applied on top of the built-in defaults. Project rules take precedence when they conflict.

Use this to define project-specific conventions (naming, test framework, architecture rules, etc.) without modifying the plugin itself. Each project can have its own `AGENTS.md`.

## Adding New Skills

You have two options:

**Option A**: Use the built-in skill

Run `/mdp-new-skill-all-agents` from any agent. It will ask for a name, description, and instructions, then create the skill file in this repo and set up symlinks to all your agents automatically.

**Option B**: Manual

1. Create `skills/mdp-my-skill/SKILL.md` in this repo
2. Run `./install.sh` to sync to all agents

Either way, the new skill becomes available as a slash command in every connected agent.
