package skillbill.nativeagent.rendering

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class RenderedAgent(val targetName: String, val contents: ByteArray) {
  override fun equals(other: Any?): Boolean = this === other
  override fun hashCode(): Int = System.identityHashCode(this)
}

internal fun sha256Digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
  .digest(bytes)
  .joinToString("") { byte -> "%02x".format(byte) }

internal fun listOrphanRenderCandidates(providerRoot: Path, rendered: List<RenderedAgent>): List<Path> =
  Files.list(providerRoot).use { stream ->
    stream.filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.fileName.toString() !in rendered.map { it.targetName }.toSet() }
      .toList()
  }

internal fun buildNativeAgentInstallRenderResult(
  generated: List<Path>,
  provider: NativeAgentProvider,
  cacheRoot: Path,
): NativeAgentInstallRenderResult = NativeAgentInstallRenderResult(
  generatedFiles = generated.sortedBy { it.toString() },
  artifacts = generated.map { path ->
    NativeAgentRenderedArtifact(
      logicalName = path.fileName.toString().removeSuffix(".${provider.extension}"),
      path = path,
      contentDigest = sha256Digest(Files.readAllBytes(path)),
    )
  }.sortedBy { it.path.toString() },
  cacheRoot = cacheRoot,
)

internal fun stageAndPromoteNativeAgentRenders(
  request: NativeAgentRenderPromotionRequest,
): NativeAgentInstallRenderResult {
  request.rendered.forEach { entry ->
    Files.write(request.staging.resolve(entry.targetName), entry.contents)
  }
  val generated = promoteStagedRenders(
    request.providerRoot,
    request.staging,
    request.rendered,
    request.orphanCandidates,
    request.beforeMutation,
  )
  return buildNativeAgentInstallRenderResult(generated, request.provider, request.cacheRoot)
}

internal fun promoteStagedRenders(
  providerRoot: Path,
  staging: Path,
  rendered: List<RenderedAgent>,
  orphanCandidates: List<Path>,
  beforeMutation: (Path) -> Unit,
): List<Path> {
  Files.createDirectories(providerRoot)
  pruneOrphanArtifacts(orphanCandidates, beforeMutation)
  return rendered.map { entry ->
    val target = providerRoot.resolve(entry.targetName)
    val source = staging.resolve(entry.targetName)
    beforeMutation(target)
    try {
      Files.move(
        source,
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        source,
        target,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
    target
  }
}

internal fun pruneOrphanArtifacts(orphanCandidates: List<Path>, beforeMutation: (Path) -> Unit) {
  orphanCandidates.forEach { path ->
    beforeMutation(path)
    Files.deleteIfExists(path)
  }
}

internal fun deleteNativeAgentRenderStaging(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
