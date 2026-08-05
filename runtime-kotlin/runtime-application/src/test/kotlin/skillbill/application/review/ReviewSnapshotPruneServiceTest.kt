package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.ReviewSnapshot
import skillbill.ports.review.ReviewSnapshotGateway
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-136 subtask 6 AC-006/AC-007: pruning is opt-in. The default invocation is a dry run that
 * deletes nothing, and deletion happens only behind an explicit confirmation.
 */
class ReviewSnapshotPruneServiceTest {
  @Test
  fun `the default invocation lists candidates and deletes nothing`() {
    val gateway = RecordingSnapshotGateway()
    val result = ReviewSnapshotPruneService(StubSessionFactory, gateway).prune(confirmed = false)

    assertEquals(2, result.candidates.size, "Every snapshot must be listed for the operator to review.")
    assertEquals(emptyList(), result.deleted)
    assertEquals(0, result.reclaimedBytes)
    assertEquals(emptyList(), gateway.deleted, "A dry run must never reach the deletion seam at all.")
    assertEquals(300, result.candidateBytes)
  }

  @Test
  fun `confirmation deletes exactly the listed snapshots and never the live database`() {
    val gateway = RecordingSnapshotGateway()
    val result = ReviewSnapshotPruneService(StubSessionFactory, gateway).prune(confirmed = true)

    assertEquals(gateway.snapshots, gateway.deleted, "Confirmation deletes precisely the listed candidates.")
    assertEquals(300, result.reclaimedBytes)
    assertTrue(
      gateway.deleted.none { it.path.fileName.toString() == "review-metrics.db" },
      "The live database is never a candidate and so can never be deleted.",
    )
  }

  private class RecordingSnapshotGateway : ReviewSnapshotGateway {
    val snapshots = listOf(
      ReviewSnapshot(Path.of("/home/u/.skill-bill/review-metrics.a.db"), "a", 100, "2026-07-02T00:00:00Z"),
      ReviewSnapshot(Path.of("/home/u/.skill-bill/review-metrics.b.db"), "b", 200, "2026-07-01T00:00:00Z"),
    )
    val deleted = mutableListOf<ReviewSnapshot>()

    override fun listSnapshots(liveDbPath: Path): List<ReviewSnapshot> = snapshots

    override fun delete(snapshot: ReviewSnapshot): Boolean {
      deleted += snapshot
      return true
    }
  }

  private object StubSessionFactory : DatabaseSessionFactory {
    override fun resolveDbPath(dbOverride: String?): Path = Path.of("/home/u/.skill-bill/review-metrics.db")

    override fun databaseExists(dbOverride: String?): Boolean = true

    override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T =
      error("Pruning must not open the database.")

    override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T =
      error("Pruning must not open the database.")

    override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T =
      error("Pruning must not open the database.")
  }
}
