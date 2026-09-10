package skillbill.infrastructure.fs

import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun checkpointFileIdentity(root: Path, path: String): ReviewCheckpointFileIdentity {
  requireRepositoryRelativePath(path)
  val realRoot = root.toRealPath()
  val candidate = realRoot.resolve(path).normalize()
  require(candidate.startsWith(realRoot)) { "Evidence path escapes the repository." }
  var current = realRoot
  for (segment in realRoot.relativize(candidate)) {
    current = current.resolve(segment)
    val attributes = try {
      Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return ReviewCheckpointFileIdentity.Absent
    }
    checkpointUnavailable(attributes, current == candidate)?.let { return it }
  }
  return checkpointDigest(root, path)?.let { ReviewCheckpointFileIdentity.Regular(it) }
    ?: ReviewCheckpointFileIdentity.Absent
}

private fun checkpointUnavailable(
  attributes: BasicFileAttributes,
  finalComponent: Boolean,
): ReviewCheckpointFileIdentity.Unavailable? = when {
  attributes.isSymbolicLink -> ReviewCheckpointFileIdentity.Unavailable.SYMBOLIC_LINK
  finalComponent && attributes.isDirectory -> ReviewCheckpointFileIdentity.Unavailable.DIRECTORY
  finalComponent && !attributes.isRegularFile -> ReviewCheckpointFileIdentity.Unavailable.OTHER
  !finalComponent && !attributes.isDirectory -> ReviewCheckpointFileIdentity.Unavailable.OTHER
  else -> null
}
