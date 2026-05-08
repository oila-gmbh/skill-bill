package skillbill.install

import skillbill.install.model.AgentTarget
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

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
    decision = decideInstallAction(linkPath, resolvedSource, managedSourceRoots, agentTarget.name),
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
    if (decision == InstallAction.Replace) {
      Files.deleteIfExists(linkPath)
    }
    createSymbolicLinkOrFail(linkPath, resolvedSource)
    InstallNativeAgentResult.Linked(linkPath)
  }
}

private fun createSymbolicLinkOrFail(linkPath: Path, resolvedSource: Path) {
  try {
    Files.createSymbolicLink(linkPath, resolvedSource)
  } catch (error: UnsupportedOperationException) {
    throw symbolicLinkFailure(linkPath, error)
  } catch (error: FileSystemException) {
    throw symbolicLinkFailure(linkPath, error)
  }
}

private fun symbolicLinkFailure(linkPath: Path, cause: Exception): RuntimeException = IllegalStateException(
  "Failed to create native agent symlink at $linkPath. " +
    "On Windows, enable Developer Mode (Settings -> Privacy & security -> For developers) " +
    "or run the install command from an elevated shell so the JVM can create symlinks.",
  cause,
)

private enum class InstallAction { Skip, AlreadyLinked, Replace, Create }

private fun decideInstallAction(
  linkPath: Path,
  resolvedSource: Path,
  managedSourceRoots: List<Path>,
  agentKind: String,
): InstallAction {
  if (!Files.isSymbolicLink(linkPath)) {
    return if (Files.exists(linkPath)) InstallAction.Skip else InstallAction.Create
  }
  val existingTarget = resolveSymlinkTarget(linkPath)
  return when {
    existingTarget == resolvedSource -> InstallAction.AlreadyLinked
    existingTarget != null && managedSourceRoots.any { root -> existingTarget.startsWith(root) } ->
      InstallAction.Replace
    existingTarget != null && isReplaceableManagedNativeAgentSymlink(
      existingTarget,
      resolvedSource,
      agentKind,
    ) -> InstallAction.Replace
    else -> InstallAction.Skip
  }
}

private fun isReplaceableManagedNativeAgentSymlink(existingTarget: Path, source: Path, agentKind: String): Boolean {
  val parts = existingTarget.map { part -> part.toString() }.toSet()
  val pointsAtSkillBillCache = ".skill-bill" in parts && "native-agents" in parts
  val pointsAtLegacyRepoArtifact = "skills" in parts || "platform-packs" in parts
  return existingTarget.fileName == source.fileName &&
    source.fileName.toString().startsWith("bill-") &&
    (
      pointsAtSkillBillCache ||
        existingTarget.parent?.fileName?.toString() == agentKind && pointsAtLegacyRepoArtifact
      )
}

private fun resolveSymlinkTarget(linkPath: Path): Path? = runCatching {
  val rawTarget = Files.readSymbolicLink(linkPath)
  val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
  resolvedTarget.toAbsolutePath().normalize()
}.getOrNull()
