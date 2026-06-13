package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadRequest
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.declaredSkillRelativeDirs
import skillbill.scaffold.policy.requireStringList
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import skillbill.scaffold.platformpack.loadPlatformPack as fsLoadPlatformPack

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
   * baseline skill directory when the payload field is absent. Packs without a baseline may use
   * their only manifest-declared skill directory as the default; packs with no unambiguous default
   * fail before scaffold planning can mutate files.
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
      defaultAddonConsumerSkillDirs(packRoot, pack)
    } else {
      requireStringList(raw, "consumer_skill_dirs").map(String::trim)
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

  private fun defaultAddonConsumerSkillDirs(packRoot: Path, pack: PlatformManifest): List<String> {
    pack.declaredFiles.baseline?.let { contentFile ->
      return listOf(packRoot.relativize(contentFile.parent).toString().replace('\\', '/'))
    }
    val declaredSkillDirs = pack.declaredSkillRelativeDirs().sorted()
    if (declaredSkillDirs.size == 1) {
      return declaredSkillDirs
    }
    throw InvalidScaffoldPayloadError(
      "Scaffold payload for add-on platform '${pack.slug}' omitted 'consumer_skill_dirs', but the pack has " +
        "no unambiguous default consumer. Provide scripted 'consumer_skill_dirs'. Declared skill directories: " +
        "$declaredSkillDirs.",
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
