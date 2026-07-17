package skillbill.managedskill

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal fun ManagedSkillRecordStoreContext.requireRealAncestors(directory: Path) {
  var current = stateRoot.root
  stateRoot.forEach { segment ->
    current = current.resolve(segment)
    if (Files.exists(current, NOFOLLOW_LINKS)) requireRealDirectory(current)
  }
  if (!directory.startsWith(stateRoot)) invalidManagedRecord(directory, "path escapes managed state root")
  stateRoot.relativize(directory).forEach { segment ->
    current = current.resolve(segment)
    requireRealDirectory(current)
  }
}

private fun requireRealDirectory(directory: Path) {
  if (!Files.exists(directory, NOFOLLOW_LINKS)) {
    invalidManagedRecord(directory, "managed state parent is not a real directory")
  }
  if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
    invalidManagedRecord(directory, "managed state parent is not a real directory")
  }
}

internal fun <T> ManagedSkillRecordStoreContext.withRecordDirectory(
  name: String,
  block: (RecordDirectory, Path) -> T,
): T {
  val directory = recordPath(name).parent
  requireRealAncestors(directory)
  requireStableStateRoot()
  return Files.newDirectoryStream(stateRoot).use { rootStream ->
    val secureRoot = rootStream as? SecureDirectoryStream<Path>
    if (useSecureDirectoryStreams && secureRoot != null) {
      withSecureRecordDirectory(secureRoot, name, directory, block)
    } else {
      block(PathRecordDirectory(directory), directory)
    }
  }.also {
    requireRealAncestors(directory)
    requireStableStateRoot()
  }
}

private fun <T> ManagedSkillRecordStoreContext.withSecureRecordDirectory(
  secureRoot: SecureDirectoryStream<Path>,
  name: String,
  displayDirectory: Path,
  block: (RecordDirectory, Path) -> T,
): T {
  val openedRoot = secureRoot.getFileAttributeView(
    Path.of("."),
    BasicFileAttributeView::class.java,
    NOFOLLOW_LINKS,
  ).readAttributes()
  requireStableStateRoot(openedRoot, "managed state root identity changed before secure access")
  return secureRoot.newDirectoryStream(Path.of("managed-skills"), NOFOLLOW_LINKS).use { managed ->
    managed.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS).use { recordDirectory ->
      block(SecureRecordDirectory(recordDirectory), displayDirectory)
    }
  }
}

private fun ManagedSkillRecordStoreContext.requireStableStateRoot() {
  val attributes = Files.readAttributes(stateRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  requireStableStateRoot(attributes, "managed state root identity changed")
}

private fun ManagedSkillRecordStoreContext.requireStableStateRoot(attributes: BasicFileAttributes, reason: String) {
  if (!attributes.isDirectory || attributes.isSymbolicLink) invalidManagedRecord(stateRoot, reason)
  val actualIdentity = attributes.fileKey()
  if (stateRootIdentity != null && actualIdentity != null && actualIdentity != stateRootIdentity) {
    invalidManagedRecord(stateRoot, reason)
  }
}

internal fun ManagedSkillRecordStoreContext.createConfinedDirectories(directory: Path) {
  var current = stateRoot.root
  stateRoot.forEach { segment ->
    current = current.resolve(segment)
    if (Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
      invalidManagedRecord(current, "managed state parent is a symbolic link")
    }
  }
  stateRoot.relativize(directory).forEach { segment ->
    current = current.resolve(segment)
    if (Files.exists(current, NOFOLLOW_LINKS)) {
      requireRealDirectory(current)
    } else {
      Files.createDirectory(current)
    }
  }
}

internal fun forceDirectoryIfSupported(directory: Path) {
  try {
    FileChannel.open(directory, READ).use { it.force(true) }
  } catch (_: UnsupportedOperationException) {
  } catch (_: java.nio.file.FileSystemException) {
  }
}

internal fun managedStateRoot(homeDirectory: Path, forceDirectory: (Path) -> Unit): Path {
  val home = homeDirectory.toAbsolutePath().normalize()
  val attributes = Files.readAttributes(home, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  if (!attributes.isDirectory || attributes.isSymbolicLink) {
    invalidManagedRecord(home, "home directory is not a real directory")
  }
  val stateRoot = home.resolve(".skill-bill")
  if (!Files.exists(stateRoot, NOFOLLOW_LINKS)) {
    try {
      Files.createDirectory(stateRoot)
      forceDirectory(home)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
    }
  }
  recordDirectoryAttributes(stateRoot)
  return stateRoot
}
