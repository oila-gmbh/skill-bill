package skillbill.managedskill

import skillbill.managedskill.model.OpaqueSkillBundle
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class MachineSkillSnapshotPublication(private val snapshotsRoot: Path) {
  fun publish(bundle: OpaqueSkillBundle): Path {
    val destination = snapshotsRoot.resolve("${bundle.name}-${bundle.contentHash}")
    if (Files.exists(destination, NOFOLLOW_LINKS)) {
      require(verified(destination, bundle)) {
        "Existing content-addressed snapshot is incomplete or corrupt: $destination"
      }
      return destination
    }
    Files.createDirectories(snapshotsRoot)
    val staging = Files.createTempDirectory(snapshotsRoot, ".${bundle.name}-")
    try {
      bundle.files.forEach { file ->
        val output = staging.resolve(file.relativePath).normalize()
        require(output.startsWith(staging))
        Files.createDirectories(output.parent)
        Files.write(output, file.content)
      }
      val rescanned = OpaqueSkillBundleScanner().scan(staging, emptySet())
      require(
        rescanned.name == bundle.name &&
          rescanned.contentHash == bundle.contentHash &&
          rescanned.totalBytes == bundle.totalBytes &&
          rescanned.files.size == bundle.files.size,
      ) {
        "Staged snapshot does not match captured bundle"
      }
      Files.writeString(staging.resolve(".skill-bill-content-hash"), bundle.contentHash)
      Files.move(staging, destination, ATOMIC_MOVE)
      return destination
    } finally {
      if (Files.exists(staging, NOFOLLOW_LINKS)) {
        Files.walk(staging).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  private fun verified(path: Path, bundle: OpaqueSkillBundle): Boolean = runCatching {
    if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
      Files.readString(path.resolve(".skill-bill-content-hash")) != bundle.contentHash
    ) {
      return@runCatching false
    }
    val actual: Map<String, ByteArray> = Files.walk(path).use { paths ->
      paths
        .iterator()
        .asSequence()
        .filter {
          Files.isRegularFile(it, NOFOLLOW_LINKS) &&
            it.fileName.toString() != ".skill-bill-content-hash"
        }
        .associate { path.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
    }
    actual.keys == bundle.files.map { it.relativePath }.toSet() &&
      bundle.files.all { actual[it.relativePath]?.contentEquals(it.content) == true }
  }.getOrDefault(false)
}
