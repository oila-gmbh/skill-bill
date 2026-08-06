package skillbill.ports.review.model

import java.nio.file.Path

/** One `~/.skill-bill/review-metrics.<label>.db` snapshot: a hand-named copy of the live store. */
data class ReviewSnapshot(
  val path: Path,
  val label: String,
  val sizeBytes: Long,
  val lastModified: String,
)
