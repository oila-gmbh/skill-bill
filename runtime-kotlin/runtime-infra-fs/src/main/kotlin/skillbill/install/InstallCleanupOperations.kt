package skillbill.install

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors

object InstallCleanupOperations {
  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
    installedSkillsRoot: Path? = null,
  ): Pair<List<Path>, List<Path>> {
    if (!Files.isDirectory(targetDir)) return emptyList<Path>() to emptyList()
    val removed = mutableListOf<Path>()
    val skipped = mutableListOf<Path>()
    Files.list(targetDir).use { stream ->
      stream
        .filter { path -> Files.isDirectory(path) && Files.exists(path.resolve(managedInstallMarker)) }
        .forEach { path -> removeCleanupTarget(path, managedInstallMarker, removed, skipped) }
    }
    // Ownership sweep: any symlink pointing into the Skill Bill installed-skills
    // cache is Skill Bill-managed, regardless of its name. This prunes orphans
    // whose skill was removed or renamed without a RENAMED_SKILL_PAIRS entry
    // (for example a deleted platform pack), which the name-based pass below
    // cannot match. readSymbolicLink is used so dangling orphans are still seen.
    if (installedSkillsRoot != null) {
      val root = installedSkillsRoot.toAbsolutePath().normalize()
      val owned = Files.list(targetDir).use { stream ->
        stream.filter { path -> symlinkPointsInto(path, root) }.collect(Collectors.toList())
      }
      owned.forEach { path -> removeCleanupTarget(path, managedInstallMarker, removed, skipped) }
    }
    (skillNames + legacyNames).distinct().forEach { name ->
      val target = targetDir.resolve(name)
      if (target !in removed) {
        removeCleanupTarget(target, managedInstallMarker, removed, skipped)
      }
    }
    return removed to skipped
  }

  private fun symlinkPointsInto(path: Path, root: Path): Boolean {
    if (!Files.isSymbolicLink(path)) return false
    return try {
      val target = Files.readSymbolicLink(path)
      val resolved = (if (target.isAbsolute) target else path.parent.resolve(target)).normalize()
      resolved.startsWith(root)
    } catch (_: java.io.IOException) {
      false
    }
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
