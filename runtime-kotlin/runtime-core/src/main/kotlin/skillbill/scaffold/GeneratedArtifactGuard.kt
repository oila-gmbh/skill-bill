@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.scaffold

import skillbill.error.ShellContentContractException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

data class GeneratedArtifactGuardReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

internal typealias TrackedRepoFilesProvider = (Path) -> Set<String>?

fun validateGeneratedArtifactGuard(repoRoot: Path): GeneratedArtifactGuardReport =
  validateGeneratedArtifactGuard(repoRoot, ::trackedRepoFiles)

internal fun validateGeneratedArtifactGuard(
  repoRoot: Path,
  trackedFilesProvider: TrackedRepoFilesProvider,
): GeneratedArtifactGuardReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val trackedFiles = trackedFilesProvider(root)
  val issues = mutableListOf<String>()
  discoverGovernedSkillOutputs(root).forEach { skillFile ->
    val relative = displayGuardPath(root, skillFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed governed SKILL.md output is not allowed; " +
      "author governed skill content in content.md and render wrappers only for install/output targets"
  }
  discoverDeclaredPointerFiles(root).forEach { pointerFile ->
    val relative = displayGuardPath(root, pointerFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed platform.yaml pointer file is not allowed; " +
      "render pointer files only for install/output targets"
  }
  discoverGeneratedSupportingPointerFiles(root).forEach { pointerFile ->
    val relative = displayGuardPath(root, pointerFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed generated supporting pointer file is not allowed; " +
      "render supporting pointers only for install/output targets"
  }
  return GeneratedArtifactGuardReport(issues.sorted())
}

private fun shouldValidateCommittedArtifact(relativePath: String, trackedFiles: Set<String>?): Boolean =
  trackedFiles == null || relativePath in trackedFiles

private fun trackedRepoFiles(root: Path): Set<String>? {
  val process = runCatching {
    ProcessBuilder("git", "-C", root.toString(), "ls-files")
      .redirectErrorStream(true)
      .start()
  }.getOrNull() ?: return null
  val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
  val exitCode = process.waitFor()
  return if (exitCode == 0) {
    output.lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toSet()
  } else {
    null
  }
}

private fun discoverGovernedSkillOutputs(root: Path): List<Path> {
  val scanRoots = listOf(root.resolve("skills"), root.resolve("platform-packs")).filter { it.isDirectory() }
  return scanRoots.flatMap { scanRoot ->
    Files.walk(scanRoot).use { stream ->
      stream
        .filter { path -> path.name == "content.md" }
        .map { contentFile -> contentFile.resolveSibling("SKILL.md") }
        .filter { skillFile -> Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS) }
        .toList()
    }
  }.sorted()
}

private fun discoverDeclaredPointerFiles(root: Path): List<Path> {
  val packsRoot = root.resolve("platform-packs")
  if (!packsRoot.isDirectory()) {
    return emptyList()
  }
  return Files.list(packsRoot).use { stream ->
    stream
      .filter { packRoot -> packRoot.isDirectory() && !packRoot.name.startsWith(".") }
      .flatMap { packRoot ->
        val pack = try {
          loadPlatformManifest(packRoot)
        } catch (_: ShellContentContractException) {
          return@flatMap emptyList<Path>().stream()
        }
        pack.pointers
          .map { spec -> pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).normalize() }
          .filter { pointerFile ->
            Files.isSymbolicLink(pointerFile) ||
              Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
          }
          .stream()
      }
      .toList()
      .sorted()
  }
}

private fun discoverGeneratedSupportingPointerFiles(root: Path): List<Path> {
  val skillsRoot = root.resolve("skills")
  if (!skillsRoot.isDirectory()) {
    return emptyList()
  }
  val targets = supportingFileTargets(root)
  return Files.list(skillsRoot).use { stream ->
    stream
      .filter { skillDir -> skillDir.isDirectory() && !skillDir.name.startsWith(".") }
      .flatMap { skillDir ->
        val skillName = skillDir.name
        requiredSupportingFilesForSkill(skillName, root)
          .asSequence()
          .filter { fileName ->
            val target = targets[fileName]?.normalize()?.toAbsolutePath()
            val sidecar = skillDir.resolve(fileName).normalize().toAbsolutePath()
            target != null && target != sidecar
          }
          .map { fileName -> skillDir.resolve(fileName) }
          .filter { pointerFile ->
            Files.isSymbolicLink(pointerFile) ||
              Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
          }
          .toList()
          .stream()
      }
      .toList()
      .sorted()
  }
}

private fun displayGuardPath(root: Path, path: Path): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
