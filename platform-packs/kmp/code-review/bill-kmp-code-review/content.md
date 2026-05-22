---
name: bill-kmp-code-review
description: Use when conducting a thorough Android/KMP PR code review. Preserve mobile review depth by running the manifest-declared baseline review layer first, then add Android/KMP-specific specialists such as UI and UX/accessibility. Produces a structured review with risk register and prioritized action items. Use when user mentions Android review, KMP review, mobile review, or asks to review Android/KMP changes.
---

# Android/KMP PR Review

You are an experienced Android/KMP architect conducting a code review.

Your job is to preserve Android/KMP review depth without duplicating shared Kotlin review logic. The generated Review Composition section is the source of truth for required baseline layers; apply this authored KMP guidance after those baseline instructions have been handled.
---

## Project Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, source set layout, module names, imports).

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

- If Android/KMP signals are strong, keep the Android/KMP route.
- If Android/KMP signals are weak or absent, delegate to `bill-kotlin-code-review` and stop instead of pretending mobile-specific coverage exists.
- If backend/server files are also touched, keep the `kmp` route and rely on the manifest-declared baseline layer so shared Kotlin concerns are still reviewed before this skill adds mobile-specific specialists.
- When uncertain, prefer the safer route that preserves Android/KMP review depth.

## Layered Review Plan

### Step 1: Scale review depth

- For small, low-risk Android/KMP diffs, keep the review compact.
- For larger, mixed, or higher-risk diffs, split the work into focused baseline and specialist passes.

### Step 2: Confirm the manifest-declared baseline layer

The generated Review Composition section defines the required baseline layer sequence. Treat that section as authoritative for which baseline skill to run, the mode to use, and the context that must be forwarded.

That baseline layer owns:
- shared Kotlin architecture, correctness, security, performance, and testing review
- backend/server risk coverage within the Kotlin specialist set when backend signals are present
- the baseline Kotlin findings that every Android/KMP review should inherit

When invoking the baseline layer:
- tell it that Android/KMP scope is valid for the manifest-declared baseline mode
- tell it to keep KMP-only review concerns out of scope
- pass the same diff source, changed files, and relevant override guidance

### Step 3: Analyze the diff and select KMP-specific agents

- Preserve Android/KMP specialists for any Android/KMP files even when backend files are changed in the same PR
- A single PR may spawn both the baseline review and KMP-only specialists, but keep the KMP-specific specialist count at 2 or fewer
- Pass any selected governed add-ons into the chosen KMP specialist review passes

#### Android/KMP Route

Keep the mobile triggers focused on what the baseline review does not cover:

| Signal in the diff                                                                                               | Specialist review to run                |
|------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect`                         | `bill-kmp-code-review-ui`               |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `bill-kmp-code-review-ux-accessibility` |

### Step 3.5: Scope diff per KMP specialist when review depth increases

When the review is split into focused KMP specialist passes, build a per-specialist file list first:

1. Scan each changed file's name and imports for the KMP routing-table signals from Step 3
2. Map each file to the KMP specialists whose signals it matches
3. If a specialist's scoped file list is empty, drop it from the selected set

This is a lightweight file-level classification (names + imports), not a full review.

### Step 4: Run KMP specialist reviews

- Run each selected KMP specialist lane against its scoped files.
- Read each KMP specialist skill file as the primary rubric for that lane.
- Keep findings attributed to each layer before merging and deduplicating them into the final review.

If no KMP-only triggers match but Android/KMP signals are clearly present, keep the baseline review output and state that no extra KMP-only specialist was needed for this scope.
