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
import java.security.MessageDigest
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader

class InvalidOpaqueSkillBundleException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class OpaqueSkillBundleScanner {
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
    val captured = Files.newDirectoryStream(root).use { directory ->
      if (directory is SecureDirectoryStream<Path>) {
        val opened = secureAttributes(directory, Path.of("."))
        val pathAfterOpen = readAttributes(root)
        if (!opened.isDirectory || opened.isSymbolicLink || pathAfterOpen.isSymbolicLink ||
          before.fileKey() != null && opened.fileKey() != null && before.fileKey() != opened.fileKey() ||
          opened.fileKey() != null && pathAfterOpen.fileKey() != null && opened.fileKey() != pathAfterOpen.fileKey()
        ) fail("The bundle root identity changed before traversal.")
        captureDirectory(directory, "", only)
      } else {
        fail("Bundle capture requires identity-bound directory handles.")
      }
    }
    requireUnchangedRoot(root, before)
    captured
  } catch (error: InvalidOpaqueSkillBundleException) {
    throw error
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot capture the selected bundle without following links: $root", error)
  }

  private fun captureDirectory(
    directory: SecureDirectoryStream<Path>,
    prefix: String,
    only: Path? = null,
  ): List<OpaqueSkillBundleFile> {
    val names = if (only == null) directory.map { it.fileName } else listOf(only)
    return names.flatMap { name ->
      val relative = if (prefix.isEmpty()) name.toString() else "$prefix/$name"
      if (name.isAbsolute || name.normalize().startsWith("..")) fail("Bundle path escapes the selected directory: $relative")
      val attributes = secureAttributes(directory, name)
      when {
        attributes.isSymbolicLink -> fail("Symbolic links are not allowed: $relative")
        attributes.isRegularFile -> listOf(OpaqueSkillBundleFile(relative, readStableBytes(directory, name, relative, attributes)))
        attributes.isDirectory -> directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { captureDirectory(it, relative) }
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

  private fun secureAttributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes = try {
    directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot inspect bundle entry: $name", error)
  }

  private fun readStableBytes(
    directory: SecureDirectoryStream<Path>,
    name: Path,
    relative: String,
    before: BasicFileAttributes,
  ): ByteArray = try {
    if (!before.isRegularFile || before.isSymbolicLink) fail("Bundle entry changed type while being read: $relative")
    val bytes = directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel -> readAll(channel) }
    val after = secureAttributes(directory, name)
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

  private fun fail(message: String): Nothing = throw InvalidOpaqueSkillBundleException(message)
  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
