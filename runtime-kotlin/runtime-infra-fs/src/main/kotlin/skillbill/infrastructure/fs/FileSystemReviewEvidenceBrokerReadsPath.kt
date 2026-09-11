package skillbill.infrastructure.fs

import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.requireRepositoryRelativePath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

internal fun checkpointDigest(root: Path, path: String): String? {
  val real = resolveRepositoryFile(root, path) ?: return null
  return digest(Files.readAllBytes(real))
}

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
  .joinToString("") { "%02x".format(it) }

internal fun normalizeEvidenceIdentity(path: String): String =
  Path.of(path).normalize().joinToString("/") { it.toString() }

internal fun validateRepositoryMapping(root: Path, repositoryPath: String) {
  requireRepositoryRelativePath(repositoryPath)
  var current = root
  repositoryPath.split('/').forEach { logicalComponent ->
    val component = runCatching { Path.of(logicalComponent) }
      .getOrElse { throw IllegalArgumentException("Review path is not representable on the active filesystem.", it) }
    require(!component.isAbsolute && component.nameCount == 1 && component.toString() == logicalComponent) {
      "Review path '$repositoryPath' is not represented exactly on the active filesystem."
    }
    current = current.resolve(component)
    require(!Files.isSymbolicLink(current)) { "Review path '$repositoryPath' crosses a symbolic link." }
  }
  require(current.normalize().startsWith(root)) { "Review path '$repositoryPath' escapes the repository root." }
}

internal fun resolveRepositoryFile(root: Path, normalized: String): Path? {
  val candidate = root.resolve(normalized).normalize()
  require(candidate.startsWith(root)) { "Evidence path escapes the repository." }
  var component = root
  root.relativize(candidate).forEach { segment ->
    component = component.resolve(segment)
    if (!Files.exists(component, NOFOLLOW_LINKS)) return null
    require(!Files.isSymbolicLink(component)) { "Evidence paths must not contain symbolic links." }
  }
  val real = candidate.toRealPath()
  require(real.startsWith(root) && Files.isRegularFile(real)) { "Evidence path must be a repository file." }
  return real
}

internal fun unavailableResult(cumulativeBytes: Long, expansionCount: Int) = ReviewEvidenceResult(
  content = null,
  bytes = 0,
  cumulativeBytes = cumulativeBytes,
  expansionCount = expansionCount,
)

internal fun forbiddenResult(forbidden: ForbiddenReviewOperation, cumulativeBytes: Long, expansionCount: Int) =
  ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansionCount,
    forbidden = forbidden,
  )

internal fun terminalResult(outcome: ReviewBudgetOutcome, cumulativeBytes: Long, expansionCount: Int) =
  ReviewEvidenceResult(
    content = null,
    bytes = 0,
    cumulativeBytes = cumulativeBytes,
    expansionCount = expansionCount,
    budgetExceeded = outcome,
  )

internal fun rejectCheckpointDrift(state: FileSystemReviewEvidenceBrokerReadState, path: String): Nothing =
  throw InvalidReviewContextSchemaError(
    sourceLabel = "review-evidence:${state.assignment.reviewId}:${state.assignment.lane}",
    reason = "Complete-file evidence '$path' changed after the immutable launch checkpoint was bound.",
  )
