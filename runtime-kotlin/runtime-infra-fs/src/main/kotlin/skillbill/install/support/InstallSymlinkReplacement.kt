package skillbill.install.support

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.install.InstallSymlinkReplacement")

internal fun createReplacementSymlinkWithGuidance(linkPath: Path, linkTarget: Path) {
  createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = true)
}

internal fun createNewSymlinkWithGuidance(linkPath: Path, linkTarget: Path) {
  createManagedSymlinkWithGuidance(linkPath, linkTarget, replaceExisting = false)
}

private fun createManagedSymlinkWithGuidance(linkPath: Path, linkTarget: Path, replaceExisting: Boolean) {
  val tempLink = linkPath.parent.resolve(".${linkPath.fileName}.tmp-${UUID.randomUUID()}").normalize()
  val oldTarget = if (replaceExisting) readSymlinkTargetOrNull(linkPath) else null
  try {
    createSymbolicLinkWithGuidance(tempLink, linkTarget)
    if (replaceExisting) {
      Files.deleteIfExists(linkPath)
    }
    moveManagedLink(tempLink, linkPath)
  } catch (error: IOException) {
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath)
    throw error
  } catch (error: UnsupportedOperationException) {
    restoreOriginalLinkIfNeeded(replaceExisting, oldTarget, linkPath)
    throw error
  } finally {
    runCatching { Files.deleteIfExists(tempLink) }
  }
}

private fun restoreOriginalLinkIfNeeded(replaceExisting: Boolean, oldTarget: Path?, linkPath: Path) {
  if (replaceExisting && oldTarget != null && !Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    runCatching { createSymbolicLinkWithGuidance(linkPath, oldTarget) }
  }
}

private fun createSymbolicLinkWithGuidance(linkPath: Path, linkTarget: Path) {
  try {
    Files.createSymbolicLink(linkPath, linkTarget)
  } catch (error: UnsupportedOperationException) {
    throw symbolicLinkFailure(linkPath, error)
  } catch (error: FileSystemException) {
    throw symbolicLinkFailure(linkPath, error)
  }
}

private fun moveManagedLink(tempLink: Path, linkPath: Path) {
  if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    throw FileAlreadyExistsException(linkPath.toString())
  }
  try {
    Files.move(tempLink, linkPath, StandardCopyOption.ATOMIC_MOVE)
  } catch (error: AtomicMoveNotSupportedException) {
    log.log(Level.FINE, "Atomic symlink replacement move is unsupported; falling back to regular move.", error)
    Files.move(tempLink, linkPath)
  }
}

private fun readSymlinkTargetOrNull(linkPath: Path): Path? = runCatching {
  val rawTarget = Files.readSymbolicLink(linkPath)
  if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget).toAbsolutePath().normalize()
}.getOrNull()
