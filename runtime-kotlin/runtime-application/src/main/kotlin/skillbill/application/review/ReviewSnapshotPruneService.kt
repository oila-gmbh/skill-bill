package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.ReviewSnapshot
import skillbill.ports.review.ReviewSnapshotGateway

/**
 * Retention policy for `~/.skill-bill/review-metrics.<label>.db` snapshots.
 *
 * Snapshots are operator artifacts: hand-named copies of the live store taken before a risky
 * migration or to preserve a point-in-time reading. They have no expiry and nothing in the runtime
 * creates, rotates, or removes them. Left alone they accumulate indefinitely — an observed
 * 37 snapshots totalling roughly 2.9 GB.
 *
 * Deletion is opt-in and operator-driven only. [prune] defaults to a dry run that lists candidates
 * and deletes nothing; passing `confirmed = true` is the sole code path in the runtime that removes
 * a snapshot. The live `review-metrics.db` never matches the snapshot pattern and is therefore never
 * a candidate.
 */
@Inject
class ReviewSnapshotPruneService(
  private val database: DatabaseSessionFactory,
  private val gateway: ReviewSnapshotGateway,
) {
  fun prune(confirmed: Boolean, dbOverride: String? = null): ReviewSnapshotPruneResult {
    val liveDbPath = database.resolveDbPath(dbOverride)
    val candidates = gateway.listSnapshots(liveDbPath)
    val deleted = if (confirmed) candidates.filter(gateway::delete) else emptyList()
    return ReviewSnapshotPruneResult(
      liveDbPath = liveDbPath.toString(),
      confirmed = confirmed,
      candidates = candidates,
      deleted = deleted,
    )
  }
}

data class ReviewSnapshotPruneResult(
  val liveDbPath: String,
  val confirmed: Boolean,
  val candidates: List<ReviewSnapshot>,
  val deleted: List<ReviewSnapshot>,
) {
  val reclaimedBytes: Long = deleted.sumOf(ReviewSnapshot::sizeBytes)
  val candidateBytes: Long = candidates.sumOf(ReviewSnapshot::sizeBytes)

  fun toCliMap(): Map<String, Any?> = linkedMapOf(
    "live_db_path" to liveDbPath,
    "confirmed" to confirmed,
    "candidate_count" to candidates.size,
    "candidate_bytes" to candidateBytes,
    "deleted_count" to deleted.size,
    "reclaimed_bytes" to reclaimedBytes,
    "candidates" to candidates.map { snapshot ->
      linkedMapOf<String, Any?>(
        "path" to snapshot.path.toString(),
        "label" to snapshot.label,
        "size_bytes" to snapshot.sizeBytes,
        "last_modified" to snapshot.lastModified,
        "deleted" to (snapshot in deleted),
      )
    },
  )
}
