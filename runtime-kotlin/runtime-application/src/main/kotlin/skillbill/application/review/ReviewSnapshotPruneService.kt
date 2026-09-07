package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ReviewSnapshotPruneResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.ReviewSnapshotGateway
import skillbill.ports.review.model.ReviewSnapshot
import java.io.IOException

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
  private val diagnostics: RuntimeDiagnostics,
) {
  fun prune(confirmed: Boolean, dbOverride: String? = null): ReviewSnapshotPruneResult {
    val liveDbPath = database.resolveDbPath(dbOverride)
    val candidates = gateway.listSnapshots(liveDbPath)
    val deleted = mutableListOf<ReviewSnapshot>()
    val failed = mutableListOf<ReviewSnapshot>()
    if (confirmed) {
      candidates.forEach { snapshot ->
        // One unreadable or locked snapshot must not abort the sweep: without this the operator gets
        // a stack trace and no result, leaving the already-deleted snapshots unreported.
        val removed = try {
          gateway.delete(snapshot)
        } catch (error: IOException) {
          diagnostics.warning("review snapshot prune: '${snapshot.path}' could not be deleted.", error)
          false
        }
        if (removed) deleted += snapshot else failed += snapshot
      }
    }
    return ReviewSnapshotPruneResult(
      liveDbPath = liveDbPath.toString(),
      confirmed = confirmed,
      candidates = candidates,
      deleted = deleted,
      failed = failed,
    )
  }
}
