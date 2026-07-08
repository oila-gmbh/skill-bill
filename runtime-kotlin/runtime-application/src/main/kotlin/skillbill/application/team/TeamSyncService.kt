package skillbill.application.team

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.RepoValidationService
import skillbill.error.InvalidTeamBundleRegistryChannelError
import skillbill.error.MissingPreviousTeamBundleError
import skillbill.error.MissingPreviousTeamBundleSourceError
import skillbill.error.TeamBundleContentHashMismatchError
import skillbill.error.TeamBundleRollbackIncompleteError
import skillbill.error.TeamBundleSyncInstallFailedError
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallationTargetPaths
import skillbill.ports.team.TeamBundleArchiveGateway
import skillbill.ports.team.TeamBundleGatewayException
import skillbill.ports.team.TeamBundleRegistryResolver
import skillbill.ports.team.TeamBundleStatePersistence
import skillbill.ports.team.model.TeamBundleCandidate
import skillbill.ports.team.model.TeamBundleExtractionRequest
import skillbill.ports.team.model.TeamBundleStateReadRequest
import skillbill.ports.team.model.TeamBundleStateWriteRequest
import skillbill.ports.team.model.TeamRegistryResolveRequest
import skillbill.team.TeamBundleValidator
import skillbill.team.model.InstalledTeamBundleRecord
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamBundleContentHashInput
import skillbill.team.model.TeamBundleHashing
import skillbill.team.model.TeamBundleSourceHash
import skillbill.team.model.TeamBundleVerificationSummary
import skillbill.team.model.TeamRollbackRequest
import skillbill.team.model.TeamRollbackResult
import skillbill.team.model.TeamStatusRequest
import skillbill.team.model.TeamStatusResult
import skillbill.team.model.TeamSyncRequest
import skillbill.team.model.TeamSyncResult
import skillbill.team.model.TeamSyncSourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

@Inject
class TeamSyncService(
  private val archiveGateway: TeamBundleArchiveGateway,
  private val registryResolver: TeamBundleRegistryResolver,
  private val statePersistence: TeamBundleStatePersistence,
  private val teamBundleValidator: TeamBundleValidator,
  private val repoValidationService: RepoValidationService,
  private val installService: InstallService,
  private val clock: Clock,
) {
  fun sync(request: TeamSyncRequest): TeamSyncResult {
    val candidate = resolveCandidate(request)
    val previous = statePersistence.read(TeamBundleStateReadRequest(request.installRequest.home))
    return try {
      val installed = installCandidate(
        candidate = candidate,
        installRequest = request.installRequest,
        previous = previous,
        sourceKind = if (request.registryRoot == null) TeamSyncSourceKind.BUNDLE_FILE else TeamSyncSourceKind.LOCAL_REGISTRY,
      )
      TeamSyncResult(installed = installed.record, previous = previous, verification = installed.verification)
    } catch (error: Throwable) {
      restorePreviousAfterFailure(previous, request.installRequest, error)
    }
  }

  fun rollback(request: TeamRollbackRequest): TeamRollbackResult {
    val current = statePersistence.read(TeamBundleStateReadRequest(request.installRequest.home))
      ?: throw MissingPreviousTeamBundleError()
    val previous = current.previous ?: throw MissingPreviousTeamBundleError()
    if (!Files.isRegularFile(previous.archivePath)) {
      throw MissingPreviousTeamBundleSourceError(previous.archivePath.toString())
    }
    val restored = installCandidate(
      candidate = archiveGateway.readBundle(previous.archivePath),
      installRequest = request.installRequest,
      previous = current.copy(previous = current.previous?.previous),
      sourceKind = TeamSyncSourceKind.ROLLBACK,
    )
    return TeamRollbackResult(restored = restored.record, replaced = current, verification = restored.verification)
  }

  fun status(request: TeamStatusRequest): TeamStatusResult =
    TeamStatusResult(statePersistence.read(TeamBundleStateReadRequest(request.home)))

  private fun resolveCandidate(request: TeamSyncRequest): TeamBundleCandidate {
    val directPath = request.bundlePath
    val registryRoot = request.registryRoot
    val channel = request.channel
    return when {
      directPath != null -> archiveGateway.readBundle(directPath)
      registryRoot != null && channel != null -> {
        if (channel !in registryChannels) {
          throw InvalidTeamBundleRegistryChannelError(channel.wireValue)
        }
        registryResolver.resolveLatest(TeamRegistryResolveRequest(registryRoot, channel))
      }
      else -> throw IllegalArgumentException("Provide either a bundle path or --registry with --channel.")
    }
  }

  private fun installCandidate(
    candidate: TeamBundleCandidate,
    installRequest: InstallPlanRequest,
    previous: InstalledTeamBundleRecord?,
    sourceKind: TeamSyncSourceKind,
  ): InstalledCandidate {
    val candidateRoot = extractRoot(installRequest.home, candidate.bundle)
    val sourceRoot = archiveGateway.extractCandidate(
      TeamBundleExtractionRequest(candidate.archivePath, candidate.bundle, candidateRoot),
    )
    val validatedBundle = teamBundleValidator.validate(candidate.metadata, candidate.archivePath.toString(), sourceRoot)
    val verification = verifyExtractedSources(validatedBundle, sourceRoot)
    val validation = repoValidationService.validateRepo(sourceRoot)
    if (!validation.passed) {
      throw TeamBundleSyncInstallFailedError("Team bundle source validation failed: ${validation.issues.joinToString("; ")}")
    }
    val plan = installService.planInstall(installRequest.forCandidateSource(sourceRoot))
    val applyResult = installService.applyInstall(plan)
    if (applyResult.status == InstallApplyStatus.FAILURE) {
      throw TeamBundleSyncInstallFailedError(
        "Team bundle install failed: ${applyResult.failures.joinToString("; ") { issue -> issue.message }}",
      )
    }
    val cachedArchive = archiveGateway.cacheBundle(
      source = candidate.archivePath,
      home = installRequest.home,
      bundleId = validatedBundle.metadata.bundleId,
      checksum = validatedBundle.hashes.bundleChecksum,
    )
    val record = InstalledTeamBundleRecord(
      bundleId = validatedBundle.metadata.bundleId,
      version = validatedBundle.metadata.version,
      channel = validatedBundle.metadata.channel,
      contentHash = validatedBundle.hashes.contentHash,
      checksum = validatedBundle.hashes.bundleChecksum,
      sourceRef = validatedBundle.metadata.sourceRef,
      sourceRepo = validatedBundle.metadata.sourceRepo,
      sourceCommit = validatedBundle.metadata.sourceCommit,
      installedAt = clock.instant().toString(),
      archivePath = cachedArchive,
      sourceKind = sourceKind,
      previous = previous,
    )
    statePersistence.write(TeamBundleStateWriteRequest(installRequest.home, record))
    return InstalledCandidate(record, verification)
  }

  private fun restorePreviousAfterFailure(
    previous: InstalledTeamBundleRecord?,
    installRequest: InstallPlanRequest,
    original: Throwable,
  ): TeamSyncResult {
    if (previous == null) {
      throw original
    }
    val restored = try {
      installCandidate(
        candidate = archiveGateway.readBundle(previous.archivePath),
        installRequest = installRequest,
        previous = previous.previous,
        sourceKind = TeamSyncSourceKind.ROLLBACK,
      )
    } catch (restoreError: Throwable) {
      throw TeamBundleRollbackIncompleteError(
        "Team bundle sync failed and rollback to ${previous.bundleId} ${previous.version} was incomplete: " +
          restoreError.message.orEmpty(),
        restoreError,
      )
    }
    throw TeamBundleSyncInstallFailedError(
      "Team bundle sync failed; restored previous bundle ${restored.record.bundleId} " +
        "${restored.record.version} (${restored.record.channel.wireValue}). Original failure: ${original.message.orEmpty()}",
      original,
    )
  }

  private fun verifyExtractedSources(bundle: TeamBundle, sourceRoot: Path): TeamBundleVerificationSummary {
    bundle.sources.forEach { source ->
      val path = sourceRoot.resolve(source.path)
      val actual = TeamBundleHashing.sha256(Files.readAllBytes(path))
      if (actual != source.contentHash) {
        throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, source.contentHash, actual)
      }
    }
    bundle.hashes.manifestHashes.forEach { (path, expected) ->
      val actual = TeamBundleHashing.sha256(Files.readAllBytes(sourceRoot.resolve(path)))
      if (actual != expected) {
        throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, expected, actual)
      }
    }
    val computedContentHash = TeamBundleHashing.contentHash(
      TeamBundleContentHashInput(
        bundleId = bundle.metadata.bundleId,
        version = bundle.metadata.version,
        channel = bundle.metadata.channel,
        createdAt = bundle.metadata.createdAt,
        createdBy = bundle.metadata.createdBy,
        sourceRepo = bundle.metadata.sourceRepo,
        sourceRef = bundle.metadata.sourceRef,
        sourceCommit = bundle.metadata.sourceCommit,
        sources = bundle.sources.map { source -> TeamBundleSourceHash(source.path, source.contentHash) },
      ),
    )
    if (computedContentHash != bundle.hashes.contentHash) {
      throw TeamBundleContentHashMismatchError(bundle.metadata.bundleId, bundle.hashes.contentHash, computedContentHash)
    }
    return TeamBundleVerificationSummary(
      checksum = bundle.hashes.bundleChecksum,
      contentHash = bundle.hashes.contentHash,
      sourceCount = bundle.sources.size,
      manifestCount = bundle.hashes.manifestHashes.size,
      sourceRoot = sourceRoot,
    )
  }

  private fun extractRoot(home: Path, bundle: TeamBundle): Path =
    home.toAbsolutePath().normalize()
      .resolve(".skill-bill/team-sync/candidates")
      .resolve("${bundle.metadata.bundleId}-${bundle.hashes.contentHash.removePrefix("sha256:").take(16)}")

  private fun InstallPlanRequest.forCandidateSource(sourceRoot: Path): InstallPlanRequest = copy(
    repoRoot = sourceRoot,
    targetPaths = InstallationTargetPaths(
      skillsRoot = sourceRoot.resolve("skills"),
      platformPacksRoot = sourceRoot.resolve("platform-packs"),
      agentTargets = targetPaths.agentTargets,
    ),
  )

  private data class InstalledCandidate(
    val record: InstalledTeamBundleRecord,
    val verification: TeamBundleVerificationSummary,
  )

  private val registryChannels = setOf(
    TeamBundleChannel.DEVELOPMENT,
    TeamBundleChannel.BETA,
    TeamBundleChannel.STABLE,
  )
}
