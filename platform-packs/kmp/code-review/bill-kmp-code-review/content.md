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

- If the shared stack-routing playbook indicates Android/KMP signals are strong, keep the Android/KMP route.
- If Android/KMP signals are weak or absent, delegate to `bill-kotlin-code-review` and stop instead of pretending mobile-specific coverage exists.
- If backend/server files are also touched, keep the `kmp` route and use `bill-kotlin-code-review` as the baseline layer so shared Kotlin concerns are still reviewed before this skill adds mobile-specific specialists.
- When uncertain, prefer the safer route that preserves Android/KMP review depth.

## Governed Add-On Selection

After the stack is already classified as `kmp`, select governed add-ons before choosing KMP-specific specialists.

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

## Layered Review Plan

### Baseline Kotlin-family review

Use the same scope to run exactly one baseline review layer:
- Use `bill-kotlin-code-review`

That baseline review layer owns:
- shared Kotlin architecture, correctness, security, performance, and testing review
- backend/server risk coverage within the Kotlin specialist set when backend signals are present
- the baseline Kotlin findings that every Android/KMP review should inherit

When invoking the baseline review in either execution mode:
- tell it that Android/KMP scope is valid
- tell it to keep KMP-only review concerns out of scope
- pass the same diff source, changed files, and relevant override guidance

### KMP specialist routing table

Analyze the diff and select KMP-specific specialists beyond the baseline:

- Preserve Android/KMP specialists for any Android/KMP files even when backend files are changed in the same PR
- A single PR may spawn both the baseline review and KMP-only specialists, but keep the KMP-specific specialist count at 2 or fewer
- Pass any selected governed add-ons into the chosen KMP specialist review passes

| Signal in the diff | Specialist review to run |
| --- | --- |
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect` | `bill-kmp-code-review-ui` |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `bill-kmp-code-review-ux-accessibility` |

### Scope diff per KMP specialist (delegated mode only)

When execution mode is `delegated`, build a per-specialist file list before launching KMP specialist subagents:

1. Scan each changed file's name and imports for the KMP routing-table signals above
2. Map each file to the KMP specialists whose signals it matches
3. If a specialist's scoped file list is empty, drop it from the selected set

This is a lightweight file-level classification (names + imports), not a full review.

If no KMP-only triggers match but Android/KMP signals are clearly present, keep the baseline review output and state that no extra KMP-only specialist was needed for this scope.
