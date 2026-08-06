package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-136 subtask 6 AC-006/AC-007. The prune surface must never treat the live store as a
 * candidate, and it must be the only thing that deletes a snapshot.
 */
class FileSystemReviewSnapshotGatewayTest {
  private val gateway = FileSystemReviewSnapshotGateway()

  @Test
  fun `snapshots are listed and the live database is never a candidate`() {
    val (live, snapshots) = seedStore()

    val listed = gateway.listSnapshots(live)

    assertEquals(
      snapshots.map { it.fileName.toString() }.sorted(),
      listed.map { it.path.fileName.toString() }.sorted(),
      "Every review-metrics.<label>.db must be listed.",
    )
    assertTrue(
      listed.none { it.path.fileName.toString() == "review-metrics.db" },
      "The live database has no label segment and must never be a prune candidate.",
    )
    assertTrue(listed.all { it.sizeBytes > 0 }, "Each candidate reports its size for the operator to weigh.")
  }

  @Test
  fun `listing a directory with no snapshots yields nothing and does not error`() {
    val live = Files.createTempDirectory("snapshot-empty").resolve("review-metrics.db")
    Files.writeString(live, "live")

    assertEquals(emptyList(), gateway.listSnapshots(live))
  }

  @Test
  fun `unrelated database files beside the store are not candidates`() {
    val (live, _) = seedStore()
    val unrelated = live.parent.resolve("other-store.backup.db")
    Files.writeString(unrelated, "unrelated")

    val listed = gateway.listSnapshots(live)

    assertTrue(
      listed.none { it.path == unrelated },
      "Only files matching the review-metrics.<label>.db pattern may be considered.",
    )
    assertTrue(Files.exists(unrelated))
  }

  @Test
  fun `deleting removes only the named snapshot`() {
    val (live, snapshots) = seedStore()
    val target = gateway.listSnapshots(live).first { it.label == "before-migration" }

    assertTrue(gateway.delete(target))

    assertTrue(Files.exists(live), "The live database must survive a snapshot deletion.")
    assertTrue(
      snapshots.filter { it != target.path }.all(Files::exists),
      "Deleting one snapshot must leave the others untouched.",
    )
  }

  private fun seedStore(): Pair<Path, List<Path>> {
    val directory = Files.createTempDirectory("snapshot-store")
    val live = directory.resolve("review-metrics.db")
    Files.writeString(live, "live store")
    val snapshots = listOf("before-migration", "2026-07-02", "pre-skill-136").map { label ->
      directory.resolve("review-metrics.$label.db").also { Files.writeString(it, "snapshot $label") }
    }
    return live to snapshots
  }
}
