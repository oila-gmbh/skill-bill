---
name: bill-new-skill-all-agents
description: Create a new skill and sync it to all detected local AI agents (Claude, Copilot, GLM). Use this when asked to create a new skill for all agents on the user's computer.
---

When asked to create a new skill, follow this workflow:

1. Collect inputs:
   - Skill name
   - One-line description
   - Full skill instructions/body
   - Which agents to install to (comma-separated, primary first)

2. Known agents and their paths:
   - copilot: `$HOME/.copilot/skills`
   - claude: `$HOME/.claude/commands`
   - glm: `$HOME/.glm/commands`

3. Normalize the skill name to a slug:
   - lowercase
   - spaces/underscores -> `-`
   - keep only `a-z`, `0-9`, `-`

4. Create canonical skill file:
   - `$HOME/Development/skill-bill/skills/{slug}/SKILL.md`
   - This is the single source of truth — all agents point here via symlinks

5. Create symlink chain (primary → canonical, secondary agents → primary):
   a. Primary: `ln -s $HOME/Development/skill-bill/skills/{slug} {primary_path}/{slug}`
   b. Each secondary: `ln -s {primary_path}/{slug} {secondary_path}/{slug}`

6. Rules:
   - Never duplicate file content across agents — always use symlinks
   - If a target symlink already exists, remove it first and recreate
   - If an agent root directory does not exist, skip that agent
   - The skill file must be named `SKILL.md` inside a directory named after the slug

7. Return a short summary:
   - skill slug
   - canonical file path
   - primary agent and symlink
   - secondary symlinks created
   - skipped agents
