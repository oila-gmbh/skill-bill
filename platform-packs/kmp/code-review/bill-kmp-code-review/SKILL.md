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

## Governed Add-On Resolution

After the stack is already classified as `kmp`, resolve governed add-ons before selecting KMP-specific specialists.

- Start with `Selected add-ons: none`.
- Select `android-compose` when the scoped diff contains Compose UI signals such as `@Composable`, Compose UI state, `Modifier` chains, previews, `remember*`, or Compose side effects.
- Select `android-navigation` when the scoped diff changes route models, `NavHost`/`NavDisplay`, deep links, multi-back-stack behavior, scene destinations, or Android navigation ownership.
- Select `android-interop` when the scoped diff mixes Compose with legacy Views, Fragments, `ComposeView`, `AndroidView`, `AndroidViewBinding`, or other Android host-boundary glue.
- Select `android-design-system` when the scoped diff changes Android theme layers, `MaterialTheme`, design tokens, styled components, or XML-theme-to-Compose translation.
- Select `android-r8` when the scoped diff changes Android keep rules, shrinker config, `proguardFiles`, `isMinifyEnabled`, `isShrinkResources`, or release-only R8 settings.
- Scan [android-compose-review.md](android-compose-review.md) first. If the add-on is split into topic files, open only the linked topic files whose cues match the scoped diff when `android-compose` is selected, such as [android-compose-edge-to-edge.md](android-compose-edge-to-edge.md) and [android-compose-adaptive-layouts.md](android-compose-adaptive-layouts.md).
- Scan [android-navigation-review.md](android-navigation-review.md) when `android-navigation` is selected.
- Scan [android-interop-review.md](android-interop-review.md) when `android-interop` is selected.
- Scan [android-design-system-review.md](android-design-system-review.md) when `android-design-system` is selected.
- Scan [android-r8-review.md](android-r8-review.md) when `android-r8` is selected.
- Add-ons enrich the routed KMP review; they do not create standalone reviewer names or bypass the `kmp` route.

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
- Pass any selected governed add-ons into the chosen KMP specialist review passes

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
Selected add-ons: none | <add-on slugs>
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

### Telemetry

For telemetry ownership, triage ownership, and the `orchestrated` flag contract, follow [telemetry-contract.md](telemetry-contract.md).

For action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

### Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.

## Description
This content file is a platform-pack baseline review module for `bill-kmp-code-review`. The
governed shell (`bill-code-review`) delegates single-stack reviews here after
stack routing settles. The sections above define the operational playbook; the
sections below satisfy the shell+content contract v1.0.

## Specialist Scope
Baseline orchestrator. Selects and coordinates specialist area reviewers
declared under the platform pack's `declared_code_review_areas` and returns a
merged review.

## Inputs
Review scope (staged/unstaged/commit range/PR), changed files, detected stack
signals, active learnings, `review_session_id`, `review_run_id`, and the
`orchestrated` flag from the shell.

## Outputs Contract
Summary, Risk Register with findings of the form
`- [F-###] <Severity> | <Confidence> | <file:line> | <description>`,
Action Items, and Verdict (`approve`, `approve-with-changes`, or
`request-changes`). The output layer follows the shell's structured format.

## Execution Mode Reporting
Report `Execution mode: inline` or `Execution mode: delegated` explicitly,
per the shell's output contract.

## Telemetry Ceremony Hooks
Follow `telemetry-contract.md` for `import_review`/`triage_findings`
ownership. Suppress emission when the shell passes `orchestrated=true`.
