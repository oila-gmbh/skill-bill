package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewSnapshot
import skillbill.ports.review.ReviewSnapshotGateway
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewSnapshotGateway : ReviewSnapshotGateway {
  override fun listSnapshots(liveDbPath: Path): List<ReviewSnapshot> {
    val live = liveDbPath.toAbsolutePath().normalize()
    val directory = live.parent ?: return emptyList()
    if (!Files.isDirectory(directory)) return emptyList()
    val liveName = live.fileName.toString()
    // review-metrics.db -> stem "review-metrics", so a snapshot is review-metrics.<label>.db. The
    // live file fails this pattern (it has no label segment) and can never be a candidate.
    val stem = liveName.removeSuffix(SUFFIX)
    val pattern = Regex("^${Regex.escape(stem)}\\.(.+)${Regex.escape(SUFFIX)}$")
    return Files.newDirectoryStream(directory).use { entries ->
      entries.mapNotNull { entry ->
        if (!Files.isRegularFile(entry)) return@mapNotNull null
        val label = pattern.find(entry.fileName.toString())?.groupValues?.get(1) ?: return@mapNotNull null
        ReviewSnapshot(
          path = entry.toAbsolutePath().normalize(),
          label = label,
          sizeBytes = Files.size(entry),
          lastModified = Files.getLastModifiedTime(entry).toInstant().toString(),
        )
      }
    }.sortedByDescending(ReviewSnapshot::lastModified)
  }

  override fun delete(snapshot: ReviewSnapshot): Boolean = Files.deleteIfExists(snapshot.path)

  private companion object {
    const val SUFFIX = ".db"
  }
}
