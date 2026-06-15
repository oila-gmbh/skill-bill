@file:Suppress("TooGenericExceptionCaught")

package skillbill.install.staging

import skillbill.install.model.RenderedSkill
import skillbill.scaffold.authoring.AuthoringTarget
import skillbill.scaffold.authoring.normalizeMarkdownLineEndings
import skillbill.scaffold.authoring.renderWrapper
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.pointer.renderPointer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

internal const val INSTALL_STAGING_SKILL_FILENAME = "SKILL.md"
internal const val INSTALL_STAGING_CONTENT_HASH_FILENAME = ".content-hash"

private val log: Logger = Logger.getLogger("skillbill.install.InstallStagingIO")

internal fun isReusableInstallStaging(finalStagingDir: Path, contentHash: String): Boolean {
  if (!Files.isDirectory(finalStagingDir)) {
    return false
  }
  val marker = finalStagingDir.resolve(INSTALL_STAGING_CONTENT_HASH_FILENAME)
  val markerIsFile = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
  val recorded = if (markerIsFile) {
    String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trimEnd('\n', '\r')
  } else {
    null
  }
  // F-011: marker alone is not enough — guard against partial-write residue or external pruning
  // by also requiring SKILL.md to exist as a regular file. Otherwise a half-written cache entry
  // would short-circuit the rebuild and we'd hand back an incomplete dir.
  val skillFile = finalStagingDir.resolve(INSTALL_STAGING_SKILL_FILENAME)
  val skillIsFile = Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)
  return markerIsFile && recorded == contentHash && skillIsFile
}

internal fun reuseInstallStaging(
  sourceSkillDir: Path,
  finalStagingDir: Path,
  contentHash: String,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
): RenderedSkill {
  val skillFile = finalStagingDir.resolve(INSTALL_STAGING_SKILL_FILENAME)
  val skillFileNormalized = skillFile.toAbsolutePath().normalize()
  val staged = Files.walk(finalStagingDir).use { stream ->
    stream
      .sorted()
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.fileName.toString() != INSTALL_STAGING_CONTENT_HASH_FILENAME }
      .filter { path -> path.toAbsolutePath().normalize() != skillFileNormalized }
      .toList()
  }
  // A staged file is "authored" iff a regular file exists at the same relative path under the
  // source skill dir and the relative path is not a generated pointer file; anything else is a
  // rendered pointer file.
  val finalRoot = finalStagingDir.toAbsolutePath().normalize()
  val pointerRelativePaths = (
    applicablePointers.map { (_, spec) -> spec.name } +
      generatedSupportPointers.map { pointer -> pointer.name }
    ).map { name -> Path.of(name) }.toSet()
  val authoredCopied = staged.filter { path ->
    val rel = finalRoot.relativize(path.toAbsolutePath().normalize())
    rel !in pointerRelativePaths && Files.isRegularFile(sourceSkillDir.resolve(rel), LinkOption.NOFOLLOW_LINKS)
  }
  val pointerFiles = staged.filter { path -> path !in authoredCopied }
  return RenderedSkill(
    skillName = sourceSkillDir.fileName.toString(),
    sourceSkillDir = sourceSkillDir,
    stagingDir = finalStagingDir,
    renderedSkillFile = skillFile,
    renderedPointerFiles = pointerFiles,
    copiedAuthoredFiles = authoredCopied,
    contentHash = contentHash,
  )
}

internal fun copyAuthoredIntoStaging(sourceSkillDir: Path, tempDir: Path, authored: List<Path>): List<Path> {
  val copied = mutableListOf<Path>()
  authored.forEach { file ->
    val rel = sourceSkillDir.relativize(file).toString().replace(File.separatorChar, '/')
    val dest = tempDir.resolve(rel).normalize()
    require(dest.startsWith(tempDir)) {
      "Authored file '$rel' resolves to '$dest' which escapes staging dir '$tempDir'."
    }
    Files.createDirectories(dest.parent)
    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
    copied.add(dest)
  }
  return copied
}

internal fun writeRenderedSkillFile(tempDir: Path, target: AuthoringTarget): Path {
  val skillFile = tempDir.resolve(INSTALL_STAGING_SKILL_FILENAME).normalize()
  require(skillFile.startsWith(tempDir)) {
    "SKILL.md staging path '$skillFile' escapes staging dir '$tempDir'."
  }
  Files.write(skillFile, renderWrapper(target).toByteArray(StandardCharsets.UTF_8))
  return skillFile
}

internal fun writeRenderedPointerFiles(
  repoRoot: Path,
  tempDir: Path,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
): List<Path> = pointers.map { (manifest, spec) ->
  val pointerFile = tempDir.resolve(spec.name).normalize()
  require(pointerFile.startsWith(tempDir)) {
    "Pointer '${spec.name}' staging path '$pointerFile' escapes staging dir '$tempDir'."
  }
  renderPointer(repoRoot = repoRoot, packRoot = manifest.packRoot, spec = spec)
  val targetFile = repoRoot.toAbsolutePath().normalize().resolve(spec.target).normalize()
  val rendered = normalizeMarkdownLineEndings(Files.readString(targetFile)).trimEnd() + "\n"
  Files.write(pointerFile, rendered.toByteArray(StandardCharsets.UTF_8))
  pointerFile
}

internal fun promoteInstallStagingDir(tempDir: Path, finalStagingDir: Path) {
  try {
    Files.move(
      tempDir,
      finalStagingDir,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  } catch (error: AtomicMoveNotSupportedException) {
    // Filesystem cannot rename atomically (e.g. across mounts). Fall back to a best-effort
    // replace. The recovery is intentional and the exception is purely informational here.
    @Suppress("UNUSED_VARIABLE")
    val ignored = error
    promoteByDeleteAndMove(tempDir, finalStagingDir)
  }
}

/**
 * F-010 fallback path: when ATOMIC_MOVE is unsupported we have to delete the existing
 * `finalStagingDir` (if any) and then move tempDir over. If that move throws AFTER the delete,
 * we may have wiped the prior good entry and only half-written a new one — clean up the partial
 * `finalStagingDir` before rethrowing so the caller never observes residue. The outer `promoted`
 * flag in `stageInstalledSkill` correctly stays `false` (we never finished promoting).
 */
private fun promoteByDeleteAndMove(tempDir: Path, finalStagingDir: Path) {
  if (Files.exists(finalStagingDir, LinkOption.NOFOLLOW_LINKS)) {
    deleteInstallStagingDirectory(finalStagingDir)
  }
  try {
    Files.move(tempDir, finalStagingDir, StandardCopyOption.REPLACE_EXISTING)
  } catch (error: IOException) {
    log.log(
      Level.SEVERE,
      "promoteByDeleteAndMove failed; cleaning up partial finalStagingDir=$finalStagingDir",
      error,
    )
    suppressedDelete(finalStagingDir)
    throw error
  } catch (error: RuntimeException) {
    log.log(
      Level.SEVERE,
      "promoteByDeleteAndMove failed; cleaning up partial finalStagingDir=$finalStagingDir",
      error,
    )
    suppressedDelete(finalStagingDir)
    throw error
  }
}

internal fun cleanupInstallStagingOnFailure(tempDir: Path, finalStagingDir: Path, promoted: Boolean) {
  // Always try to clear the temp dir we created during this attempt. Wrap each delete in its own
  // try/catch (suppressedDelete) so a secondary IO error during cleanup never shadows the
  // primary failure that triggered cleanup.
  log.log(
    Level.WARNING,
    "cleanupInstallStagingOnFailure tempDir=$tempDir finalDir=$finalStagingDir promoted=$promoted",
  )
  suppressedDelete(tempDir)
  // Only delete `finalStagingDir` if WE promoted it during this attempt. Otherwise it belongs to
  // a prior successful install and must not be touched.
  if (promoted) {
    suppressedDelete(finalStagingDir)
  }
}

private fun suppressedDelete(path: Path) {
  if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  try {
    deleteInstallStagingDirectory(path)
  } catch (error: IOException) {
    // Cleanup is best-effort. Log + swallow so the primary error keeps propagating.
    log.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  } catch (error: RuntimeException) {
    log.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  }
}

internal fun deleteInstallStagingDirectory(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
