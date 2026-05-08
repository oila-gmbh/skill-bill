package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
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
  val rendered = renderPointer(context.repoRoot, packRoot, spec)
  // Use NOFOLLOW_LINKS for existence so a symlink (even a dangling one) counts as "exists" and we
  // never accidentally write through it onto its target file.
  val existed = Files.exists(pointerFile, LinkOption.NOFOLLOW_LINKS)
  val isSymlink = Files.isSymbolicLink(pointerFile)
  val currentContent: String? = when {
    !existed -> null
    isSymlink -> Files.readSymbolicLink(pointerFile).toString().replace(File.separatorChar, '/')
    else -> Files.readString(pointerFile).trimEnd('\n', '\r')
  }
  if (currentContent == rendered) {
    return
  }
  Files.createDirectories(pointerFile.parent)
  if (existed && context.originalBytes != null && pointerFile !in context.originalBytes) {
    // Capture the pre-existing form for rollback. For symlinks we record the target string as
    // bytes (rollback restores it via the same symlink-or-text fallback writer); for regular
    // files we record the raw bytes verbatim.
    val originalBytesForRollback = if (isSymlink) {
      currentContent.orEmpty().toByteArray(Charsets.UTF_8)
    } else {
      Files.readAllBytes(pointerFile)
    }
    context.originalBytes[pointerFile] = originalBytesForRollback
  }
  writePointerArtifact(pointerFile, rendered, existed, isSymlink)
  if (!existed) {
    context.createdPaths?.add(pointerFile)
  }
  context.written.add(pointerFile)
}

/**
 * Materializes the pointer at [pointerFile] with content [rendered]. Prefers a real symbolic
 * link (matching the canonical form checked into git on Linux/macOS), and falls back to writing
 * the content as a regular text file when the platform/filesystem refuses symbolic links
 * (notably Windows without Developer Mode). When falling back to regular-file writes we still
 * use the staged-tmp + ATOMIC_MOVE pattern so an interrupted write never leaves a half-written
 * pointer on disk; the symlink path uses delete-then-create because [Files.move] does not apply
 * to symbolic links and symlink writes are already atomic enough for our purposes.
 */
private fun writePointerArtifact(pointerFile: Path, rendered: String, existed: Boolean, wasSymlink: Boolean) {
  if (existed) {
    // Always remove the existing artifact before re-creating; this both avoids "file already
    // exists" errors when switching between symlink/regular forms and prevents writing through
    // an existing symlink to its target file.
    if (wasSymlink) {
      Files.delete(pointerFile)
    } else {
      Files.deleteIfExists(pointerFile)
    }
  }
  try {
    Files.createSymbolicLink(pointerFile, Path.of(rendered))
  } catch (_: FileSystemException) {
    // Symlinks unsupported on this filesystem (e.g. Windows without Developer Mode). Fall back
    // to writing the rendered target as the file's content, which is the form git checks out
    // on the same platforms when core.symlinks=false.
    atomicWrite(pointerFile, rendered.toByteArray(Charsets.UTF_8))
  } catch (_: UnsupportedOperationException) {
    atomicWrite(pointerFile, rendered.toByteArray(Charsets.UTF_8))
  }
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
