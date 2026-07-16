package skillbill.managedskill

import skillbill.managedskill.model.OpaqueSkillBundle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

data class PromotedMachineSkillSource(val destination: Path, val backup: Path?)

class MachineSkillSourcePublication(private val managedSourcesRoot: Path) {
  fun stage(bundle: OpaqueSkillBundle): Path {
    Files.createDirectories(managedSourcesRoot)
    val staging = Files.createTempDirectory(managedSourcesRoot, ".${bundle.name}-staging-")
    bundle.files.forEach { file ->
      val output = staging.resolve(file.relativePath).normalize()
      require(output.startsWith(staging))
      Files.createDirectories(output.parent)
      Files.write(output, file.content)
    }
    val verified = OpaqueSkillBundleScanner().scan(staging, emptySet())
    require(
      verified.name == bundle.name &&
        verified.contentHash == bundle.contentHash &&
        verified.totalBytes == bundle.totalBytes,
    ) {
      "Staged managed source does not match captured bundle"
    }
    return staging
  }

  fun promote(name: String, staging: Path): PromotedMachineSkillSource {
    val destination = managedSourcesRoot.resolve(name)
    val backup =
      if (Files.exists(destination, NOFOLLOW_LINKS)) {
        destination.resolveSibling(".$name-backup-${System.nanoTime()}")
      } else {
        null
      }
    if (backup != null) Files.move(destination, backup, ATOMIC_MOVE)
    try {
      Files.move(staging, destination, ATOMIC_MOVE)
    } catch (failure: IOException) {
      if (backup != null) Files.move(backup, destination, ATOMIC_MOVE)
      throw failure
    }
    return PromotedMachineSkillSource(destination, backup)
  }

  fun rollback(promotion: PromotedMachineSkillSource) {
    deleteTree(promotion.destination)
    promotion.backup?.let { Files.move(it, promotion.destination, ATOMIC_MOVE) }
  }

  fun commit(promotion: PromotedMachineSkillSource) {
    promotion.backup?.let(::deleteTree)
  }

  private fun deleteTree(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths ->
      paths.iterator().asSequence().sortedByDescending { it.nameCount }.forEach(Files::delete)
    }
  }
}
