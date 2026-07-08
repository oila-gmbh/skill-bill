@file:Suppress("TooManyFunctions")

package skillbill.application.team

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.RepoValidationService
import skillbill.contracts.JsonSupport
import skillbill.contracts.team.TEAM_BUNDLE_CONTRACT_VERSION
import skillbill.ports.team.TeamExportFileGateway
import skillbill.ports.team.TeamExportFileGatewayException
import skillbill.ports.team.model.TeamExportCollectedSource
import skillbill.ports.team.model.TeamExportRegistryPublishRequest
import skillbill.ports.validation.model.RepoValidationReport
import skillbill.team.TeamBundleValidator
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundleContentHashInput
import skillbill.team.model.TeamBundleHashing
import skillbill.team.model.TeamBundlePrivacyLevel
import skillbill.team.model.TeamBundleSourceHash
import skillbill.team.model.TeamExportRequest
import skillbill.team.model.TeamExportResult
import skillbill.team.model.TeamExportSourceEntryHash
import skillbill.team.model.TeamExportValidationSummary
import java.nio.file.Path
import java.time.Clock

@Inject
class TeamExportService(
  private val repoValidationService: RepoValidationService,
  private val teamBundleValidator: TeamBundleValidator,
  private val fileGateway: TeamExportFileGateway,
  private val clock: Clock,
) {
  fun export(request: TeamExportRequest): TeamExportResult {
    val repoRoot = request.repoRoot.toAbsolutePath().normalize()
    val validation = repoValidationService.validateRepo(repoRoot)
    if (!validation.passed) {
      throw TeamExportException("Repository validation failed: ${validation.issues.joinToString("; ")}")
    }

    val sources = collectSources(repoRoot)
    val bundleId = "team-${request.channel.wireValue}-${request.version}"
    val outputPath = request.outputPath
      ?: if (request.registryRoot == null) defaultOutputPath(repoRoot, bundleId) else null
    val contentHash = contentHash(request, bundleId, sources)
    val metadataWithPlaceholder = bundleMetadata(
      request,
      bundleId,
      contentHash,
      TeamBundleHashing.BUNDLE_CHECKSUM_PLACEHOLDER,
      sources,
    )
    teamBundleValidator.validate(metadataWithPlaceholder, "team-bundle-metadata", repoRoot)
    val metadataChecksum = bundleVerificationChecksum(metadataWithPlaceholder, sources, repoRoot)
    val embeddedMetadata = bundleMetadata(request, bundleId, contentHash, metadataChecksum, sources)
    teamBundleValidator.validate(embeddedMetadata, "team-bundle-metadata", repoRoot)
    val embeddedMetadataJson = JsonSupport.mapToJsonString(embeddedMetadata)
    val archive = gatewayCall { fileGateway.archiveBytes(embeddedMetadataJson, sources, repoRoot) }

    val directBundlePath = if (!request.dryRun) {
      outputPath?.toAbsolutePath()?.normalize()?.also {
        gatewayCall { fileGateway.writeDirectBundle(it, archive, embeddedMetadataJson, metadataChecksum) }
      }
    } else {
      null
    }
    val registryDestination = if (!request.dryRun) {
      request.registryRoot?.let {
        gatewayCall {
          fileGateway.publishRegistry(
            TeamExportRegistryPublishRequest(
              registryRoot = it.toAbsolutePath().normalize(),
              channel = request.channel.wireValue,
              version = request.version,
              bundleId = bundleId,
              archive = archive,
              metadataJson = embeddedMetadataJson,
              checksum = metadataChecksum,
              failAfterTempWrite = request.failAfterRegistryTempWrite,
            ),
          )
        }
      }
    } else {
      null
    }

    return TeamExportResult(
      bundlePath = directBundlePath ?: registryDestination?.path?.resolve("bundle.zip"),
      bundleId = bundleId,
      version = request.version,
      channel = request.channel,
      contentHash = contentHash,
      checksum = metadataChecksum,
      sourceRef = request.sourceRef,
      validationSummary = validation.toSummary(),
      registryDestination = registryDestination,
      sourceEntryHashes = sources.map { TeamExportSourceEntryHash(it.path, it.contentHash) },
    )
  }

  fun defaultCreatedAt(): String = clock.instant().toString()

  private fun collectSources(repoRoot: Path): List<TeamExportCollectedSource> {
    val sources = gatewayCall { fileGateway.collectSources(repoRoot) }
    val sourceMaps = sources.map { source ->
      mapOf("category" to source.category.wireValue, "path" to source.path, "content_hash" to source.contentHash)
    }
    teamBundleValidator.validate(
      minimalValidationBundle(sourceMaps),
      "team-bundle-source-collection",
      repoRoot,
    )
    return sources
  }

  private fun defaultOutputPath(repoRoot: Path, bundleId: String): Path =
    repoRoot.resolve("dist").resolve("$bundleId.zip")

  private fun bundleMetadata(
    request: TeamExportRequest,
    bundleId: String,
    contentHash: String,
    bundleChecksum: String,
    sources: List<TeamExportCollectedSource>,
  ): Map<String, Any?> = linkedMapOf(
    "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
    "bundle_id" to bundleId,
    "version" to request.version,
    "channel" to request.channel.wireValue,
    "created_at" to request.createdAt,
    "created_by" to request.createdBy,
    "source_repo" to request.sourceRepo,
    "source_ref" to request.sourceRef,
    "source_commit" to request.sourceCommit,
    "content_hash" to contentHash,
    "manifest_hashes" to manifestHashes(sources),
    "bundle_checksum" to bundleChecksum,
    "sources" to sources.map { source ->
      linkedMapOf(
        "category" to source.category.wireValue,
        "path" to source.path,
        "content_hash" to source.contentHash,
      )
    },
    "compatibility" to linkedMapOf(
      "min_skill_bill_version" to request.minSkillBillVersion,
      "shell_contract_version" to request.shellContractVersion,
      "platform_pack_contract_version" to request.platformPackContractVersion,
    ),
    "telemetry_defaults" to linkedMapOf(
      "enabled" to false,
      "level" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
    ),
    "privacy_defaults" to linkedMapOf(
      "telemetry" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
      "source_paths" to TeamBundlePrivacyLevel.ANONYMOUS.wireValue,
      "author_identity" to TeamBundlePrivacyLevel.OFF.wireValue,
    ),
    "exclusions" to linkedMapOf(
      "paths" to emptyList<String>(),
      "reasons" to emptyMap<String, String>(),
    ),
  )

  private fun manifestHashes(sources: List<TeamExportCollectedSource>): Map<String, String> {
    val manifests = sources.filter { it.path.endsWith("platform.yaml") || it.path.endsWith("content.md") }
    val selected = if (manifests.isNotEmpty()) manifests else sources
    return selected.sortedBy { it.path }.associate { it.path to it.contentHash }
  }

  private fun contentHash(
    request: TeamExportRequest,
    bundleId: String,
    sources: List<TeamExportCollectedSource>,
  ): String = TeamBundleHashing.contentHash(
    TeamBundleContentHashInput(
      bundleId = bundleId,
      version = request.version,
      channel = request.channel,
      createdAt = request.createdAt,
      createdBy = request.createdBy,
      sourceRepo = request.sourceRepo,
      sourceRef = request.sourceRef,
      sourceCommit = request.sourceCommit,
      sources = sources.map { source -> TeamBundleSourceHash(source.path, source.contentHash) },
    ),
  )

  private fun bundleVerificationChecksum(
    metadataWithPlaceholder: Map<String, Any?>,
    sources: List<TeamExportCollectedSource>,
    repoRoot: Path,
  ): String = TeamBundleHashing.sha256(
    gatewayCall {
      fileGateway.archiveBytes(JsonSupport.mapToJsonString(metadataWithPlaceholder), sources, repoRoot)
    },
  )

  private fun minimalValidationBundle(sources: List<Map<String, String>>): Map<String, Any?> = linkedMapOf(
    "contract_version" to TEAM_BUNDLE_CONTRACT_VERSION,
    "bundle_id" to "team-source-validation",
    "version" to "0.0.0",
    "channel" to TeamBundleChannel.EXPERIMENTAL.wireValue,
    "created_at" to "1970-01-01T00:00:00Z",
    "created_by" to "skill-bill",
    "source_repo" to "local",
    "source_ref" to "HEAD",
    "content_hash" to TeamBundleHashing.sha256(ByteArray(0)),
    "manifest_hashes" to mapOf("source_index" to TeamBundleHashing.sha256(ByteArray(0))),
    "bundle_checksum" to TeamBundleHashing.sha256(ByteArray(0)),
    "sources" to sources,
    "compatibility" to mapOf("min_skill_bill_version" to "0.1", "shell_contract_version" to "1.2"),
    "telemetry_defaults" to mapOf("enabled" to false, "level" to "anonymous"),
    "privacy_defaults" to mapOf("telemetry" to "anonymous", "source_paths" to "anonymous", "author_identity" to "off"),
    "exclusions" to mapOf("paths" to emptyList<String>(), "reasons" to emptyMap<String, String>()),
  )

  private fun RepoValidationReport.toSummary(): TeamExportValidationSummary = TeamExportValidationSummary(
    status = if (passed) "passed" else "failed",
    skillCount = skillCount,
    addonCount = addonCount,
    platformPackCount = platformPackCount,
    nativeAgentCount = nativeAgentCount,
  )

  private fun <T> gatewayCall(block: () -> T): T = try {
    block()
  } catch (error: TeamExportFileGatewayException) {
    throw TeamExportException(error.message.orEmpty(), error)
  }
}

class TeamExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
