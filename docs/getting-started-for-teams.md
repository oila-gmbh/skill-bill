# Getting Started for Teams

A practical guide for teams evaluating or rolling out Skill Bill. For install mechanics and the full skill catalog, see the [README](../README.md).

## Who this is for

Engineers and tech leads deciding whether to adopt Skill Bill on a team. It focuses on **what to expect**, **how to customize**, and **when to trust the output** — the parts that matter once install is done.

## Install in one minute

Follow the [Installation section of README.md](../README.md#installation). The short version:

1. Clone the repo somewhere stable (e.g. `~/Development/skill-bill`).
2. Run `./install.sh`.
3. Select your agents (Claude Code / Copilot / Codex / OpenCode / GLM).
4. Select your platform packages — check the [platform coverage tiers](../README.md#platform-coverage-tiers) first so you know what each stack actually gets.

Installed skills are symlinks back to the repo, so `git pull` updates everything without re-running install.

## The three commands that matter

99% of daily use comes down to three base commands. They auto-detect your stack and route to the specialist skills.

| Command | Use it when | Expected output |
|---------|-------------|-----------------|
| `/bill-code-review` | Reviewing staged changes, a PR, or a commit range | Structured report: Summary, Risk Register, Action Items, Verdict |
| `/bill-feature-implement` | Building a feature end-to-end from a design doc | Branch + atomic commits + review + quality-check + PR description |
| `/bill-quality-check` | Verifying lint/tests/format before opening a PR | Pass/fail report per check, with fixes applied where possible |

Everything else (`bill-feature-verify`, `bill-pr-description`, `bill-grill-plan`, etc.) is useful but sits on top of these three.

## Customizing for your team

Two customization files, applied in order of precedence:

1. **`.agents/skill-overrides.md`** — per-skill rule overrides (project root)
2. **`AGENTS.md`** — repo-wide guidance for all skills (project root)
3. **built-in skill defaults** (lowest priority)

### `.agents/skill-overrides.md` format

Strict structure, enforced by the validator:

- First line must be `# Skill Overrides`
- Each section must be `## <existing-skill-name>`
- Each section body must be a bullet list
- Freeform text outside sections is invalid

### Three realistic examples

**Example 1 — Treat Kotlin warnings as blocking in your monorepo**

```md
# Skill Overrides

## bill-kotlin-quality-check
- Treat warnings as blocking work.
- Skip formatting-only rewrites unless the user explicitly asks for them.
```

**Example 2 — Align PR descriptions with your team's template**

```md
# Skill Overrides

## bill-pr-description
- Always include the Jira ticket link when the branch name matches `SKILL-\d+`.
- Include a "Rollout notes" section for changes behind feature flags.
- Keep QA steps concise unless the user asks for a full matrix.
```

**Example 3 — Bias reviews toward your team's priorities**

```md
# Skill Overrides

## bill-backend-kotlin-code-review
- Prioritize persistence and reliability specialists over performance for this service.
- Flag any new dependency additions as at minimum Minor severity.

## bill-backend-kotlin-code-review-persistence
- Require explicit migration rollback steps for any schema change.
```

`AGENTS.md` at the repo root applies to all skills. Use it for cross-cutting context like "this service writes to both Postgres and DynamoDB" — context every review skill benefits from knowing.

## What to expect from review output

A typical `/bill-code-review` run produces four sections:

```text
Routed to: bill-backend-kotlin-code-review
Review session ID: rvs-...
Review run ID: rvw-...
Detected stack: backend-kotlin
Execution mode: inline | delegated
Applied learnings: none | L-001, L-002

### 1. Summary
<prose overview of the change>

### 2. Risk Register
- [F-001] Blocker | High | path/to/file.kt:42 | <description>
- [F-002] Major | Medium | path/to/file.kt:88 | <description>

### 3. Action Items
<prioritized, max 10>

### 4. Verdict
Pass | Fail — <reason>
```

Severity is one of `Blocker | Major | Minor`. Confidence is `High | Medium | Low`. Every finding has a file:line reference.

## When to trust it vs verify

Treat every review as a **second opinion**, not a gate. It's calibrated for signal-to-noise, not for replacing human judgment.

### Trust more

- **Correctness findings with file:line references that you can open and verify in seconds.** The locations are accurate; the reasoning is usually worth reading.
- **Specialist depth** on Deep-tier platforms (Kotlin family). Multi-layer routing means multiple specialist passes reinforce each other.
- **Quality-check results.** These run real tools (Gradle, composer, go test) and report actual exit codes.
- **Structural findings** — dependency direction, boundary violations, missing error paths. The model is good at spotting these.

### Verify before acting

- **Performance claims without benchmarks.** Review output will sometimes label a change "performance risk" based on pattern recognition alone. Confirm with a profile or benchmark before rewriting.
- **Security findings on unfamiliar code paths.** High severity + confidence is a signal to look, not a signal to accept. Check the actual threat model.
- **Findings on Solid-tier platforms (Go)** that reference framework-specific behavior. Without governed add-ons, framework-specific reasoning can be less reliable.
- **Findings about library behavior.** The model may confidently describe an API that has changed between versions. Check your actual dependency version.
- **Anything labeled "Confidence: Low."** This is the model flagging its own uncertainty — treat as a prompt to investigate, not a conclusion.

### Expect these false positives

- Wording nits on user-facing strings that your product team has already decided on.
- Style suggestions that your linter/formatter already handles.
- "Missing error handling" in paths where the error genuinely cannot occur.

Use the triage workflow (`/bill-code-review` → respond to findings) or create a learning via `skill-bill learnings add` to stop flagging recurring false positives. Learnings apply automatically on future reviews in the same scope.

## Rolling out to a team

A suggested sequence:

1. **Week 1 — one person.** Install on your own machine. Run `/bill-code-review` on 5-10 recent PRs and see what it catches and misses. Calibrate.
2. **Week 2 — commit `.agents/skill-overrides.md`.** Encode the team's non-obvious preferences so new installs get the same behavior.
3. **Week 3 — invite 2-3 engineers.** Have them install from the committed branch. Collect feedback on false positives; add learnings.
4. **Week 4+ — open it up.** Once the override file is stable, the team can install independently.

## Getting unstuck

- The skill catalog drifted from README? Run `.venv/bin/python3 scripts/validate_agent_configs.py` — it fails loudly on catalog drift.
- Review output won't parse into telemetry? See the exact required format in [review-telemetry.md](review-telemetry.md).
- A skill routed to the wrong stack? Open an issue with the detected signals and scope; stack routing is the most commonly tuned part of the system.

For the broader direction of the project, see [ROADMAP.md](ROADMAP.md).
