package skillbill.managedskill

import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.managedskill.model.OpaqueSkillBundleFile
import skillbill.managedskill.model.requireSafeManagedSkillName
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class InvalidOpaqueSkillBundleException(
  message: String,
  cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class OpaqueSkillBundleScanner private constructor(
  private val context: OpaqueSkillBundleScanContext,
  @Suppress("UNUSED_PARAMETER") rootOpenTestSeam: Unit,
) {
  constructor() : this(OpaqueSkillBundleScanContext({}, true), Unit)

  internal constructor(beforeRootOpen: (Path) -> Unit) :
    this(OpaqueSkillBundleScanContext(beforeRootOpen, true), Unit)

  internal constructor(useSecureDirectoryStreams: Boolean) :
    this(OpaqueSkillBundleScanContext({}, useSecureDirectoryStreams), Unit)

  fun scan(source: Path, protectedNames: Set<String>): OpaqueSkillBundle = context.scan(source, protectedNames)
}

internal class OpaqueSkillBundleScanContext(
  val beforeRootOpen: (Path) -> Unit,
  val useSecureDirectoryStreams: Boolean,
  val limits: OpaqueSkillBundleScanLimits = OpaqueSkillBundleScanLimits(),
)

internal data class OpaqueSkillBundleScanLimits(
  val maxDepth: Int = 32,
  val maxFiles: Int = 2_000,
  val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
  val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
)

private const val DEFAULT_MAX_FILE_BYTES = 4_194_304L
private const val DEFAULT_MAX_TOTAL_BYTES = 33_554_432L

private fun OpaqueSkillBundleScanContext.scan(source: Path, protectedNames: Set<String>): OpaqueSkillBundle {
  val absoluteSource = source.toAbsolutePath().normalize()
  val sourceAttributes = readBundleAttributes(absoluteSource)
  val root = bundleRoot(absoluteSource, sourceAttributes)
  val only = if (sourceAttributes.isRegularFile) absoluteSource.fileName else null
  val captured = captureSecure(root, only).sortedBy { it.relativePath }
  if (captured.count { it.relativePath == "SKILL.md" } != 1) {
    invalidBundle("The directory must contain exactly one root SKILL.md.")
  }
  val skillText = captured.single { it.relativePath == "SKILL.md" }.content.toString(Charsets.UTF_8)
  val frontmatter = parseFrontmatter(skillText)
  val name = requireSafeManagedSkillName(frontmatter.path("name").textValue().orEmpty(), protectedNames)
  val description = frontmatter.path("description").textValue().orEmpty().trim()
  if (description.isEmpty()) invalidBundle("SKILL.md frontmatter requires a non-empty description.")
  val summary = hashCapturedFiles(captured)
  return OpaqueSkillBundle(name, description, absoluteSource, captured, summary.totalBytes, summary.digest)
}

private fun bundleRoot(source: Path, attributes: BasicFileAttributes): Path = when {
  attributes.isSymbolicLink -> invalidBundle("The selected source is a symbolic link.")
  attributes.isRegularFile && source.fileName.toString() == "SKILL.md" -> source.parent
  attributes.isRegularFile -> invalidBundle("The selected file must be named SKILL.md.")
  attributes.isDirectory -> source
  else -> invalidBundle("The selected source is not a regular file or directory.")
}

private data class CapturedFileHash(val totalBytes: Long, val digest: String)

private fun hashCapturedFiles(files: List<OpaqueSkillBundleFile>): CapturedFileHash {
  val digest = MessageDigest.getInstance("SHA-256")
  var totalBytes = 0L
  files.forEach { file ->
    val relativeBytes = file.relativePath.toByteArray()
    val content = file.content
    totalBytes += content.size
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relativeBytes.size).array())
    digest.update(relativeBytes)
    digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(content.size.toLong()).array())
    digest.update(content)
  }
  return CapturedFileHash(totalBytes, digest.digest().toHex())
}

internal inline fun <T> bundleOperation(message: String, operation: () -> T): T =
  runCatching(operation).getOrElse { error ->
    when (error) {
      is InvalidOpaqueSkillBundleException -> throw error
      is Exception -> throw InvalidOpaqueSkillBundleException(message, error)
      else -> throw error
    }
  }

internal fun invalidBundle(message: String): Nothing = throw InvalidOpaqueSkillBundleException(message)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
