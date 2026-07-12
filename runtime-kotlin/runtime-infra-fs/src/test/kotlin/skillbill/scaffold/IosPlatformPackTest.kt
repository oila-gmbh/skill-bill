package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.rendering.canonicalSeverityCloser
import skillbill.scaffold.substance.Fraction
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlatformPackTest {
  private val packRoot by lazy { repoRootFromTest().resolve("platform-packs/ios") }

  @Test
  fun `ios platform pack declares enriched routing and bespoke area metadata`() {
    val pack = loadPlatformPack(packRoot)

    assertEquals("ios", pack.slug)
    assertEquals("iOS", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-ios-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-ios-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      ".xcodeproj",
      "*.xcodeproj",
      ".xcworkspace",
      "*.xcworkspace",
      ".swift",
      "*.swift",
      "project.pbxproj",
      "Info.plist",
      "Podfile",
      ".swiftlint.yml",
      "fastlane/Fastfile",
      "import SwiftUI",
      "import UIKit",
      "iOSApplicationExtension",
    ).forEach { marker -> assertContains(pack.routingSignals.strong, marker) }

    val tieBreakers = pack.routingSignals.tieBreakers.joinToString("\n")
    assertContains(tieBreakers, "Prefer iOS")
    assertContains(tieBreakers, "dominate")
    assertContains(tieBreakers, "Do not prefer iOS")
    assertContains(tieBreakers, "adjacent mixed-stack")
    assertContains(tieBreakers, "iosMain")
    assertContains(tieBreakers, "KMP")
    assertContains(tieBreakers, "generated sources")
    assertContains(tieBreakers, "vendored dependencies")
    assertContains(tieBreakers, "dominance scoring")

    assertEquals(10, pack.areaMetadata.values.toSet().size)
    iosAreaContexts.forEach { (area, contexts) ->
      val focus = pack.areaMetadata.getValue(area)
      contexts.forEach { context -> assertContains(focus, context) }
    }
  }

  @Test
  fun `ios native agents exactly mirror manifest focuses`() {
    val pack = loadPlatformPack(packRoot)
    val agentsDocument = Yaml().load<Map<String, Any>>(
      Files.readString(packRoot.resolve("code-review/bill-ios-code-review/native-agents/agents.yaml")),
    )
    val agents = (agentsDocument.getValue("agents") as List<*>)
      .filterIsInstance<Map<*, *>>()
      .associate { agent -> agent["name"] as String to agent["description"] as String }

    assertEquals(APPROVED_CODE_REVIEW_AREAS.size, agents.size)
    pack.areaMetadata.forEach { (area, focus) ->
      assertEquals(
        "iOS ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against $focus. Returns a Risk Register in the F-XXX bullet format.",
        agents["bill-ios-code-review-$area"],
      )
    }
  }

  @Test
  fun `ios quality check pins discovery selection and fix discipline`() {
    val qualityCheck = source("quality-check/bill-ios-code-check/content.md")

    listOf(
      "Discover build files, repository wrappers, and CI configuration",
      "Xcode workspace or project",
      "xcodebuild build",
      "xcodebuild test",
      "swift build",
      "swift test",
      ".swiftlint.yml",
      ".swiftformat",
      "priority-ordered fix ladder",
      "never suppress",
      "Re-run targeted checks",
      "full suite when targeted checks cannot establish safety",
    ).forEach { marker -> assertContains(qualityCheck, marker) }
  }

  @Test
  fun `ios specialists pin native platform failure modes`() {
    val apiContracts = specialist("api-contracts")
    assertContains(apiContracts, "REST/`Codable` branch")
    assertContains(apiContracts, "Missing, null, type-mismatched, or corrupt")
    assertContains(apiContracts, "controlled request error path")
    assertContains(apiContracts, "classify the response status before decoding a success model")
    assertContains(apiContracts, "Structured server error payloads must be decoded and preserved")
    assertContains(apiContracts, "repository's codegen command must reproduce committed generated output exactly")
    assertContains(apiContracts, "schema, every changed operation and fragment, and generated client artifacts")

    val persistence = specialist("persistence")
    assertContains(persistence, "Core Data/SwiftData branch")
    assertContains(persistence, "on its owning queue")
    assertContains(persistence, "on its owning actor")
    assertContains(persistence, "never pass an `NSManagedObject` or `ModelContext`")
    assertContains(persistence, "`NSManagedObjectID` or an explicitly `Sendable` value representation")
    assertContains(persistence, "Core Data lightweight migration and SwiftData automatic migration")
    assertContains(persistence, "`VersionedSchema` with `SchemaMigrationPlan`")
    assertContains(persistence, "evidence that an existing store upgrades successfully")

    val correctness = specialist("platform-correctness")
    assertContains(correctness, "must satisfy `Sendable` or remain isolated behind an actor")
    assertContains(correctness, "mutable reference types")
    assertContains(correctness, "`@unchecked Sendable`")
    assertContains(correctness, "concrete thread-safety invariant")
    assertContains(correctness, "`@MainActor`")
    assertContains(correctness, "resume exactly once")

    val reliability = specialist("reliability")
    assertContains(reliability, "registered during application launch")
    assertContains(reliability, "exactly match the project configuration and permitted identifiers")
    assertContains(reliability, "submission failures must remain visible and actionable")
    assertContains(reliability, "expiration handler must be installed before work proceeds")
    assertContains(reliability, "cancel all tracked work on expiration")
    assertContains(reliability, "`setTaskCompleted` exactly once")
    assertContains(reliability, "`beginBackgroundTask`")
    assertContains(reliability, "Background `URLSession`")
    assertContains(reliability, "termination-safe")

    val performance = specialist("performance")
    assertContains(performance, "expensive work repeated from SwiftUI `body`")
    assertContains(performance, "CGImageSourceCreateThumbnailAtIndex")
    assertContains(performance, "bounded `autoreleasepool` scopes")

    val security = specialist("security")
    assertContains(security, "intended access group")
    assertContains(security, "kSecAttrAccessible")
    assertContains(security, "ATS exceptions")
    assertContains(security, "public privacy specifier")
    assertContains(security, "must carry an expiration date")

    val ui = specialist("ui")
    assertContains(ui, "For deployment targets before iOS 17")
    assertContains(ui, "`@StateObject`")
    assertContains(ui, "`@ObservedObject`")
  }

  @Test
  fun `ios baseline routes by signal and excludes low-signal files`() {
    val baseline = source("code-review/bill-ios-code-review/content.md")

    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertTrue(
        Regex("(?m)^- .+ -> `$area` specialist\\.$").containsMatchIn(baseline),
        "Missing signal-based routing for $area",
      )
    }
    listOf(
      "generated `API.swift`",
      "Pods",
      ".build",
      "snapshot baselines",
      "project.pbxproj` churn",
      "vendored dependencies",
      "build outputs",
      "non-stack/non-iOS files",
    ).forEach { marker -> assertContains(baseline, marker) }
    assertContains(baseline, "deterministic, capacity-bounded waves")
    assertContains(baseline, "retain every selected specialist result")
    assertContains(baseline, "attributed merge")
    assertContains(baseline, "without losing evidence")
  }

  @Test
  fun `ios pack satisfies shared specialist structure and severity contracts`() {
    assertEquals(
      emptyList(),
      ReviewSkillStructureConformanceTest().structureViolations(packRoot),
    )

    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      val projectRules = specialist(area)
        .substringAfter("## Project-Specific Rules")
        .substringBefore("\n## ")
      val finalRule = projectRules.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("- ") }
        .last()
      assertEquals(canonicalSeverityCloser(area), finalRule)
    }

    val iosContent = Files.walk(packRoot).use { paths ->
      paths
        .filter { it.fileName.toString() == "content.md" }
        .map(Files::readString)
        .toList()
        .joinToString("\n")
    }
    assertFalse(Regex("\\bNit\\b").containsMatchIn(iosContent))
  }

  @Test
  fun `ios specialists checker duplication and offline add-ons satisfy substance contracts`() {
    val report = PlatformPackSubstanceAudit.audit(repoRootFromTest())
    val ios = report.packs.single { metric -> metric.pack == "ios" }

    assertEquals(APPROVED_CODE_REVIEW_AREAS, ios.physicalAreas.toSet())
    ios.specialists.filterNot { specialist -> specialist.inherited }.forEach { specialist ->
      assertTrue(specialist.substantiveRules >= 10, "Thin iOS specialist: $specialist")
      assertEquals(3, specialist.failureModeClusters, "Missing failure cluster: $specialist")
      assertTrue(specialist.concreteEvidenceRules >= 10, "Missing concrete evidence: $specialist")
      assertTrue(specialist.placeholders.isEmpty(), "Placeholder in $specialist")
    }
    assertEquals(7, ios.qualityCheckFacets.size)
    assertTrue(ios.sharedShingles <= Fraction(35, 100), ios.sharedShingles.percentage())
    report.pairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/ios/") || pair.secondFile.startsWith("platform-packs/ios/")
    }.forEach { pair ->
      assertTrue(pair.similarity <= Fraction(65, 100), "${pair.role}: ${pair.similarity.percentage()}")
    }

    val pack = loadPlatformPack(packRoot)
    val expectedAddOns = setOf(
      "offline-first-review.md",
      "offline-sync-consistency.md",
      "offline-conflict-resolution.md",
      "offline-background-reliability.md",
    )
    val reachable = pack.addonUsage.flatMap { usage ->
      val consumerPointers = pack.pointers
        .filter { pointer -> pointer.skillRelativeDir == usage.skillRelativeDir }
        .map { pointer -> pointer.name }
        .toSet()
      usage.addons.flatMap { selection ->
        val selected = listOf(selection.entrypoint) + selection.companionPointers
        assertTrue(
          selected.all { pointer -> pointer in consumerPointers },
          "Unreachable add-on for ${usage.skillRelativeDir}",
        )
        selected
      }
    }.toSet()
    assertEquals(expectedAddOns, reachable)
    assertEquals(
      setOf(
        "code-review/bill-ios-code-review",
        "code-review/bill-ios-code-review-persistence",
        "code-review/bill-ios-code-review-platform-correctness",
        "code-review/bill-ios-code-review-reliability",
      ),
      pack.addonUsage.map { usage -> usage.skillRelativeDir }.toSet(),
    )
  }

  private fun source(relativePath: String): String = Files.readString(packRoot.resolve(relativePath))

  private fun specialist(area: String): String = source("code-review/bill-ios-code-review-$area/content.md")

  private companion object {
    val iosAreaContexts = mapOf(
      "api-contracts" to listOf("REST/Codable", "Apollo GraphQL"),
      "architecture" to listOf("Swift module boundaries", "SPM package ownership"),
      "performance" to listOf("SwiftUI recomputation", "ImageIO downsampling"),
      "persistence" to listOf("Core Data and SwiftData", "GRDB transaction consistency"),
      "platform-correctness" to listOf("Swift actor isolation", "Sendable crossings"),
      "reliability" to listOf("BGTaskScheduler", "background URLSession"),
      "security" to listOf("Keychain accessibility", "ATS exceptions"),
      "testing" to listOf("XCTest", "snapshot intent"),
      "ui" to listOf("SwiftUI state ownership", "deployment-version fallbacks"),
      "ux-accessibility" to listOf("VoiceOver semantics", "Dynamic Type"),
    )
  }
}
