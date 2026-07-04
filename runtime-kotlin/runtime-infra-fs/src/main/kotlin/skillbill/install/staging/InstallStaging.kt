@file:Suppress("ThrowsCount", "TooGenericExceptionCaught")

package skillbill.install.staging

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
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

private const val INSTALL_CACHE_KEY_BYTES = 8
private const val INSTALL_STAGING_RECIPE_VERSION = "install-staging-v3-platform-packs-child-inline-pointers"

private val log: Logger = Logger.getLogger("skillbill.install.InstallStaging")

private data class FreshInstallInputs(
  val home: Path,
  val sourceSkillDir: Path,
  val repoRoot: Path,
  val target: AuthoringTarget,
  val platformPointers: List<Pair<PlatformManifest, PointerSpec>>,
  val supportPointers: List<GeneratedSupportPointer>,
  val authored: List<Path>,
  val contentHash: String,
  val finalStagingDir: Path,
  val internalChildren: List<InternalSidecarTarget>,
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
  applicablePointers.forEach { (manifest, spec) ->
    val packRoot = manifest.packRoot.toAbsolutePath().normalize()
    val pointerPath = packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toAbsolutePath().normalize()
    excluded.add(pointerPath)
  }
  generatedSupportPointers.forEach { pointer ->
    excluded.add(sourceSkillDir.resolve(pointer.name).toAbsolutePath().normalize())
  }
  // SKILL-102 subtask 1: a parent's source dir must not also author a file whose name collides
  // with a would-be internal sidecar (PD2 collision guard). The hard-fail lives in
  // writeInternalSidecarFiles; here we just ensure such an authored file is NOT also copied
  // verbatim (which would race with the sidecar render).
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

internal fun computeInstallContentHash(
  sourceSkillDir: Path,
  authored: List<Path>,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
  internalChildren: List<InternalSidecarTarget> = emptyList(),
): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val newline = byteArrayOf('\n'.code.toByte())
  digest.update(INSTALL_STAGING_RECIPE_VERSION.toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  authored.forEach { file ->
    val rel = sourceSkillDir.relativize(file).toString().replace(File.separatorChar, '/')
    digest.update(rel.toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
    digest.update(Files.readAllBytes(file))
    digest.update(newline)
  }
  digest.update("--pointers--".toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  applicablePointers
    .sortedWith(compareBy({ it.second.skillRelativeDir }, { it.second.name }))
    .forEach { (manifest, spec) ->
      val line = "${spec.skillRelativeDir}|${spec.name}|${spec.target}"
      digest.update(line.toByteArray(StandardCharsets.UTF_8))
      digest.update(newline)
      val repoRoot = manifest.packRoot.toAbsolutePath().normalize().parent?.parent
        ?: error("Platform pack '${manifest.slug}' root '${manifest.packRoot}' has no repo root parent.")
      val targetFile = repoRoot.resolve(spec.target).normalize()
      require(targetFile.startsWith(repoRoot)) {
        "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' outside repoRoot '$repoRoot'."
      }
      require(Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
        "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
          "which does not exist at '$targetFile'."
      }
      digest.update(Files.readAllBytes(targetFile))
      digest.update(newline)
    }
  generatedSupportPointers
    .sortedBy { it.name }
    .forEach { pointer ->
      // Hash the inlined canonical content, not the target path, so editing the orchestration doc
      // invalidates the cache and re-inlines on the next install.
      digest.update("${pointer.name}|".toByteArray(StandardCharsets.UTF_8))
      digest.update(Files.readAllBytes(pointer.target))
      digest.update(newline)
    }
  // SKILL-102 subtask 1 (PD2): fold internal-skill sidecar wrappers into the parent's content
  // hash so editing a child's content.md invalidates the parent's cache entry and re-renders.
  // The section is appended ONLY when there is at least one internal child, so repos with no
  // internal skills produce byte-identical hashes (and thus byte-identical staged trees) to
  // before this change (criterion 7).
  if (internalChildren.isNotEmpty()) {
    digest.update("--internal-sidecars--".toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
    internalChildren
      .sortedBy { child -> child.skillName }
      .forEach { child ->
        // Hash the rendered wrapper bytes (the same bytes that land at <skill-name>.md), not the
        // raw content.md, so demotion/frontmatter changes in the renderer also invalidate the cache.
        val rendered = renderInternalSidecarWrapper(child)
        digest.update("${child.skillName}.md|".toByteArray(StandardCharsets.UTF_8))
        digest.update(rendered.toByteArray(StandardCharsets.UTF_8))
        digest.update(newline)
      }
  }
  val hashBytes = digest.digest()
  return hashBytes.take(INSTALL_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
}

internal fun stageInstalledSkill(
  repoRoot: Path,
  sourceSkillDir: Path,
  home: Path,
  manifests: List<PlatformManifest>? = null,
): RenderedSkill {
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val skillName = resolvedSource.fileName.toString()
  val target: AuthoringTarget = resolveTarget(resolvedRepoRoot, skillName)
  val pointers = applicablePointers(resolvedRepoRoot, resolvedSource, manifests)
  val generatedSupportPointers = generatedSupportPointersFor(resolvedRepoRoot, resolvedSource, skillName)
  val skillsRoot = resolvedRepoRoot.resolve("skills")
  val internalChildren = discoverInternalSidecarTargets(resolvedRepoRoot, skillName, skillsRoot)
  val sidecarNames = internalChildren.map { child -> "${child.skillName}.md" }.toSet()
  val authored = authoredFilesFor(
    sourceSkillDir = resolvedSource,
    applicablePointers = pointers,
    generatedSupportPointers = generatedSupportPointers,
    excludedSidecarNames = sidecarNames,
  )
  val contentHash = computeInstallContentHash(
    sourceSkillDir = resolvedSource,
    authored = authored,
    applicablePointers = pointers,
    generatedSupportPointers = generatedSupportPointers,
    internalChildren = internalChildren,
  )
  val finalStagingDir = installedSkillStagingDir(home, resolvedSource, contentHash)

  // Idempotent reuse: same hash, marker present and intact, SKILL.md present -> reuse existing dir.
  if (isReusableInstallStaging(finalStagingDir, contentHash)) {
    log.fine(
      "stageInstalledSkill reuse=true skill=$skillName hash=$contentHash dir=$finalStagingDir",
    )
    return reuseInstallStaging(
      sourceSkillDir = resolvedSource,
      finalStagingDir = finalStagingDir,
      contentHash = contentHash,
      applicablePointers = pointers,
      generatedSupportPointers = generatedSupportPointers,
      internalSidecarNames = sidecarNames,
    )
  }
  log.fine(
    "stageInstalledSkill reuse=false skill=$skillName hash=$contentHash dir=$finalStagingDir",
  )

  return buildFreshInstallStaging(
    FreshInstallInputs(
      home = home,
      sourceSkillDir = resolvedSource,
      repoRoot = resolvedRepoRoot,
      target = target,
      platformPointers = pointers,
      supportPointers = generatedSupportPointers,
      authored = authored,
      contentHash = contentHash,
      finalStagingDir = finalStagingDir,
      internalChildren = internalChildren,
    ),
  )
}

private fun buildFreshInstallStaging(inputs: FreshInstallInputs): RenderedSkill {
  Files.createDirectories(installedSkillsCacheRoot(inputs.home))
  val tempDir = Files.createTempDirectory(installedSkillsCacheRoot(inputs.home), ".staging-tmp-")
  // F-009/F-010: ownership flag — only delete `finalStagingDir` on failure if WE successfully
  // promoted it during this attempt. Otherwise we'd risk wiping a previously-good cache entry.
  var promoted = false
  return try {
    val copiedInTemp = copyAuthoredIntoStaging(inputs.sourceSkillDir, tempDir, inputs.authored)
    val skillFileInTemp = writeRenderedSkillFile(tempDir, inputs.target)
    val pointerFilesInTemp = writeRenderedPointerFiles(inputs.repoRoot, tempDir, inputs.platformPointers)
    val supportPointerFilesInTemp = writeRenderedSupportPointerFiles(
      repoRoot = inputs.repoRoot,
      sourceSkillDir = inputs.sourceSkillDir,
      tempDir = tempDir,
      pointers = inputs.supportPointers,
    )
    val sidecarFilesInTemp = writeInternalSidecarFiles(
      tempDir = tempDir,
      parentSourceDir = inputs.sourceSkillDir,
      children = inputs.internalChildren,
    )
    val packsRoot = inputs.repoRoot.resolve("platform-packs")
    if (Files.isDirectory(packsRoot)) {
      Files.createSymbolicLink(tempDir.resolve("platform-packs"), packsRoot)
    }
    Files.write(
      tempDir.resolve(INSTALL_STAGING_CONTENT_HASH_FILENAME),
      inputs.contentHash.toByteArray(StandardCharsets.UTF_8),
    )
    promoteInstallStagingDir(tempDir, inputs.finalStagingDir)
    promoted = true
    val finalSkillFile = inputs.finalStagingDir.resolve(tempDir.relativize(skillFileInTemp))
    val finalPointerFiles = (pointerFilesInTemp + supportPointerFilesInTemp)
      .map { p -> inputs.finalStagingDir.resolve(tempDir.relativize(p)) }
    val finalCopied = copiedInTemp.map { p -> inputs.finalStagingDir.resolve(tempDir.relativize(p)) }
    val finalSidecars = sidecarFilesInTemp.map { p -> inputs.finalStagingDir.resolve(tempDir.relativize(p)) }
    // F-013: prune older staging dirs for the same skill slug (different hash). Best-effort only;
    // pruning failures are logged and suppressed so they never mask the successful install.
    pruneStaleStagingDirs(inputs.home, inputs.sourceSkillDir, inputs.contentHash)
    RenderedSkill(
      skillName = inputs.sourceSkillDir.fileName.toString(),
      sourceSkillDir = inputs.sourceSkillDir,
      stagingDir = inputs.finalStagingDir,
      renderedSkillFile = finalSkillFile,
      renderedPointerFiles = finalPointerFiles,
      copiedAuthoredFiles = finalCopied,
      contentHash = inputs.contentHash,
      renderedSidecarFiles = finalSidecars,
    )
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

internal fun resolveStagedSymlinkTarget(
  resolvedSkill: Path,
  repoRoot: Path?,
  home: Path,
  manifests: List<PlatformManifest>? = null,
): Path {
  // F-008: explicit predicate replaces the prior try/catch on SkillBillRuntimeException.
  // Outcomes:
  //  - repoRoot == null  -> legacy source-symlink fallback (manual `link-skill` flow predates the
  //    staging cache and has no way to inject repoRoot; preserve backward compat verbatim).
  //  - !isContentManagedSkill -> source-symlink fallback (legacy non-content-managed skill).
  //  - content-managed + repoRoot -> stage and propagate any genuine error (no swallowing).
  // Note (deviation from F-008 literal text): the review suggested throwing when content-managed
  // AND repoRoot == null. That would break the existing `link-skill` CLI flow which is exercised
  // by CliExternalAuthorDryRunTest. We retain the legacy fallback; the reviewer's primary intent
  // (kill the try/catch on SkillBillRuntimeException as control flow) is preserved.
  if (repoRoot == null || !isContentManagedSkill(resolvedSkill)) {
    return resolvedSkill
  }
  return stageInstalledSkill(repoRoot, resolvedSkill, home, manifests).stagingDir.toAbsolutePath().normalize()
}

private fun pruneStaleStagingDirs(home: Path, resolvedSource: Path, currentHash: String) {
  val cacheRoot = installedSkillsCacheRoot(home)
  val slug = installedSkillSlug(resolvedSource)
  if (!Files.isDirectory(cacheRoot) || slug.isEmpty()) {
    return
  }
  val currentLeaf = "$slug-$currentHash"
  // F-016: slugs are not delimiter-bounded (e.g. `bill-kotlin-code-review` is a prefix of
  // `bill-kotlin-code-review-security`). A naive `startsWith("$slug-")` filter would delete
  // unrelated skills' staging dirs. Require the suffix after `<slug>-` to be EXACTLY a content
  // hash: 2 * INSTALL_CACHE_KEY_BYTES lowercase hex chars (matches `computeInstallContentHash`).
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
