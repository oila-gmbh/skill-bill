package skillbill.domain.skillremove

import skillbill.model.FileLocation

internal fun validateAddOnRelativePath(relative: String, repoRoot: FileLocation): String? = when {
  relative.isBlank() -> "Invalid add-on path: must not be blank."
  relative.contains('\\') -> "Invalid add-on path '$relative': backslashes are not allowed."
  else -> validateResolvedAddOnRelativePath(relative, repoRoot)
}

private fun validateResolvedAddOnRelativePath(relative: String, repoRoot: FileLocation): String? {
  val parseProblem = malformedPathProblem(relative, "Invalid add-on path '$relative'")
  if (parseProblem != null) return parseProblem
  val path = FileLocation(relative)
  return when {
    path.isAbsolute -> "Invalid add-on path '$relative': absolute paths are not allowed."
    path.segments.any { segment -> segment == PARENT_SEGMENT } ->
      "Invalid add-on path '$relative': '..' segments are not allowed."
    else -> validateAddOnUnderPacksRoot(relative, repoRoot, path)
  }
}

private fun validateAddOnUnderPacksRoot(relative: String, repoRoot: FileLocation, path: FileLocation): String? {
  val resolved = repoRoot.resolve(path.value).normalized()
  val packsRoot = repoRoot.resolve("platform-packs").normalized()
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
  sourceRootAbsolutePath.contains(NUL_CHARACTER) ->
    "Invalid external add-on source path '$sourceRootAbsolutePath': malformed path."
  !FileLocation(sourceRootAbsolutePath).isAbsolute ->
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
  val sourceRoot = FileLocation(sourceRootAbsolutePath).normalized()
  val parseProblem = malformedPathProblem(fileName, "Invalid external add-on filename '$fileName'")
  if (parseProblem != null) return parseProblem
  val filePath = FileLocation(fileName)
  return when {
    filePath.isAbsolute -> "Invalid external add-on filename '$fileName': absolute paths are not allowed."
    filePath.segments.any { segment -> segment == PARENT_SEGMENT } ->
      "Invalid external add-on filename '$fileName': '..' segments are not allowed."
    sourceRoot.resolve(fileName).normalized().segments.dropLast(1) != sourceRoot.segments ->
      "Invalid external add-on filename '$fileName': must live directly in the source."
    else -> null
  }
}

private fun malformedPathProblem(value: String, label: String): String? =
  if (value.contains(NUL_CHARACTER)) "$label: Nul character not allowed: $value" else null

private const val PARENT_SEGMENT: String = ".."
private const val NUL_CHARACTER: Char = '\u0000'
