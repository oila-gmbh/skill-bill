package skillbill.managedskill

import skillbill.managedskill.model.OpaqueSkillBundle
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
    val skillText = readText(skillFiles.single())
    val frontmatter = parseFrontmatter(skillText)
    val name = requireSafeManagedSkillName(frontmatter["name"].orEmpty(), protectedNames)
    val description = frontmatter["description"].orEmpty().trim()
    if (description.isEmpty()) fail("SKILL.md frontmatter requires a non-empty description.")
    val sorted = files.sortedBy { normalizedRelative(root, it) }
    val digest = MessageDigest.getInstance("SHA-256")
    var totalBytes = 0L
    sorted.forEach { file ->
      val relative = normalizedRelative(root, file)
      val bytes = readBytes(file)
      totalBytes += bytes.size
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relative.toByteArray().size).array())
      digest.update(relative.toByteArray())
      digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
      digest.update(bytes)
    }
    return OpaqueSkillBundle(name, description, absoluteSource, sorted, totalBytes, digest.digest().toHex())
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

  private fun parseFrontmatter(text: String): Map<String, String> {
    val lines = text.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") fail("SKILL.md must start with YAML frontmatter.")
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) fail("SKILL.md frontmatter is not terminated.")
    return lines.subList(1, end + 1).mapNotNull { line ->
      val separator = line.indexOf(':')
      if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"', '\'')
    }.toMap()
  }

  private fun readAttributes(path: Path): BasicFileAttributes = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
  } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot inspect bundle entry: $path", error)
  }

  private fun readText(path: Path): String = try { Files.readString(path) } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot read SKILL.md: $path", error)
  }

  private fun readBytes(path: Path): ByteArray = try { Files.readAllBytes(path) } catch (error: Exception) {
    throw InvalidOpaqueSkillBundleException("Cannot read bundle entry: $path", error)
  }

  private fun fail(message: String): Nothing = throw InvalidOpaqueSkillBundleException(message)
  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
