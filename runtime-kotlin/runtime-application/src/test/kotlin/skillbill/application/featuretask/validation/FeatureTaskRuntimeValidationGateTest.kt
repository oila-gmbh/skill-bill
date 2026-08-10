package skillbill.application.featuretask.validation

import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateTest {
  private val repoRoot: Path = Path.of(".").toAbsolutePath().normalize()

  private val gateDeclaration = ValidationGateDeclaration(
    fullGateCommand = listOf("echo", "cache"),
    cacheBypassingFullGateCommand = listOf("echo", "full"),
    buildOnlyCommand = listOf("echo", "build-only"),
    findings = ValidationGateFindingsLocator(
      format = ValidationGateFindingsFormat.JUNIT_XML,
      artifactGlobs = listOf("**/*.xml"),
      executedWork = ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY),
    ),
  )

  @Test
  fun `BUILD_ONLY selects build_only_command argv`() {
    assertEquals(
      listOf("echo", "build-only"),
      validationGateArgv(gateDeclaration, ValidationDepth.BUILD_ONLY, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
  }

  @Test
  fun `terminal verifying selects cache bypass argv`() {
    assertEquals(
      listOf("echo", "full"),
      validationGateArgv(gateDeclaration, ValidationDepth.FULL, ValidationGateCacheMode.FORCED_FULL),
    )
  }

  @Test
  fun `intermediate repair runs stay on cache-eligible argv`() {
    assertEquals(
      listOf("echo", "cache"),
      validationGateArgv(gateDeclaration, ValidationDepth.FULL, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
  }

  @Test
  fun `truncated projection reports dropped count and blocks success semantics`() {
    val findings = (1..100).map { index ->
      ValidationGateFinding("m$index", "t$index", "message-$index", "loc-$index")
    }
    val projection = ValidationFindingSetProjector.project(
      findings,
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 256, maxCollectionItems = 2),
    )
    assertTrue(projection.droppedCount > 0)
    assertTrue(projection.hasUnreportedRemainder)
  }

  @Test
  fun `repair cycle cap is distinct and explicit`() {
    assertEquals(3, MAX_VALIDATE_GATE_REPAIR_ITERATIONS)
  }

  @Test
  fun `zero work terminal outcome is rejected`() {
    val runner = object : ValidationGateRunner {
      override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
        ValidationGateRunResult(
          exitCode = 0,
          durationMs = 3,
          outcome = ValidationGateRunOutcome.REJECTED_ZERO_WORK,
          cacheMode = request.cacheMode,
          executedWorkUnits = 0,
          findings = emptyList(),
        )
    }
    val result = runner.run(
      ValidationGateRunRequest(
        repoRoot = repoRoot,
        argv = listOf("true"),
        cacheMode = ValidationGateCacheMode.FORCED_FULL,
        declaration = gateDeclaration,
        terminalVerifying = true,
      ),
    )
    assertEquals(ValidationGateRunOutcome.REJECTED_ZERO_WORK, result.outcome)
    assertEquals(0, result.executedWorkUnits)
  }

  @Test
  fun `absent gate resolution returns absent for pack without declaration`() {
    val resolver = ValidationGateResolver(
      ScaffoldCatalogService(
        object : ScaffoldCatalogGateway {
          override fun approvedCodeReviewAreas() = emptySet<String>()
          override fun preShellFamilies() = emptySet<String>()
          override fun shelledFamilies() = emptySet<String>()
          override fun platformPackPresets() = emptyMap<String, String>()
          override fun scaffoldPayloadVersion() = "test"
          override fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection> =
            emptyList()
          override fun discoverPlatformManifests(packsRoot: Path) = listOf(kotlinPackWithoutGate())
          override fun discoverBaselineReviewCatalog(packsRoot: Path) =
            BaselineReviewCatalog(emptyList(), emptyList())
        },
      ),
    )
    val resolution = resolver.resolve(repoRoot, listOf("runtime-kotlin/foo.kt"))
    assertTrue(resolution is ValidationGateResolution.Absent)
  }

  private fun kotlinPackWithoutGate(): PlatformManifest = PlatformManifest(
    slug = "kotlin",
    packRoot = repoRoot.resolve("platform-packs/kotlin"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(
      strong = listOf("runtime-kotlin"),
      tieBreakers = emptyList(),
      path = listOf("runtime-kotlin"),
    ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    validationGate = null,
  )
}
