package skillbill.install

import skillbill.scaffold.normalizePointerPath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun writeRenderedSupportPointerFiles(
  repoRoot: Path,
  sourceSkillDir: Path,
  tempDir: Path,
  pointers: List<GeneratedSupportPointer>,
): List<Path> = pointers.map { pointer ->
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  val targetFile = pointer.target.toAbsolutePath().normalize()
  val pointerFile = tempDir.resolve(pointer.name).normalize()
  require(pointerFile.startsWith(tempDir)) {
    "Supporting pointer '${pointer.name}' staging path '$pointerFile' escapes staging dir '$tempDir'."
  }
  require(targetFile.startsWith(resolvedRepoRoot)) {
    "Supporting pointer '${pointer.name}' target '$targetFile' escapes repoRoot '$resolvedRepoRoot'."
  }
  require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
    "Supporting pointer '${pointer.name}' targets '$targetFile' which does not exist."
  }
  require(resolvedSource.resolve(pointer.name).normalize() != targetFile) {
    "Supporting pointer '${pointer.name}' resolves to itself at '$targetFile'."
  }
  val rendered = normalizePointerPath(resolvedSource.relativize(targetFile).toString())
  Files.write(pointerFile, rendered.toByteArray(StandardCharsets.UTF_8))
  pointerFile
}
