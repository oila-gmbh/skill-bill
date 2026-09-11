package skillbill.nativeagent.platformpack

import skillbill.error.InvalidManifestSchemaError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_REQUIRED_GUIDANCE_BYTES = 256 * 1024L
private const val MAX_REQUIRED_COMPANIONS = 16

internal fun readRequiredRubricCompanions(packRoot: Path, contentPath: Path, names: List<String>): Map<Path, String> {
  if (names.isEmpty()) return emptyMap()
  if (names.size > MAX_REQUIRED_COMPANIONS || names.distinct().size != names.size) {
    throw InvalidManifestSchemaError("Required rubric companion declaration exceeds its bounds or repeats a file.")
  }
  return try {
    val root = packRoot.toRealPath()
    val owner = contentPath.parent.toRealPath()
    validateCompanionOwner(root, owner)
    var total = 0L
    names.associate { name ->
      val real = requiredCompanionPath(owner, name)
      val bytes = Files.newInputStream(real).use {
        it.readNBytes((MAX_REQUIRED_GUIDANCE_BYTES - total + 1).toInt())
      }
      total += bytes.size
      validateCompanionBytes(total)
      real to bytes.toString(Charsets.UTF_8)
    }
  } catch (error: IOException) {
    throw InvalidManifestSchemaError("Required rubric companion is missing or unreadable.", error)
  }
}

private fun validateCompanionOwner(root: Path, owner: Path) {
  if (!owner.startsWith(root)) {
    throw InvalidManifestSchemaError("Required companion owner escapes its pack.")
  }
}

private fun validateCompanionBytes(total: Long) {
  if (total > MAX_REQUIRED_GUIDANCE_BYTES) {
    throw InvalidManifestSchemaError("Required rubric guidance exceeds its byte limit.")
  }
}

private fun requiredCompanionPath(owner: Path, name: String): Path {
  val invalidName = listOf("/", "\\", "..").any(name::contains)
  if (invalidName || !name.endsWith(".md") || name == "content.md") {
    throw InvalidManifestSchemaError("Required companion must be a specialist-owned Markdown filename.")
  }
  val real = owner.resolve(name).toRealPath()
  if (!real.startsWith(owner) || !Files.isRegularFile(real) || !Files.isReadable(real)) {
    throw InvalidManifestSchemaError("Required rubric companion is unreadable or escapes its specialist directory.")
  }
  return real
}
