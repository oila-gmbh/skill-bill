package skillbill.managedskill

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

data class SnapshotCleanupResult(val removed: List<Path>, val warnings: List<String>)

class MachineSkillSnapshotCleanup(private val store: FileManagedSkillRecordStore) {
  fun cleanup(discoveredReferences: Set<Path>, discoveryComplete: Boolean): SnapshotCleanupResult {
    val records = store.listRecords()
    if (!discoveryComplete || records.any { it.error != null }) {
      return SnapshotCleanupResult(
        emptyList(),
        listOf("Snapshot reference discovery is incomplete; cleanup was skipped"),
      )
    }
    val recorded =
      records.mapNotNull { it.record }
        .map { store.snapshotRoot(it.name, it.activeContentHash).normalize() }
        .toSet()
    val referenced = recorded + discoveredReferences.map { it.toAbsolutePath().normalize() }
    val removed = store.listSnapshotPaths().filter { path ->
      path !in referenced && Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    }.onEach(::deleteTree)
    return SnapshotCleanupResult(removed, emptyList())
  }

  private fun deleteTree(root: Path) {
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
  }
}
