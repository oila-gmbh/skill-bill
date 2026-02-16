# Android Review Plugin

A portable AI code review skill suite for Android/Kotlin projects.
Works across multiple AI agents (Claude, Copilot, GLM) with a single source of truth.

## Skills Included

| Skill | Description |
|-------|-------------|
| `code-review` | Orchestrator: runs 6 parallel specialist reviews, merges results |
| `code-review-architecture` | Architecture, boundaries, DI, source-of-truth |
| `code-review-platform-correctness` | Lifecycle, coroutines, threading, correctness |
| `code-review-performance` | Recomposition, main-thread work, resource usage |
| `code-review-security` | Secrets, auth, PII, transport/storage |
| `code-review-testing` | Coverage gaps, flaky tests, regression risk |
| `code-review-ux-accessibility` | UX states, a11y, validation, Compose delegation |
| `compose-check` | Jetpack Compose best practices and optimization |
| `gcheck` | Run `./gradlew check` and fix all issues (no suppressions) |
| `feature-guard` | Wrap changes in feature flags for safe rollout |
| `flow-composition` | Compose async streams into deterministic UI state |
| `repository-resilience` | Error handling, safe defaults, predictable contracts |
| `source-of-truth-sync` | Sync engines, cache invalidation, conflict resolution |
| `new-skill-all-agents` | Create new skills and sync to all agents |

## Setup

### Quick Install

```bash
git clone <this-repo> ~/Development/android-review-plugin
cd ~/Development/android-review-plugin
chmod +x install.sh
./install.sh
```

### What It Does

1. Reads `config.yaml` to find your AI agents and primary agent
2. Symlinks skills from `plugin/skills/` → primary agent's skill directory
3. Symlinks other agents → primary agent (chained symlinks)

```
plugin/skills/code-review/  ←── source of truth
        ↓ symlink
~/.copilot/skills/code-review/  ←── primary (Copilot)
        ↓ symlink
~/.claude/commands/code-review/  ←── secondary (Claude)
~/.glm/commands/code-review/     ←── secondary (GLM)
```

### Configuration

Edit `config.yaml` to match your setup:

```yaml
primary: copilot

agents:
  copilot:
    skills_dir: ~/.copilot/skills
  claude:
    skills_dir: ~/.claude/commands
  glm:
    skills_dir: ~/.glm/commands
```

- Set `primary` to your main AI agent
- Add/remove agents as needed
- Agents that aren't installed on the machine are automatically skipped

### Claude Plugin Mode

This repo is also a valid **Claude Code Plugin**. Install directly:

```bash
claude plugin add ~/Development/android-review-plugin
```

### Adding New Skills

1. Create `skills/my-skill/SKILL.md` in this repo
2. Run `./install.sh` to sync to all agents

### Transferring to Another Machine

```bash
git clone <this-repo> ~/Development/android-review-plugin
# Edit config.yaml if agent paths differ
./install.sh
```
