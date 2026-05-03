# Skill Repo Contracts

The former Python helper module `scripts/skill_repo_contracts.py` has been
retired. Repo contract ownership now lives in the Kotlin runtime:

- `skillbill.scaffold.ScaffoldSupport`
- `skillbill.scaffold.ShellContentLoader`
- `skillbill.scaffold.SkillMdShapeValidator`
- `skillbill.scaffold.RepoValidationRuntime`

Use `scripts/validate_agent_configs` or `skill-bill validate-agent-configs`
for current validation.

