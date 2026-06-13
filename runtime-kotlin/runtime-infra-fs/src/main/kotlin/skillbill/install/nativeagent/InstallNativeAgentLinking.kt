package skillbill.install.nativeagent

import skillbill.install.model.AgentTarget
import skillbill.install.support.createNewSymlinkWithGuidance
import skillbill.install.support.createReplacementSymlinkWithGuidance
import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path

private val generatedNativeAgentProviderDirs = NativeAgentProvider.entries
  .map { provider -> provider.directoryName }
  .toSet()

internal fun installNativeAgentFile(
  source: Path,
  agentTarget: AgentTarget,
  managedSourceRoots: List<Path>,
): InstallNativeAgentResult {
  val resolvedSource = source.toAbsolutePath().normalize()
  if (!Files.isRegularFile(resolvedSource)) {
    throw java.io.FileNotFoundException("Native agent file '$resolvedSource' does not exist.")
  }
  Files.createDirectories(agentTarget.path)
  val linkPath = agentTarget.path.resolve(resolvedSource.fileName)
  return applyInstallDecision(
    linkPath = linkPath,
    resolvedSource = resolvedSource,
    decision = decideInstallAction(linkPath, resolvedSource, managedSourceRoots),
  )
}

internal fun uninstallNativeAgentFiles(sources: List<Path>, candidateDirs: List<Path>): List<Path> {
  val removed = mutableListOf<Path>()
  sources.forEach { source ->
    val resolvedSource = source.toAbsolutePath().normalize()
    candidateDirs.forEach { targetDir ->
      val linkPath = targetDir.resolve(resolvedSource.fileName)
      val existingTarget = if (Files.isSymbolicLink(linkPath)) resolveSymlinkTarget(linkPath) else null
      if (existingTarget == resolvedSource) {
        Files.deleteIfExists(linkPath)
        removed.add(linkPath)
      }
    }
  }
  return removed
}

private fun applyInstallDecision(
  linkPath: Path,
  resolvedSource: Path,
  decision: InstallAction,
): InstallNativeAgentResult = when (decision) {
  InstallAction.Skip -> InstallNativeAgentResult.Skipped(
    linkPath,
    "existing non-managed file at $linkPath was preserved",
  )
  InstallAction.AlreadyLinked -> InstallNativeAgentResult.Skipped(linkPath, "already linked to $resolvedSource")
  InstallAction.Replace, InstallAction.Create -> {
    when (decision) {
      InstallAction.Replace -> createReplacementSymlinkWithGuidance(linkPath, resolvedSource)
      InstallAction.Create -> createNewSymlinkWithGuidance(linkPath, resolvedSource)
      InstallAction.Skip,
      InstallAction.AlreadyLinked,
      -> error("Unexpected native-agent install decision '$decision'.")
    }
    InstallNativeAgentResult.Linked(linkPath)
  }
}

private enum class InstallAction { Skip, AlreadyLinked, Replace, Create }

private fun decideInstallAction(linkPath: Path, resolvedSource: Path, managedSourceRoots: List<Path>): InstallAction {
  if (!Files.isSymbolicLink(linkPath)) {
    return if (Files.exists(linkPath)) InstallAction.Skip else InstallAction.Create
  }
  val existingTarget = resolveSymlinkTarget(linkPath)
  return when {
    existingTarget == resolvedSource -> InstallAction.AlreadyLinked
    existingTarget != null && managedSourceRoots.any { root -> existingTarget.startsWith(root) } ->
      InstallAction.Replace
    existingTarget != null && existingTarget.isLegacyGeneratedNativeAgentArtifact() -> InstallAction.Replace
    else -> InstallAction.Skip
  }
}

private fun Path.isLegacyGeneratedNativeAgentArtifact(): Boolean =
  parent?.fileName?.toString() in generatedNativeAgentProviderDirs

private fun resolveSymlinkTarget(linkPath: Path): Path? = runCatching {
  val rawTarget = Files.readSymbolicLink(linkPath)
  val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
  resolvedTarget.toAbsolutePath().normalize()
}.getOrNull()
