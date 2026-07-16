package skillbill.managedskill

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.managedskill.model.OpaqueSkillBundleFile
import skillbill.managedskill.model.requireSafeManagedSkillName
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader

class InvalidOpaqueSkillBundleException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class OpaqueSkillBundleScanner private constructor(
  private val beforeRootOpen: (Path) -> Unit,
  private val useSecureDirectoryStreams: Boolean,
  private val allowOrdinaryFileFallback: Boolean,
  @Suppress("UNUSED_PARAMETER") rootOpenTestSeam: Unit,
) {
  constructor() : this({}, true, false, Unit)

  internal constructor(beforeRootOpen: (Path) -> Unit) : this(beforeRootOpen, true, false, Unit)

  internal constructor(
    useSecureDirectoryStreams: Boolean,
    allowOrdinaryFileFallback: Boolean = false,
  ) : this({}, useSecureDirectoryStreams, allowOrdinaryFileFallback, Unit)

  fun scan(source: Path, protectedNames: Set<String>): OpaqueSkillBundle {
    val absoluteSource = source.toAbsolutePath().normalize()
    val attributes = readAttributes(absoluteSource)
    val root = when {
      attributes.isSymbolicLink -> fail("The selected source is a symbolic link.")
      attributes.isRegularFile && absoluteSource.fileName.toString() == "SKILL.md" -> absoluteSource.parent
      attributes.isRegularFile -> fail("The selected file must be named SKILL.md.")
      attributes.isDirectory -> absoluteSource
      else -> fail("The selected source is not a regular file or directory.")
    }
    val singleFile = attributes.isRegularFile
    val captured = captureSecure(root, if (singleFile) absoluteSource.fileName else null).sortedBy { it.relativePath }
    if (captured.count { it.relativePath == "SKILL.md" } != 1) fail("The directory must contain exactly one root SKILL.md.")
    val skillText = captured.single { it.relativePath == "SKILL.md" }.content.toString(Charsets.UTF_8)
    val frontmatter = parseFrontmatter(skillText)
    val name = requireSafeManagedSkillName(frontmatter.path("name").textValue().orEmpty(), protectedNames)
    val description = frontmatter.path("description").textValue().orEmpty().trim()
    if (description.isEmpty()) fail("SKILL.md frontmatter requires a non-empty description.")
    val digest = MessageDigest.getInstance("SHA-256")
    var totalBytes = 0L
    captured.forEach { file ->
      val relative = file.relativePath
      val bytes = file.content
      totalBytes += bytes.size
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relative.toByteArray().size).array())
      digest.update(relative.toByteArray())
      digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
      digest.update(bytes)
    }
    return OpaqueSkillBundle(name, description, absoluteSource, captured, totalBytes, digest.digest().toHex())
  }

  private fun captureSecure(root: Path, only: Path?): List<OpaqueSkillBundleFile> = try {
    val before = readAttributes(root)
    if (!before.isDirectory || before.isSymbolicLink) fail("The bundle root is not a real directory.")
    beforeRootOpen(root)
    val parentPath = root.parent
    val captured = if (parentPath == null) {
      capturePathDirectory(root, before, only)
    } else {
      Files.newDirectoryStream(parentPath).use { parent ->
        if (useSecureDirectoryStreams && parent is SecureDirectoryStream<Path>) {
          parent.newDirectoryStream(root.fileName, NOFOLLOW_LINKS).use { opened ->
            val openedAttributes = secureAttributes(opened, Path.of("."))
            requireSameDirectory(root, before, openedAttributes)
            captureDirectory(SecureBundleDirectory(opened), "", only)
          }
        } else {
          capturePathDirectory(root, before, only)
        }
      }
    }
    requireUnchangedRoot(root, before)
    captured
  } catch (error: InvalidOpaqueSkillBundleException) {
    throw error
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot capture the selected bundle without following links: $root", error)
  }

  private fun capturePathDirectory(
    root: Path,
    attributes: BasicFileAttributes,
    only: Path?,
  ): List<OpaqueSkillBundleFile> {
    val scheme = root.fileSystem.provider().scheme
    if (scheme != "jar" && !allowsOrdinaryFileFallback(root)) {
      fail("The filesystem cannot provide identity-bound bundle traversal.")
    }
    return captureDirectory(PathBundleDirectory(root, attributes, noFollowChannels = scheme != "jar"), "", only)
  }

  private fun captureDirectory(
    directory: BundleDirectory,
    prefix: String,
    only: Path? = null,
  ): List<OpaqueSkillBundleFile> {
    val names = if (only == null) directory.names() else listOf(only)
    return names.flatMap { name ->
      val relative = if (prefix.isEmpty()) name.toString() else "$prefix/$name"
      if (name.isAbsolute || name.normalize().startsWith("..")) fail("Bundle path escapes the selected directory: $relative")
      val attributes = directory.attributes(name)
      when {
        attributes.isSymbolicLink -> fail("Symbolic links are not allowed: $relative")
        attributes.isRegularFile -> listOf(OpaqueSkillBundleFile(relative, readStableBytes(directory, name, relative, attributes)))
        attributes.isDirectory -> directory.openDirectory(name).use { captureDirectory(it, relative) }
        else -> fail("Special files are not allowed: $relative")
      }
    }
  }

  private fun parseFrontmatter(text: String): JsonNode {
    val lines = text.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") fail("SKILL.md must start with YAML frontmatter.")
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) fail("SKILL.md frontmatter is not terminated.")
    val yaml = lines.subList(1, end + 1).joinToString("\n")
    rejectYamlOwnershipSyntax(yaml)
    val mapper = YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    val node = try { mapper.readTree(yaml) } catch (error: Exception) {
      throw InvalidOpaqueSkillBundleException("SKILL.md frontmatter is invalid YAML.", error)
    }
    if (!node.isObject) fail("SKILL.md frontmatter must be a YAML mapping.")
    if (!node.path("name").isTextual || !node.path("description").isTextual) {
      fail("SKILL.md frontmatter name and description must be strings.")
    }
    if (containsNestedOwnershipName(node)) fail("SKILL.md frontmatter must not contain a nested name key.")
    return node
  }

  private fun rejectYamlOwnershipSyntax(yaml: String) {
    try {
      val parser = ParserImpl(StreamReader(yaml), LoaderOptions())
      while (parser.peekEvent() != null) {
        val event = parser.event
        val explicitTag = when (event) {
          is ScalarEvent -> event.tag
          is CollectionStartEvent -> event.tag
          else -> null
        }
        if (event is AliasEvent || event is NodeEvent && (event.anchor != null || explicitTag != null)) {
          fail("SKILL.md frontmatter must not contain aliases, anchors, or custom tags.")
        }
      }
    } catch (error: InvalidOpaqueSkillBundleException) {
      throw error
    } catch (error: Exception) {
      throw InvalidOpaqueSkillBundleException("SKILL.md frontmatter is invalid YAML.", error)
    }
  }

  private fun requireUnchangedRoot(root: Path, before: BasicFileAttributes) {
    val after = readAttributes(root)
    if (!after.isDirectory || after.isSymbolicLink ||
      before.fileKey() != null && after.fileKey() != null && before.fileKey() != after.fileKey() ||
      before.lastModifiedTime() != after.lastModifiedTime()
    ) {
      fail("The bundle root changed while being scanned.")
    }
  }

  private fun containsNestedOwnershipName(root: JsonNode): Boolean {
    fun visit(node: JsonNode, depth: Int): Boolean = when {
      node.isObject -> node.fields().asSequence().any { (key, value) ->
        (depth > 0 && key == "name") || visit(value, depth + 1)
      }
      node.isArray -> node.elements().asSequence().any { visit(it, depth + 1) }
      else -> false
    }
    return root.fields().asSequence().any { (_, value) -> visit(value, 1) }
  }

  private fun readAttributes(path: Path): BasicFileAttributes = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot inspect bundle entry: $path", error)
  }

  private fun readStableBytes(
    directory: BundleDirectory,
    name: Path,
    relative: String,
    before: BasicFileAttributes,
  ): ByteArray = try {
    if (!before.isRegularFile || before.isSymbolicLink) fail("Bundle entry changed type while being read: $relative")
    val bytes = directory.newByteChannel(name).use { channel -> readAll(channel) }
    val after = directory.attributes(name)
    requireStableFile(before, after, relative)
    bytes
  } catch (error: InvalidOpaqueSkillBundleException) {
    throw error
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot read bundle entry without following links: $relative", error)
  }

  private fun requireStableFile(before: BasicFileAttributes, after: BasicFileAttributes, relative: String) {
    if (!after.isRegularFile || after.isSymbolicLink ||
      (before.fileKey() != null && after.fileKey() != null && before.fileKey() != after.fileKey()) ||
      before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()
    ) {
      fail("Bundle entry changed while being read: $relative")
    }
  }

  private fun readAll(channel: java.nio.channels.SeekableByteChannel): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(8192)
    while (channel.read(buffer) >= 0) {
      buffer.flip()
      output.write(buffer.array(), 0, buffer.remaining())
      buffer.clear()
    }
    return output.toByteArray()
  }

  private fun requireSameDirectory(path: Path, expected: BasicFileAttributes, actual: BasicFileAttributes) {
    if (!actual.isDirectory || actual.isSymbolicLink ||
      expected.fileKey() != null && actual.fileKey() != null && expected.fileKey() != actual.fileKey()
    ) fail("The bundle root identity changed before traversal: $path")
  }

  private interface BundleDirectory : AutoCloseable {
    fun names(): List<Path>
    fun attributes(name: Path): BasicFileAttributes
    fun newByteChannel(name: Path): SeekableByteChannel
    fun openDirectory(name: Path): BundleDirectory
    override fun close() = Unit
  }

  private class SecureBundleDirectory(private val stream: SecureDirectoryStream<Path>) : BundleDirectory {
    override fun names() = stream.map { it.fileName }
    override fun attributes(name: Path) = secureAttributes(stream, name)
    override fun newByteChannel(name: Path) = stream.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS))
    override fun openDirectory(name: Path) = SecureBundleDirectory(stream.newDirectoryStream(name, NOFOLLOW_LINKS))
    override fun close() = stream.close()
  }

  private fun allowsOrdinaryFileFallback(root: Path): Boolean {
    return allowOrdinaryFileFallback || (
      root.fileSystem.provider().scheme == "file" &&
        System.getProperty("os.name").contains("windows", ignoreCase = true)
      )
  }

  private class PathBundleDirectory(
    private val path: Path,
    private val identity: BasicFileAttributes,
    private val noFollowChannels: Boolean,
  ) : BundleDirectory {
    init {
      if (noFollowChannels && identity.fileKey() == null) {
        throw InvalidOpaqueSkillBundleException("The filesystem cannot provide stable bundle directory identity: $path")
      }
    }

    override fun names(): List<Path> = checked {
      Files.newDirectoryStream(path).use { entries -> entries.map { it.fileName } }
    }

    override fun attributes(name: Path): BasicFileAttributes = checked {
      Files.readAttributes(resolve(name), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    }

    override fun newByteChannel(name: Path): SeekableByteChannel = checked {
      val options = if (noFollowChannels) setOf(READ, NOFOLLOW_LINKS) else setOf(READ)
      Files.newByteChannel(resolve(name), options)
    }

    override fun openDirectory(name: Path): BundleDirectory = checked {
      val child = resolve(name)
      val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      if (!attributes.isDirectory || attributes.isSymbolicLink) {
        throw InvalidOpaqueSkillBundleException("Symbolic links are not allowed: $name")
      }
      PathBundleDirectory(child, attributes, noFollowChannels)
    }

    private fun resolve(name: Path): Path {
      if (name.isAbsolute || name.nameCount != 1 || name.normalize().startsWith("..")) {
        throw InvalidOpaqueSkillBundleException("Bundle entry escapes its directory: $name")
      }
      return path.resolve(name)
    }

    private inline fun <T> checked(operation: () -> T): T {
      requireIdentity()
      return operation().also {
        requireIdentity()
      }
    }

    private fun requireIdentity() {
      val actual = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      if (!actual.isDirectory || actual.isSymbolicLink ||
        noFollowChannels && actual.fileKey() == null ||
        identity.fileKey() != null && actual.fileKey() != null && identity.fileKey() != actual.fileKey()
      ) {
        throw InvalidOpaqueSkillBundleException("The bundle directory identity changed during traversal: $path")
      }
    }
  }

  private fun fail(message: String): Nothing = throw InvalidOpaqueSkillBundleException(message)
  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

private fun secureAttributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes = try {
  directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
} catch (error: Exception) {
  throw InvalidOpaqueSkillBundleException("Cannot inspect bundle entry: $name", error)
}
