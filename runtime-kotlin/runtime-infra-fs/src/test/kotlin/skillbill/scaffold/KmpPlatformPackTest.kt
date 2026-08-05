package skillbill.scaffold

import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewPathMatcher
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val KMP_CODE_REVIEW_AREAS =
  setOf("platform-correctness", "persistence", "reliability", "ui", "ux-accessibility")

private val BACKEND_RUBRIC_TERMS = listOf(
  "Exposed",
  "@Transactional",
  "Hibernate",
  "JDBC",
  "R2DBC",
  "resilience4j",
  "offset",
  "broker",
)

private const val COMPOSE_NAV_PATH = "app/src/main/java/com/acme/nav/NavGraph.kt"

private val COMPOSE_NAV_CONTENT = """
  package com.acme.nav

  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.collectAsState
  import androidx.navigation.compose.NavHost
  import androidx.navigation.compose.composable
  import androidx.navigation.compose.rememberNavController
  import androidx.lifecycle.ViewModel
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow

  class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
  }

  @Composable
  fun AppNavGraph(viewModel: HomeViewModel) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "home") {
      composable("home") { HomeScreen(viewModel.state.collectAsState().value) }
    }
  }
""".trimIndent()

private val PLAIN_ANDROID_DIFF = listOf(
  ReviewRoutingChangedFile(
    "app/build.gradle.kts",
    """
      plugins {
        id("com.android.application")
        id("dagger.hilt.android.plugin")
      }
      android { namespace = "com.acme" }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "app/src/main/AndroidManifest.xml",
    "<manifest><application android:name=\".AcmeApp\" /></manifest>",
  ),
  ReviewRoutingChangedFile(
    "app/src/main/java/com/acme/data/AppDatabase.kt",
    """
      package com.acme.data

      import androidx.room.Database
      import androidx.room.RoomDatabase

      @Database(entities = [SiteEntity::class], version = 3)
      abstract class AppDatabase : RoomDatabase() {
        abstract fun siteDao(): SiteDao
      }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "app/src/main/java/com/acme/data/SettingsStore.kt",
    """
      package com.acme.data

      import androidx.datastore.preferences.core.booleanPreferencesKey
      import androidx.datastore.preferences.core.edit

      class SettingsStore(private val store: DataStore<Preferences>) {
        suspend fun setSyncEnabled(enabled: Boolean) {
          store.edit { it[booleanPreferencesKey("sync")] = enabled }
        }
      }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "app/src/main/java/com/acme/di/DataModule.kt",
    """
      package com.acme.di

      import dagger.hilt.InstallIn
      import dagger.hilt.components.SingletonComponent

      @InstallIn(SingletonComponent::class)
      object DataModule
    """.trimIndent(),
  ),
)

private val BACKEND_DOMINANT_DIFF = listOf(
  ReviewRoutingChangedFile(
    "service/src/main/kotlin/com/acme/billing/InvoiceTable.kt",
    """
      package com.acme.billing

      import org.jetbrains.exposed.sql.Table
      import org.jetbrains.exposed.sql.transactions.transaction

      object InvoiceTable : Table("invoice") {
        val id = long("id").autoIncrement()
      }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "service/src/main/kotlin/com/acme/billing/InvoiceRepository.kt",
    """
      package com.acme.billing

      import kotlinx.coroutines.flow.Flow
      import org.jetbrains.exposed.sql.selectAll

      class InvoiceRepository {
        suspend fun all(): List<Invoice> = InvoiceTable.selectAll().map(::toInvoice)
      }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "service/src/main/kotlin/com/acme/billing/InvoiceRoutes.kt",
    """
      package com.acme.billing

      import io.ktor.server.routing.Route
      import io.ktor.server.routing.get
      import kotlinx.coroutines.withContext

      fun Route.invoiceRoutes(repository: InvoiceRepository) {
        get("/invoices") { call.respond(repository.all()) }
      }
    """.trimIndent(),
  ),
  ReviewRoutingChangedFile(
    "android-lib/src/main/java/com/acme/widget/BadgeView.kt",
    """
      package com.acme.widget

      import androidx.compose.runtime.Composable

      @Composable
      fun Badge(label: String) = Text(label)
    """.trimIndent(),
  ),
)

class KmpPlatformPackTest {
  @Test
  fun `kmp manifest registers elevated specialists and routing contract`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kmp")
    val pack = loadPlatformPack(packRoot)

    assertEquals("kmp", pack.slug)
    assertEquals("Android & Kotlin Multiplatform", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(KMP_CODE_REVIEW_AREAS, pack.declaredCodeReviewAreas.toSet())
    assertEquals(KMP_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(KMP_CODE_REVIEW_AREAS, pack.areaMetadata.keys)
    assertEquals(packRoot.resolve("quality-check/bill-kmp-code-check/content.md"), pack.declaredQualityCheckFile)
    listOf(".kt", "*.kt", ".kts", "*.kts", "commonMain", "androidMain", "iosMain", "expect", "actual")
      .forEach { signal -> assertContains(pack.routingSignals.strong, signal) }
    assertTrue(pack.routingSignals.tieBreakers.any { "dominate" in it && "prefer" in it })
    assertTrue(pack.routingSignals.tieBreakers.any { "Do not prefer" in it && "adjacent" in it })
    assertTrue(pack.routingSignals.tieBreakers.any { "generated" in it && "vendored" in it })

    val manifest = Files.readString(packRoot.resolve("platform.yaml"))
    KMP_CODE_REVIEW_AREAS.forEach { area ->
      assertTrue("code-review/bill-kmp-code-review-$area:" in manifest)
    }
    assertTrue("mode: kmp-baseline" in manifest)
  }

  @Test
  fun `kmp persistence and reliability area focus names Android native frameworks only`() {
    val pack = loadPlatformPack(repoRootFromTest().resolve("platform-packs/kmp"))
    val persistence = pack.areaMetadata.getValue("persistence")
    val reliability = pack.areaMetadata.getValue("reliability")

    listOf("Room", "SQLDelight", "DataStore").forEach { assertContains(persistence, it) }
    assertContains(reliability, "WorkManager")
    listOf(persistence, reliability).forEach { focus ->
      listOf("Exposed", "Hibernate", "JDBC", "R2DBC", "Spring", "resilience4j")
        .forEach { assertFalse(it in focus, "Backend framework $it leaked into kmp area focus: $focus") }
    }
  }

  @Test
  fun `kmp effective review coverage is Kotlin baseline with five overriding lanes`() {
    val repoRoot = repoRootFromTest()
    val report = PlatformPackSubstanceAudit.audit(repoRoot)
    val kmp = report.packs.single { it.pack == "kmp" }

    assertEquals((APPROVED_CODE_REVIEW_AREAS - KMP_CODE_REVIEW_AREAS).sorted(), kmp.inheritedAreas)
    assertEquals(KMP_CODE_REVIEW_AREAS.sorted(), kmp.physicalAreas)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, (kmp.inheritedAreas + kmp.physicalAreas).toSet())
    assertTrue(report.violations.none { it.pack == "kmp" && it.areaOrRole.startsWith("composition:") })
  }

  @Test
  fun `kmp quality checker covers discovered multiplatform and release tasks`() {
    val checker = Files.readString(
      repoRootFromTest().resolve("platform-packs/kmp/quality-check/bill-kmp-code-check/content.md"),
    )

    listOf(
      "tasks --all",
      "common metadata",
      "Android",
      "Kotlin/Native",
      "Compose Multiplatform resource",
      "dependency alignment",
      "release variants",
      "XCFramework",
      "shrinker",
      "unavailable toolchain",
    ).forEach { signal -> assertContains(checker, signal) }
  }

  @Test
  fun `every KMP add-on declares activation and exclusion boundaries and is reachable`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kmp")
    val pack = loadPlatformPack(packRoot)
    val addOns = Files.list(packRoot.resolve("addons")).use { paths ->
      paths.filter { Files.isRegularFile(it) }.toList()
    }
    val addOnNames = addOns.map { it.fileName.toString() }.toSet()
    val governedSelections = pack.addonUsage.flatMap { usage ->
      usage.addons.flatMap { selection -> listOf(selection.entrypoint) + selection.companionPointers }
    } + pack.featureAddonUsage.flatMap { usage ->
      usage.addons.flatMap { selection -> listOf(selection.entrypoint) + selection.companionPointers }
    }

    assertEquals(12, addOns.size)
    assertEquals(addOnNames, governedSelections.toSet())
    addOns.forEach { addOn ->
      val content = Files.readString(addOn)
      assertTrue(
        "## Activation signals" in content || "Read this file when" in content,
        "Missing positive activation signals in ${addOn.fileName}",
      )
      assertTrue(
        "## Exclusions" in content || "## Boundary" in content || "## Review boundary" in content ||
          "## Implementation boundary" in content,
        "Missing exclusion boundary in ${addOn.fileName}",
      )
      assertTrue(content.lineSequence().count { it.startsWith("- ") } >= 3, "Thin guidance in ${addOn.fileName}")
      assertTrue(
        pack.pointers.any { pointer ->
          pointer.name == addOn.fileName.toString() &&
            pointer.target == "platform-packs/kmp/addons/${addOn.fileName}"
        },
        "Unreachable governed pointer for ${addOn.fileName}",
      )
    }
    pack.addonUsage.forEach { usage ->
      val consumerPointers =
        pack.pointers.filter { it.skillRelativeDir == usage.skillRelativeDir }.map { it.name }.toSet()
      usage.addons.forEach { selection ->
        assertTrue(selection.entrypoint in consumerPointers)
        assertTrue(selection.companionPointers.all { it in consumerPointers })
      }
    }
    pack.featureAddonUsage.forEach { usage ->
      val consumerPointers =
        pack.pointers.filter { it.skillRelativeDir == usage.consumer }.map { it.name }.toSet()
      usage.addons.forEach { selection ->
        assertTrue(selection.entrypoint in consumerPointers)
        assertTrue(selection.companionPointers.all { it in consumerPointers })
      }
    }
  }

  @Test
  fun `kmp baseline routes correctness r8 navigation and every specialist`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kmp")
    val baseline = Files.readString(packRoot.resolve("code-review/bill-kmp-code-review/content.md"))

    listOf(
      "expect",
      "actual",
      "commonMain",
      "kotlinx.serialization",
      "kotlinx-datetime",
      "Dispatchers.Main",
      "ObjC",
      "Skie",
      "suspend cancellation",
    )
      .forEach { rule -> assertTrue(rule in baseline, "Missing KMP routing signal $rule") }
    assertTrue("proguard-rules.pro" in baseline && "android-r8" in baseline)
    assertTrue(
      "NavController" in baseline &&
        "`ui` specialist; include the UI `android-navigation` add-on" in baseline,
    )
    KMP_CODE_REVIEW_AREAS.forEach { area ->
      assertTrue(Regex("(?m)^- .+ -> `$area` specialist\\.").containsMatchIn(baseline))
    }
    mapOf(
      "persistence" to listOf("Room", "SQLDelight", "DataStore"),
      "reliability" to listOf("WorkManager", "CoroutineWorker"),
    ).forEach { (area, symbols) ->
      val row = Regex("(?m)^- (.+) -> `$area` specialist\\.").find(baseline)!!.groupValues[1]
      symbols.forEach { assertTrue(it in row, "Missing $it in the $area routing row") }
    }
  }

  @Test
  fun `kmp specialists enforce target boundaries compose semantics and canonical closers`() {
    val reviewRoot = repoRootFromTest().resolve("platform-packs/kmp/code-review")
    val correctness = Files.readString(reviewRoot.resolve("bill-kmp-code-review-platform-correctness/content.md"))
    val ui = Files.readString(reviewRoot.resolve("bill-kmp-code-review-ui/content.md"))
    val ux = Files.readString(reviewRoot.resolve("bill-kmp-code-review-ux-accessibility/content.md"))
    val compose = Files.readString(reviewRoot.resolve("bill-kmp-code-review-ui/compose-guidelines.md"))

    listOf(
      "expect",
      "actual",
      "commonMain",
      "polymorphic",
      "kotlinx-datetime",
      "Dispatchers.Main",
      "ObjC",
      "Skie",
      "cancellation",
    )
      .forEach { rule -> assertTrue(rule in correctness, "Missing platform correctness rule $rule") }
    assertTrue("## Ignore" in ui && "ux-accessibility" in ui && "security" in ui)
    assertFalse("## UI Delegation" in ux)
    listOf(
      "Modifier.semantics",
      "mergeDescendants",
      "clearAndSetSemantics",
      "contentDescription = null",
      "stateDescription",
      "Role",
      "liveRegion",
      "heading()",
      "error()",
      "traversalIndex",
      "isTraversalGroup",
      "minimumInteractiveComponentSize",
      "48dp",
      "fontScale",
    )
      .forEach { api -> assertTrue(api in ux, "Missing accessibility API $api") }
    listOf(
      "## Compose Multiplatform",
      "Res.string",
      "ComposeUIViewController",
      "UIKitView",
      "lifecycle-runtime-compose",
      "derivedStateOf",
      "snapshotFlow",
      "rememberUpdatedState",
      "TextField",
    )
      .forEach { rule -> assertTrue(rule in compose, "Missing Compose rule $rule") }
    assertTrue("Android-target-only" in compose)
    assertTrue(ui.trimEnd().endsWith("user-visible interaction or rendering failure scenario."))
    assertTrue(ux.trimEnd().endsWith("accessibility or task-completion failure scenario."))
    assertTrue(correctness.trimEnd().endsWith("invalid-state or ordering failure scenario."))
  }

  @Test
  fun `plain single-module Android diff routes to kmp`() {
    val result = ReviewStackRouting.route(routingManifests(), PLAIN_ANDROID_DIFF)

    assertContains(result.routedSlugs, "kmp")
    assertContains(result.ownedPathsBySlug.getValue("kmp"), "app/src/main/java/com/acme/data/AppDatabase.kt")
    assertContains(result.ownedPathsBySlug.getValue("kmp"), "app/src/main/AndroidManifest.xml")
  }

  @Test
  fun `commonMain and androidMain diff routes to kmp`() {
    val result = ReviewStackRouting.route(
      routingManifests(),
      listOf(
        ReviewRoutingChangedFile(
          "shared/src/commonMain/kotlin/com/acme/SessionStore.kt",
          "expect class SessionStore { fun load(): String }",
        ),
        ReviewRoutingChangedFile(
          "shared/src/androidMain/kotlin/com/acme/SessionStore.android.kt",
          "actual class SessionStore { actual fun load(): String = \"\" }",
        ),
      ),
    )

    assertContains(result.routedSlugs, "kmp")
  }

  @Test
  fun `backend dominant Kotlin monorepo with an incidental Android module still routes to kotlin`() {
    val result = ReviewStackRouting.route(routingManifests(), BACKEND_DOMINANT_DIFF)

    val kotlinOwned = result.ownedPathsBySlug.getValue("kotlin")
    BACKEND_DOMINANT_DIFF.map { it.path }.filter { it.startsWith("service/") }
      .forEach { assertContains(kotlinOwned, it) }
    assertEquals(setOf("android-lib/src/main/java/com/acme/widget/BadgeView.kt"), result.ownedPathsBySlug["kmp"])
  }

  @Test
  fun `composed kmp plan resolves ui to the kmp ui specialist`() {
    val plan = ReviewLaunchPlanPolicy.flatten("kmp", routingManifests(), setOf("ui"))

    val ui = plan.lanes.single { it.area == "ui" }
    assertEquals("bill-kmp-code-review-ui", ui.skillName)
    assertEquals("kmp", ui.packSlug)
    assertEquals(0, ui.depth)
  }

  @Test
  fun `ui lane fires on a src main layout with no androidMain directory`() {
    val ui = ReviewLaunchPlanPolicy.flatten("kmp", routingManifests(), setOf("ui")).lanes.single { it.area == "ui" }

    assertTrue(ReviewLaneInclusionPolicy.ownsChangedFile(ui, COMPOSE_NAV_PATH, COMPOSE_NAV_CONTENT))
    assertFalse("androidMain" in COMPOSE_NAV_PATH)
    assertTrue(ui.pathSignals.any { ReviewPathMatcher.matches(COMPOSE_NAV_PATH, it) })
  }

  @Test
  fun `Compose navigation file under plain Android routing is owned by kmp`() {
    val result = ReviewStackRouting.route(
      routingManifests(),
      listOf(ReviewRoutingChangedFile(COMPOSE_NAV_PATH, COMPOSE_NAV_CONTENT)),
    )

    assertContains(result.ownedPathsBySlug.getValue("kmp"), COMPOSE_NAV_PATH)
  }

  @Test
  fun `kmp persistence and reliability rubrics stay Android native and never drift to backend rubric`() {
    val reviewRoot = repoRootFromTest().resolve("platform-packs/kmp/code-review")
    val persistence = Files.readString(reviewRoot.resolve("bill-kmp-code-review-persistence/content.md"))
    val reliability = Files.readString(reviewRoot.resolve("bill-kmp-code-review-reliability/content.md"))

    listOf("Room", "SQLDelight", "DataStore", "Migration", "idempotency", "cursor", "transaction")
      .forEach { term -> assertContains(persistence, term, message = "Missing persistence rubric term $term") }
    listOf("WorkManager", "CoroutineWorker", "BackoffPolicy", "Constraints", "process death", "collector")
      .forEach { term -> assertContains(reliability, term, message = "Missing reliability rubric term $term") }

    mapOf("persistence" to persistence, "reliability" to reliability).forEach { (area, rubric) ->
      BACKEND_RUBRIC_TERMS.forEach { term ->
        assertFalse(term in rubric, "Backend rubric term $term leaked into the kmp $area specialist")
      }
      assertFalse(
        Regex("(?i)\\back\\b").containsMatchIn(rubric),
        "Broker acknowledgement semantics leaked into the kmp $area specialist",
      )
    }
  }

  private fun routingManifests() = listOf("kmp", "kotlin", "generic").map { slug ->
    loadPlatformPack(repoRootFromTest().resolve("platform-packs/$slug"))
  }

  @Test
  fun `kmp native agent registry has exact specialist parity`() {
    val agents = Files.readString(
      repoRootFromTest().resolve("platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/agents.yaml"),
    )
    assertTrue(agents.startsWith("contract_version: \"0.1\""))
    KMP_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(1, Regex("(?m)^  - name: bill-kmp-code-review-$area$").findAll(agents).count())
    }
  }
}
