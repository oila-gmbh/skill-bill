package skillbill.managedskill

import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal class PathRecordDirectory(
  private val directory: Path,
) : RecordDirectory {
  private val identity = recordDirectoryAttributes(directory)

  override fun attributes(name: Path): BasicFileAttributes = checked {
    Files.readAttributes(resolve(name), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  }

  override fun newByteChannel(name: Path, options: Set<java.nio.file.OpenOption>): SeekableByteChannel = checked {
    Files.newByteChannel(resolve(name), options + NOFOLLOW_LINKS)
  }

  override fun atomicMove(source: Path, target: Path) = checked {
    Files.move(resolve(source), resolve(target), ATOMIC_MOVE, REPLACE_EXISTING)
    Unit
  }

  override fun deleteFile(name: Path) = checked { Files.delete(resolve(name)) }

  private fun resolve(name: Path): Path {
    if (name.isAbsolute || name.nameCount != 1) {
      invalidManagedRecord(name, "managed record entry escapes its directory")
    }
    if (name.normalize() != name || name.toString() == "." || name.toString() == "..") {
      invalidManagedRecord(name, "managed record entry escapes its directory")
    }
    return directory.resolve(name)
  }

  private inline fun <T> checked(operation: () -> T): T {
    requireIdentity()
    return operation().also { requireIdentity() }
  }

  private fun requireIdentity() {
    if (!hasSameFileIdentity(identity, recordDirectoryAttributes(directory))) {
      invalidManagedRecord(directory, "managed record directory identity changed")
    }
  }
}

internal fun recordDirectoryAttributes(directory: Path): BasicFileAttributes =
  Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also { attributes ->
    if (!attributes.isDirectory || attributes.isSymbolicLink) {
      invalidManagedRecord(directory, "managed record directory is not a real directory")
    }
  }

internal fun hasSameFileIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean {
  val beforeKey = before.fileKey() ?: return true
  val afterKey = after.fileKey() ?: return true
  return beforeKey == afterKey
}
