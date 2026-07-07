# Capability Deep-dive

Under one `curl` command is a full system. Each capability below is doing real work behind the slash commands.

<details>
<summary><b>1. One-shot multi-agent install via symlinks</b></summary>

`install.sh` symlinks every skill into each detected agent's directory (Claude Code, Copilot, Codex, OpenCode, Junie). A single source-of-truth `skills/` tree powers all of them, so an edit in one place reaches every agent immediately. The same mechanism handles uninstall and the runtime launcher binaries.

</details>

<details>
<summary><b>2. <code>bill-feature-task</code> — the end-to-end feature factory</b></summary>

One slash command that takes a spec or design doc and walks it all the way to a merged-ready PR, scaling ceremony to the size of the work. The pipeline: assessment → branch → pre-planning digest → planning (or decomposition) → implementation → code review → completeness audit → quality check → history/decisions → commit/push → PR description.

Cross-cutting properties:

- Every heavy phase runs in its own subagent with a self-contained briefing — orchestrator stays small, specialists go deep.
- Durable workflow state at every phase boundary — crash anywhere and resume cleanly, even from a different agent: a runtime-mode run paused under Claude Code continues under Codex with the same `bill-feature <KEY>`.
- Phase-to-artifact mapping is explicit (`assessment`, `preplan_digest`, `plan`, `implementation_summary`, `review_result`, `audit_report`, `validation_result`, `history_result`, `commit_push_result`, `pr_result`) — every step produces a named, persistable output.
- Telemetry is mandatory and transport-resilient.
- Stack-aware via platform packs.
- Project-tunable via `.agents/skill-overrides.md`.
- Decomposes itself when too big.

It is a tiny CI/CD for the feature itself, not just the code.

</details>

<details>
<summary><b>3. Native platform overrides via platform packs</b></summary>

Generic skills like `/bill-code-review` and `/bill-code-check` are routing shells. The real work lives in `platform-packs/<lang>/` (today: `go`, `ios`, `kotlin`, `kmp`, `php`, `python`), with native versions such as `bill-go-code-review`, `bill-ios-code-review`, `bill-kotlin-code-review`, `bill-php-code-review`, and `bill-python-code-review` plus area specialists (`-architecture`, `-security`, `-performance`, `-persistence`, `-api-contracts`, `-reliability`, `-platform-correctness`, `-testing`, and UI/UX-accessibility where the pack declares them). KMP layers further on top of Kotlin with Android/KMP depth and add-ons; iOS adds `bill-ios-code-check`, `.xcodeproj`/`.xcworkspace`, SwiftUI/UIKit, lifecycle, concurrency, UI, and accessibility signals; Go, PHP, and Python include backend/service review, persistence/API/security/testing lanes, and UI/UX lanes. At runtime the generic entry point reads `routing_signals` from each pack's `platform.yaml` (e.g. `go.mod`, `.go`, `.xcodeproj`, `.xcworkspace`, `import SwiftUI`, `import UIKit`, `.kt`, `build.gradle.kts`, `composer.json`, `.php`, `pyproject.toml`, `.py`, plus KMP tie-breakers like `androidMain`/`expect/actual`) and hands off to the matching native skill. Adding a new language is purely additive — drop in `platform-packs/<lang>/` and `/bill-code-review` starts routing to it. No edits to the generic skill, no fork.

</details>

<details>
<summary><b>4. Manifest-driven task decomposition, auto-resume by issue key</b></summary>

When planning detects work is too big (rules of thumb: more than 15 atomic tasks, more than 6 boundaries, multiple independently resumable milestones, or sequencing with verify-able foundations), `bill-feature-task` switches into `mode: "decompose"` instead of implementing.

- **Subtask specs are real artifacts**: planning writes `.feature-specs/{ISSUE_KEY}-{feature-name}/spec_subtask_1_foundation.md`, `_2_runtime-wiring.md`, etc. — each with its own acceptance criteria, non-goals, dependency notes, validation strategy, and the exact `bill-feature-task` prompt to run for it later.
- **Schema-validated manifest**: a `decomposition-manifest.yaml` is generated and validated against `orchestration/contracts/decomposition-manifest-schema.yaml`, so the plan itself is a contract, not loose prose.
- **You only need the issue key**: when you come back and say "continue SKILL-51", the runtime resolves the parent manifest, finds the in-progress subtask at its last durable workflow step, and picks up there. If none is in-progress, it starts the first pending subtask whose dependencies are complete. You never have to remember "was I on subtask 2 step 4 or subtask 3 step 1."
- **Fresh context per subtask, no context rot**: every subtask starts in a fresh session briefed from curated durable artifacts (the subtask spec, boundary `history.md`, recorded decisions) instead of inheriting a long-lived transcript. Long goals do not degrade as hours accumulate, because no context lives long enough to rot — continuity travels through durable state, not through an ever-growing conversation.
- **Blocked-aware**: if the current path is blocked it stops and tells you why, instead of silently skipping to a later dependent subtask.
- **Branch strategy is declared, not improvised**: defaults to `same_branch_commit_per_subtask` (one commit per subtask on the parent feature branch); `stacked_branches` is an explicit opt-in where the runtime refuses to advance if the current branch/base does not match the manifest.
- **Decomposition is a successful outcome, not a failure**: the workflow closes as `abandoned_at_planning` with `plan_deviation_notes: decomposed into N subtasks` — logged as scope governance, not as a crash.

</details>

<details>
<summary><b>5. Full Compose Desktop UI hiding all of the above</b></summary>

`runtime-desktop` is a real native app on top of all of this, not just a CLI. Modular Compose Multiplatform build with kotlin-inject DI, KSP-generated components, and platform packaging targets.

What the UI gives the user without exposing the machinery:

- Tree-based skill/artifact browser — authored skills, generated artifacts, platform packs, all navigable in one view.
- First-run setup dialog and repo directory chooser.
- Scaffold wizard — deterministic `skill-bill new` prompts backed by the same payload contract used by automation; the tree selection jumps to the newly authored file on success.
- Validate-agent-configs runner — the manifest/drift validator wired as a button with a result panel.
- Confirm-deletion dialog — for deterministic `skill-bill remove`, with safety prompts built in.
- Command palette — keyboard-driven action surface for all operations.
- Keyboard accelerators and accessibility-friendly navigation.
- Repo file-change observer — external edits (e.g. from a coding agent) reflect in the tree without manual refresh.
- Packaging configuration that produces real installable artifacts.

All of the symlink installs, manifest validation, platform-pack routing, native-agent generation, telemetry, and workflow state happens underneath without ever leaking into the user's view.

</details>

<details>
<summary><b>6. Stateful, resumable workflows with native subagents</b></summary>

`bill-feature-task` is not a monolithic prompt; it is an orchestrator over durable state and a fleet of purpose-built subagents.

- **Durable state**: `feature_task_prose_workflow_open` mints a `workflow_id`; every phase boundary writes via `feature_task_prose_workflow_update` and gets back a compact acknowledgement, not a full snapshot. If a session dies mid-run, `feature_task_prose_workflow_continue` is the mutating activation path: it re-opens the exact phase and returns a compact continuation payload (resume step, required/available artifact keys, compact current-step artifacts) as the continuation contract — full durable state is fetched only on demand through the read-only `workflow show`. The run survives crashes, compaction, even a host reboot. Same shape exists for `bill-feature-verify` (`feature_verify_workflow_*`).
- **Native subagents per phase**: pre-planning, planning, implementation, completeness-audit, quality-check, PR-description, and an implementation-fix loop each ship as their own installed agent.
- **Native subagents per review specialist**: every shipped review specialist with native-agent source is its own subagent too.
- **Why this matters for tokens**: each subagent gets a self-contained briefing scoped to its phase/area instead of inheriting the full orchestrator transcript. The orchestrator stays small; specialists go deep on their narrow slice. Better focus and lower cost — the opposite of the usual "more steps = more context bloat" trap.
- **Transport-resilient telemetry**: a packaged Kotlin `runtime-mcp` stdio fallback ensures a dropped MCP transport does not leave a workflow stuck in `running`.

For decomposed goals, the foreground `skill-bill goal` runtime owns a flat worker model: it selects one runnable subtask, opens or resumes that child workflow, launches one fresh child process, and advances only from durable workflow state. Nested/native subagents inside the child session are useful for focus and debugging, but the reliability contract is the runtime-owned workflow row plus the decomposition projection. Because continuity lives in runtime-owned state rather than in any agent's context, goal execution and resume are agent-independent — the agent that continues a goal does not have to be the agent that started it.

</details>

<details>
<summary><b>7. <code>content.md</code> is the default authored surface; runtime files are generated</b></summary>

A skill author usually touches exactly one file. Free-form markdown, frontmatter on top, prose body underneath, write it however you want. Documented governed sidecar contracts are the narrow exception. No JSON, no schema, no boilerplate.

Generated from it (and you never hand-edit):

- Per-agent skill files in each agent's native format (Claude, Copilot, Codex, OpenCode, Junie), installed as symlinks back to the one source `content.md` so any edit lands everywhere instantly.
- Native subagent files in each agent's required format, registered by name and briefed by the orchestrator at runtime.
- Pointer files inside platform packs — single-line markdown files regenerated from `platform.yaml` by the renderer (you are literally not supposed to commit them).
- Slash-command registration in each agent.
- Skill discovery descriptions derived from the frontmatter `description`.
- MCP tool exposure for workflow and telemetry, without the author wiring anything.

The author contract is: write the body, declare the description, use documented governed sidecars only where the contract allows them, and leave the rest to the renderer. The validator keeps the generated artifacts from drifting from the manifest. Soft inside, hard shell.

</details>

<details>
<summary><b>8. Per-project skill fine-tuning via <code>.agents/skill-overrides.md</code></b></summary>

Every skill reads the project's override file as part of its shared ceremony, so you can change skill behavior for a specific repo without forking or editing the skill source. The file lives in the repo, is versioned with the code, and applies to whichever agent is running.

- **Orchestrator-owned read**: the override file is read by the orchestrator, not delegated to a subagent. (When delegated, action mandates used to get paraphrased into free-form notes and silently dropped — see `agent/decisions.md` for the incident that hardened this.)
- **Action mandates at named lifecycle positions**: overrides can declare mandates that fire at specific orchestrator lifecycle points (e.g. before applying the skill body, at end-of-run for state writes). Skills cannot quietly skip them.
- **Composable with `AGENTS.md`**: the shared ceremony loads both general project conventions and per-skill targeted tweaks.

Net effect: you fine-tune `bill-code-review` with an extra checklist item, or force `bill-feature-task` to call a project-specific telemetry tool, by editing one markdown file in the repo. No skill fork, no agent reinstall.

</details>

<details>
<summary><b>9. Per-module memory</b></summary>

Every module/package has its own `agent/decisions.md` and `agent/history.md`. The `/bill-boundary-decisions` and `/bill-boundary-history` skills know how to write high-signal entries with hygiene rules that keep history from rotting. Result: cross-session institutional knowledge attached to the code itself, not to your head or a wiki. You can see it in this very repo — `agent/decisions.md` records the exact incident that hardened the override read in #8. That is how the system stays self-aware across sessions and contributors.

</details>

<details>
<summary><b>10. First-class, transport-resilient structured telemetry</b></summary>

Every skill that matters emits typed telemetry, not just log lines.

- **Per-skill start/finish pairs** with stable session ids: `feature_task_prose_started/_finished`, `feature_verify_started/_finished`, `quality_check_started/_finished`, `review_stats`, `pr_description_generated`, `import_review`, `triage_findings`, `resolve_learnings`, plus aggregate views (`feature_task_prose_stats`, `feature_verify_stats`, `telemetry_remote_stats`, `telemetry_proxy_capabilities`).
- **Orchestrator/child relationship is modeled**: orchestrated subagents call their own `*_finished` with `orchestrated=true` and return a `telemetry_payload`; the orchestrator assembles a parent/child tree. You can see the whole run as a tree, not a flat stream.
- **Separated from workflow state, on purpose**: workflow state persists even when telemetry returns `status: skipped`. Telemetry is observability; workflow state is correctness. They never get confused.
- **Health-checked and transport-resilient**: before terminal writes the orchestrator pings the MCP transport; if closed, it switches to the packaged `runtime-mcp` stdio fallback for the remaining telemetry and workflow calls. Runs do not get left in a half-reported state because a transport died.
- **Aggregate stats tools** give you queryable rollups, so you can actually see how your feature-task pipeline is behaving instead of grepping logs.
- **Pluggable proxy target**: events flow through a telemetry proxy, with a hosted relay as the default. Point it at your own service by setting `proxy_url` in the telemetry config (or `TELEMETRY_PROXY_URL` in the environment) and `skill-bill telemetry sync` / `capabilities` / `stats` will operate against it. Self-host, anonymize, or fork the proxy itself — Skill Bill's telemetry pipeline doesn't lock you to anyone's backend.

</details>

<details>
<summary><b>11. Strict, declarative skill-set contract with drift protection</b></summary>

Every platform pack is anchored by a `platform.yaml` that declares: contract version, routing signals, the full set of declared code-review areas and their content-file paths, the declared quality-check file, and pointer files (auto-generated so no one hand-edits them). Backed by `scripts/validate_agent_configs`, which fails the build if the on-disk layout does not match the manifest (missing files, stray skills, broken pointers, agent-install inconsistencies). You cannot accidentally rename a skill, half-delete an area, or let one agent's copy diverge from another. Render/install regenerates pointers from the manifest, and validation refuses to let drift land.

This is the governance layer that keeps the other ten features from rotting — once you have seen what they enable, you also see why this one exists.

</details>
