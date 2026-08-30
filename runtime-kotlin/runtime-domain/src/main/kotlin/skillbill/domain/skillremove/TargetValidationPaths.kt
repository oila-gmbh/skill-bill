package skillbill.domain.skillremove

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

internal fun validateAddOnRelativePath(relative: String, repoRoot: Path): String? = when {
  relative.isBlank() -> "Invalid add-on path: must not be blank."
  relative.contains('\\') -> "Invalid add-on path '$relative': backslashes are not allowed."
  else -> validateResolvedAddOnRelativePath(relative, repoRoot)
}

private fun validateResolvedAddOnRelativePath(relative: String, repoRoot: Path): String? {
  val (parsed, parseProblem) = parseRelativePath(relative, "Invalid add-on path '$relative'")
  if (parseProblem != null) return parseProblem
  val path = parsed!!
  return when {
    path.isAbsolute -> "Invalid add-on path '$relative': absolute paths are not allowed."
    path.any { it.toString() == ".." } -> "Invalid add-on path '$relative': '..' segments are not allowed."
    else -> validateAddOnUnderPacksRoot(relative, repoRoot, path)
  }
}

private fun validateAddOnUnderPacksRoot(relative: String, repoRoot: Path, path: Path): String? {
  val resolved = repoRoot.resolve(path).normalize()
  val packsRoot = repoRoot.resolve("platform-packs").normalize()
  return when {
    !resolved.startsWith(repoRoot) ->
      "Invalid add-on path '$relative': resolves outside the repository root."
    !resolved.startsWith(packsRoot) ->
      "Invalid add-on path '$relative': add-ons must live under 'platform-packs/'."
    else -> null
  }
}

internal fun validateExternalAddOnPaths(sourceRootAbsolutePath: String, fileName: String): String? =
  validateExternalAddOnSourceRoot(sourceRootAbsolutePath)
    ?: validateExternalAddOnFileName(sourceRootAbsolutePath, fileName)

private fun validateExternalAddOnSourceRoot(sourceRootAbsolutePath: String): String? = when {
  sourceRootAbsolutePath.isBlank() -> "Invalid external add-on source path: must not be blank."
  parseAbsolutePath(sourceRootAbsolutePath) == null ->
    "Invalid external add-on source path '$sourceRootAbsolutePath': malformed path."
  !Paths.get(sourceRootAbsolutePath).isAbsolute ->
    "Invalid external add-on source path '$sourceRootAbsolutePath': must be absolute."
  else -> null
}

private fun validateExternalAddOnFileName(sourceRootAbsolutePath: String, fileName: String): String? = when {
  fileName.isBlank() -> "Invalid external add-on filename: must not be blank."
  !fileName.endsWith(".md") -> "Invalid external add-on filename '$fileName': must end with '.md'."
  fileName.contains('/') || fileName.contains('\\') ->
    "Invalid external add-on filename '$fileName': path separators are not allowed."
  else -> validateExternalAddOnFileNameResolved(sourceRootAbsolutePath, fileName)
}

private fun validateExternalAddOnFileNameResolved(sourceRootAbsolutePath: String, fileName: String): String? {
  val sourceRoot = parseAbsolutePath(sourceRootAbsolutePath)!!
  val (parsedPath, parseProblem) = parseRelativePath(fileName, "Invalid external add-on filename '$fileName'")
  if (parseProblem != null) return parseProblem
  val filePath = parsedPath!!
  return when {
    filePath.isAbsolute -> "Invalid external add-on filename '$fileName': absolute paths are not allowed."
    filePath.any { it.toString() == ".." } ->
      "Invalid external add-on filename '$fileName': '..' segments are not allowed."
    sourceRoot.resolve(filePath).normalize().parent != sourceRoot ->
      "Invalid external add-on filename '$fileName': must live directly in the source."
    else -> null
  }
}

private fun parseRelativePath(value: String, label: String): Pair<Path?, String?> = try {
  Paths.get(value) to null
} catch (error: InvalidPathException) {
  @Suppress("SwallowedException")
  null to "$label: ${error.message.orEmpty()}"
}

private fun parseAbsolutePath(value: String): Path? = try {
  Paths.get(value).toAbsolutePath().normalize()
} catch (error: InvalidPathException) {
  @Suppress("SwallowedException")
  null
}
