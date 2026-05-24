package skillbill.install

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object InstallCleanupOperations {
  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
  ): Pair<List<Path>, List<Path>> {
    if (!Files.isDirectory(targetDir)) return emptyList<Path>() to emptyList()
    val removed = mutableListOf<Path>()
    val skipped = mutableListOf<Path>()
    Files.list(targetDir).use { stream ->
      stream
        .filter { path -> Files.isDirectory(path) && Files.exists(path.resolve(managedInstallMarker)) }
        .forEach { path -> removeCleanupTarget(path, managedInstallMarker, removed, skipped) }
    }
    (skillNames + legacyNames).distinct().forEach { name ->
      removeCleanupTarget(targetDir.resolve(name), managedInstallMarker, removed, skipped)
    }
    return removed to skipped
  }

  private fun removeCleanupTarget(
    target: Path,
    managedInstallMarker: String,
    removed: MutableList<Path>,
    skipped: MutableList<Path>,
  ) {
    if (Files.isSymbolicLink(target)) {
      Files.deleteIfExists(target)
      removed.add(target)
    } else if (Files.isDirectory(target) && Files.exists(target.resolve(managedInstallMarker))) {
      deleteTree(target)
      removed.add(target)
    } else if (Files.exists(target)) {
      skipped.add(target)
    }
  }

  private fun deleteTree(target: Path) {
    Files.walkFileTree(
      target,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
          Files.delete(file)
          return java.nio.file.FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
          if (exc != null) throw exc
          Files.delete(dir)
          return java.nio.file.FileVisitResult.CONTINUE
        }
      },
    )
  }
}
