package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class PointerRegenerationResult(
  val regeneratedFiles: List<Path>,
)

private class PointerRegenerationContext(
  val repoRoot: Path,
  val originalBytes: MutableMap<Path, ByteArray>?,
  val createdPaths: MutableList<Path>?,
  val written: MutableList<Path>,
)

object PointerOperations {
  fun regenerate(
    repoRoot: Path,
    originalBytes: MutableMap<Path, ByteArray>? = null,
    createdPaths: MutableList<Path>? = null,
  ): PointerRegenerationResult {
    val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
    val packsRoot = resolvedRepoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      return PointerRegenerationResult(emptyList())
    }
    val context = PointerRegenerationContext(
      repoRoot = resolvedRepoRoot,
      originalBytes = originalBytes,
      createdPaths = createdPaths,
      written = mutableListOf(),
    )
    discoverPlatformPackManifests(packsRoot).forEach { pack ->
      // F-013: refuse to render against a pack whose contract version disagrees with the shell
      // so we never silently emit pointers from a future schema. We deliberately check only the
      // contract version here (not the full validatePlatformPack), because pointer regeneration
      // does not require governed-skill content to exist — those checks live in validateRepo.
      requireMatchingContractVersion(pack)
      regeneratePackPointers(context, pack)
    }
    return PointerRegenerationResult(context.written.sortedBy { it.toString() })
  }
}

private fun regeneratePackPointers(context: PointerRegenerationContext, pack: PlatformManifest) {
  val sortedPointers = pack.pointers.sortedWith(
    compareBy({ it.skillRelativeDir }, { it.name }),
  )
  sortedPointers.forEach { spec ->
    writePointerIfChanged(context, pack.packRoot, spec)
  }
}

private fun writePointerIfChanged(context: PointerRegenerationContext, packRoot: Path, spec: PointerSpec) {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val pointerFile = resolvedPackRoot.resolve(spec.skillRelativeDir).resolve(spec.name).normalize()
  require(pointerFile.startsWith(resolvedPackRoot)) {
    "Pointer '${spec.name}' under '${spec.skillRelativeDir}' resolves outside packRoot '$resolvedPackRoot'."
  }
  val rendered = renderPointer(context.repoRoot, packRoot, spec).toByteArray(Charsets.UTF_8)
  val existed = Files.exists(pointerFile)
  val current = if (existed) Files.readAllBytes(pointerFile) else null
  if (current != null && current.contentEquals(rendered)) {
    return
  }
  Files.createDirectories(pointerFile.parent)
  if (existed && context.originalBytes != null && pointerFile !in context.originalBytes) {
    context.originalBytes[pointerFile] = current ?: ByteArray(0)
  }
  atomicWrite(pointerFile, rendered)
  if (!existed) {
    context.createdPaths?.add(pointerFile)
  }
  context.written.add(pointerFile)
}

private fun requireMatchingContractVersion(pack: PlatformManifest) {
  if (pack.contractVersion != SHELL_CONTRACT_VERSION) {
    throw ContractVersionMismatchError(
      "Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }
}

private fun atomicWrite(target: Path, bytes: ByteArray) {
  // Mirror the staged-then-moved pattern used by NativeAgentOperations.promoteStagedRenders so
  // an interrupted write never leaves a half-written pointer on disk.
  val tmp = Files.createTempFile(target.parent, target.fileName.toString() + ".", ".tmp")
  try {
    Files.write(tmp, bytes)
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      // Some filesystems (notably tmpfs/overlayfs in CI) refuse ATOMIC_MOVE; fall back to a
      // best-effort replace which is still safer than a non-staged write.
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(tmp)
  }
}
