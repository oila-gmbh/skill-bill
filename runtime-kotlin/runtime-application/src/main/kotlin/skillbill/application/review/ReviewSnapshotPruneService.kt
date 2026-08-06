package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ReviewSnapshotPruneResult
import skillbill.ports.persistence.DatabaseSessionFactory
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
