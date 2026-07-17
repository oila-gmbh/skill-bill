package skillbill.managedskill

import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

internal class PathBundleDirectory(
  private val path: Path,
  private val identity: BasicFileAttributes,
) : BundleDirectory {
  override fun names(): List<Path> = checked {
    Files.newDirectoryStream(path).use { entries -> entries.map { it.fileName } }
  }

  override fun attributes(name: Path): BasicFileAttributes = checked {
    Files.readAttributes(resolve(name), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  }

  override fun newByteChannel(name: Path): SeekableByteChannel = checked {
    val options = if (path.fileSystem == FileSystems.getDefault()) {
      setOf(READ, NOFOLLOW_LINKS)
    } else {
      setOf(READ)
    }
    Files.newByteChannel(resolve(name), options)
  }

  override fun openDirectory(name: Path): BundleDirectory = checked {
    val child = resolve(name)
    val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory || attributes.isSymbolicLink) {
      invalidBundle("Symbolic links are not allowed: $name")
    }
    PathBundleDirectory(child, attributes)
  }

  private fun resolve(name: Path): Path {
    if (name.isAbsolute || name.nameCount != 1) {
      invalidBundle("Bundle entry escapes its directory: $name")
    }
    if (name.normalize().startsWith("..")) {
      invalidBundle("Bundle entry escapes its directory: $name")
    }
    return path.resolve(name)
  }

  private inline fun <T> checked(operation: () -> T): T {
    requireIdentity()
    return operation().also { requireIdentity() }
  }

  private fun requireIdentity() {
    val actual = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!actual.isDirectory || actual.isSymbolicLink) {
      invalidBundle("The bundle directory identity changed during traversal: $path")
    }
    if (!hasSameBundleIdentity(identity, actual)) {
      invalidBundle("The bundle directory identity changed during traversal: $path")
    }
  }
}
