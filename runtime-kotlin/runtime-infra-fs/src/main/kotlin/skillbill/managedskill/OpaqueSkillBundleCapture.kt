package skillbill.managedskill

import skillbill.managedskill.model.OpaqueSkillBundleFile
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes

internal fun OpaqueSkillBundleScanContext.captureSecure(root: Path, only: Path?): List<OpaqueSkillBundleFile> =
  bundleOperation(
    "Cannot capture the selected bundle without following links: $root",
  ) {
    val before = readBundleAttributes(root)
    requireRealBundleDirectory(before)
    beforeRootOpen(root)
    val captured = captureFromParent(root, before, only)
    requireUnchangedRoot(root, before)
    captured
  }

private fun OpaqueSkillBundleScanContext.captureFromParent(
  root: Path,
  before: BasicFileAttributes,
  only: Path?,
): List<OpaqueSkillBundleFile> {
  val parentPath = root.parent
  if (parentPath == null) {
    requireNonDefaultPathFallback(root)
    return capturePathDirectory(root, before, only)
  }
  return Files.newDirectoryStream(parentPath).use { parent ->
    if (useSecureDirectoryStreams && parent is SecureDirectoryStream<Path>) {
      parent.newDirectoryStream(root.fileName, NOFOLLOW_LINKS).use { opened ->
        requireSameDirectory(root, before, secureBundleAttributes(opened, Path.of(".")))
        captureDirectory(SecureBundleDirectory(opened), "", only)
      }
    } else {
      requireNonDefaultPathFallback(root)
      capturePathDirectory(root, before, only)
    }
  }
}

private fun requireRealBundleDirectory(attributes: BasicFileAttributes) {
  if (!attributes.isDirectory || attributes.isSymbolicLink) {
    invalidBundle("The bundle root is not a real directory.")
  }
}

private fun requireNonDefaultPathFallback(root: Path) {
  if (root.fileSystem == FileSystems.getDefault()) {
    invalidBundle("The selected filesystem does not support secure bundle traversal.")
  }
}

private fun capturePathDirectory(
  root: Path,
  attributes: BasicFileAttributes,
  only: Path?,
): List<OpaqueSkillBundleFile> = captureDirectory(PathBundleDirectory(root, attributes), "", only)

private fun captureDirectory(
  directory: BundleDirectory,
  prefix: String,
  only: Path? = null,
): List<OpaqueSkillBundleFile> {
  val names = if (only == null) directory.names() else listOf(only)
  return names.flatMap { name -> captureEntry(directory, prefix, name) }
}

private fun captureEntry(directory: BundleDirectory, prefix: String, name: Path): List<OpaqueSkillBundleFile> {
  val relative = if (prefix.isEmpty()) name.toString() else "$prefix/$name"
  if (name.isAbsolute || name.normalize().startsWith("..")) {
    invalidBundle("Bundle path escapes the selected directory: $relative")
  }
  val attributes = directory.attributes(name)
  return when {
    attributes.isSymbolicLink -> invalidBundle("Symbolic links are not allowed: $relative")
    attributes.isRegularFile -> listOf(
      OpaqueSkillBundleFile(relative, readStableBytes(directory, name, relative, attributes)),
    )
    attributes.isDirectory -> directory.openDirectory(name).use { captureDirectory(it, relative) }
    else -> invalidBundle("Special files are not allowed: $relative")
  }
}

private fun requireUnchangedRoot(root: Path, before: BasicFileAttributes) {
  val after = readBundleAttributes(root)
  if (!after.isDirectory || after.isSymbolicLink) invalidBundle("The bundle root changed while being scanned.")
  if (!hasSameBundleIdentity(before, after)) invalidBundle("The bundle root changed while being scanned.")
  if (before.lastModifiedTime() != after.lastModifiedTime()) {
    invalidBundle("The bundle root changed while being scanned.")
  }
}

private fun requireSameDirectory(path: Path, expected: BasicFileAttributes, actual: BasicFileAttributes) {
  if (!actual.isDirectory || actual.isSymbolicLink) {
    invalidBundle("The bundle root identity changed before traversal: $path")
  }
  if (!hasSameBundleIdentity(expected, actual)) {
    invalidBundle("The bundle root identity changed before traversal: $path")
  }
}
