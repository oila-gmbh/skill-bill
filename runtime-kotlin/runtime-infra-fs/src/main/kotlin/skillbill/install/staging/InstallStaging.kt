@file:Suppress("TooGenericExceptionCaught")

package skillbill.install.staging

import skillbill.agentaddon.AgentAddonPointer
import skillbill.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.install.identity.SkillContentIdentity
import skillbill.install.identity.routeInstalledSkillBody
import skillbill.install.identity.suppliedSkillContentIdentity
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.RenderedSkill
import skillbill.install.support.writeRenderedSupportPointerFiles
import skillbill.scaffold.authoring.AuthoringTarget
import skillbill.scaffold.authoring.resolveTarget
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.install.InstallStaging")

internal data class StagedSymlinkTargetInput(
  val resolvedSkill: Path,
  val repoRoot: Path?,
  val home: Path,
  val manifests: List<PlatformManifest>? = null,
  val selectedPackSkills: List<InstallPlanSkill> = emptyList(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
)

internal fun installedSkillsCacheRoot(home: Path): Path =
  home.toAbsolutePath().normalize().resolve(".skill-bill/installed-skills")

internal fun installedSkillStagingDir(home: Path, sourceSkillDir: Path, contentHash: String): Path {
  val cacheRoot = installedSkillsCacheRoot(home)
  val slug = installedSkillSlug(sourceSkillDir)
  val leaf = if (slug.isEmpty()) contentHash else "$slug-$contentHash"
  val staging = cacheRoot.resolve(leaf).normalize()
  // Defense-in-depth: assert the resolved staging dir cannot escape the cache root via '..' segments.
  require(staging.startsWith(cacheRoot)) {
    "Resolved staging dir '$staging' escapes installed-skills cache root '$cacheRoot'."
  }
  return staging
}

internal fun applicablePointers(
  repoRoot: Path,
  installPath: Path,
  manifests: List<PlatformManifest>? = null,
): List<Pair<PlatformManifest, PointerSpec>> {
  val resolvedInstall = installPath.toAbsolutePath().normalize()
  val packsRoot = repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
  // F-015: prefer the caller-provided pre-discovered manifest list to avoid re-walking
  // platform-packs for every skill in a multi-skill scaffold install.
  val discovered = manifests ?: run {
    if (!Files.isDirectory(packsRoot)) {
      return emptyList()
    }
    discoverPlatformPackManifests(packsRoot)
  }
  val collected = mutableListOf<Pair<PlatformManifest, PointerSpec>>()
  discovered.forEach { manifest ->
    val packRoot = manifest.packRoot.toAbsolutePath().normalize()
    if (!resolvedInstall.startsWith(packRoot)) {
      return@forEach
    }
    val skillRelativeDir = packRoot.relativize(resolvedInstall).toString().replace(File.separatorChar, '/')
    manifest.pointers
      .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
      .forEach { spec -> collected.add(manifest to spec) }
  }
  return collected
}

internal fun authoredFilesFor(
  sourceSkillDir: Path,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
  excludedSidecarNames: Set<String> = emptySet(),
): List<Path> {
  val excluded = mutableSetOf<Path>()
  excluded.add(sourceSkillDir.resolve(INSTALL_STAGING_SKILL_FILENAME).toAbsolutePath().normalize())
  excluded.add(sourceSkillDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME).toAbsolutePath().normalize())
  applicablePointers.forEach { (manifest, spec) ->
    val packRoot = manifest.packRoot.toAbsolutePath().normalize()
    val pointerPath = packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toAbsolutePath().normalize()
    excluded.add(pointerPath)
  }
  generatedSupportPointers.forEach { pointer ->
    excluded.add(sourceSkillDir.resolve(pointer.name).toAbsolutePath().normalize())
  }
  // An authored file at a would-be sidecar name must not be copied verbatim (it would race with
  // the sidecar render); the collision hard-fail lives in writeInternalSidecarFiles.
  excludedSidecarNames.forEach { sidecarName ->
    excluded.add(sourceSkillDir.resolve(sidecarName).toAbsolutePath().normalize())
  }
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  return Files.walk(sourceSkillDir).use { stream ->
    stream
      .sorted()
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.toAbsolutePath().normalize() !in excluded }
      .peek { path -> requireWithinSource(path, resolvedSource) }
      .toList()
  }
}

/**
 * F-012: defense-in-depth — even though `Files.walk` follows NOFOLLOW by default, post-filter
 * each path's real-path so any path that escapes the source skill dir (via a symlink whose target
 * lives elsewhere) is rejected loudly. Not an assertion; a hard fail.
 */
private fun requireWithinSource(path: Path, resolvedSourceSkillDir: Path) {
  val realPath = try {
    path.toRealPath()
  } catch (_: IOException) {
    // Broken symlink or transient FS error. Don't trust the path; reject.
    throw IllegalArgumentException(
      "Authored path '$path' under '$resolvedSourceSkillDir' could not be resolved to a real path.",
    )
  }
  val realRoot = resolvedSourceSkillDir.toRealPath()
  require(realPath.startsWith(realRoot)) {
    "Authored path '$path' resolves to '$realPath' which escapes source skill dir '$realRoot'."
  }
}

internal fun stageInstalledSkill(input: StageInstalledSkillInput): RenderedSkill {
  val prepared = prepareStageInstalledSkill(input)
  tryReusePreparedStageInstalledSkill(prepared, input.suppliedCompactIdentity)?.let { reused ->
    log.fine(
      "stageInstalledSkill reuse=true skill=${prepared.skillName} hash=${prepared.contentHash} dir=${prepared.finalStagingDir}",
    )
    return reused
  }
  log.fine(
    "stageInstalledSkill reuse=false skill=${prepared.skillName} hash=${prepared.contentHash} dir=${prepared.finalStagingDir}",
  )
  return buildFreshInstallStaging(
    FreshInstallInputs(
      home = input.home,
      sourceSkillDir = prepared.resolvedSource,
      repoRoot = prepared.resolvedRepoRoot,
      target = prepared.target,
      platformPointers = prepared.pointers,
      supportPointers = prepared.internal.supportPointers,
      authored = prepared.authored,
      contentHash = prepared.contentHash,
      contentIdentity = prepared.contentIdentity,
      finalStagingDir = prepared.finalStagingDir,
      internalChildren = prepared.internal.children,
      agentAddonPointers = prepared.agentAddonPointers,
    ),
  )
}

private fun buildFreshInstallStaging(inputs: FreshInstallInputs): RenderedSkill {
  Files.createDirectories(installedSkillsCacheRoot(inputs.home))
  val tempDir = Files.createTempDirectory(installedSkillsCacheRoot(inputs.home), ".staging-tmp-")
  var promoted = false
  return try {
    val staged = populateFreshInstallStagingTemp(inputs, tempDir)
    promoteInstallStagingDir(tempDir, inputs.finalStagingDir)
    promoted = true
    finalizeFreshInstallStaging(inputs, tempDir, staged)
  } catch (error: Throwable) {
    // F-007: catch every Throwable so any failure path (render error, IO error, programmer error,
    // OOM, etc.) leaves zero staging residue. Cleanup is best-effort and never shadows the
    // primary failure (each delete is wrapped + suppressed inside cleanupInstallStagingOnFailure).
    log.log(
      Level.SEVERE,
      "stageInstalledSkill failure skill=${inputs.sourceSkillDir.fileName} hash=${inputs.contentHash} " +
        "source=${inputs.sourceSkillDir} tempDir=$tempDir finalDir=${inputs.finalStagingDir} " +
        "promoted=$promoted error=${error::class.simpleName}",
      error,
    )
    cleanupInstallStagingOnFailure(tempDir, inputs.finalStagingDir, promoted)
    throw error
  }
}

internal fun writeInstallStagingMarkers(tempDir: Path, inputs: FreshInstallInputs) {
  Files.write(
    tempDir.resolve(INSTALL_STAGING_CONTENT_HASH_FILENAME),
    inputs.contentHash.toByteArray(StandardCharsets.UTF_8),
  )
  Files.writeString(
    tempDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME),
    inputs.contentIdentity.compact(),
    StandardCharsets.UTF_8,
  )
}

internal fun resolveStagedSymlinkTarget(input: StagedSymlinkTargetInput): Path {
  if (input.repoRoot == null || !isContentManagedSkill(input.resolvedSkill)) {
    return input.resolvedSkill
  }
  return stageInstalledSkill(
    StageInstalledSkillInput(
      repoRoot = input.repoRoot,
      sourceSkillDir = input.resolvedSkill,
      home = input.home,
      manifests = input.manifests,
      selectedPackSkills = input.selectedPackSkills,
      selectedPlatformSlugs = input.selectedPlatformSlugs,
      suppliedCompactIdentity = suppliedSkillContentIdentity(input.resolvedSkill).compact(),
    ),
  ).stagingDir.toAbsolutePath().normalize()
}

private fun pruneStaleStagingDirs(home: Path, resolvedSource: Path, currentHash: String) {
  val cacheRoot = installedSkillsCacheRoot(home)
  val slug = installedSkillSlug(resolvedSource)
  if (!Files.isDirectory(cacheRoot) || slug.isEmpty()) {
    return
  }
  val currentLeaf = "$slug-$currentHash"
  val hashRegex = Regex("^${Regex.escape(slug)}-[0-9a-f]{${INSTALL_CACHE_KEY_BYTES * 2}}$")
  val candidates = try {
    Files.list(cacheRoot).use { stream ->
      stream
        .filter { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
        .filter { entry ->
          val name = entry.fileName.toString()
          name.matches(hashRegex) && name != currentLeaf
        }
        .toList()
    }
  } catch (error: IOException) {
    log.log(Level.WARNING, "pruneStaleStagingDirs list failure cacheRoot=$cacheRoot", error)
    emptyList()
  }
  candidates.forEach { stale ->
    try {
      deleteInstallStagingDirectory(stale)
    } catch (error: IOException) {
      log.log(
        Level.WARNING,
        "pruneStaleStagingDirs delete failure dir=$stale (suppressed; install completed successfully)",
        error,
      )
    } catch (error: RuntimeException) {
      log.log(
        Level.WARNING,
        "pruneStaleStagingDirs delete failure dir=$stale (suppressed; install completed successfully)",
        error,
      )
    }
  }
}
