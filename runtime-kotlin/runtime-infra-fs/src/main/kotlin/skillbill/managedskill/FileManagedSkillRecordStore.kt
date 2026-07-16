package skillbill.managedskill

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import skillbill.managedskill.model.ManagedSkillRecord
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class FileManagedSkillRecordStore private constructor(
  private val context: ManagedSkillRecordStoreContext,
  @Suppress("UNUSED_PARAMETER") publicationTestSeam: Unit,
) {
  constructor(
    homeDirectory: Path,
    forceDirectory: (Path) -> Unit = { directory ->
      forceDirectoryIfSupported(directory)
    },
  ) : this(
    ManagedSkillRecordStoreContext(
      managedStateRoot(homeDirectory, forceDirectory),
      null,
      forceDirectory,
      true,
    ),
    Unit,
  )

  internal constructor(
    stateRoot: Path,
    atomicReplace: (Path, Path) -> Unit,
    forceDirectory: (Path) -> Unit = { directory ->
      forceDirectoryIfSupported(directory)
    },
  ) : this(ManagedSkillRecordStoreContext(stateRoot, atomicReplace, forceDirectory, true), Unit)

  internal constructor(
    stateRoot: Path,
    useSecureDirectoryStreams: Boolean,
  ) : this(
    ManagedSkillRecordStoreContext(
      stateRoot,
      null,
      ::forceDirectoryIfSupported,
      useSecureDirectoryStreams,
    ),
    Unit,
  )

  companion object {
    const val EXPECTED_ABSENT = "absent"

    internal fun fromStateRoot(stateRoot: Path): FileManagedSkillRecordStore = FileManagedSkillRecordStore(
      ManagedSkillRecordStoreContext(stateRoot, null, ::forceDirectoryIfSupported, true),
      Unit,
    )

    internal fun openReadOnly(homeDirectory: Path): FileManagedSkillRecordStore? {
      val stateRoot = homeDirectory.toAbsolutePath().normalize().resolve(".skill-bill")
      if (Files.notExists(stateRoot, NOFOLLOW_LINKS)) return null
      val attributes = Files.readAttributes(
        stateRoot,
        java.nio.file.attribute.BasicFileAttributes::class.java,
        NOFOLLOW_LINKS,
      )
      require(attributes.isDirectory && !attributes.isSymbolicLink) {
        "Managed state root is not a real directory: $stateRoot"
      }
      return fromStateRoot(stateRoot)
    }
  }

  fun recordPath(name: String): Path = context.recordPath(name)

  fun sourceRoot(name: String): Path = context.sourceRoot(name)

  fun snapshotRoot(name: String, contentHash: String): Path = context.snapshotRoot(name, contentHash)

  fun read(name: String): ManagedSkillRecord = context.read(name)

  fun readPath(path: Path): ManagedSkillRecord = context.readPath(path)

  fun write(record: ManagedSkillRecord, expectedDigest: String? = null) = context.write(record, expectedDigest)

  fun digest(name: String): String? = context.digest(name)

  fun listRecords(): List<ManagedSkillRecordDiscovery> {
    val directory = context.stateRoot.resolve("managed-skills")
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
    return Files.newDirectoryStream(directory).use { entries ->
      entries.map { entry ->
        val path = entry.resolve("record.json")
        runCatching { ManagedSkillRecordDiscovery(path, readPath(path), null) }
          .getOrElse { error -> ManagedSkillRecordDiscovery(entry, null, error.message ?: error::class.java.name) }
      }.sortedBy { it.path.fileName.toString() }
    }
  }

  fun listSnapshotPaths(): List<Path> {
    val directory = context.stateRoot.resolve("installed-skills")
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return emptyList()
    return Files.newDirectoryStream(directory).use { entries ->
      entries.map(Path::toAbsolutePath).map(Path::normalize).sortedBy(Path::toString)
    }
  }
}

data class ManagedSkillRecordDiscovery(
  val path: Path,
  val record: ManagedSkillRecord?,
  val error: String?,
)

internal class ManagedSkillRecordStoreContext(
  stateRoot: Path,
  val atomicReplace: ((Path, Path) -> Unit)?,
  val forceDirectory: (Path) -> Unit,
  val useSecureDirectoryStreams: Boolean,
) {
  val stateRoot: Path = stateRoot.toAbsolutePath().normalize()
  val stateRootIdentity: Any? = recordDirectoryAttributes(this.stateRoot).fileKey()
  val mapper: ObjectMapper = ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(SerializationFeature.INDENT_OUTPUT)
}
