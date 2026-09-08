package skillbill.install.nativeagent

import skillbill.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path

internal object NativeAgentLinkInventoryBootstrap {
  data class BootstrapPlan(
    val retain: List<NativeAgentLinkInventoryEntry>,
    val remove: List<NativeAgentLinkInventoryEntry>,
  )

  fun bootstrap(home: Path, managedRoots: List<Path>, sourceRoot: Path): BootstrapPlan {
    val retain = mutableListOf<NativeAgentLinkInventoryEntry>()
    val remove = mutableListOf<NativeAgentLinkInventoryEntry>()
    NativeAgentProvider.entries.flatMap { provider ->
      provider.homeAgentDirs(home).flatMap { directory ->
        if (!Files.isDirectory(directory)) return@flatMap emptyList()
        Files.list(directory).use { paths ->
          paths.iterator().asSequence().filter(Files::isSymbolicLink).mapNotNull { link: Path ->
            val raw = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return@mapNotNull null
            val resolved = link.parent.resolve(raw).toAbsolutePath().normalize()
            val logicalName = link.fileName.toString().removeSuffix(".${provider.extension}")
            if (
              !NativeAgentLinkInventoryLimits.LOGICAL_NAME.matches(logicalName) ||
              link.fileName.toString() != provider.fileName(logicalName)
            ) {
              return@mapNotNull null
            }
            if (!isCanonicalNativeAgentArtifactTarget(home, provider, logicalName, resolved, managedRoots)) {
              return@mapNotNull null
            }
            val entry = NativeAgentLinkInventoryEntry(
              logicalName,
              provider.name.lowercase(),
              link.toAbsolutePath().normalize(),
              resolved,
              contentDigest = if (Files.isRegularFile(resolved) && Files.isReadable(resolved)) {
                runCatching { NativeAgentLinkInventoryPaths.sha256(Files.readAllBytes(resolved)) }
                  .getOrDefault(NativeAgentLinkInventoryLimits.EMPTY_DIGEST)
              } else {
                NativeAgentLinkInventoryLimits.EMPTY_DIGEST
              },
              sourceRoot = sourceRoot.toAbsolutePath().normalize(),
            )
            if (NativeAgentLinkInventoryDecode.isSemanticallyValid(entry, home, managedRoots)) {
              retain += entry
            } else {
              remove += entry
            }
            entry
          }.toList()
        }
      }
    }
    return BootstrapPlan(retain, remove)
  }

  fun removeIfStillManaged(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
  ) {
    val link = entry.installedPath
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    if (link.fileName.toString() != provider.fileName(entry.logicalName)) return
    if (!Files.isSymbolicLink(link)) return
    val rawTarget = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots)) {
      beforeMutation(link)
      Files.deleteIfExists(link)
    }
  }
}
