package skillbill.ports.diff

import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import java.nio.file.Path

interface DiffResolverPort {
  fun runProcess(args: List<String>, workDir: Path): String?

  fun reviewWorktreeFileIdentities(root: Path, paths: List<String>): Map<String, ReviewCheckpointFileIdentity> =
    throw InvalidReviewContextSchemaError(
      "review-source",
      "This diff resolver cannot capture worktree evidence identities.",
    )

  fun readDiff(path: Path, maxBytes: Long): String? = null
}
