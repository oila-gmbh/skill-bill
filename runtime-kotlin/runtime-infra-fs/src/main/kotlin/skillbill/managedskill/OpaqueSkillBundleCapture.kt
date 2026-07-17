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
    val captured = captureFromParent(root, before, only, BundleCaptureBudget(limits))
    requireUnchangedRoot(root, before)
    captured
  }

private fun OpaqueSkillBundleScanContext.captureFromParent(
  root: Path,
  before: BasicFileAttributes,
  only: Path?,
  budget: BundleCaptureBudget,
): List<OpaqueSkillBundleFile> {
  val parentPath = root.parent
  if (parentPath == null) {
    requireNonDefaultPathFallback(root)
    return capturePathDirectory(root, before, only, budget)
  }
  return Files.newDirectoryStream(parentPath).use { parent ->
    if (useSecureDirectoryStreams && parent is SecureDirectoryStream<Path>) {
      parent.newDirectoryStream(root.fileName, NOFOLLOW_LINKS).use { opened ->
        requireSameDirectory(root, before, secureBundleAttributes(opened, Path.of(".")))
        captureDirectory(SecureBundleDirectory(opened), "", only, budget)
      }
    } else {
      requireNonDefaultPathFallback(root)
      capturePathDirectory(root, before, only, budget)
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
  budget: BundleCaptureBudget,
): List<OpaqueSkillBundleFile> = captureDirectory(PathBundleDirectory(root, attributes), "", only, budget)

private fun captureDirectory(
  directory: BundleDirectory,
  prefix: String,
  only: Path?,
  budget: BundleCaptureBudget,
): List<OpaqueSkillBundleFile> {
  val names = if (only == null) directory.names() else listOf(only)
  return names.flatMap { name -> captureEntry(directory, prefix, name, budget) }
}

private fun captureEntry(
  directory: BundleDirectory,
  prefix: String,
  name: Path,
  budget: BundleCaptureBudget,
): List<OpaqueSkillBundleFile> {
  val relative = if (prefix.isEmpty()) name.toString() else "$prefix/$name"
  if (name.isAbsolute || name.normalize().startsWith("..")) {
    invalidBundle("Bundle path escapes the selected directory: $relative")
  }
  val attributes = directory.attributes(name)
  return when {
    attributes.isSymbolicLink -> invalidBundle("Symbolic links are not allowed: $relative")
    attributes.isRegularFile -> {
      budget.acceptFile(relative, attributes.size())
      listOf(OpaqueSkillBundleFile(relative, readStableBytes(directory, name, relative, attributes)))
    }
    attributes.isDirectory -> {
      budget.acceptDepth(relative)
      directory.openDirectory(name).use { captureDirectory(it, relative, null, budget) }
    }
    else -> invalidBundle("Special files are not allowed: $relative")
  }
}

private class BundleCaptureBudget(private val limits: OpaqueSkillBundleScanLimits) {
  private var files = 0
  private var totalBytes = 0L

  fun acceptDepth(relative: String) {
    if (relative.count { it == '/' } + 1 > limits.maxDepth) invalidBundle("Bundle exceeds maximum depth.")
  }

  fun acceptFile(relative: String, bytes: Long) {
    if (++files > limits.maxFiles) invalidBundle("Bundle exceeds maximum file count.")
    if (bytes > limits.maxFileBytes) invalidBundle("Bundle file exceeds maximum size: $relative")
    totalBytes += bytes
    if (totalBytes > limits.maxTotalBytes) invalidBundle("Bundle exceeds maximum total size.")
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
