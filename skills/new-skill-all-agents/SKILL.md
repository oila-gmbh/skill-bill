---
name: new-skill-all-agents
description: Create a new skill and sync it to all detected local AI agents (Claude, Copilot, GLM). Use this when asked to create a new skill for all agents on the user's computer.
---

When asked to create a new skill, follow this workflow:

1. Collect inputs:
   - Skill name
   - One-line description
   - Full skill instructions/body

2. Normalize the skill name to a slug:
   - lowercase
   - spaces/underscores -> `-`
   - keep only `a-z`, `0-9`, `-`

3. Create canonical skill file:
   - `.ai/skills/{slug}.md`

4. Detect installed agent roots (with env overrides):
   - Claude: `${CLAUDE_ROOT:-$HOME/.claude}`
   - Copilot: `${COPILOT_ROOT:-$HOME/.copilot}` or `${COPILOT_CONFIG_ROOT:-$HOME/.config/copilot}`
   - GLM: `${GLM_ROOT:-$HOME/.glm}` or `${GLM_CONFIG_ROOT:-$HOME/.config/glm}`

5. Install per detected root only:
   - Claude: `{root}/commands/{slug}.md` with Claude frontmatter
   - Copilot: `{root}/skills/{slug}.md`
   - GLM: `{root}/skills/{slug}.md`

6. Rules:
   - If root exists, create target subdirectory if needed and write file.
   - If root does not exist, skip that agent.
   - Keep instruction content consistent across all generated files.

7. Return a short summary:
   - skill slug
   - canonical file path
   - created targets
   - skipped agents
