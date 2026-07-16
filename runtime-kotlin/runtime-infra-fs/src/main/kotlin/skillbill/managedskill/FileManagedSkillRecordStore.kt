package skillbill.managedskill

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import skillbill.managedskill.model.ManagedSkillRecord
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
  }

  fun recordPath(name: String): Path = context.recordPath(name)

  fun sourceRoot(name: String): Path = context.sourceRoot(name)

  fun snapshotRoot(name: String, contentHash: String): Path = context.snapshotRoot(name, contentHash)

  fun read(name: String): ManagedSkillRecord = context.read(name)

  fun readPath(path: Path): ManagedSkillRecord = context.readPath(path)

  fun write(record: ManagedSkillRecord, expectedDigest: String? = null) = context.write(record, expectedDigest)

  fun digest(name: String): String? = context.digest(name)
}

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
