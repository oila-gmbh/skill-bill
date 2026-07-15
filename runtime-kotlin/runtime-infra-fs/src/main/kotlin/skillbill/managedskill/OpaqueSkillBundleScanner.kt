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
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

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
    val files = if (singleFile) listOf(absoluteSource) else collectRegularFiles(root)
    val skillFiles = files.filter { root.relativize(it).toString().replace('\\', '/') == "SKILL.md" }
    if (skillFiles.size != 1) fail("The directory must contain exactly one root SKILL.md.")
    val captured = files.map { file -> OpaqueSkillBundleFile(normalizedRelative(root, file), readStableBytes(file)) }
      .sortedBy { it.relativePath }
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

  private fun collectRegularFiles(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    Files.walk(root).use { paths ->
      paths.forEach { path ->
        val attributes = readAttributes(path)
        when {
          path == root -> Unit
          attributes.isSymbolicLink -> fail("Symbolic links are not allowed: ${root.relativize(path)}")
          attributes.isRegularFile -> files.add(path)
          attributes.isDirectory -> Unit
          else -> fail("Special files are not allowed: ${root.relativize(path)}")
        }
      }
    }
    return files
  }

  private fun normalizedRelative(root: Path, file: Path): String {
    val normalized = root.relativize(file).normalize()
    if (normalized.isAbsolute || normalized.startsWith("..")) fail("Bundle path escapes the selected directory: $normalized")
    return normalized.joinToString("/") { it.toString() }
  }

  private fun parseFrontmatter(text: String): JsonNode {
    val lines = text.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") fail("SKILL.md must start with YAML frontmatter.")
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) fail("SKILL.md frontmatter is not terminated.")
    val yaml = lines.subList(1, end + 1).joinToString("\n")
    val mapper = YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    val node = try { mapper.readTree(yaml) } catch (error: Exception) {
      throw InvalidOpaqueSkillBundleException("SKILL.md frontmatter is invalid YAML.", error)
    }
    if (!node.isObject) fail("SKILL.md frontmatter must be a YAML mapping.")
    if (!node.path("name").isTextual || !node.path("description").isTextual) {
      fail("SKILL.md frontmatter name and description must be strings.")
    }
    return node
  }

  private fun readAttributes(path: Path): BasicFileAttributes = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot inspect bundle entry: $path", error)
  }

  private fun readStableBytes(path: Path): ByteArray = try {
    val before = readAttributes(path)
    if (!before.isRegularFile || before.isSymbolicLink) fail("Bundle entry changed type while being read: $path")
    val bytes = Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteBuffer.allocate(8192)
      while (channel.read(buffer) >= 0) {
        buffer.flip()
        output.write(buffer.array(), 0, buffer.remaining())
        buffer.clear()
      }
      output.toByteArray()
    }
    val after = readAttributes(path)
    if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
      fail("Bundle entry changed while being read: $path")
    }
    bytes
  } catch (error: InvalidOpaqueSkillBundleException) {
    throw error
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot read bundle entry without following links: $path", error)
  }

  private fun fail(message: String): Nothing = throw InvalidOpaqueSkillBundleException(message)
  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
