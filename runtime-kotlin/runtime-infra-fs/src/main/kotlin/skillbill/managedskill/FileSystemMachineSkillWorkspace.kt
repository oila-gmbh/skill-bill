package skillbill.managedskill

import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.ports.managedskill.MachineSkillBundleWorkspacePort
import skillbill.ports.managedskill.MachineSkillRecordWorkspacePort
import skillbill.ports.managedskill.MachineSkillSnapshotWorkspacePort
import skillbill.ports.managedskill.MachineSkillTargetWorkspacePort
import skillbill.ports.managedskill.MachineSkillWorkspacePort
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class FileSystemMachineSkillWorkspace(
  home: Path,
  targetRoots: List<Path>,
  protectedNames: Set<String> = emptySet(),
) : MachineSkillWorkspacePort {
  private val normalizedHome = home.toAbsolutePath().normalize()
  private val store = FileManagedSkillRecordStore(normalizedHome)
  private val normalizedTargetRoots = targetRoots.map { it.toAbsolutePath().normalize() }.toSet()
  private val inspector = FileSystemMachineSkillMutationInspector(normalizedHome, normalizedTargetRoots.toList())

  override val bundles: MachineSkillBundleWorkspacePort = FileSystemMachineSkillBundles(store, protectedNames)
  override val records: MachineSkillRecordWorkspacePort = FileSystemMachineSkillRecords(store)
  override val snapshots: MachineSkillSnapshotWorkspacePort =
    FileSystemMachineSkillSnapshots(normalizedHome, store, normalizedTargetRoots, inspector)
  override val targets: MachineSkillTargetWorkspacePort =
    FileSystemMachineSkillTargets(normalizedTargetRoots, inspector)
}

private class FileSystemMachineSkillBundles(
  private val store: FileManagedSkillRecordStore,
  private val protectedNames: Set<String>,
) : MachineSkillBundleWorkspacePort {
  private val scanner = OpaqueSkillBundleScanner()

  override fun capture(source: Path): OpaqueSkillBundle = scanner.scan(source, protectedNames)

  override fun captureEditedSource(
    name: String,
    expectedSourceHash: String,
    skillMarkdown: String,
  ): OpaqueSkillBundle {
    val source = store.sourceRoot(name)
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
      deleteWorkspaceTree(staging)
    }
  }
}

private class FileSystemMachineSkillRecords(
  private val store: FileManagedSkillRecordStore,
) : MachineSkillRecordWorkspacePort {
  override fun readRecord(name: String): ManagedSkillRecord? = runCatching { store.read(name) }.getOrNull()

  override fun recordDigest(name: String): String? = store.digest(name)

  override fun sourceRoot(name: String): Path = store.sourceRoot(name)

  override fun recordPath(name: String): Path = store.recordPath(name)
}

private class FileSystemMachineSkillSnapshots(
  home: Path,
  private val store: FileManagedSkillRecordStore,
  private val targetRoots: Set<Path>,
  private val inspector: FileSystemMachineSkillMutationInspector,
) : MachineSkillSnapshotWorkspacePort {
  private val snapshotsRoot = home.resolve(".skill-bill/installed-skills")

  override fun snapshotRoot(name: String, contentHash: String): Path = store.snapshotRoot(name, contentHash)

  override fun snapshotHealthy(bundle: OpaqueSkillBundle): Boolean {
    val snapshot = snapshotRoot(bundle.name, bundle.contentHash)
    return Files.isDirectory(snapshot, NOFOLLOW_LINKS) && !Files.isSymbolicLink(snapshot) && runCatching {
      Files.readString(snapshot.resolve(".skill-bill-content-hash")) == bundle.contentHash &&
        capturedSnapshotFiles(snapshot).matches(bundle)
    }.getOrDefault(false)
  }

  override fun orphanSnapshots(): Set<Path> {
    val references = snapshotReferences()
    if (!references.complete || !Files.isDirectory(snapshotsRoot, NOFOLLOW_LINKS)) return emptySet()
    return Files.newDirectoryStream(snapshotsRoot).use { entries ->
      entries.filterTo(mutableSetOf()) { Files.isDirectory(it, NOFOLLOW_LINKS) && it !in references.references }
    }
  }

  override fun snapshotUnreferencedAfterDelete(name: String, snapshot: Path, ownedLinks: Set<Path>): Boolean {
    val records = FileManagedSkillRecordStore.openReadOnly(store.recordPath(name).parent.parent.parent)
      ?.listRecords().orEmpty()
    if (records.any { it.error != null }) return false
    val referencedByRecord = records.mapNotNull { it.record }
      .any { it.name != name && store.snapshotRoot(it.name, it.activeContentHash) == snapshot }
    return !referencedByRecord && targetRoots.none { root ->
      val link = root.resolve(name)
      link !in ownedLinks && runCatching {
        inspector.observe(listOf(link)).single().normalizedLinkTarget == snapshot
      }.getOrDefault(false)
    }
  }

  override fun snapshotReferences() = inspector.snapshotReferences()

  private fun capturedSnapshotFiles(snapshot: Path): Map<String, ByteArray> = Files.walk(snapshot).use { paths ->
    paths.iterator().asSequence()
      .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString() != ".skill-bill-content-hash" }
      .associate { snapshot.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
  }

  private fun Map<String, ByteArray>.matches(bundle: OpaqueSkillBundle): Boolean =
    keys == bundle.files.map { it.relativePath }.toSet() &&
      bundle.files.all { get(it.relativePath)?.contentEquals(it.content) == true }
}

private class FileSystemMachineSkillTargets(
  private val targetRoots: Set<Path>,
  private val inspector: FileSystemMachineSkillMutationInspector,
) : MachineSkillTargetWorkspacePort {
  override fun observe(paths: Collection<Path>) = inspector.observe(paths)

  override fun isDiscoveredTarget(target: AgentSkillTargetId): Boolean = target.skillsPath in targetRoots

  override fun ownedLinkPaths(name: String, expectedSnapshot: Path): Set<Path> =
    targetRoots.mapNotNullTo(mutableSetOf()) { root ->
      val link = root.resolve(name)
      val observation = runCatching { inspector.observe(listOf(link)).single() }.getOrNull()
      link.takeIf {
        observation?.kind == NoFollowEntryKind.SYMBOLIC_LINK && observation.normalizedLinkTarget == expectedSnapshot
      }
    }

  override fun symlinkCapability() = inspector.symlinkCapability()
}

private fun deleteWorkspaceTree(path: Path) {
  if (!Files.exists(path, NOFOLLOW_LINKS)) return
  Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
