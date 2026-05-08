package skillbill.scaffold

import skillbill.error.ShellContentContractException
import skillbill.scaffold.model.PlatformManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

private const val POINTER_FILE_MAX_BYTES: Long = 500L
private val POINTER_CONTENT_PATTERN: Regex = Regex("^(\\.{1,2}/)+[^\\s]+\\.md$")

data class PointerValidationReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

fun validatePlatformPackPointers(repoRoot: Path): PointerValidationReport {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val packsRoot = resolvedRepoRoot.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return PointerValidationReport(emptyList())
  }
  val packs = loadValidPlatformPackManifests(packsRoot)
  val issues = mutableListOf<String>()
  val declaredFiles = mutableSetOf<Path>()
  packs.forEach { pack ->
    validatePackPointersDriftAndMissing(resolvedRepoRoot, pack, declaredFiles, issues)
  }
  validatePackPointersOrphans(resolvedRepoRoot, packsRoot, declaredFiles, issues)
  return PointerValidationReport(issues.sorted())
}

private fun loadValidPlatformPackManifests(packsRoot: Path): List<PlatformManifest> {
  val packDirs = Files.list(packsRoot).use { stream ->
    stream
      .filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }
      .toList()
  }
  return packDirs.mapNotNull { dir -> tryLoadPlatformManifest(dir) }
}

private fun tryLoadPlatformManifest(dir: Path): PlatformManifest? = try {
  loadPlatformManifest(dir)
} catch (_: ShellContentContractException) {
  // Manifest-shape failures are surfaced by the manifest validator elsewhere (validatePlatformPacks
  // emits a user-facing issue for the same pack), so the pointer validator intentionally tolerates
  // them and skips this pack.
  null
}

private fun validatePackPointersDriftAndMissing(
  repoRoot: Path,
  pack: PlatformManifest,
  declaredFiles: MutableSet<Path>,
  issues: MutableList<String>,
) {
  pack.pointers.forEach { spec ->
    val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).normalize()
    declaredFiles.add(pointerFile)
    // Pointer files are stored in git as symlinks (mode 120000); on Linux/macOS they materialize
    // as real symlinks, on Windows fallback (core.symlinks=false) they materialize as regular
    // text files containing the symlink target. Both forms count as "exists on disk".
    val pointerExists = Files.isSymbolicLink(pointerFile) ||
      Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
    if (!pointerExists) {
      issues += "${displayPointer(repoRoot, pointerFile)}: declared pointer is missing on disk"
      return@forEach
    }
    runCatching { renderPointer(repoRoot, pack.packRoot, spec) }
      .onFailure { error ->
        issues += "${displayPointer(repoRoot, pointerFile)}: cannot render pointer: ${error.message.orEmpty()}"
      }
      .onSuccess { rendered ->
        // Strip trailing CR/LF on both sides so Windows CRLF checkouts do not false-flag drift.
        val expected = rendered.trimEnd('\n', '\r')
        // Read the pointer in a form comparable to the renderer's output: real symlinks expose
        // their target string (with forward-slash normalization); regular files (Windows
        // core.symlinks=false fallback) expose their text content with trailing newline trimmed.
        val actual = if (Files.isSymbolicLink(pointerFile)) {
          Files.readSymbolicLink(pointerFile).toString().replace(File.separatorChar, '/')
        } else {
          Files.readString(pointerFile).trimEnd('\n', '\r')
        }
        if (expected != actual) {
          issues += "${displayPointer(repoRoot, pointerFile)}: pointer drifted from manifest " +
            "(expected '$expected', found '$actual')"
        }
      }
  }
}

private fun validatePackPointersOrphans(
  repoRoot: Path,
  packsRoot: Path,
  declaredFiles: Set<Path>,
  issues: MutableList<String>,
) {
  val packDirs = Files.list(packsRoot).use { stream ->
    stream
      .filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }
      .toList()
  }
  packDirs.forEach { packDir ->
    discoverPointerCandidates(packDir).forEach { candidate ->
      reportIfOrphan(repoRoot, candidate, declaredFiles, issues)
    }
  }
}

private fun discoverPointerCandidates(packDir: Path): List<Path> {
  val resolvedPackDir = packDir.toAbsolutePath().normalize()
  Files.walk(packDir).use { stream ->
    return stream
      // Treat both regular files and symlinks as pointer candidates: on Linux/macOS the
      // canonical pointer form is a symlink, on Windows fallback it's a regular text file.
      .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
      .filter { it.fileName.toString().endsWith(".md") }
      .filter { !isInsideExcludedSubtree(resolvedPackDir, it) }
      .filter { looksLikePointerFile(it) }
      .toList()
  }
}

private fun isInsideExcludedSubtree(packDir: Path, candidate: Path): Boolean {
  val resolvedCandidate = candidate.toAbsolutePath().normalize()
  val relative = if (resolvedCandidate.startsWith(packDir)) packDir.relativize(resolvedCandidate) else null
  val firstSegment = relative?.takeIf { it.nameCount > 0 }?.getName(0)?.toString()
  return firstSegment == "addons" || firstSegment == "native-agents"
}

private fun reportIfOrphan(repoRoot: Path, candidate: Path, declaredFiles: Set<Path>, issues: MutableList<String>) {
  val resolved = candidate.normalize()
  if (resolved !in declaredFiles) {
    issues += "${displayPointer(repoRoot, resolved)}: orphan pointer file is not declared in any " +
      "platform.yaml 'pointers:' block"
  }
}

private fun looksLikePointerFile(path: Path): Boolean {
  // On Linux/macOS the pointer is materialized as a symbolic link; treat any symlink in a
  // specialist directory as pointer-shaped by definition without sniffing its target bytes.
  if (Files.isSymbolicLink(path)) {
    return true
  }
  val size = runCatching { Files.size(path) }.getOrNull() ?: 0L
  val text = if (size in 1..POINTER_FILE_MAX_BYTES) {
    runCatching { Files.readString(path) }.getOrNull().orEmpty()
  } else {
    ""
  }
  return text.isNotEmpty() &&
    text.count { it == '\n' } <= 1 &&
    POINTER_CONTENT_PATTERN.matches(text.trim())
}

private fun displayPointer(repoRoot: Path, path: Path): String {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
