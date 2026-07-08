package skillbill.cli

import skillbill.application.install.InstallPlanningPorts
import skillbill.application.install.InstallReconcilePorts
import skillbill.application.install.InstallService
import skillbill.application.scaffold.RepoValidationService
import skillbill.application.team.TeamExportService
import skillbill.application.team.TeamSyncService
import skillbill.application.team.TeamSyncServiceDependencies
import skillbill.error.GeneratedTeamBundleArtifactEntryError
import skillbill.error.InvalidTeamBundleChecksumError
import skillbill.error.InvalidTeamBundleSchemaError
import skillbill.error.TeamBundleRollbackIncompleteError
import skillbill.error.TeamBundleSyncInstallFailedError
import skillbill.infrastructure.fs.FileSystemTeamBundleSyncGateway
import skillbill.infrastructure.fs.FileSystemTeamExportFileGateway
import skillbill.infrastructure.fs.TeamBundleValidatorAdapter
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.apply.model.InstallApplyExecutionResult
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.ReadBaselineManifestResult
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestResult
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.link.model.InstallSkillLinkResult
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFacts
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsResult
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortResult
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.plan.model.InstallStagingIntentResult
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionResult
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionResult
import skillbill.ports.team.TeamBundleArchiveGateway
import skillbill.ports.team.model.TeamBundleCandidate
import skillbill.ports.team.model.TeamBundleExtractionRequest
import skillbill.ports.team.model.TeamBundleStateReadRequest
import skillbill.ports.team.model.TeamRegistryResolveRequest
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationReport
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundleVerificationSummary
import skillbill.team.model.TeamExportRequest
import skillbill.team.model.TeamRollbackRequest
import skillbill.team.model.TeamSyncRequest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamSyncGatewayRuntimeTest {
  private val gateway = FileSystemTeamBundleSyncGateway()

  @Test
  fun `readBundle rejects checksum sidecar mismatch`() {
    val bundle = exportBundle("1.0.0")
    Files.writeString(bundle.resolveSibling("${bundle.fileName}.sha256"), "sha256:bad  ${bundle.fileName}\n")

    val error = assertFailsWith<InvalidTeamBundleChecksumError> {
      gateway.readBundle(bundle)
    }

    assertContains(error.message.orEmpty(), "checksum mismatch")
  }

  @Test
  fun `extractCandidate rejects generated artifact entry before writing it`() {
    val bundle = exportBundle("1.0.0")
    val mutated = bundle.resolveSibling("generated-entry.zip")
    appendStoredEntry(bundle, mutated, "sources/skills/bill-demo/SKILL.md", "# Generated\n")
    val candidate = gateway.readBundle(mutated)
    val root = Files.createTempDirectory("team-sync-candidate")

    val error = assertFailsWith<GeneratedTeamBundleArtifactEntryError> {
      gateway.extractCandidate(TeamBundleExtractionRequest(mutated, candidate.bundle, root))
    }

    assertContains(error.message.orEmpty(), "not declared")
  }

  @Test
  fun `resolveLatest selects highest valid bundle for registry channel`() {
    val registry = Files.createTempDirectory("team-sync-registry")
    exportBundle("1.0.0", registry)
    exportBundle("1.10.0", registry)
    exportBundle("1.2.0", registry)

    val selected = gateway.resolveLatest(TeamRegistryResolveRequest(registry, TeamBundleChannel.BETA))

    assertEquals("1.10.0", selected.bundle.metadata.version)
    assertEquals("team-beta-1.10.0", selected.bundle.metadata.bundleId)
  }

  @Test
  fun `sync rejects stale bundle contract version before install mutation`() {
    val bundle = exportBundle("1.0.0")
    val stale = bundle.resolveSibling("stale-contract.zip")
    rewriteBundleJson(bundle, stale) { raw ->
      raw.replace("\"contract_version\":\"0.1\"", "\"contract_version\":\"9.9\"")
    }
    val service = syncService(SequenceApplyExecutionPort(emptyList()))

    assertFailsWith<InvalidTeamBundleSchemaError> {
      service.sync(syncRequest(stale))
    }
  }

  @Test
  fun `sync rejects missing content md through source validation`() {
    val bundle = exportBundle("1.0.0")
    val candidate = gateway.readBundle(bundle)
    val archiveGateway = MutatingArchiveGateway(gateway, candidate) { root ->
      root.resolve("skills/bill-demo/content.md").deleteIfExists()
    }
    val service = syncService(SequenceApplyExecutionPort(emptyList()), archiveGateway)

    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      service.sync(syncRequest(bundle))
    }

    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `sync rejects malformed platform yaml through source validation`() {
    val bundle = exportBundle("1.0.0", includePlatformPack = true)
    val candidate = gateway.readBundle(bundle)
    val archiveGateway = MutatingArchiveGateway(gateway, candidate) { root ->
      root.resolve("platform-packs/kotlin/platform.yaml").writeText("slug: [\n")
    }
    val service = syncService(SequenceApplyExecutionPort(emptyList()), archiveGateway)

    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      service.sync(syncRequest(bundle))
    }

    assertContains(error.message.orEmpty(), "platform")
  }

  @Test
  fun `sync install failure restores previous bundle state`() {
    val first = exportBundle("1.0.0")
    val second = exportBundle("2.0.0")
    val applyPort = SequenceApplyExecutionPort(
      listOf(InstallApplyStatus.SUCCESS, InstallApplyStatus.FAILURE, InstallApplyStatus.SUCCESS),
    )
    val service = syncService(applyPort)
    val home = Files.createTempDirectory("team-sync-home")

    service.sync(syncRequest(first, home))
    val error = assertFailsWith<TeamBundleSyncInstallFailedError> {
      service.sync(syncRequest(second, home))
    }

    assertContains(error.message.orEmpty(), "restored previous bundle")
    val state = gateway.read(TeamBundleStateReadRequest(home))
    assertEquals("1.0.0", state?.version)
    assertEquals(3, applyPort.applyCount)
  }

  @Test
  fun `sync install failure without previous state reports rollback incomplete`() {
    val bundle = exportBundle("1.0.0")
    val applyPort = SequenceApplyExecutionPort(listOf(InstallApplyStatus.FAILURE))
    val service = syncService(applyPort)
    val home = Files.createTempDirectory("team-sync-home")

    val error = assertFailsWith<TeamBundleRollbackIncompleteError> {
      service.sync(syncRequest(bundle, home))
    }

    assertContains(error.message.orEmpty(), "no previous team bundle state")
    assertEquals(1, applyPort.applyCount)
    assertEquals(null, gateway.read(TeamBundleStateReadRequest(home)))
  }

  @Test
  fun `rollback restores previous bundle through sync install path`() {
    val first = exportBundle("1.0.0")
    val second = exportBundle("2.0.0")
    val applyPort = SequenceApplyExecutionPort(
      listOf(InstallApplyStatus.SUCCESS, InstallApplyStatus.SUCCESS, InstallApplyStatus.SUCCESS),
    )
    val service = syncService(applyPort)
    val home = Files.createTempDirectory("team-sync-home")

    service.sync(syncRequest(first, home))
    service.sync(syncRequest(second, home))
    val rollback = service.rollback(TeamRollbackRequest(installRequest(home)))

    assertEquals("1.0.0", rollback.restored.version)
    assertEquals("2.0.0", rollback.replaced.version)
    assertEquals("1.0.0", gateway.read(TeamBundleStateReadRequest(home))?.version)
    assertEquals(3, applyPort.applyCount)
  }

  private fun exportBundle(version: String, registryRoot: Path? = null, includePlatformPack: Boolean = false): Path {
    val repo = writeGovernedRepoFixture(includePlatformPack)
    val result = teamExportService().export(
      TeamExportRequest(
        repoRoot = repo,
        version = version,
        channel = TeamBundleChannel.BETA,
        outputPath = if (registryRoot == null) repo.resolve("dist/bundle-$version.zip") else null,
        registryRoot = registryRoot,
        createdAt = "2026-01-01T00:00:00Z",
        createdBy = "test",
        sourceRepo = "local",
        sourceRef = "refs/heads/test",
      ),
    )
    return requireNotNull(result.bundlePath)
  }

  private fun teamExportService(): TeamExportService = TeamExportService(
    RepoValidationService(FakeTeamSyncRepoValidationGateway()),
    TeamBundleValidatorAdapter(),
    FileSystemTeamExportFileGateway(),
    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
  )

  private fun writeGovernedRepoFixture(includePlatformPack: Boolean = false): Path {
    val repo = Files.createTempDirectory("team-sync-export")
    repo.resolve("skills/bill-demo").createDirectories()
    repo.resolve("skills/bill-demo/content.md").writeText(
      """
      ---
      name: bill-demo
      description: Demo skill.
      ---

      # Demo

      Use this governed source.
      """.trimIndent() + "\n",
    )
    if (includePlatformPack) {
      repo.resolve("platform-packs/kotlin").createDirectories()
      repo.resolve("platform-packs/kotlin/platform.yaml").writeText(
        """
        platform: kotlin
        contract_version: "1.2"
        display_name: Kotlin
        routing_signals:
          strong:
            - ".kt"
        declared_code_review_areas: []
        """.trimIndent() + "\n",
      )
    }
    repo.resolve("orchestration/contracts").createDirectories()
    repo.resolve("orchestration/contracts/team-bundle-schema.yaml").writeText("contract_version: fixture\n")
    return repo
  }

  private fun syncService(
    applyPort: SequenceApplyExecutionPort,
    archiveGateway: TeamBundleArchiveGateway = gateway,
  ): TeamSyncService = TeamSyncService(
    dependencies = TeamSyncServiceDependencies(
      archiveGateway = archiveGateway,
      registryResolver = gateway,
      statePersistence = gateway,
      teamBundleValidator = TeamBundleValidatorAdapter(),
      repoValidationService = RepoValidationService(FakeTeamSyncRepoValidationGateway()),
      installService = installService(applyPort),
    ),
    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
  )

  private fun syncRequest(bundle: Path, home: Path = Files.createTempDirectory("team-sync-home")): TeamSyncRequest =
    TeamSyncRequest(bundlePath = bundle, installRequest = installRequest(home))

  private fun installRequest(home: Path): InstallPlanRequest = InstallPlanRequest(
    repoRoot = home.resolve("unused-source"),
    home = home,
    agentSelection = InstallAgentSelection(
      mode = InstallAgentSelectionMode.MANUAL,
      manualAgents = setOf(InstallAgent.CODEX),
    ),
    platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.ALL),
    telemetryLevel = InstallTelemetryLevel.OFF,
    mcpRegistrationChoice = McpRegistrationChoice(register = false),
    runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = home.resolve(".skill-bill/runtime")),
    targetPaths = InstallationTargetPaths(
      skillsRoot = home.resolve("unused-source/skills"),
      platformPacksRoot = home.resolve("unused-source/platform-packs"),
      agentTargets = listOf(
        InstallAgentTarget(InstallAgent.CODEX, home.resolve(".codex/skills"), InstallAgentTargetSource.MANUAL),
      ),
    ),
    windowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
  )

  private fun installService(applyPort: SequenceApplyExecutionPort): InstallService = InstallService(
    planningPorts = InstallPlanningPorts(
      planningFactsPort = MinimalPlanningFactsPort,
      platformSkillMaterializationPort = NoopPlatformSkillMaterializationPort,
      stagingIntentPort = MinimalStagingIntentPort,
    ),
    reconcilePorts = unsupportedReconcilePorts,
    applyExecutionPort = applyPort,
    skillLinkPort = UnsupportedSkillLinkPort,
    installSelectionPersistencePort = NoopInstallSelectionPersistencePort,
    installPlanWireValidator = PassThroughInstallPlanWireValidator,
  )

  private fun appendStoredEntry(source: Path, target: Path, entryName: String, content: String) {
    val entries = ZipFile(source.toFile()).use { zip ->
      zip.entries().asSequence().map { entry ->
        entry.name to zip.getInputStream(entry).use { it.readBytes() }
      }.toList() + (entryName to content.toByteArray(Charsets.UTF_8))
    }
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      entries.sortedBy { it.first }.forEach { (name, bytes) ->
        zip.putStoredEntry(name, bytes)
      }
    }
    Files.write(target, output.toByteArray())
  }

  private fun ZipOutputStream.putStoredEntry(name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(name).apply {
      method = ZipEntry.STORED
      size = bytes.size.toLong()
      compressedSize = bytes.size.toLong()
      this.crc = crc.value
      time = 0L
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  private fun rewriteBundleJson(source: Path, target: Path, transform: (String) -> String) {
    val entries = ZipFile(source.toFile()).use { zip ->
      zip.entries().asSequence().map { entry ->
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        entry.name to if (entry.name == "bundle.json") {
          transform(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
          bytes
        }
      }.toList()
    }
    ZipOutputStream(Files.newOutputStream(target)).use { zip ->
      entries.sortedBy { it.first }.forEach { (name, bytes) ->
        zip.putStoredEntry(name, bytes)
      }
    }
  }

  private class MutatingArchiveGateway(
    private val delegate: FileSystemTeamBundleSyncGateway,
    private val candidate: TeamBundleCandidate,
    private val mutate: (Path) -> Unit,
  ) : TeamBundleArchiveGateway {
    override fun readBundle(path: Path): TeamBundleCandidate = candidate

    override fun extractCandidate(request: TeamBundleExtractionRequest): Path =
      delegate.extractCandidate(request).also(mutate)

    override fun verifyExtractedSources(
      bundle: skillbill.team.model.TeamBundle,
      sourceRoot: Path,
    ): TeamBundleVerificationSummary = delegate.verifyExtractedSources(bundle, sourceRoot)

    override fun cacheBundle(source: Path, home: Path, bundleId: String, checksum: String): Path =
      delegate.cacheBundle(source, home, bundleId, checksum)
  }

  private inner class SequenceApplyExecutionPort(
    statuses: List<InstallApplyStatus>,
  ) : InstallApplyExecutionPort {
    private val remaining = statuses.toMutableList()
    var applyCount: Int = 0
      private set

    override fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult {
      applyCount += 1
      val status = if (remaining.isEmpty()) InstallApplyStatus.SUCCESS else remaining.removeAt(0)
      return InstallApplyExecutionResult(applyResult(request.plan, status))
    }
  }

  private object MinimalPlanningFactsPort : InstallPlanningFactsPort {
    override fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult =
      InstallPlanningFactsResult(
        InstallPlanningFacts(
          baseSkills = listOf(
            InstallPlanSkill(
              name = "bill-demo",
              sourceDir = request.installRequest.repoRoot.resolve("skills/bill-demo"),
              kind = InstallPlanSkillKind.BASE,
            ),
          ),
          platformManifests = emptyList(),
          detectedAgentTargets = emptyList(),
          defaultAgentTargets = emptyList(),
        ),
      )
  }

  private object NoopPlatformSkillMaterializationPort : InstallPlatformSkillMaterializationPort {
    override fun materializePlatformSkills(
      request: InstallPlatformSkillMaterializationPortRequest,
    ): InstallPlatformSkillMaterializationPortResult = InstallPlatformSkillMaterializationPortResult(emptyList())
  }

  private object MinimalStagingIntentPort : InstallStagingIntentPort {
    override fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult =
      InstallStagingIntentResult(
        InstallStagingIntent(
          root = request.installRequest.home.resolve(".skill-bill/installed-skills"),
          skillPaths = emptyList(),
        ),
      )
  }

  private object UnsupportedSkillLinkPort : InstallSkillLinkPort {
    override fun linkSkill(request: InstallSkillLinkRequest): InstallSkillLinkResult =
      error("linkSkill is not part of this test")
  }

  private object NoopInstallSelectionPersistencePort : InstallSelectionPersistencePort {
    override fun readLatestSuccessfulSelection(
      request: ReadLatestSuccessfulInstallSelectionRequest,
    ): ReadLatestSuccessfulInstallSelectionResult = error("readLatestSuccessfulSelection is not part of this test")

    override fun writeLatestSuccessfulSelection(
      request: WriteLatestSuccessfulInstallSelectionRequest,
    ): WriteLatestSuccessfulInstallSelectionResult =
      WriteLatestSuccessfulInstallSelectionResult(request.installHome.resolve(".skill-bill/install-selection.json"))
  }

  private object PassThroughInstallPlanWireValidator : skillbill.install.model.InstallPlanWireValidator {
    override fun validate(plan: Map<String, Any?>) = Unit
  }

  private object UnsupportedInstallReconcilePort : InstallReconcilePort {
    override fun reconcile(
      request: skillbill.ports.install.reconcile.model.InstallReconcileRequest,
    ): skillbill.ports.install.reconcile.model.InstallReconcileResult = error("reconcile is not part of this test")
  }

  private object UnsupportedInstallReconcileApplyPort : InstallReconcileApplyPort {
    override fun apply(
      request: skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest,
    ): skillbill.ports.install.reconcile.model.InstallReconcileApplyResult =
      error("reconcile apply is not part of this test")
  }

  private object UnsupportedBaselineManifestPersistencePort : BaselineManifestPersistencePort {
    override fun readBaseline(request: ReadBaselineManifestRequest): ReadBaselineManifestResult =
      error("readBaseline is not part of this test")

    override fun writeBaseline(request: WriteBaselineManifestRequest): WriteBaselineManifestResult =
      error("writeBaseline is not part of this test")
  }

  private fun applyResult(plan: InstallPlan, status: InstallApplyStatus): InstallApplyResult = InstallApplyResult(
    status = status,
    skills = if (status == InstallApplyStatus.FAILURE) {
      emptyList()
    } else {
      listOf(
        InstallAppliedSkill(
          skillName = "bill-demo",
          kind = InstallPlanSkillKind.BASE,
          sourceDir = plan.request.repoRoot.resolve("skills/bill-demo"),
          staging = InstallSkillStagingOutcome(
            status = InstallSkillStagingStatus.STAGED,
            sourceDir = plan.request.repoRoot.resolve("skills/bill-demo"),
          ),
        ),
      )
    },
    nativeAgents = emptyList(),
    telemetryOutcome = InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = if (status == InstallApplyStatus.FAILURE) {
        InstallTelemetryApplyStatus.SKIPPED
      } else {
        InstallTelemetryApplyStatus.SUCCESS
      },
    ),
    mcpRegistrationOutcomes = emptyList(),
    warnings = emptyList(),
    failures = if (status == InstallApplyStatus.FAILURE) {
      listOf(InstallApplyIssue(InstallApplyIssueKind.STAGING_FAILED, "planned failure"))
    } else {
      emptyList()
    },
    windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
      preflight = plan.windowsSymlinkPreflight,
      fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
    ),
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = plan.request.mcpRegistrationChoice.register,
      runtimeMcpBin = plan.request.mcpRegistrationChoice.runtimeMcpBin,
      agents = listOf(InstallAgent.CODEX),
    ),
  )

  private companion object {
    val unsupportedReconcilePorts = InstallReconcilePorts(
      reconcilePort = UnsupportedInstallReconcilePort,
      reconcileApplyPort = UnsupportedInstallReconcileApplyPort,
      baselineManifestPersistencePort = UnsupportedBaselineManifestPersistencePort,
    )
  }
}

private class FakeTeamSyncRepoValidationGateway : RepoValidationGateway {
  override fun validateRepo(repoRoot: Path): RepoValidationReport = RepoValidationReport(
    issues = emptyList(),
    skillCount = 1,
    addonCount = 0,
    platformPackCount = 0,
    nativeAgentCount = 0,
  )

  override fun parseReleaseRef(rawRef: String): ReleaseRefMetadata =
    ReleaseRefMetadata(rawRef, rawRef, prerelease = false)

  override fun appendGithubOutput(outputPath: Path, metadata: ReleaseRefMetadata) = Unit
}
