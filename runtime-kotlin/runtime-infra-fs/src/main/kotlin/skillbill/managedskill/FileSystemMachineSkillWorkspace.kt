package skillbill.managedskill

import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.ports.managedskill.MachineSkillWorkspacePort
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class FileSystemMachineSkillWorkspace(
  home: Path,
  targetRoots: List<Path>,
  private val protectedNames: Set<String> = emptySet(),
) : MachineSkillWorkspacePort {
  private val store = FileManagedSkillRecordStore(home)
  private val scanner = OpaqueSkillBundleScanner()
  private val inspector = FileSystemMachineSkillMutationInspector(home, targetRoots)

  override fun capture(source: Path): OpaqueSkillBundle = scanner.scan(source, protectedNames)

  override fun captureEditedSource(name: String, expectedSourceHash: String, skillMarkdown: String): OpaqueSkillBundle {
    val source = sourceRoot(name)
    val current = scanner.scan(source, protectedNames)
    require(current.contentHash == expectedSourceHash) { "Managed source changed before edit save." }
    val staging = Files.createTempDirectory(source.parent, ".edit-$name-")
    return try {
      current.files.forEach { file ->
        val destination = staging.resolve(file.relativePath).normalize()
        require(destination.startsWith(staging))
        Files.createDirectories(destination.parent)
        Files.write(destination, if (file.relativePath == "SKILL.md") skillMarkdown.toByteArray() else file.content)
      }
      scanner.scan(staging, protectedNames)
    } finally {
      deleteTree(staging)
    }
  }

  override fun readRecord(name: String): ManagedSkillRecord? = runCatching { store.read(name) }.getOrNull()
  override fun recordDigest(name: String): String? = store.digest(name)
  override fun sourceRoot(name: String): Path = store.sourceRoot(name)
  override fun recordPath(name: String): Path = store.recordPath(name)
  override fun snapshotRoot(name: String, contentHash: String): Path = store.snapshotRoot(name, contentHash)
  override fun observe(paths: Collection<Path>) = inspector.observe(paths)
  override fun snapshotHealthy(bundle: OpaqueSkillBundle): Boolean {
    val snapshot = snapshotRoot(bundle.name, bundle.contentHash)
    return Files.isDirectory(snapshot, NOFOLLOW_LINKS) && runCatching { scanner.scan(snapshot, protectedNames).contentHash }.getOrNull() == bundle.contentHash
  }
  override fun symlinkCapability() = inspector.symlinkCapability()
  override fun snapshotReferences() = inspector.snapshotReferences()

  private fun deleteTree(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
  }
}
