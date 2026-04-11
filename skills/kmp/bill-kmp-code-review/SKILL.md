---
name: bill-kmp-code-review
description: Use when conducting a thorough Android/KMP PR code review. Preserve mobile review depth by running the appropriate Kotlin baseline review layer first, then add Android/KMP-specific specialists such as UI and UX/accessibility. Produces a structured review with risk register and prioritized action items. Use when user mentions Android review, KMP review, mobile review, or asks to review Android/KMP changes.
---

# Android/KMP PR Review

You are an experienced Android/KMP architect conducting a code review.

Your job is to preserve Android/KMP review depth without duplicating the shared Kotlin review logic.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kmp-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass relevant project-wide guidance and matching per-skill overrides to every delegated or inline specialist review pass.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

---

## Project Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, source set layout, module names, imports).

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For shared review-orchestration rules, see [review-orchestrator.md](review-orchestrator.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

When the caller already passed the detected stack, skip reading [stack-routing.md](stack-routing.md). For standalone invocation, read it before classifying.

Before selecting KMP specialist review passes or formatting the final report, read [review-orchestrator.md](review-orchestrator.md) unless the caller already passed the shared review contract.

Before delegating baseline or KMP specialist review passes, read only your current runtime's section in [review-delegation.md](review-delegation.md).

Classify the review as one of:
- `kmp`
- `mixed-kmp`
- `not-kmp`

### Additional Backend/Server Signals

- `io.ktor.server`, `routing {}`, `Application.module`
- `spring-boot`, `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Transactional`
- Micronaut, Quarkus, http4k, Javalin, gRPC server code
- `application.yml`, `application.yaml`, `application.conf`
- SQL/ORM/data-access layers: Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase
- Queues, schedulers, consumers, caches, metrics, tracing, server auth middleware

### Decision Rules

- If the shared stack-routing playbook indicates Android/KMP signals are strong, keep the Android/KMP route.
- If Android/KMP signals are weak or absent, delegate to `bill-kotlin-code-review` and stop instead of pretending mobile-specific coverage exists.
- If backend/server files are also touched, choose `bill-backend-kotlin-code-review` as the baseline review layer so backend coverage is preserved before this skill adds mobile-specific specialists.
- When uncertain, prefer the safer route that preserves Android/KMP review depth.

---

## Layered Review Plan

### Step 1: Choose execution mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the Android/KMP review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, mobile or backend specialist risk is present, mixed scope is meaningfully involved, or the safest choice is unclear

### Step 2: Choose and run the baseline Kotlin-family review

Use the same scope to run exactly one baseline review layer:
- Use `bill-backend-kotlin-code-review` when backend/server files or markers are meaningfully in scope
- Otherwise use `bill-kotlin-code-review`

That baseline review layer owns:
- shared Kotlin architecture, correctness, security, performance, and testing review
- backend/server specialist selection when backend signals are present
- the baseline Kotlin findings that every Android/KMP review should inherit

When invoking the baseline review in either execution mode:
- tell it that Android/KMP scope is valid
- tell it to keep KMP-only review concerns out of scope
- pass the same diff source, changed files, and relevant override guidance

If execution mode is `inline`, apply the selected baseline review inline in the current thread.

If execution mode is `delegated`, run the selected baseline review as a delegated subagent and use the runtime-specific delegation contract from [review-delegation.md](review-delegation.md).

### Step 3: Analyze the diff and select KMP-specific agents

- Preserve Android/KMP specialists for any Android/KMP files even when backend files are changed in the same PR
- A single PR may spawn both the baseline review and KMP-only specialists, but keep the KMP-specific specialist count at 2 or fewer

#### Android/KMP Route

Keep the mobile triggers focused on what the baseline review does not cover:

| Signal in the diff | Specialist review to run |
|---------------------|--------------------------|
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect` | `bill-kmp-code-review-ui` |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `bill-kmp-code-review-ux-accessibility` |

### Step 3.5: Scope diff per KMP specialist (delegated mode only)

When execution mode is `delegated`, build a per-specialist file list before launching KMP specialist subagents:

1. Scan each changed file's name and imports for the KMP routing-table signals from Step 3
2. Map each file to the KMP specialists whose signals it matches
3. If a specialist's scoped file list is empty, drop it from the selected set

This is a lightweight file-level classification (names + imports), not a full review.

### Step 4: Run KMP specialist reviews

If execution mode is `inline`:
- run the selected KMP specialist review passes sequentially in the current thread
- read each KMP specialist skill file as the primary rubric for that pass
- apply the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- keep findings attributed to each layer before merging and deduplicating them for the final report

If execution mode is `delegated`:
- run one delegated subagent per selected KMP specialist review pass
- pass the specialist-scoped file list (from Step 3.5), applicable active learnings, instructions to read the KMP specialist skill file, the parent thread's model when the runtime supports delegated-worker model inheritance, and the shared specialist contract in [specialist-contract.md](specialist-contract.md)
- if delegated review is required for this scope but the current runtime lacks a documented delegation path or cannot start the required subagent(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

If no KMP-only triggers match but Android/KMP signals are clearly present, keep the baseline review output and state that no extra KMP-only specialist was needed for this scope.

---

## Review Output

### 1. Summary

```text
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: <stack>
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Specialist reviews: <selected specialists>
Reason: <why these specialists were selected>
```

Every finding in `### 2. Risk Register` must use this exact bullet format (do NOT use markdown tables):

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Severity: `Blocker | Major | Minor`. Confidence: `High | Medium | Low`.

### Auto-Import

After producing the final review output, automatically import it into the local telemetry store so the review run and findings are recorded without manual intervention.

Call the `import_review` MCP tool:
- `review_text`: the complete review output (Section 1 through Section 4)

### Auto-Triage

After the user responds to the review findings and the agent has acted on each decision (applied fixes, skipped findings, etc.), record the triage decisions so the telemetry event fires.

Each finding gets one decision using its position number from the risk register:
- `fix` — the finding was accepted and the fix was applied
- `accept` — the finding was accepted but no code change was needed
- `skip` — the finding was intentionally skipped (append a reason after ` - `)
- `false_positive` — the finding was incorrect

Call the `triage_findings` MCP tool:
- `review_run_id`: the review run ID from the review output
- `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`
- fallback: explicit numbered decisions still work, e.g. `["1 fix", "2 skip - intentional", "3 accept"]`

Skip auto-triage when the review produced no findings.

For action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

### Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.
