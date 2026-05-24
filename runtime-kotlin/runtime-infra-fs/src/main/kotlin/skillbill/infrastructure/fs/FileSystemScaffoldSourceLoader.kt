package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadRequest
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadResult
import skillbill.scaffold.declaredSkillRelativeDirs
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.requireStringList
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import skillbill.scaffold.loadPlatformPack as fsLoadPlatformPack

/**
 * Filesystem adapter for [ScaffoldSourceLoaderPort]. Delegates to the existing
 * `skillbill.scaffold.loadPlatformPack` parse seam in `runtime-infra-fs`, which owns the
 * `platform.yaml` schema validation and on-disk file reading.
 *
 * SKILL-52.1 subtask 3 also collected the IO-coupled add-on consumer-skill-dir validators
 * that previously lived at top-level inside `skillbill.scaffold.ScaffoldService.kt` into
 * this adapter:
 *  - [resolveAddonConsumerSkillDirs] (parses and de-dups the requested consumer dirs)
 *  - [validateAddonConsumerSkillDir] (normalizes one dir + checks it against the pack
 *    layout on disk)
 *
 * The architecture test `ImplementationOwnershipArchitectureTest` asserts the FQN of those
 * functions resolves to this adapter, not to top-level `skillbill.scaffold`.
 */
@Inject
class FileSystemScaffoldSourceLoader : ScaffoldSourceLoaderPort {
  override fun loadPlatformPack(request: ScaffoldPlatformPackLoadRequest): ScaffoldPlatformPackLoadResult =
    ScaffoldPlatformPackLoadResult(
      packRoot = request.packRoot,
      manifest = fsLoadPlatformPack(request.packRoot),
    )

  /**
   * Resolves the `consumer_skill_dirs` payload entry against a loaded [pack]. Returns the
   * normalized, de-duplicated list of skill-relative directories. Falls back to the pack's
   * baseline skill directory when the payload field is absent.
   *
   * Replaces the legacy top-level `resolveAddonConsumerSkillDirs` in
   * `skillbill.scaffold.ScaffoldService.kt`.
   */
  internal fun resolveAddonConsumerSkillDirs(
    payload: Map<String, Any?>,
    packRoot: Path,
    pack: PlatformManifest,
  ): List<String> {
    val raw = payload["consumer_skill_dirs"]
    val requested = if (raw == null) {
      listOfNotNull(pack.declaredFiles.baseline?.let { contentFile -> packRoot.relativize(contentFile.parent) })
        .map { path -> path.toString().replace('\\', '/') }
    } else {
      requireStringList(raw, "consumer_skill_dirs")
    }
    val seen = mutableSetOf<String>()
    return requested.map { dir ->
      validateAddonConsumerSkillDir(pack, dir)
    }.filter { dir -> seen.add(dir) }
  }

  /**
   * Validates and normalizes a single skill-relative directory string against the loaded
   * [pack]. Rejects absolute paths, parent-segment escapes, and references to non-declared
   * skill directories.
   *
   * Replaces the legacy top-level `validateAddonConsumerSkillDir` in
   * `skillbill.scaffold.ScaffoldService.kt`.
   */
  internal fun validateAddonConsumerSkillDir(pack: PlatformManifest, skillRelativeDir: String): String {
    val relative = parseRelativePath(skillRelativeDir)
    if (relative.isAbsolute || skillRelativeDir.startsWith("/") || skillRelativeDir.startsWith("\\")) {
      failConsumerSkillDirsNotRelative()
    }
    relative.iterator().forEachRemaining { segment ->
      if (segment.toString() == "..") {
        failConsumerSkillDirsParentSegment()
      }
    }
    val normalized = relative.normalize()
    val normalizedDir = normalized.toString().replace('\\', '/')
    if (!Files.isDirectory(pack.packRoot.resolve(normalized))) {
      failConsumerSkillDirsMissing(skillRelativeDir)
    }
    if (normalizedDir !in pack.declaredSkillRelativeDirs()) {
      failConsumerSkillDirsNotDeclared(pack, skillRelativeDir)
    }
    return normalizedDir
  }

  private fun parseRelativePath(skillRelativeDir: String): Path = try {
    Path.of(skillRelativeDir)
  } catch (error: InvalidPathException) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'consumer_skill_dirs' contains invalid path '$skillRelativeDir': ${error.message}",
      error,
    )
  }
}

private fun failConsumerSkillDirsNotRelative(): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'consumer_skill_dirs' entries must be relative skill directories.",
)

private fun failConsumerSkillDirsParentSegment(): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'consumer_skill_dirs' entries must not contain '..' segments.",
)

private fun failConsumerSkillDirsMissing(skillRelativeDir: String): Nothing = throw InvalidScaffoldPayloadError(
  "Scaffold payload field 'consumer_skill_dirs' references missing skill directory '$skillRelativeDir'.",
)

private fun failConsumerSkillDirsNotDeclared(pack: PlatformManifest, skillRelativeDir: String): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'consumer_skill_dirs' references '$skillRelativeDir', but that directory is not " +
      "declared as a skill in platform pack '${pack.slug}'. Declared skill directories: " +
      "${pack.declaredSkillRelativeDirs().sorted()}.",
  )
