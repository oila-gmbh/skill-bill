package skillbill.scaffold

import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.runtime.InstallOperations
import skillbill.scaffold.authoring.renderAuthoringTarget
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.substance.Fraction
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val KOTLIN_CODE_REVIEW_AREAS = setOf(
  "api-contracts",
  "architecture",
  "performance",
  "persistence",
  "platform-correctness",
  "reliability",
  "security",
  "testing",
  "ui",
  "ux-accessibility",
)

class KotlinPlatformPackTest {
  @Test
  fun `kotlin platform pack declares elevated manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/kotlin")

    val pack = loadPlatformPack(packRoot)

    assertEquals("kotlin", pack.slug)
    assertEquals("Kotlin", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-kotlin-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-kotlin-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(KOTLIN_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(KOTLIN_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(KOTLIN_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      ".kt",
      "*.kt",
      ".kts",
      "*.kts",
      "build.gradle",
      "build.gradle.kts",
      "settings.gradle.kts",
      "gradle/libs.versions.toml",
      "detekt.yml",
      "kotlin/",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("Kotlin/JVM") && it.contains("dominate") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("adjacent KMP") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated and vendored") })
    assertEquals(10, pack.areaMetadata.values.toSet().size)
    pack.areaMetadata.values.forEach { focus -> assertContains(focus, "Kotlin") }
    val pointerConsumers = pack.pointers.map { pointer -> pointer.skillRelativeDir }.toSet()
    KOTLIN_CODE_REVIEW_AREAS.forEach { area ->
      assertContains(pointerConsumers, "code-review/bill-kotlin-code-review-$area")
    }
  }

  @Test
  fun `kotlin review and quality-check source content is authored and substantive`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kotlin")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream
        .filter { path -> path.fileName.toString() == "content.md" }
        .sorted()
        .toList()
    }

    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertTrue(text.contains("internal-for:"), "Missing internal classification in $contentFile")
      assertFalse(text.contains("TODO"), "Kotlin pack source must not contain TODO placeholders: $contentFile")
    }

    val reviewRoot = packRoot.resolve("code-review")
    listOf(
      "CancellationException",
      "SupervisorJob",
      "runBlocking",
      "runTest",
      "explicitNulls",
      "newSuspendedTransaction",
    ).forEach { requiredRule ->
      assertTrue(
        Files.walk(reviewRoot).use { paths ->
          paths.filter { file -> Files.isRegularFile(file) }.anyMatch { file ->
            Files.readString(file).contains(requiredRule)
          }
        },
        "Kotlin specialists must contain $requiredRule guidance",
      )
    }

    val baseline = Files.readString(reviewRoot.resolve("bill-kotlin-code-review/content.md"))
    KOTLIN_CODE_REVIEW_AREAS.forEach { area ->
      assertTrue(Regex("(?m)^- .+ -> `$area` specialist\\.").containsMatchIn(baseline))
    }
    listOf("Compose Desktop", "Swing EDT", "JavaFX Application Thread", "server templates", "CLI", "TUI")
      .forEach { signal -> assertContains(baseline, signal) }
  }

  @Test
  fun `kotlin specialists preserve corrected framework and concurrency contracts`() {
    val reviewRoot = repoRootFromTest().resolve("platform-packs/kotlin/code-review")

    val architecture = specialistContent(reviewRoot, "architecture")
    assertContains(architecture, "dispatcher may be encapsulated by the adapter")
    assertContains(architecture, "When Exposed is detected")
    assertFalse(architecture.contains("every blocking API must receive an injected dispatcher"))

    val correctness = specialistContent(reviewRoot, "platform-correctness")
    assertContains(correctness, "propagate `CancellationException`")
    assertContains(correctness, "equality-based conflation")
    assertContains(correctness, "For APIs actually published to Java callers")

    val persistence = specialistContent(reviewRoot, "persistence")
    assertContains(persistence, "do not hop to `Dispatchers.IO` from inside an active thread-bound transaction")
    assertContains(persistence, "transactional outbox")

    val reliability = specialistContent(reviewRoot, "reliability")
    assertContains(reliability, "parent or sibling `CancellationException`")
    assertContains(reliability, "broker's drain or rebalance contract")

    val performance = specialistContent(reviewRoot, "performance")
    assertContains(performance, "`flowOn` changes only the upstream flow execution context")
    assertContains(performance, "not a universal substitute")

    val security = specialistContent(reviewRoot, "security")
    assertContains(security, "does not instantiate arbitrary JVM classes")
    assertContains(security, "canonical resolution")
    assertContains(security, "direct argument-list execution does not perform shell expansion")

    val testing = specialistContent(reviewRoot, "testing")
    assertContains(testing, "never as evidence of production thread or event ordering")
    assertContains(testing, "`backgroundScope`")
    assertContains(testing, "do not universally require `compileTestKotlin`")

    val ui = specialistContent(reviewRoot, "ui")
    assertContains(ui, "`DisposableEffect` for acquire/dispose")
    assertContains(ui, "Compose Desktop UI thread")
    assertContains(ui, "only when the current execution is off that thread")

    val accessibility = specialistContent(reviewRoot, "ux-accessibility")
    assertContains(accessibility, "version- and platform-dependent")
    assertContains(accessibility, "semantics alone do not prove")
  }

  @Test
  fun `kotlin specialists native agents rendering and substance have exact ten-area parity`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/kotlin")
    val pack = loadPlatformPack(packRoot)
    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-kotlin-code-review/native-agents/agents.yaml"),
    )
    val expectedNames = KOTLIN_CODE_REVIEW_AREAS.map { area -> "bill-kotlin-code-review-$area" }.toSet()

    assertEquals(expectedNames, agents.map { agent -> agent.name }.toSet())
    agents.forEach { agent ->
      assertTrue(composeNativeAgentSource(repoRoot, agent).body.isNotBlank())
      val rendered = renderAuthoringTarget(repoRoot, agent.name)
      assertContains(rendered.stdout, "===== SKILL.md:")
      assertEquals(
        setOf("review-orchestrator.md", "shell-ceremony.md", "telemetry-contract.md"),
        rendered.blocks.drop(1).map { block -> block.header.substringAfterLast('/').substringBefore(" =====") }.toSet(),
      )
    }

    val report = PlatformPackSubstanceAudit.audit(repoRoot)
    val kotlin = report.packs.single { metric -> metric.pack == "kotlin" }
    assertEquals(KOTLIN_CODE_REVIEW_AREAS, kotlin.physicalAreas.toSet())
    kotlin.specialists.filterNot { specialist -> specialist.inherited }.forEach { specialist ->
      assertTrue(
        specialist.substantiveRules >= 10,
        "Thin Kotlin specialist: ${specialist.area} (${specialist.substantiveRules})",
      )
      assertEquals(3, specialist.failureModeClusters, "Missing failure cluster: ${specialist.area}")
      assertTrue(specialist.concreteEvidenceRules >= 10, "Missing evidence: ${specialist.area}")
      assertTrue(specialist.placeholders.isEmpty(), "Placeholder in ${specialist.area}")
    }
    assertEquals(7, kotlin.qualityCheckFacets.size)
    assertTrue(kotlin.sharedShingles <= Fraction(35, 100), kotlin.sharedShingles.percentage())
    report.pairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/kotlin/") || pair.secondFile.startsWith("platform-packs/kotlin/")
    }.forEach { pair ->
      assertTrue(pair.similarity <= Fraction(65, 100), "${pair.role}: ${pair.similarity.percentage()}")
    }
  }

  @Test
  fun `install plan selects kotlin baseline checker and all specialists`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-kotlin-install-plan-home-")
    val runtimeInstallRoot = home.resolve(".skill-bill/runtime")

    val plan = InstallOperations.planInstall(
      InstallPlanRequest(
        repoRoot = repoRoot,
        home = home,
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CODEX),
        ),
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("kotlin"),
        ),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice = McpRegistrationChoice(
          register = false,
          runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp"),
        ),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
        targetPaths = InstallationTargetPaths(
          skillsRoot = repoRoot.resolve("skills"),
          platformPacksRoot = repoRoot.resolve("platform-packs"),
          agentTargets = emptyList(),
        ),
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      ),
    )

    val skillsByName = plan.skills.associateBy { skill -> skill.name }

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "kotlin")
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertEquals(
      InstallPlanSkillKind.PLATFORM_PACK,
      skillsByName.getValue("bill-kotlin-code-review").kind,
    )
    assertEquals(
      InstallPlanSkillKind.PLATFORM_PACK,
      skillsByName.getValue("bill-kotlin-code-check").kind,
    )
    KOTLIN_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-kotlin-code-review-$area").kind,
      )
    }
  }
}

private fun specialistContent(reviewRoot: java.nio.file.Path, area: String): String =
  Files.readString(reviewRoot.resolve("bill-kotlin-code-review-$area/content.md"))
