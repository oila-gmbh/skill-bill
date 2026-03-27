---
name: bill-new-skill-all-agents
description: Use when creating a new skill and syncing it to all detected local AI agents (Claude, Copilot, GLM, Codex).
---

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-new-skill-all-agents` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

When asked to create a new skill, follow this workflow:

1. Collect inputs:
   - Skill name
   - One-line description
   - Full skill instructions/body
   - Which agents to install to (comma-separated, primary first)
   - Whether the skill is shared across stacks or specific to a stack/framework

1a. Validate the proposed name against the repo naming strategy:
   - Keep `bill` as the namespace prefix
   - Shared skills should use `bill-<capability>`
   - Stack-specific skills should use `bill-<stack>-<capability>`
   - Add a deeper qualifier only when needed, such as `bill-<stack>-<area>-<capability>`
   - Do not rename an existing shared skill just to add taxonomy consistency; create a new stack-specific variant only when behavior actually differs
   - If renaming an existing stack-bound skill, update `install.sh` legacy-name migration rules and README references in the same change

1b. Derive the source package from the validated name:
   - `shared` for neutral skills like `bill-pr-description` or `bill-feature-guard`
   - `kotlin` for Kotlin-prefixed skills
   - `kmp` for KMP-prefixed skills
   - `php` for PHP-prefixed skills
   - If the package is unclear, ask once before creating files

2. Known agents and their paths:
   - copilot: `$HOME/.copilot/skills`
   - claude: `$HOME/.claude/commands`
   - glm: `$HOME/.glm/commands`
   - codex: prefer `$HOME/.codex/skills`, fallback to `$HOME/.agents/skills`

3. Normalize the skill name to a slug:
   - lowercase
   - spaces/underscores -> `-`
   - keep only `a-z`, `0-9`, `-`
   - preserve the validated `bill-...` prefix structure

4. Create canonical skill file:
   - `$HOME/Development/skill-bill/skills/{package}/{slug}/SKILL.md`
   - This is the single source of truth — all agents point here via symlinks

5. Create symlink chain (primary → canonical, secondary agents → primary):
   a. Primary: `ln -s $HOME/Development/skill-bill/skills/{package}/{slug} {primary_path}/{slug}`
   b. Each secondary: `ln -s {primary_path}/{slug} {secondary_path}/{slug}`

6. Rules:
    - Never duplicate file content across agents — always use symlinks
    - If a target symlink already exists, remove it first and recreate
    - If an agent root directory does not exist, skip that agent
    - The skill file must be named `SKILL.md` inside a directory named after the slug
    - New skill files must include a `## Project Overrides` section near the top that tells the skill to read `.agents/skill-overrides.md` for a matching `## {slug}` section, with precedence `skill override > AGENTS.md > built-in defaults`

7. Return a short summary:
   - skill slug
   - canonical file path
   - primary agent and symlink
   - secondary symlinks created
   - skipped agents
