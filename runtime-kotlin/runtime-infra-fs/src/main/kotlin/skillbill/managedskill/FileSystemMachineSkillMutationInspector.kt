package skillbill.managedskill

import skillbill.managedskill.model.FileSystemIdentity
import skillbill.managedskill.model.IdentityCapability
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.PathObservation
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.MachineSkillMutationInspectorPort
import skillbill.ports.managedskill.model.SnapshotReferenceDiscovery
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

fun interface NativeSymlinkCapabilityProbe {
  fun capability(): SymlinkCapability
}

class FileSystemMachineSkillMutationInspector(
  private val home: Path,
  private val targets: List<Path>,
  private val capabilityProbe: NativeSymlinkCapabilityProbe =
    NativeSymlinkCapabilityProbe { SymlinkCapability.AVAILABLE },
) : MachineSkillMutationInspectorPort {
  override fun observe(paths: Collection<Path>): List<PathObservation> =
    paths.distinct().sortedBy(Path::toString).map(::observe)

  private fun observe(path: Path): PathObservation {
    if (Files.notExists(path, NOFOLLOW_LINKS)) {
      return PathObservation(
        path,
        NoFollowEntryKind.ABSENT,
        FileSystemIdentity(IdentityCapability.UNAVAILABLE),
      )
    }
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val kind = when {
      attributes.isSymbolicLink -> NoFollowEntryKind.SYMBOLIC_LINK
      attributes.isRegularFile -> NoFollowEntryKind.REGULAR_FILE
      attributes.isDirectory -> NoFollowEntryKind.DIRECTORY
      else -> NoFollowEntryKind.SPECIAL
    }
    val raw = if (attributes.isSymbolicLink) Files.readSymbolicLink(path).toString() else null
    val normalized = raw?.let { target ->
      val value = Path.of(target)
      (if (value.isAbsolute) value else path.toAbsolutePath().parent.resolve(value)).normalize()
    }
    val key = attributes.fileKey()?.toString()
    val identityCapability =
      if (key == null) IdentityCapability.UNAVAILABLE else IdentityCapability.AVAILABLE
    return PathObservation(
      path.toAbsolutePath().normalize(),
      kind,
      FileSystemIdentity(identityCapability, key),
      raw,
      normalized,
    )
  }

  override fun symlinkCapability(): SymlinkCapability = capabilityProbe.capability()

  override fun snapshotReferences(): SnapshotReferenceDiscovery {
    val store = FileManagedSkillRecordStore.openReadOnly(home)
    val records = store?.listRecords().orEmpty()
    val warnings = records.mapNotNull { it.error }
    val fromRecords =
      records.mapNotNull { it.record }.map { store!!.snapshotRoot(it.name, it.activeContentHash) }
    val fromLinks = targets.flatMap { root ->
      if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
        emptyList()
      } else {
        Files.newDirectoryStream(root).use { entries ->
          entries.filter { Files.isSymbolicLink(it) }.mapNotNull { link ->
            runCatching { observe(link).normalizedLinkTarget }.getOrNull()
          }
        }
      }
    }
    return SnapshotReferenceDiscovery((fromRecords + fromLinks).toSet(), warnings.isEmpty(), warnings)
  }
}
