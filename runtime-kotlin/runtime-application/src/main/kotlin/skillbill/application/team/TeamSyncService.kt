package skillbill.application.team

import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.RepoValidationService
import skillbill.error.InvalidTeamBundleRegistryChannelError
import skillbill.error.MissingPreviousTeamBundleError
import skillbill.error.TeamBundleRollbackIncompleteError
import skillbill.error.TeamBundleSyncInstallFailedError
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallationTargetPaths
import skillbill.ports.team.TeamBundleArchiveGateway
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
import skillbill.team.model.TeamBundleVerificationSummary
import skillbill.team.model.TeamRollbackRequest
import skillbill.team.model.TeamRollbackResult
import skillbill.team.model.TeamStatusRequest
import skillbill.team.model.TeamStatusResult
import skillbill.team.model.TeamSyncRequest
import skillbill.team.model.TeamSyncResult
import skillbill.team.model.TeamSyncSourceKind
import java.nio.file.Path
import java.time.Clock

@Inject
class TeamSyncService(
  private val dependencies: TeamSyncServiceDependencies,
  private val clock: Clock,
) {
  private val archiveGateway = dependencies.archiveGateway
  private val registryResolver = dependencies.registryResolver
  private val statePersistence = dependencies.statePersistence
  private val teamBundleValidator = dependencies.teamBundleValidator
  private val repoValidationService = dependencies.repoValidationService
  private val installService = dependencies.installService

  fun sync(request: TeamSyncRequest): TeamSyncResult {
    val candidate = resolveCandidate(request)
    val previous = statePersistence.read(TeamBundleStateReadRequest(request.installRequest.home))
    return try {
      val installed = installCandidate(
        candidate = candidate,
        installRequest = request.installRequest,
        previous = previous,
        sourceKind = request.sourceKind(),
      )
      TeamSyncResult(installed = installed.record, previous = previous, verification = installed.verification)
    } catch (error: TeamBundlePostMutationInstallFailure) {
      restorePreviousAfterFailure(previous, request.installRequest, error.cause ?: error)
    }
  }

  fun rollback(request: TeamRollbackRequest): TeamRollbackResult {
    val current = statePersistence.read(TeamBundleStateReadRequest(request.installRequest.home))
      ?: missingPreviousTeamBundle()
    val previous = current.previous ?: missingPreviousTeamBundle()
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
    val verification = archiveGateway.verifyExtractedSources(validatedBundle, sourceRoot)
    val validation = repoValidationService.validateRepo(sourceRoot)
    if (!validation.passed) {
      failSourceValidation(validation.issues)
    }
    val plan = installService.planInstall(installRequest.forCandidateSource(sourceRoot))
    val applyResult = runCatching {
      installService.applyInstall(plan)
    }.getOrElse(::postMutationFailure)
    if (applyResult.status == InstallApplyStatus.FAILURE) {
      postMutationApplyFailure(applyResult.failures.joinToString("; ") { issue -> issue.message })
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
      rollbackIncompleteWithoutPrevious(original)
    }
    val restored = runCatching {
      installCandidate(
        candidate = archiveGateway.readBundle(previous.archivePath),
        installRequest = installRequest,
        previous = previous.previous,
        sourceKind = TeamSyncSourceKind.ROLLBACK,
      )
    }.getOrElse { restoreError -> rollbackIncomplete(previous, restoreError) }
    syncFailedAfterRollback(restored, original)
  }

  private fun syncFailedAfterRollback(restored: InstalledCandidate, original: Throwable): Nothing =
    throw TeamBundleSyncInstallFailedError(
      "Team bundle sync failed; restored previous bundle ${restored.record.bundleId} " +
        "${restored.record.version} (${restored.record.channel.wireValue}). " +
        "Original failure: ${original.message.orEmpty()}",
      original,
    )

  private fun extractRoot(home: Path, bundle: TeamBundle): Path = home.toAbsolutePath().normalize()
    .resolve(".skill-bill/team-sync/candidates")
    .resolve(candidateDirectoryName(bundle))

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

@Inject
class TeamSyncServiceDependencies(
  val archiveGateway: TeamBundleArchiveGateway,
  val registryResolver: TeamBundleRegistryResolver,
  val statePersistence: TeamBundleStatePersistence,
  val teamBundleValidator: TeamBundleValidator,
  val repoValidationService: RepoValidationService,
  val installService: InstallService,
)

private const val SHORT_HASH_LENGTH = 16

private fun TeamSyncRequest.sourceKind(): TeamSyncSourceKind =
  if (registryRoot == null) TeamSyncSourceKind.BUNDLE_FILE else TeamSyncSourceKind.LOCAL_REGISTRY

private fun candidateDirectoryName(bundle: TeamBundle): String =
  "${bundle.metadata.bundleId}-${bundle.hashes.contentHash.removePrefix("sha256:").take(SHORT_HASH_LENGTH)}"

private fun missingPreviousTeamBundle(): Nothing = throw MissingPreviousTeamBundleError()

private fun failSourceValidation(issues: List<String>): Nothing =
  throw TeamBundleSyncInstallFailedError("Team bundle source validation failed: ${issues.joinToString("; ")}")

private fun postMutationFailure(error: Throwable): Nothing = throw TeamBundlePostMutationInstallFailure(error)

private fun postMutationApplyFailure(failures: String): Nothing =
  postMutationFailure(TeamBundleSyncInstallFailedError("Team bundle install failed: $failures"))

private fun rollbackIncompleteWithoutPrevious(original: Throwable): Nothing = throw TeamBundleRollbackIncompleteError(
  "Team bundle sync failed after install mutation began and no previous team bundle state was available " +
    "to restore: " +
    original.message.orEmpty(),
  original,
)

private fun rollbackIncomplete(previous: InstalledTeamBundleRecord, restoreError: Throwable): Nothing =
  throw TeamBundleRollbackIncompleteError(
    "Team bundle sync failed and rollback to ${previous.bundleId} ${previous.version} was incomplete: " +
      restoreError.message.orEmpty(),
    restoreError,
  )

private class TeamBundlePostMutationInstallFailure(cause: Throwable) : RuntimeException(cause.message, cause)
