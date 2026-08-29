package skillbill.install.nativeagent

import skillbill.install.model.AgentTarget
import skillbill.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.nativeagent.rendering.NativeAgentInstallRenderResult
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path

internal fun linkProviderAgentsBody(args: NativeAgentLinkProviderBodyArgs): NativeAgentLinkOutcome {
  val generated = NativeAgentOperations.renderInstallArtifacts(
    NativeAgentInstallRenderRequest(
      platformPacksRoot = args.request.platformPacksRoot,
      skillsRoot = args.request.skillsRoot,
      selectedPlatforms = args.request.selectedPlatforms,
      provider = args.provider,
      home = args.resolvedHome,
      overrides = NativeAgentInstallRenderOverrides(
        cacheRoot = args.request.overrides.installCacheRoot,
        sourceRoots = args.request.overrides.sourceRoots,
        beforeMutation = args.journal::beforeMutation,
        afterTemporaryCreation = args.journal::afterTemporaryCreation,
      ),
    ),
  )
  val managedRoots = listOfNotNull(generated.cacheRoot, args.request.overrides.legacyManagedRoot)
  publishInstalledReviewCatalog(
    args.request.platformPacksRoot,
    args.request.selectedPlatforms,
    generated.cacheRoot,
    args.journal,
  )
  val linkResults = linkGeneratedNativeAgentFiles(args, generated, managedRoots)
  val desired = desiredNativeAgentInventory(
    provider = args.provider,
    targets = args.targets,
    generated = generated,
    linked = linkResults.linked,
    validationRoot = args.validationRoot,
  )
  desired.forEach(::verifyInstalledNativeAgent)
  NativeAgentLinkInventory.reconcile(
    home = args.resolvedHome,
    provider = args.provider.name.lowercase(),
    desired = desired,
    managedRoots = managedRoots,
    sourceRoot = args.validationRoot,
    beforeMutation = args.journal::beforeMutation,
    afterTemporaryCreation = args.journal::afterTemporaryCreation,
  )
  return NativeAgentLinkOutcome(linkResults.linked, linkResults.skipped)
}

private data class NativeAgentFileLinkResults(val linked: List<Path>, val skipped: List<NativeAgentSkippedLink>)

private fun linkGeneratedNativeAgentFiles(
  args: NativeAgentLinkProviderBodyArgs,
  generated: NativeAgentInstallRenderResult,
  managedRoots: List<Path>,
): NativeAgentFileLinkResults {
  val linked = mutableListOf<Path>()
  val skipped = mutableListOf<NativeAgentSkippedLink>()
  val artifactsByPath = generated.artifacts.associateBy { it.path }
  args.targets.forEach { target ->
    generated.generatedFiles.forEach { file ->
      when (
        val result = installNativeAgentFile(
          file,
          target,
          managedSourceRoots = managedRoots,
          ownership = NativeAgentLinkOwnership(
            args.resolvedHome,
            args.provider,
            requireNotNull(artifactsByPath[file]).logicalName,
          ),
          beforeMutation = args.journal::beforeMutation,
        )
      ) {
        is InstallNativeAgentResult.Linked -> linked.add(result.link)
        is InstallNativeAgentResult.Skipped -> skipped.add(NativeAgentSkippedLink(result.link, result.reason))
      }
    }
  }
  return NativeAgentFileLinkResults(linked, skipped)
}

internal fun desiredNativeAgentInventory(
  provider: NativeAgentProvider,
  targets: List<AgentTarget>,
  generated: NativeAgentInstallRenderResult,
  linked: List<Path>,
  validationRoot: Path,
): List<NativeAgentLinkInventoryEntry> {
  val linkedPaths = linked.toSet()
  return generated.artifacts.flatMap { artifact ->
    targets.mapNotNull { target ->
      val agentDir = target.path
      val installedPath = agentDir.resolve(artifact.path.fileName)
      val isOurs = installedPath in linkedPaths ||
        (Files.isSymbolicLink(installedPath) && resolveSymlinkTarget(installedPath) == artifact.path)
      if (!isOurs) return@mapNotNull null
      NativeAgentLinkInventoryEntry(
        logicalName = artifact.logicalName,
        provider = provider.name.lowercase(),
        installedPath = installedPath,
        cacheTargetPath = artifact.path,
        contentDigest = artifact.contentDigest,
        sourceRoot = validationRoot,
      )
    }
  }
}

internal fun linkProviderAgentsWithJournal(
  journal: ProviderMutationJournal,
  block: () -> NativeAgentLinkOutcome,
): NativeAgentLinkOutcome = runCatching(block).onFailure { error ->
  journal.restore().forEach { suppressed -> error.addSuppressed(suppressed) }
}.getOrThrow()
