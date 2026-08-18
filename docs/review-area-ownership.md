# Review Area Ownership: KMP vs Kotlin

SKILL-197 records a disposition for every code-review area the `kmp` pack does not declare
directly. An area is either **declared on `kmp`** with Android-appropriate content, or
**retained on `kotlin`** with a justification that the Kotlin baseline rules are reachable and
sufficient on an Android/KMP diff.

`kmp` composes the `kotlin` pack as a required baseline layer, so a retained area still plans a
lane — it plans the `kotlin` rubric. A declared area plans the `kmp` rubric instead: lane flattening
picks one owner per area at the nearest composition depth, so declaring an area on `kmp` displaces
the `kotlin` rubric for that area entirely. A `kmp` rubric therefore cannot defer a concern to the
`kotlin` specialist for the same area — that lane does not run. Retention is a statement about
content quality, not about coverage: the area set planned for an Android/KMP diff is the full
approved set either way.

## Disposition Table

| Area | Disposition | Owning pack on an Android/KMP diff |
| --- | --- | --- |
| `architecture` | declared on `kmp` (SKILL-197 subtask 1) | `kmp` |
| `platform-correctness` | declared on `kmp` | `kmp` |
| `persistence` | declared on `kmp` | `kmp` |
| `reliability` | declared on `kmp` | `kmp` |
| `ui` | declared on `kmp` | `kmp` |
| `ux-accessibility` | declared on `kmp` | `kmp` |
| `security` | declared on `kmp` (SKILL-197 subtask 2) | `kmp` |
| `performance` | retained on `kotlin` | `kotlin` |
| `testing` | retained on `kotlin` | `kotlin` |
| `api-contracts` | retained on `kotlin` | `kotlin` |

## security — declared on `kmp`

Four of the sixteen `kotlin` security rules reach an Android/KMP diff: `kotlinx.serialization`
DTO validation before domain use, canonical path resolution with `toRealPath` under an allowed
root, secrets and `Authorization` headers in `logger` calls, and Gradle dependency integrity plus
advisory checking. All four are restated as `kmp` analogs — untrusted payload and path validation,
secrets and PII reaching logs, hardcoded key material, and a dependency-advisory plus
`verification-metadata.xml` rule in the release-surface cluster — because declaring the area
displaces the `kotlin` rubric.

The remaining twelve are server-side. Their triggers — `Principal`-based object authorization at a
service boundary, `SecurityContext`-derived tenant predicates, concatenated SQL in ORM or raw
statements, `kotlinx.html`/Thymeleaf/FreeMarker output escaping, `HttpClient` destination validation
for SSRF against metadata or internal services, `ProcessBuilder` and `sh -c` command construction,
JVM `ObjectInputStream` and Jackson polymorphic typing gadget chains, and JWT
signature/issuer/audience verification against trusted keys — do not exist in an Android application
process. They do exist in a `jvmMain` or server source set of a Kotlin Multiplatform repository,
which this pack also owns. The `kmp` rubric therefore carries a shared-and-JVM source-set cluster
covering entry-point object authorization and tenant derivation, bound parameters instead of
assembled statements or shell strings, token signature plus issuer, audience, and expiry
verification, and outbound destination and path allowlisting. The framework-specific view-templating
and gadget-chain triggers stay out because no source set this pack builds renders server-side
templates or accepts Java-serialized input.

Retention was rejected on sufficiency, not only on reachability. The four reachable rules leave the
entire on-device attack surface unrepresented: exported components and intent redirection,
`PendingIntent` mutability, WebView JavaScript bridges and file access, cleartext traffic and
certificate pinning, Keystore and `EncryptedSharedPreferences` key handling, `allowBackup` and
content-provider export, deeplink parameter validation, and PII reaching the clipboard. An Android
security lane running the `kotlin` rubric is silent on every one of those failures.

The `kmp` rubric lives at
`platform-packs/kmp/code-review/bill-kmp-code-review-security/content.md`.

## performance — retained on `kotlin`

Audited rules in `platform-packs/kotlin/code-review/bill-kotlin-code-review-performance/content.md`.

Reachable and load-bearing on Android: the `runBlocking` rule, which names UI dispatchers
explicitly and covers the dominant Android performance failure — blocking the main thread; the
blocking-boundary dispatcher-hop rule, which covers filesystem and legacy clients as well as JDBC;
measured concurrency against dispatcher parallelism and downstream capacity; `flowOn` upstream-only
semantics; unbounded `buffer` and `Channel.UNLIMITED` accumulation; eager `map`/`filter`
intermediates and `sequence` rewrites; repeated `Json.encodeToString` on a hot path; `batchSize`
and semaphore limits around queue consumers; and the reproducible-evidence bar for any performance
claim.

Inert on an Android/KMP diff: the ORM projection rule, whose only trigger is `@Entity` hydration
and N+1 access, and the lazy-association rule, whose only trigger is `Hibernate.initialize`.
Neither Hibernate nor a JPA entity manager exists in an Android runtime, so these two rules never
match an Android input. They are silent rather than misleading — they produce no finding at all on
a Room or SQLDelight diff, so they cannot direct a reviewer toward a wrong conclusion. Room and
SQLDelight query cost is already owned by the `kmp` persistence specialist, and Compose
recomposition cost by the `kmp` ui specialist, so the two inert rules leave no gap.

## testing — retained on `kotlin`

Audited rules in `platform-packs/kotlin/code-review/bill-kotlin-code-review-testing/content.md`.

Reachable and load-bearing on Android: `runTest` with virtual-time control; `StandardTestDispatcher`
explicit advancement via `runCurrent`, `advanceTimeBy`, and `advanceUntilIdle`;
`UnconfinedTestDispatcher` misuse under thread confinement; child `Job` and `backgroundScope`
lifecycle assertions; bounded Turbine `awaitItem` sequences with the `StateFlow`/`SharedFlow`
hot-stream carve-out; decoding fixtures for absent fields, explicit nulls, constructor defaults,
and unknown enum values; separate authorization, validation, timeout, duplicate-delivery, and
permanent-failure paths; tautological `assertEquals(stub, subject())` rejection; KSP and kapt
generated-output compilation, which covers Room, Hilt, and Compose compiler plugins; unordered
`toSet()` assertions over contractual Flow order; and the source-set-aware Gradle task discovery
rule, which explicitly refuses to universally require `compileTestKotlin` because that omits
multiplatform tasks.

Inert on an Android/KMP diff: the Spring integration-test clause of the framework rule (its Ktor
and DI clauses remain reachable), the `Testcontainers` real-persistence rule, and the
`apiCheck` binary-toolchain clause for a non-published Android application module. These name a
container runtime and a JVM publication contract that an Android test source set does not have;
they fail to fire rather than producing a wrong finding, and the Android analog — Room in-memory
and instrumented database tests — is covered by the `kmp` persistence specialist's own evidence
rules. This is the least-skewed of the four areas and was the observed run's most productive lane.

## api-contracts — retained on `kotlin`

Audited rules in `platform-packs/kotlin/code-review/bill-kotlin-code-review-api-contracts/content.md`.

Reachable and load-bearing on Android: `Json { explicitNulls = ... }` against the published
absent-versus-null contract; `ignoreUnknownKeys` policy together with Kotlin constructor defaults
silently accepting an omitted required input; `encodeDefaults` before adding a DTO default;
`@SerialName` renaming without migration evidence; unknown enum fallback or rejection; `Instant`
wire format and offset normalization plus zone-free `LocalDate` semantics; and `@JvmInline` value
class serializer shape. These are the client-decoding failures that actually break a mobile app
against an evolving server, and a stale installed app cannot be redeployed alongside the server.
The idempotency rule is reachable through client-side retry of a mutation after a network timeout.

Inert on an Android/KMP diff: the `jackson-module-kotlin` registration rule, the Bean Validation
`@field:NotBlank` target rule, the Ktor/Spring server exception-mapping rule, and the
server-authored pagination-ordering rule. Their triggers are Jackson, a Bean Validation provider,
and server-side routing — none present in an Android application. The `apiCheck` binary-compatibility
rule is inert for an application module and remains reachable for a published KMP library, so it is
retained rather than replaced. Each inert rule is silent on Android inputs: it names a symbol that
does not appear in the diff, so it yields no finding instead of an incorrect one.

## Non-Goal Recorded

The audit found `security` unreachable enough to declare and the other three reachable, so the
`kotlin` pack was not split into a backend pack plus a neutral Kotlin core. That restructuring
remains a separate ticket per the SKILL-197 parent spec's non-goals.
