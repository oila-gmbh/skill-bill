# Getting Started for Teams

A team adoption guide for Skill Bill. Use [Getting Started](getting-started.md) for install details and full CLI/MCP command coverage.

## Team Adoption Model

Skill Bill gives a team governed agent workflows, not just prompt files. The useful adoption unit is:

- stable slash commands for review, quality checks, feature work, verification, PR descriptions, and skill authoring
- a packaged Kotlin CLI and MCP runtime
- manifest-driven platform packs
- strict validation around generated wrappers, manifests, and MCP schemas
- model-mediated reasoning for code review, planning, audits, and prose

The current normal runtime is Kotlin-only. Installed `skill-bill` and `skill-bill-mcp` enter packaged Kotlin distribution scripts built by `./install.sh`. Normal use does not invoke Gradle and does not fall back to Python.

## Before Inviting The Team

One maintainer should do this first:

1. Install from the branch or release the team will use.
2. Run `skill-bill version`, `skill-bill doctor`, and a real `/bill-code-review`.
3. Decide which reference packs matter for the team.
4. Add project guidance in `AGENTS.md` or `.agents/skill-overrides.md`.
5. Run the validation gate before asking others to install.

Validation gate:

```bash
.venv/bin/python3 -m unittest discover -s tests
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

The Gradle command validates the Kotlin runtime for maintainers. It is not how installed users normally launch the CLI or MCP server.

## Commands Most Teams Start With

| Command | Use it when | What to expect |
|---------|-------------|----------------|
| `/bill-code-review` | Reviewing staged changes, a PR, or a commit range | Routed review with summary, risk register, action items, and verdict |
| `/bill-quality-check` | Running repo checks before a PR | Real tool execution through the routed platform quality-check skill |
| `/bill-feature-implement` | Building from a design doc | Structured plan, implementation, review, audit, validation, history, and PR handoff |
| `/bill-feature-verify` | Checking a teammate PR against a spec | Criteria-based verification plus review and validation guidance |

The commands route by dominant stack first, then apply platform-pack behavior and add-ons.

## Runtime Expectations

Team members should not need to know migration history. Current behavior is:

- `skill-bill` is the packaged Kotlin CLI.
- `skill-bill-mcp` is the packaged Kotlin stdio MCP server.
- Missing packaged distributions fail closed with install/build guidance.
- Agent skills are symlinked back to the repo checkout.
- Python scripts still exist for repo validation and maintainer tooling, but Python is not the active CLI/MCP runtime.
- Runtime rollback means installing a previous release, not toggling a Python fallback.

On Codex and OpenCode, `bill-kmp-code-review` ships native subagent definitions for its KMP specialists, `bill-kotlin-code-review` ships native subagent definitions for its Kotlin specialists, and `bill-feature-implement` ships native subagent definitions for each of its workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). Codex installs TOMLs under `~/.codex/agents/` (with `~/.agents/agents/` fallback) from `platform-packs/<slug>/**/codex-agents/*.toml` and `skills/<slug>/**/codex-agents/*.toml`; OpenCode installs markdown agents under `~/.config/opencode/agents/` from the matching `opencode-agents/*.md` walks. The orchestrator's spawn prose ("spawn the `bill-kmp-code-review-ui` subagent", "spawn the `bill-feature-implement-planning` subagent", and so on) is runtime-neutral: Codex resolves each TOML by `name`, OpenCode resolves each markdown agent by filename-derived name and supports manual `@<name>` invocation, while Claude maps the same prose to its native subagent tool. `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps feature-flag, completeness, and verdict audits inline. Workflow-state resume is supported intra-runtime via the skill-bill MCP server (any runtime that registered the MCP server can call `feature_implement_workflow_continue`); cross-runtime resume of a paused workflow is best-effort and not part of the support contract. Parsing tolerance for `RESULT:` blocks across runtimes is documented at [`skills/bill-feature-implement/parsing_tolerance.md`](../skills/bill-feature-implement/parsing_tolerance.md).

## Fallback And Failure Boundaries

Explain this boundary during rollout so engineers know what failed and what merely degraded.

Fail closed:

- invalid MCP arguments for strict tools
- missing packaged Kotlin CLI/MCP distributions
- missing platform manifests or declared files
- wrong shell contract version
- missing required `SKILL.md` sections or sibling `content.md`/`shell-ceremony.md`
- invalid scaffold payloads
- generated wrapper or agent-config drift

Degrade or report explicitly:

- telemetry can stay off, queue locally, or fail remote sync without blocking local work
- remote metrics can report capability/network limits
- no detected agent directories means install needs an explicit target path
- routed review can return lower confidence where code context is thin
- learnings may resolve to `none` until the team has enough triage history

## What To Trust

Strict guarantees are runtime and contract boundaries:

- MCP schemas are strict where declared and reject unknown top-level arguments.
- Contract fixtures enforce manifest shape, contract version, declared files, required wrapper sections, and sidecars.
- Scaffold and validation commands use structured payloads and loud-fail on invalid state.
- Install primitives create real symlinks and can be tested against temporary agent paths.

Model-mediated output is still judgement:

- review findings
- severity and confidence labels
- implementation plans
- completeness audits
- PR description prose
- explanations of unfamiliar library behavior

Treat model-mediated output as a second opinion. Act quickly on findings with clear file:line evidence; verify high-risk security, performance, data-loss, or migration claims against the code and real tool output.

## Governance System vs Reference Packs

This distinction matters for team ownership.

Skill Bill is the governance system: routing, shell contracts, scaffold rules, validation, workflow state, telemetry, installer behavior, and MCP/CLI runtime boundaries.

The shipped `kotlin` and `kmp` packs are reference packs. They are production-usable examples, not a hardcoded platform limit. A team can fork an existing pack or add a new conforming pack when behavior materially differs. The governance system should keep discovering packs from manifests instead of relying on a fixed platform list.

Start from reference packs when they fit:

- `kotlin`: Kotlin baseline review and quality-check behavior
- `kmp`: Kotlin plus Android/KMP depth and governed add-ons

Create or fork a pack when team-specific architecture, framework, API, persistence, reliability, UI, or accessibility expectations need their own maintained behavior.

## Customization Layers

Use the lightest customization that solves the problem:

| Layer | Best for | Owner |
|-------|----------|-------|
| `AGENTS.md` | Repo-wide facts every skill should know | Project team |
| `.agents/skill-overrides.md` | Skill-specific preferences and local policy | Project team |
| Learnings | Repeated false positives or accepted review patterns | Team using review telemetry |
| Forked platform pack | Durable platform behavior that differs from the reference pack | Platform owner |
| New platform pack | A materially different stack or review/quality model | Platform owner |

`.agents/skill-overrides.md` is validator-enforced:

- first line must be `# Skill Overrides`
- each section must be `## <existing-skill-name>`
- section bodies must be bullet lists
- freeform text outside sections is invalid

Example:

```md
# Skill Overrides

## bill-kotlin-code-review
- Prioritize platform-correctness and testing over performance for this service.
- Flag new dependencies as at least Minor severity.

## bill-pr-description
- Include the Jira ticket when the branch name contains `SKILL-\d+`.
- Keep QA steps concise unless the user asks for the full matrix.
```

## External Author Dry Run

Before a team maintains its own pack, rehearse the external-author flow in a temporary checkout or disposable branch:

1. Scaffold a temporary platform pack.
2. Validate the generated state.
3. Link a generated skill to a temporary agent path.
4. Remove the generated link and temporary pack.

Command shape:

```bash
skill-bill new --payload /tmp/skill-bill-pack.json
skill-bill validate
skill-bill install link-skill \
  --source platform-packs/java/code-review/bill-java-code-review \
  --target-dir /tmp/skill-bill-agent/skills \
  --agent codex
rm /tmp/skill-bill-agent/skills/bill-java-code-review
rm -rf platform-packs/java
```

The Kotlin CLI has an integration test for this flow using temporary directories and an isolated `user.home`, so the dry run cannot touch real agent directories.

## Rolling Out

Suggested rollout:

1. One maintainer calibrates on recent real PRs and records obvious overrides.
2. Two or three engineers install from the same branch or tag and run review plus quality check on live work.
3. The team triages false positives and adds learnings or overrides.
4. Platform owners fork or create packs only when overrides are no longer enough.
5. The team makes the validation gate part of release or pack-maintenance work.

## Operating Rules

- Keep platform discovery manifest-driven.
- Keep `contract_version: "1.1"` in lockstep with the shell.
- Add platform behavior as manifest-declared overrides, approved code-review areas, or pack-owned add-ons.
- Do not edit generated `SKILL.md` wrappers during normal authoring; edit `content.md` through `skill-bill fill` or the documented scaffolder path.
- Treat missing manifests, missing declared files, and wrong contract versions as product bugs, not soft warnings.

## Getting Unstuck

- Install health: `skill-bill doctor`
- Runtime version: `skill-bill version`
- Agent paths: `skill-bill install detect-agents`
- Skill state: `skill-bill show <skill> --content preview`
- Repo validation: `skill-bill validate`
- Full maintainer gate: run the four validation commands above
- Telemetry format: [Review Telemetry](review-telemetry.md)
