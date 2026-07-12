package skillbill.scaffold

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

private val KMP_CODE_REVIEW_AREAS = setOf("platform-correctness", "ui", "ux-accessibility")

class KmpPlatformPackTest {
  @Test
  fun `kmp manifest registers elevated specialists and routing contract`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kmp")
    val pack = loadPlatformPack(packRoot)

    assertEquals("kmp", pack.slug)
    assertEquals("KMP", pack.displayName)
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
  fun `kmp effective review coverage is Kotlin baseline with three overriding lanes`() {
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
