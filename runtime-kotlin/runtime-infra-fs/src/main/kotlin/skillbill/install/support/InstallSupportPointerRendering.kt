package skillbill.install.support

import skillbill.install.staging.GeneratedSupportPointer
import skillbill.scaffold.authoring.normalizeMarkdownLineEndings
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
  // Inline the canonical doc instead of a repo-relative path: the installed-skills cache is detached
  // from the repo, so a relative pointer dangles when an agent resolves it from the cache location.
  val rendered = normalizeMarkdownLineEndings(Files.readString(targetFile)).trimEnd() + "\n"
  Files.write(pointerFile, rendered.toByteArray(StandardCharsets.UTF_8))
  pointerFile
}
