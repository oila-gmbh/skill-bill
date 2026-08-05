package skillbill.ports.review

import java.nio.file.Path

/** One `~/.skill-bill/review-metrics.<label>.db` snapshot: a hand-named copy of the live store. */
data class ReviewSnapshot(
  val path: Path,
  val label: String,
  val sizeBytes: Long,
  val lastModified: String,
)

/**
 * Read and delete access to review-metrics snapshots.
 *
 * Retention policy: snapshots are never created, rotated, or deleted automatically. They are
 * operator artifacts with no expiry, and nothing in the runtime removes them — deletion happens
 * only when an operator runs `skill-bill review prune-snapshots --confirm`. The live
 * `review-metrics.db` is never a snapshot and is never a deletion candidate.
 */
interface ReviewSnapshotGateway {
  /** Snapshots beside [liveDbPath], newest first. Never includes [liveDbPath] itself. */
  fun listSnapshots(liveDbPath: Path): List<ReviewSnapshot>

  fun delete(snapshot: ReviewSnapshot): Boolean
}
