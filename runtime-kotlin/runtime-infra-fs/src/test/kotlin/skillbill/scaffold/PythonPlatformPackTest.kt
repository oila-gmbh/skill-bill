package skillbill.scaffold

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
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.substance.Fraction
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
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

class PythonPlatformPackTest {
  @Test
  fun `python platform pack declares expected manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/python")

    val pack = loadPlatformPack(packRoot)

    assertEquals("python", pack.slug)
    assertEquals("Python", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-python-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-python-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      "pyproject.toml",
      "requirements.txt",
      "setup.py",
      "setup.cfg",
      "Pipfile",
      "poetry.lock",
      "uv.lock",
      "tox.ini",
      "pytest.ini",
      ".py",
      "*.py",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("vendored") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("Python appears only as tooling") })
  }

  @Test
  fun `python quality-check content must remain an internal bill-code-check sidecar`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-python-pack-malformed-")
    val packRoot = tempRoot.resolve("python")
    copyDirectory(repoRoot.resolve("platform-packs/python"), packRoot)

    val qualityCheckContent = packRoot.resolve("quality-check/bill-python-code-check/content.md")
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
  fun `python review and quality-check source content is authored`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/python")
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
      assertFalse(text.contains("TODO"), "Python pack source must not contain TODO placeholders: $contentFile")
      assertTrue(
        text.lines().size > 12,
        "Python pack source should contain substantive guidance: $contentFile",
      )
    }
  }

  @Test
  fun `python specialists expose deep native coverage synchronized with metadata and agents`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/python")
    val pack = loadPlatformPack(packRoot)
    val contentByArea = pack.declaredFiles.areas.mapValues { (_, path) -> Files.readString(path) }
    val governedRulesByArea = contentByArea.mapValues { (_, content) -> governedRules(content) }
    PYTHON_EXPECTED_MARKERS.forEach { (area, markers) ->
      val rules = governedRulesByArea.getValue(area).joinToString("\n")
      markers.forEach { marker -> assertContains(rules, marker) }
    }
    PYTHON_EXPECTED_RULE_CONTRACTS.forEach { (area, contracts) ->
      val rules = governedRulesByArea.getValue(area)
      contracts.forEach { markers ->
        assertTrue(
          rules.any { rule -> markers.all { marker -> rule.contains(marker) } },
          "Python $area must keep coupled rule contract: ${markers.joinToString()}",
        )
      }
    }

    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-python-code-review/native-agents/agents.yaml"),
    ).associateBy { agent -> agent.name.removePrefix("bill-python-code-review-") }
    assertEquals(APPROVED_CODE_REVIEW_AREAS, agents.keys)
    agents.forEach { (area, agent) ->
      assertEquals(
        "${pack.displayName} ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against ${pack.areaMetadata.getValue(area)}. " +
          "Returns a Risk Register in the F-XXX bullet format.",
        agent.description,
      )
    }
  }

  @Test
  fun `python specialists and checker pass completed-pack substance gates`() {
    val repoRoot = repoRootFromTest()
    val report = PlatformPackSubstanceAudit.audit(repoRoot)
    val python = report.packs.single { pack -> pack.pack == "python" }

    assertEquals(APPROVED_CODE_REVIEW_AREAS, python.physicalAreas.toSet())
    python.specialists.filterNot { specialist -> specialist.inherited }.forEach { specialist ->
      assertTrue(
        specialist.substantiveRules >= 10,
        "Thin Python specialist: ${specialist.area} (${specialist.substantiveRules})",
      )
      assertEquals(3, specialist.failureModeClusters, "Missing failure cluster: ${specialist.area}")
      assertTrue(specialist.concreteEvidenceRules >= 10, "Missing concrete evidence: ${specialist.area}")
      assertTrue(specialist.placeholders.isEmpty(), "Placeholder in ${specialist.area}")
    }
    assertTrue(python.qualityCheckFacets.size >= 7)
    assertTrue(python.sharedShingles <= Fraction(35, 100), python.sharedShingles.percentage())
    assertTrue(
      python.highestCorrespondingSimilarity == null ||
        python.highestCorrespondingSimilarity.similarity <= Fraction(65, 100),
    )
    assertFalse(report.violations.any { violation -> violation.pack == "python" })
  }

  @Test
  fun `python quality checker preserves safe ordered repository execution`() {
    val repoRoot = repoRootFromTest()
    val content = Files.readString(
      repoRoot.resolve("platform-packs/python/quality-check/bill-python-code-check/content.md"),
    )
    listOf(
      "already-provisioned environment",
      "uv run --frozen --no-sync",
      "documented non-syncing invocation is unavailable, report a blocker",
      "pip-tools",
      "ruff format --check",
      "ruff check",
      "mypy",
      "pyright",
      "pytest path::test_name",
      "python -m build",
      "pip-audit",
      "python manage.py check",
      "pre-existing condition",
      "environmental blocker",
      "full suite",
    ).forEach { marker -> assertContains(content, marker) }
    assertFalse(content.contains("`poetry run`"))

    val orderedMarkers = listOf(
      "Verify environment and metadata integrity first",
      "Verify formatting without mutation",
      "Run configured linting",
      "Run configured typing",
      "Run targeted `pytest",
      "Build and inspect distributions",
      "Run configured dependency and security checks",
      "run framework and lifecycle validation",
    )
    val orderedPositions = orderedMarkers.map { marker -> content.indexOf(marker) }
    assertTrue(orderedPositions.all { position -> position >= 0 })
    assertTrue(orderedPositions.zipWithNext().all { (first, second) -> first < second })
  }

  @Test
  fun `install plan selects real python pack skills from manifests`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-python-install-plan-home-")
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
          selectedSlugs = setOf("python"),
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

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "python")
    assertEquals(listOf("python"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-python-code-review-$area").kind,
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

  private fun governedRules(content: String): List<String> {
    var governed = false
    return content.lineSequence().mapNotNull { line ->
      when {
        line.startsWith("### ") -> {
          governed = line.contains("Rules")
          null
        }
        line.startsWith("## ") -> {
          governed = false
          null
        }
        governed && line.startsWith("- ") -> line
        else -> null
      }
    }.toList()
  }
}

private val PYTHON_EXPECTED_MARKERS = mapOf(
  "api-contracts" to listOf(
    "model_fields_set",
    "Serializer.is_valid",
    "StreamingResponse",
    "generally retriable mutation",
  ),
  "architecture" to listOf(
    "importlib.import_module",
    "repository-owned settings module",
    "namespace packages",
    "[project.scripts]",
  ),
  "performance" to listOf(
    "sync_to_async",
    "thread_sensitive",
    "joinedload",
    "values_list",
    "ProcessPoolExecutor",
    "asyncio.Semaphore",
  ),
  "persistence" to listOf(
    "non-overlapping responsibility ownership",
    "one `AsyncSession` per concurrent task",
    "QuerySet.select_for_update()",
    "atomic Django `F()` updates",
    "database-per-tenant",
    "cannot be bypassed by raw SQL",
    "joinedload",
    "bounded query-count evidence",
    "values_list",
    "transaction.on_commit",
    "CONCURRENTLY",
  ),
  "platform-correctness" to listOf(
    "default_factory",
    "asyncio.TaskGroup",
    "task owner that initiated cancellation",
    "Queue.join",
    "time.monotonic",
    "injectable clock",
  ),
  "reliability" to listOf(
    "httpx.Timeout",
    "repository-owned shared client policy",
    "explicitly retained tasks",
    "visibility_timeout",
    "cancel_futures=True",
    "durable checkpoint writes",
    "restart position",
  ),
  "security" to listOf(
    "pickle.loads",
    "ZipFile.extractall",
    "shell=True",
    "SSRF",
    "exact raw request body",
    "bounded timestamp freshness window",
    "reject replayed event identifiers",
  ),
  "testing" to listOf(
    "pytest.raises",
    "hypothesis",
    "pytest-asyncio",
    "threading.Event",
    "injected wall and monotonic clocks",
    "durable checkpoint",
    "duplicate-safe replay",
  ),
  "ui" to listOf("ModelAdmin", "st.session_state", "PySide"),
  "ux-accessibility" to listOf(
    "aria-invalid=\"true\"",
    "stable, control-specific",
    "role-specific ARIA role",
    "documented keyboard pattern",
    "aria-live",
    "ngettext",
    "Jupyter notebooks",
  ),
)

private val PYTHON_EXPECTED_RULE_CONTRACTS = mapOf(
  "api-contracts" to listOf(
    listOf("generally retriable mutation", "idempotency key", "durable effects", "observable"),
  ),
  "performance" to listOf(
    listOf("per-item", "batching", "measured", "amplification"),
    listOf("sync_to_async", "thread_sensitive", "synchronous session", "event-loop"),
    listOf("hot-cache", "bounded capacity", "eviction or TTL", "memory evidence"),
  ),
  "persistence" to listOf(
    listOf("non-overlapping responsibility ownership", "creation", "rollback", "close"),
    listOf("whole transaction", "roll back", "ambiguous commit outcome", "external effects"),
    listOf("tenant predicate", "database-per-tenant", "raw SQL", "reused sessions"),
    listOf("joinedload", "selectinload", "bounded query-count evidence", "result cardinality"),
  ),
  "platform-correctness" to listOf(
    listOf("wall-clock timestamps", "time.monotonic", "timeout calculations", "injectable clock"),
  ),
  "reliability" to listOf(
    listOf("atomic outbox", "transaction.on_commit", "reconciliation", "loses required delivery"),
    listOf("stop new admissions", "already accepted work", "durably requeued", "observable terminal state"),
    listOf("durable checkpoint writes", "interruption cleanup", "restart position", "duplicate-safe replay"),
  ),
  "security" to listOf(
    listOf("browser sessions", "session rotation", "SameSite", "fixation"),
    listOf("capability URLs", "bounded expiry", "resource", "purpose"),
    listOf("ModelForm", "fields", "model_validate", "model_construct"),
  ),
  "testing" to listOf(
    listOf("framework requests", "transaction retries", "durable effects", "observable"),
    listOf("wall and monotonic clocks", "deadline boundaries", "persisted timestamps"),
    listOf("both sides", "durable checkpoint boundary", "before and after", "no skipped effects", "no duplicates"),
  ),
  "ux-accessibility" to listOf(
    listOf("aria-invalid=\"true\"", "control-specific", "aria-describedby", "aria-errormessage"),
    listOf("role-specific ARIA role", "accessible name", "focus behavior", "keyboard pattern"),
  ),
)
