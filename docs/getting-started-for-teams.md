# Getting Started for Teams

A team adoption guide for Skill Bill. Use [Getting Started](getting-started.md) for install details and full CLI/MCP command coverage.

## Team Adoption Model

Skill Bill gives a team governed agent workflows, not just prompt files. There are two useful adoption modes:

- Adopt the bundled flagship workflow: start with `/bill-feature-task`, then use `/bill-code-review`, `/bill-code-check`, and `/bill-feature-verify` as standalone phase entry points when needed.
- Adopt the governed workflow platform: fork, replace, or delete bundled skills and author team-owned workflows and platform packs on the same contracts.

The useful adoption unit is:

- stable slash commands for review, quality checks, feature work, verification, PR descriptions, and skill authoring
- a packaged Kotlin CLI and MCP runtime
- manifest-driven platform packs
- strict validation around generated wrappers, manifests, and MCP schemas
- model-mediated reasoning for code review, planning, audits, and prose

The current normal runtime is Kotlin-only. Installed `skill-bill` and `skill-bill-mcp` launchers point at packaged Kotlin distribution scripts copied by `./install.sh` into `~/.skill-bill/runtime/`. Normal use does not invoke Gradle and does not fall back to a legacy runtime.

## Before Inviting The Team

One maintainer should do this first:

1. Install from the branch or release the team will use.
2. Run `skill-bill version`, `skill-bill doctor`, and a real `/bill-feature-task` on a small spec.
3. Decide which reference packs matter for the team.
4. Add project guidance in `AGENTS.md` or `.agents/skill-overrides.md`.
5. Run the validation gate before asking others to install.

Validation gate:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

The Gradle command validates the Kotlin runtime for maintainers. It is not how installed users normally launch the CLI or MCP server.

## Commands Most Teams Start With

| Command | Use it when | What to expect |
|---------|-------------|----------------|
| `/bill-feature-spec` | Preparing specs before implementation starts | Shared single-spec/decomposed preparation artifacts for feature and goal workflows |
| `/bill-feature-task` | Building from a design doc | Structured plan, implementation, review, audit, validation, history, and PR handoff |
| `/bill-code-review` | Reviewing staged changes, a PR, or a commit range | Routed review with summary, risk register, action items, and verdict |
| `/bill-code-check` | Running repo checks before a PR | Real tool execution through the routed platform quality-check skill |
| `/bill-feature-verify` | Checking a teammate PR against a spec | Criteria-based verification plus review and validation guidance |

Start with `/bill-feature-task` when introducing Skill Bill to a team, or `/bill-feature-spec` when you want a prep-only session before implementation. The other commands are reusable phases inside that workflow and direct shortcuts when the team only needs one phase. The commands route by dominant stack first, then apply platform-pack behavior and add-ons.

## Runtime Expectations

Team members should not need to know migration history. Current behavior is:

- `skill-bill` is the packaged Kotlin CLI.
- `skill-bill-mcp` is the packaged Kotlin stdio MCP server.
- Missing packaged distributions fail closed with install/build guidance.
- Agent skills are symlinked to rendered staging directories under `~/.skill-bill/installed-skills/`.
- Source skill directories under `skills/` contain only `content.md` plus optional `native-agents/`; generated `SKILL.md` wrappers and support pointer files are install/render output.
- Repo validation and maintainer commands are Kotlin-backed; the legacy maintainer stack is no longer required for current team workflows.
- Runtime rollback means installing a previous release, not toggling a legacy fallback.

On Claude, Codex, OpenCode, and Junie, `bill-kmp-code-review` ships native subagent definitions for its KMP specialists, `bill-kotlin-code-review` ships native subagent definitions for its Kotlin specialists, `bill-php-code-review` ships native subagent definitions for its PHP specialists, and `bill-feature-task` ships native subagent definitions for each of its workflow phases (pre-planning, planning, implementation, implementation-fix, completeness-audit, quality-check, pr-description). Native subagent sources live as provider-neutral `native-agents/agents.yaml` bundles or standalone `native-agents/<name>.md` files. New and rendered neutral sources include `contract_version: "0.1"`; parser tolerance for older unpinned sources is migration support, not a reason to omit the pin in new source. Install renders the same sources into `~/.skill-bill/native-agents/` before linking Claude markdown, Codex TOMLs, OpenCode markdown, or Junie markdown into each runtime's native agents directory. Codex TOMLs normally install to `~/.codex/agents/`; `~/.agents/agents/` is only a Skill Bill compatibility path for homes without `.codex`. Generated provider files are install-cache outputs, not committed source. The orchestrator's spawn prose ("spawn the `bill-kmp-code-review-ui` subagent", "spawn the `bill-kotlin-code-review-architecture` subagent", "spawn the `bill-php-code-review-security` subagent", "spawn the `bill-feature-task-planning` subagent", and so on) is runtime-neutral: Claude and Junie resolve the installed Markdown/YAML custom subagents, Codex resolves each TOML by `name`, and OpenCode resolves each markdown agent by filename-derived name and supports manual `@<name>` invocation. `bill-feature-verify` has no verify-specific native subagents; it delegates review through `bill-code-review` and keeps feature-flag, completeness, and verdict audits inline. Workflow-state resume is supported intra-runtime via the skill-bill MCP server (any runtime that registered the MCP server can call `feature_implement_workflow_continue`); cross-runtime resume of a paused workflow is best-effort and not part of the support contract. Parsing tolerance for `RESULT:` blocks across runtimes is documented inline in `skills/bill-feature-task/content.md`.

For decomposed feature goals, teach operators to trust the runtime-owned flat
worker model. `skill-bill goal <issue-key>` selects one runnable subtask, opens
or resumes that child workflow, starts one fresh child process, and advances
from durable workflow state. Nested/native subagents inside that child are an
optional debugging and context-management convenience, not the reliability
contract. Use `skill-bill goal status <issue-key>` and
`skill-bill goal watch <issue-key> --interval-seconds 5 --max-refreshes 3` for
read-only progress; add `--diff-stat` for a bounded worktree summary or
`--diff-hunk <path> --diff-hunk-max-hunks 2 --diff-hunk-max-lines 20 --diff-hunk-max-bytes 4000`
for path-scoped hunk output. Expected lines include `goal_observability:`,
`latest_observability:`, `diff_stat:`, `watch_diff_stat:`, and
`selected_diff_line:`. This preserves the SKILL-56/SKILL-58 goal-runner
contracts: status/watch stay read-only, raw child streams remain hidden by
default, stale running children are closed when a terminal outcome is
authoritative, and durable child outcomes drive goal advancement.

## Fallback And Failure Boundaries

Explain this boundary during rollout so engineers know what failed and what merely degraded.

Fail closed:

- invalid MCP arguments for strict tools
- missing packaged Kotlin CLI/MCP distributions
- missing platform manifests or declared files
- wrong shell contract version
- missing sibling `content.md`, invalid generated wrapper sections, or missing generated support pointers
- extra files in `skills/` source directories beyond `content.md` and optional `native-agents/`
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
- Contract fixtures enforce manifest shape, contract version, declared files, generated wrapper sections, and support-pointer renderability.
- Scaffold and validation commands use structured payloads and loud-fail on invalid state.
- Install primitives create real symlinks to rendered staging directories and can be tested against temporary agent paths.

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

The shipped skills and platform packs are reference assets. They are production-usable examples, not a hardcoded product limit. A team can fork existing skills, delete bundled skills, add new workflows, fork an existing pack, or add a new conforming pack when behavior materially differs. The governance system should keep discovering packs from manifests instead of relying on a fixed platform list.

Start from reference packs when they fit:

- `kotlin`: Kotlin baseline review and quality-check behavior
- `kmp`: Kotlin plus Android/KMP depth and governed add-ons
- `php`: PHP backend, service, persistence, API, security, testing, UI, UX/accessibility, and quality-check behavior

Create or fork a pack when team-specific architecture, framework, API, persistence, reliability, UI, or accessibility expectations need their own maintained behavior.

## External Authoring Signals

Treat platform-pack authoring as a product test, not only a customization task. After someone outside the maintainer loop creates or extends a pack, capture:

- How long did it take to scaffold or create the pack?
- Which docs or CLI messages were confusing?
- Did validation catch a real mistake?
- Which `/bill-*` commands became habitual afterward?
- Could the author proceed without maintainer context?
- Did the pack stay cleanly manifest-driven, or did it require local hacks?

Externally authored PHP and Golang packs are the kind of evidence this framework is designed to support. A pack does not need to start as a bundled reference pack for the authoring path to be successful.

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

1. One maintainer calibrates on a small real feature spec with `/bill-feature-task`.
2. Two or three engineers install from the same branch or tag and run feature implementation plus standalone review on live work.
3. The team triages false positives and adds learnings or overrides.
4. Platform owners fork, delete, or create skills and packs only when bundled defaults no longer fit.
5. The team makes the validation gate part of release or pack-maintenance work.

## Operating Rules

- Keep platform discovery manifest-driven.
- Keep `contract_version: "1.1"` in lockstep with the shell.
- Add platform behavior as manifest-declared overrides, approved code-review areas, or pack-owned add-ons.
- Do not commit generated `SKILL.md` wrappers or support pointer files under `skills/`; edit `content.md` through `skill-bill fill` or the documented scaffolder path.
- Keep source skill directories limited to `content.md` and optional `native-agents/`.
- Include the pinned native-agent `contract_version` in newly authored neutral native-agent sources.
- Treat missing manifests, missing declared files, and wrong contract versions as product bugs, not soft warnings.

## Getting Unstuck

- Install health: `skill-bill doctor`
- Runtime version: `skill-bill version`
- Agent paths: `skill-bill install detect-agents`
- Skill state: `skill-bill show <skill> --content preview`
- Repo validation: `skill-bill validate`
- Full maintainer gate: run the four validation commands above
- Source and generation model: [Skill Source And Generation Model](skill-source-generation.md)
- Telemetry format: [Review Telemetry](review-telemetry.md)
