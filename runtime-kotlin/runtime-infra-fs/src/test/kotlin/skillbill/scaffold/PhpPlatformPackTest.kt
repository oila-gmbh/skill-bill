package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidManifestSchemaError
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
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.scaffold.substance.SubstancePolicy
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhpPlatformPackTest {
  @Test
  fun `php platform pack declares expected manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/php")

    val pack = loadPlatformPack(packRoot)

    assertEquals("php", pack.slug)
    assertEquals("PHP", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-php-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-php-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      "composer.json",
      "composer.lock",
      ".php",
      "*.php",
      "phpunit.xml",
      "phpunit.xml.dist",
      "pest.php",
      "phpstan.neon",
      "psalm.xml",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertEquals(3, pack.routingSignals.tieBreakers.size)
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("Prefer PHP") && it.contains("dominate") })
    assertTrue(
      pack.routingSignals.tieBreakers.any {
        it.contains("Do not prefer PHP") && it.contains("adjacent") && it.contains("tooling or CI glue")
      },
    )
    assertTrue(
      pack.routingSignals.tieBreakers.any {
        it.contains("vendor/") && it.contains("generated clients") && it.contains("dominance scoring")
      },
    )
    assertPhpAreaMetadata(pack.areaMetadata)
  }

  private fun assertPhpAreaMetadata(areaMetadata: Map<String, String>) {
    assertEquals(10, areaMetadata.values.toSet().size)
    val concreteAreaContexts = mapOf(
      "api-contracts" to listOf("Symfony, Laravel, and PSR", "webhooks, idempotency"),
      "architecture" to listOf("Composer and PSR-4", "worker lifetimes", "fibers"),
      "performance" to listOf("PDO and ORM", "OPcache", "fiber blocking"),
      "persistence" to listOf("PDO, Doctrine, and Eloquent", "unit-of-work lifetime"),
      "platform-correctness" to listOf("coercion, truthiness", "request-versus-worker lifetime"),
      "reliability" to listOf("Laravel and Messenger", "worker reset", "fatal telemetry"),
      "security" to listOf("Blade and Twig escaping", "processes, SSRF"),
      "testing" to listOf("PHPUnit and Pest", "PHPStan and Psalm", "runtime matrices"),
      "ui" to listOf("Blade, Twig, Livewire", "Symfony Form", "hydration correctness"),
      "ux-accessibility" to listOf("keyboard and focus behavior", "progressive enhancement"),
    )
    assertEquals(concreteAreaContexts.keys, areaMetadata.keys)
    concreteAreaContexts.forEach { (area, expectedContexts) ->
      val focus = areaMetadata.getValue(area)
      assertContains(focus, "PHP")
      expectedContexts.forEach { context -> assertContains(focus, context) }
    }
  }

  @Test
  fun `php native agents exactly mirror manifest focuses`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/php")
    val pack = loadPlatformPack(packRoot)
    val agentsDocument = Yaml().load<Map<String, Any>>(
      Files.readString(packRoot.resolve("code-review/bill-php-code-review/native-agents/agents.yaml")),
    )
    val agents = (agentsDocument.getValue("agents") as List<*>)
      .filterIsInstance<Map<*, *>>()
      .associate { agent -> agent["name"] as String to agent["description"] as String }

    assertEquals(APPROVED_CODE_REVIEW_AREAS.size, agents.size)
    pack.areaMetadata.forEach { (area, focus) ->
      assertEquals(
        "PHP ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against $focus. Returns a Risk Register in the F-XXX bullet format.",
        agents["bill-php-code-review-$area"],
      )
    }
  }

  @Test
  fun `php quality-check content must remain an internal bill-code-check sidecar`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-php-pack-malformed-")
    val packRoot = tempRoot.resolve("php")
    copyDirectory(repoRoot.resolve("platform-packs/php"), packRoot)

    val qualityCheckContent = packRoot.resolve("quality-check/bill-php-code-check/content.md")
    Files.writeString(
      qualityCheckContent,
      Files.readString(qualityCheckContent).replace("internal-for: bill-code-check\n", ""),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformPack(packRoot)
    }

    assertContains(error.message.orEmpty(), "internal-for: bill-code-check")
    assertContains(error.message.orEmpty(), "quality-check")
  }

  @Test
  fun `php review and quality-check source content is authored`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/php")
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
      assertFalse(text.contains("TODO"), "PHP pack source must not contain TODO placeholders: $contentFile")
      assertTrue(
        text.lines().size > 12,
        "PHP pack source should contain substantive guidance: $contentFile",
      )
    }
  }

  @Test
  fun `php specialists and checker meet substantive depth and similarity gates`() {
    val repoRoot = repoRootFromTest()
    val report = PlatformPackSubstanceAudit.audit(repoRoot)
    val php = report.packs.single { metric -> metric.pack == "php" }
    val policy = SubstancePolicy()

    assertEquals(APPROVED_CODE_REVIEW_AREAS, php.specialists.map { it.area }.toSet())
    php.specialists.forEach { specialist ->
      assertTrue(specialist.substantiveRules >= policy.minimumRules, specialist.toString())
      assertEquals(policy.minimumClusters, specialist.failureModeClusters, specialist.toString())
      assertTrue(specialist.concreteEvidenceRules >= policy.minimumRules, specialist.toString())
      assertTrue(specialist.placeholders.isEmpty(), specialist.toString())
    }
    assertEquals(policy.minimumQualityFacets, php.qualityCheckFacets.size)
    assertTrue(php.sharedShingles <= policy.maximumSharedShingles, php.sharedShingles.percentage())

    val phpPairs = report.pairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/php/") || pair.secondFile.startsWith("platform-packs/php/")
    }
    assertTrue(phpPairs.isNotEmpty())
    assertTrue(phpPairs.all { it.similarity <= policy.maximumPairSimilarity })
    assertTrue(
      phpPairs.any { pair ->
        pair.firstFile.startsWith("platform-packs/go/") || pair.secondFile.startsWith("platform-packs/go/")
      },
    )
  }

  @Test
  fun `php content names concrete runtime framework and quality failure modes`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/php")
    val requiredByArea = mapOf(
      "platform-correctness" to listOf("empty()", "Throwable", "RoadRunner", "Fiber", "autoload.psr-4"),
      "architecture" to listOf("services.yaml", "EntityManager::wrapInTransaction()", "Connection::transactional()", "dispatch()", "Fiber"),
      "api-contracts" to listOf("FormRequest::rules()", "JSON_THROW_ON_ERROR", "PSR-15", "Idempotency-Key"),
      "persistence" to listOf("PDO::ATTR_ERRMODE", "EntityManager", "fillable", "FOR UPDATE"),
      "security" to listOf("unserialize()", "|raw", "finfo", "Process::fromShellCommandline()"),
      "reliability" to listOf("retry_after", "SIGTERM", "withoutOverlapping()", "error_get_last()"),
      "performance" to listOf("LazyCollection", "OPcache", "classmap-authoritative", "memory_limit"),
      "testing" to listOf("tearDown()", "Queue::fake()", "PHPStan", "composer.json"),
      "ui" to listOf("wire:key", "withQueryString()", "419", "Cache::remember()"),
      "ux-accessibility" to listOf("aria-describedby", "focus-trap", "aria-live", "dir"),
    )
    requiredByArea.forEach { (area, terms) ->
      val content = Files.readString(packRoot.resolve("code-review/bill-php-code-review-$area/content.md"))
      terms.forEach { term -> assertContains(content, term) }
    }

    val checker = Files.readString(packRoot.resolve("quality-check/bill-php-code-check/content.md"))
    listOf(
      "composer validate --strict",
      "php -l",
      "phpstan analyse",
      "vendor/bin/phpunit",
      "composer audit",
      "composer dump-autoload --strict-psr",
      "supported PHP version, extension, dependency, and database matrix",
      "configured formatter or style verifier",
      "detected framework validation",
    ).forEach { term -> assertContains(checker, term) }
  }

  @Test
  fun `install plan selects real php pack skills from manifests`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-php-install-plan-home-")
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
          selectedSlugs = setOf("php"),
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

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "php")
    assertEquals(listOf("php"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-php-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-php-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-php-code-review-$area").kind,
      )
    }
    assertFalse(skillsByName.containsKey("bill-ios-code-review"))
    assertFalse(skillsByName.containsKey("bill-kotlin-code-review"))
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val destination = target.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }
}
