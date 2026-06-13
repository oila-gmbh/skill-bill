package skillbill.scaffold.pointer

import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Renders the on-disk content of a pointer file as a single relative path.
 *
 * Pointer files live at `<packRoot>/<spec.skillRelativeDir>/<spec.name>` and contain a single
 * forward-slash-separated relative path from that location to `<repoRoot>/<spec.target>`. No
 * leading `./`, no double slashes, and no trailing newline. The renderer fails loud if the
 * declared target file does not exist, surfacing migration mistakes at render time rather than
 * leaking past validation. Failures are raised as [IllegalArgumentException] so they propagate
 * through `runWithUpgradeRollback` and trigger the rollback path.
 */
fun renderPointer(repoRoot: Path, packRoot: Path, spec: PointerSpec): String {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val pointerDir = resolvedPackRoot.resolve(spec.skillRelativeDir).normalize()
  val pointerFile = pointerDir.resolve(spec.name).normalize()
  val targetFile = resolvedRepoRoot.resolve(spec.target).normalize()
  require(targetFile.startsWith(resolvedRepoRoot)) {
    "Pointer '${spec.name}' target '${spec.target}' escapes repoRoot '$resolvedRepoRoot'."
  }
  require(pointerFile.startsWith(resolvedRepoRoot)) {
    "Pointer '${spec.name}' under '${spec.skillRelativeDir}' escapes repoRoot '$resolvedRepoRoot'."
  }
  require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
    "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
      "which does not exist at '$targetFile'."
  }
  if (Files.isSymbolicLink(targetFile)) {
    val real = targetFile.toRealPath()
    require(real.startsWith(resolvedRepoRoot)) {
      "Pointer '${spec.name}' target '${spec.target}' is a symlink pointing outside repoRoot at '$real'."
    }
  }
  require(pointerFile != targetFile) {
    "Pointer '${spec.name}' under '${spec.skillRelativeDir}' resolves to itself at '$pointerFile'."
  }
  val relative = pointerDir.relativize(targetFile).toString()
  return normalizePointerPath(relative)
}

internal fun normalizePointerPath(raw: String): String {
  val forwardSlashed = raw.replace('\\', '/')
  val withoutLeadingDot = if (forwardSlashed.startsWith("./")) forwardSlashed.removePrefix("./") else forwardSlashed
  return collapseDuplicateSlashes(withoutLeadingDot)
}

private fun collapseDuplicateSlashes(value: String): String {
  if ("//" !in value) {
    return value
  }
  val builder = StringBuilder(value.length)
  var previousSlash = false
  for (ch in value) {
    if (ch == '/') {
      if (!previousSlash) {
        builder.append(ch)
      }
      previousSlash = true
    } else {
      builder.append(ch)
      previousSlash = false
    }
  }
  return builder.toString()
}
