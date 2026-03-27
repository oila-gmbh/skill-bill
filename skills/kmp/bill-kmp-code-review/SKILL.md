---
name: bill-kmp-code-review
description: Use when conducting a thorough Android/KMP PR code review. Preserve mobile review depth by running the appropriate Kotlin baseline review layer first, then add Android/KMP-specific specialists such as UI and UX/accessibility. Produces a structured review with risk register and prioritized action items.
---

# Android/KMP PR Review

You are an experienced Android/KMP architect conducting a code review.

Your job is to preserve Android/KMP review depth without duplicating the shared Kotlin review logic.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kmp-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass relevant project-wide guidance and matching per-skill overrides to all spawned sub-agents.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

---

## Project Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, source set layout, module names, imports).

Before classifying, read `orchestration/stack-routing/PLAYBOOK.md`. Use it as the source of truth for routing Android/KMP work into the `kmp` package bucket.

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

### Step 1: Choose and run the baseline Kotlin-family review

Use the same scope to run exactly one baseline review layer:
- Use `bill-backend-kotlin-code-review` when backend/server files or markers are meaningfully in scope
- Otherwise use `bill-kotlin-code-review`

That baseline review layer owns:
- shared Kotlin architecture, correctness, security, performance, and testing review
- backend/server specialist selection when backend signals are present
- the baseline Kotlin findings that every Android/KMP review should inherit

When invoking the baseline review:
- tell it that Android/KMP scope is valid
- tell it to keep KMP-only review concerns out of scope
- pass the same diff source, changed files, and relevant override guidance

### Step 2: Analyze the diff and select KMP-specific agents

- Preserve Android/KMP specialists for any Android/KMP files even when backend files are changed in the same PR
- A single PR may spawn both the baseline review and KMP-only specialists, but keep the KMP-specific specialist count at 2 or fewer

#### Android/KMP Route

Keep the mobile triggers focused on what the baseline review does not cover:

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect` | `bill-kmp-code-review-ui` |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `bill-kmp-code-review-ux-accessibility` |

### Step 3: Launch KMP specialists in parallel

Spawn all selected KMP specialists simultaneously using the `task` tool. Each agent gets:
- the detected project type
- the list of changed files
- instructions to read its own skill file for the review rubric
- the shared contract below

If no KMP-only triggers match but Android/KMP signals are clearly present, keep the baseline review output and state that no extra KMP-only specialist was needed for this scope.

---

## Shared Contract For Every Specialist

- Scope: review only the changes in the current PR/unit of work — do not flag pre-existing issues in unchanged code
- Review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage)
- Flag newly introduced deprecated components, APIs, or patterns when a supported alternative exists, or when deprecated usage is broad in scope and not explicitly justified
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences
- Evidence is mandatory: include `file:line` + short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Maximum 7 findings per specialist
- Include a minimal, concrete fix for each finding

### Required Finding Schema

```text
[SEVERITY] Area: Issue title
  Location: file:line
  Impact: Why it matters (1 sentence)
  Fix: Concrete fix (1-2 lines)
  Confidence: High/Medium/Low
```

---

## Orchestrator Merge Rules

1. Collect the baseline findings from `bill-kotlin-code-review` or `bill-backend-kotlin-code-review`.
2. Collect all KMP-specific specialist findings.
3. If a specialist agent fails or returns no output, note it in the summary and continue with available results.
4. Deduplicate by root cause (same evidence or same failing behavior).
5. Keep highest severity/confidence when duplicates conflict.
6. Prioritize: Blocker > Major > Minor, then blast radius.
7. Produce one consolidated report.

---

## Review Output Format

### 1. Classification & Layer Summary
```text
Detected stack: kmp | mixed-kmp
Signals: @Composable, AndroidManifest.xml, ViewModel
Baseline review: bill-kotlin-code-review | bill-backend-kotlin-code-review
KMP agents spawned: bill-kmp-code-review-ui
Reason: Android/KMP signals were high-confidence, so the mobile layer was added on top of the baseline Kotlin-family review
```

### 2. Risk Register

Format each issue as:
```text
[IMPACT_LEVEL] Area: Issue title
  Location: file:line
  Impact: Description
  Fix: Concrete action
```

Impact levels: BLOCKER | MAJOR | MINOR

### 3. Action Items (Max 10, prioritized)

```text
1. [P0 BLOCKER] Fix issue (Effort: S, Impact: High)
2. [P1 MAJOR] Fix issue (Effort: M, Impact: Medium)
3. [P2 MINOR] Fix issue (Effort: S, Impact: Low)
```

Priority: P0 (blocker) | P1 (critical) | P2 (important) | P3 (nice-to-have)
Effort: S (<1h) | M (1-4h) | L (>4h)

### 4. Verdict

`Ship` | `Ship with fixes [list P0/P1 items]` | `Block until [list blockers]`

---

## Implementation Mode

If invoked standalone, ask: **"Which item would you like me to fix?"**

If invoked from `bill-feature-implement`, `bill-feature-verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.

After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.

---

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent has project-specific rules in its skill file
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture is broken
- No overoptimization: do not report negligible performance findings with no measurable user-facing or production-facing impact
- Honest: if unsure, say what context is missing
