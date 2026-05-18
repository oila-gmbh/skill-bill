@file:Suppress(
  "MaxLineLength",
  "TooGenericExceptionCaught",
  "ReturnCount",
  "ComplexMethod",
  "LongMethod",
  "TooManyFunctions",
  "NestedBlockDepth",
  "UnusedParameter",
)

package skillbill.skillremove

import skillbill.domain.skillremove.SkillBillRollbackException
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.AppliedCascade
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogWarning
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.install.InstallNativeAgentOperations
import skillbill.install.NativeAgentLinkRequest
import skillbill.nativeagent.NativeAgentProvider
import skillbill.scaffold.ReadmeCatalogEdits
import skillbill.scaffold.ReadmeEditOutcome
import skillbill.scaffold.removeCodeReviewArea
import skillbill.scaffold.removeDeclaredQualityCheckFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger

/**
 * JVM implementation of [SkillRemoveFileSystem] for SKILL-46. Reuses install primitives
 * ([InstallNativeAgentOperations.unlink*Agents]) for symlink cleanup — the plan mandates no
 * duplicated traversal — and the manifest/readme edit primitives in `runtime-core/scaffold/`.
 *
 * The implementation is deliberately conservative:
 * - All discovery is filesystem-driven (no hardcoded skill names). The `discoverCascadedSkillNames`
 *   path walks `platform-packs/` to find every `bill-<platform>-<skillName>` override and every
 *   `bill-<platform>-<skillName>-<area>` specialist directory.
 * - `applyCascade` uses a two-phase commit: it first records the original bytes of every file
 *   we are about to delete or rewrite into a transient stash, then performs the mutations. On
 *   any throw it attempts to restore from the stash. If restoration also throws we wrap the
 *   composite failure in [SkillBillRollbackException] so the gateway can surface
 *   `rollbackComplete = false` to the UI.
 */
class SkillRemoveJvmFileSystem(
  private val home: Path? = null,
) : SkillRemoveFileSystem {

  override fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String> {
    val target = request.target as? SkillRemovalTarget.HorizontalSkill ?: return emptyList()
    val repoRoot = repoRoot(request)
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val out = linkedSetOf<String>()
    Files.list(packsRoot).use { stream ->
      stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
        val platform = packDir.fileName.toString()
        val basePrefix = "bill-$platform-${target.skillName}"
        listOf("code-review", "quality-check").forEach { family ->
          val familyDir = packDir.resolve(family)
          if (Files.isDirectory(familyDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(familyDir).use { areaStream ->
              areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
                val name = areaDir.fileName.toString()
                if (name == basePrefix || name.startsWith("$basePrefix-")) {
                  out += name
                }
              }
            }
          }
        }
      }
    }
    return out.toList()
  }

  override fun targetExists(request: SkillRemovalRequest): Boolean {
    val root = repoRoot(request)
    return when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill ->
        Files.isDirectory(root.resolve("skills/${target.skillName}"), LinkOption.NOFOLLOW_LINKS)
      is SkillRemovalTarget.PlatformPack ->
        Files.isDirectory(root.resolve("platform-packs/${target.platform}"), LinkOption.NOFOLLOW_LINKS)
      is SkillRemovalTarget.AddOn ->
        Files.exists(root.resolve(target.relativePath), LinkOption.NOFOLLOW_LINKS)
    }
  }

  override fun resolveCascadeFilesystemPaths(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<String> = when (val target = request.target) {
    is SkillRemovalTarget.HorizontalSkill -> horizontalCascadePaths(repoRoot(request), cascadedSkillNames)
    is SkillRemovalTarget.PlatformPack -> platformPackCascadePaths(repoRoot(request), target.platform)
    is SkillRemovalTarget.AddOn -> {
      val absolute = repoRoot(request).resolve(target.relativePath)
      if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) listOf(target.relativePath) else emptyList()
    }
  }

  override fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit> =
    when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> horizontalManifestEdits(repoRoot(request), cascadedSkillNames)
      is SkillRemovalTarget.PlatformPack -> emptyList() // the manifest itself is deleted with the tree
      is SkillRemovalTarget.AddOn -> emptyList()
    }

  override fun planAgentSymlinkUnlinks(
    request: SkillRemovalRequest,
    cascadedSkillNames: List<String>,
  ): List<AgentSymlinkUnlink> = when (val target = request.target) {
    is SkillRemovalTarget.HorizontalSkill ->
      agentUnlinksForSkills(repoRoot(request), cascadedSkillNames)
    is SkillRemovalTarget.PlatformPack ->
      agentUnlinksForPlatform(repoRoot(request), target.platform)
    is SkillRemovalTarget.AddOn -> emptyList()
  }

  override fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit> {
    val target = request.target
    if (target !is SkillRemovalTarget.HorizontalSkill) return emptyList()
    val readme = repoRoot(request).resolve("README.md")
    if (!Files.isRegularFile(readme, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return listOf(
      ReadmeCatalogEdit(
        readmePath = "README.md",
        kind = ReadmeCatalogEditKind.REMOVE_CATALOG_ROW,
        detail = "Remove catalog row for `/${target.skillName}`",
      ),
      ReadmeCatalogEdit(
        readmePath = "README.md",
        kind = ReadmeCatalogEditKind.DECREMENT_SECTION_COUNT,
        detail = "Decrement Canonical Skills section count",
      ),
    )
  }

  override fun applyCascade(request: SkillRemovalRequest, preview: SkillRemovalPreview): AppliedCascade {
    val repoRoot = repoRoot(request)
    val rollbackStash = mutableListOf<RollbackEntry>()
    val removedPaths = mutableListOf<String>()
    val editedManifests = mutableListOf<String>()
    val unlinkedSymlinks = mutableListOf<String>()
    val readmeWarnings = mutableListOf<ReadmeCatalogWarning>()
    try {
      // F-ROLLBACK-INCOMPLETE: order is now manifest/README first, file-tree second, symlinks
      // LAST so any throw before the symlink step leaves agent homes untouched. Rollback restores
      // manifest/README/files; symlinks are never touched until success is otherwise locked in.

      // 1) snapshot manifests + README before any change so rollback can restore byte-identical
      preview.manifestEdits.forEach { edit ->
        stashFile(repoRoot.resolve(edit.manifestPath), rollbackStash)
      }
      preview.readmeCatalogEdits.forEach { edit ->
        stashFile(repoRoot.resolve(edit.readmePath), rollbackStash)
      }

      // 2) apply manifest edits using the runtime-core helpers
      preview.manifestEdits.forEach { edit ->
        val manifest = repoRoot.resolve(edit.manifestPath)
        when (edit.editKind) {
          ManifestEditKind.REMOVE_CODE_REVIEW_AREA,
          ManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY,
          ManifestEditKind.REMOVE_AREA_METADATA_ENTRY,
          -> {
            removeCodeReviewArea(manifest, edit.detail)
          }
          ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE -> {
            removeDeclaredQualityCheckFile(manifest)
          }
        }
        editedManifests += edit.manifestPath
      }

      // 3) apply README catalog edits. F-002-RELIABILITY-README: capture every helper outcome
      //    and forward LandmarksMissing entries as non-fatal warnings (`readmeWarnings`).
      val skillNameForReadme = (request.target as? SkillRemovalTarget.HorizontalSkill)?.skillName
      preview.readmeCatalogEdits.forEach { edit ->
        val readme = repoRoot.resolve(edit.readmePath)
        val outcome: ReadmeEditOutcome? = when (edit.kind) {
          ReadmeCatalogEditKind.REMOVE_CATALOG_ROW ->
            if (skillNameForReadme != null) ReadmeCatalogEdits.removeCatalogRow(readme, skillNameForReadme) else null
          ReadmeCatalogEditKind.DECREMENT_SECTION_COUNT ->
            ReadmeCatalogEdits.decrementSectionCount(readme)
        }
        if (outcome is ReadmeEditOutcome.LandmarksMissing) {
          readmeWarnings += ReadmeCatalogWarning(
            readmePath = edit.readmePath,
            kind = edit.kind,
            reason = outcome.reason,
          )
        }
      }

      // 4) delete the listed filesystem paths. Order: depth-first so directories are emptied
      //    before being removed. Stash file bytes before deleting so rollback can restore.
      //    F-ROLLBACK-INCOMPLETE: this used to be step 5; pulling it ahead of the symlink step
      //    means we leave agent homes untouched if any of these deletes throw.
      val absolutePaths = preview.filesystemPaths
        .map { rel -> repoRoot.resolve(rel) }
        .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
      absolutePaths.forEach { stashTree(it, rollbackStash) }
      absolutePaths.forEach { absolute ->
        deletePath(absolute)
        removedPaths += absolute.toString().replace('\\', '/')
      }

      // 5) unlink agent symlinks via install primitives. We invoke the four provider helpers
      //    once each so the primitives walk their own state once — never duplicated traversal.
      //    F-ROLLBACK-INCOMPLETE: this is now the LAST mutation. Any earlier throw leaves
      //    symlinks intact. F-RUNCATCHING-SILENT/F-001-ARCH: errors per provider are collected
      //    and thrown as SkillBillRollbackException so the gateway maps to
      //    `Failed(rollbackComplete=false)` honestly rather than silently swallowing failures.
      val symlinks = unlinkProviderAgents(repoRoot, request.target)
      unlinkedSymlinks += symlinks.map { it.toString().replace('\\', '/') }

      return AppliedCascade(
        removedPaths = removedPaths,
        editedManifests = editedManifests,
        unlinkedSymlinks = unlinkedSymlinks,
        readmeWarnings = readmeWarnings,
      )
    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
      // F-CANCEL-ROLLBACK: best-effort rollback before re-throwing; cancellation still propagates.
      attemptRollback(rollbackStash)
      throw cancellation
    } catch (error: Exception) {
      // F-ERROR-PROPAGATE: narrowed from Throwable -> Exception so JVM Error propagates to the
      // supervisor and is never wrapped in a SkillBillRollbackException.
      val rollbackOk = attemptRollback(rollbackStash)
      if (!rollbackOk) {
        throw SkillBillRollbackException(
          "Skill removal failed AND rollback could not fully restore the repo: ${error.message.orEmpty()}",
          error,
        )
      }
      throw error
    }
  }

  // --- private helpers ----------------------------------------------------------------

  private fun repoRoot(request: SkillRemovalRequest): Path =
    Path.of(request.repoRootAbsolutePath).toAbsolutePath().normalize()

  private fun horizontalCascadePaths(repoRoot: Path, cascadedSkillNames: List<String>): List<String> {
    val out = linkedSetOf<String>()
    cascadedSkillNames.forEach { skillName ->
      val direct = repoRoot.resolve("skills/$skillName")
      if (Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) out += "skills/$skillName"
      // Platform-pack overrides may live under multiple subtrees; discover by walking platform-packs.
      val packs = repoRoot.resolve("platform-packs")
      if (Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) {
        Files.list(packs).use { stream ->
          stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
            listOf("code-review", "quality-check").forEach { family ->
              val candidate = packDir.resolve(family).resolve(skillName)
              if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                out += repoRoot.relativize(candidate).toString().replace('\\', '/')
              }
            }
          }
        }
      }
    }
    return out.toList()
  }

  private fun platformPackCascadePaths(repoRoot: Path, platform: String): List<String> {
    val out = linkedSetOf<String>()
    val pack = repoRoot.resolve("platform-packs/$platform")
    val pairedSkills = repoRoot.resolve("skills/$platform")
    if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) out += "platform-packs/$platform"
    if (Files.exists(pairedSkills, LinkOption.NOFOLLOW_LINKS)) out += "skills/$platform"
    return out.toList()
  }

  private fun horizontalManifestEdits(repoRoot: Path, cascadedSkillNames: List<String>): List<ManifestEdit> {
    val edits = mutableListOf<ManifestEdit>()
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    Files.list(packsRoot).use { stream ->
      stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { packDir ->
        val manifest = packDir.resolve("platform.yaml")
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return@forEach
        val manifestRel = repoRoot.relativize(manifest).toString().replace('\\', '/')
        val platform = packDir.fileName.toString()
        cascadedSkillNames.forEach { skillName ->
          // code-review area: derive area name from `bill-<platform>-<skillName>-<area>` shape.
          val areaPrefix = "bill-$platform-$skillName-"
          val codeReviewDir = packDir.resolve("code-review")
          if (Files.isDirectory(codeReviewDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(codeReviewDir).use { areaStream ->
              areaStream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { areaDir ->
                val name = areaDir.fileName.toString()
                if (name.startsWith(areaPrefix)) {
                  val area = name.removePrefix(areaPrefix)
                  // Use detail = area so applyCascade can route to removeCodeReviewArea.
                  edits += ManifestEdit(manifestRel, ManifestEditKind.REMOVE_CODE_REVIEW_AREA, area)
                }
              }
            }
          }
          // quality-check override: presence implies declared_quality_check_file
          val qcDir = packDir.resolve("quality-check").resolve("bill-$platform-$skillName")
          if (Files.exists(qcDir, LinkOption.NOFOLLOW_LINKS)) {
            edits += ManifestEdit(
              manifestRel,
              ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE,
              "remove declared_quality_check_file",
            )
          }
        }
      }
    }
    return edits
  }

  private fun agentUnlinksForSkills(repoRoot: Path, cascadedSkillNames: List<String>): List<AgentSymlinkUnlink> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val out = mutableListOf<AgentSymlinkUnlink>()
    cascadedSkillNames.forEach { name ->
      AgentSymlinkProvider.values().forEach { provider ->
        val homeDirs = nativeProvider(provider).homeAgentDirs(resolvedHome)
        homeDirs.forEach { dir ->
          // The actual unlink is performed by install primitives; we report likely paths.
          val candidate = dir.resolve("$name.md")
          out += AgentSymlinkUnlink(provider = provider, path = candidate.toString().replace('\\', '/'))
        }
      }
    }
    return out
  }

  private fun agentUnlinksForPlatform(repoRoot: Path, platform: String): List<AgentSymlinkUnlink> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val out = mutableListOf<AgentSymlinkUnlink>()
    AgentSymlinkProvider.values().forEach { provider ->
      val homeDirs = nativeProvider(provider).homeAgentDirs(resolvedHome)
      homeDirs.forEach { dir ->
        out += AgentSymlinkUnlink(
          provider = provider,
          path = dir.resolve("bill-$platform-*").toString().replace('\\', '/'),
        )
      }
    }
    return out
  }

  private fun nativeProvider(provider: AgentSymlinkProvider): NativeAgentProvider = when (provider) {
    AgentSymlinkProvider.CLAUDE -> NativeAgentProvider.Claude
    AgentSymlinkProvider.CODEX -> NativeAgentProvider.Codex
    AgentSymlinkProvider.OPENCODE -> NativeAgentProvider.Opencode
    AgentSymlinkProvider.JUNIE -> NativeAgentProvider.Junie
  }

  /**
   * F-RUNCATCHING-SILENT / F-001-ARCH: iterate the four providers in order, collect each successful
   * unlink into the result list, and accumulate IOException-class failures into [UnlinkFailure]
   * entries instead of dropping them with `runCatching {}`. CancellationException is rethrown
   * eagerly so coroutine cancellation propagates verbatim. Any accumulated failures throw
   * [SkillBillRollbackException] so the outer catch in [applyCascade] maps to
   * `Failed(rollbackComplete=false)` rather than reporting a misleading success.
   *
   * Source-of-truth: the live `InstallNativeAgentOperations.unlink*Agents` calls below are the
   * authoritative implementation; the planner in [planAgentSymlinkUnlinks] reports likely paths
   * for the UI dossier but the executor consults the install primitives directly so we never
   * duplicate traversal logic between the two.
   */
  private fun unlinkProviderAgents(repoRoot: Path, target: SkillRemovalTarget): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    val platformPacksRoot = repoRoot.resolve("platform-packs")
    val skillsRoot = repoRoot.resolve("skills")
    val selectedPlatforms: List<String>? = when (target) {
      is SkillRemovalTarget.PlatformPack -> listOf(target.platform)
      else -> null
    }
    val baseRequest = NativeAgentLinkRequest(
      platformPacksRoot = platformPacksRoot,
      skillsRoot = skillsRoot,
      home = resolvedHome,
      selectedPlatforms = selectedPlatforms,
    )
    val unlinked = mutableListOf<Path>()
    val failures = mutableListOf<UnlinkFailure>()
    val providers: List<Pair<AgentSymlinkProvider, (NativeAgentLinkRequest) -> List<Path>>> = listOf(
      AgentSymlinkProvider.CLAUDE to InstallNativeAgentOperations::unlinkClaudeAgents,
      AgentSymlinkProvider.CODEX to InstallNativeAgentOperations::unlinkCodexAgents,
      AgentSymlinkProvider.OPENCODE to InstallNativeAgentOperations::unlinkOpencodeAgents,
      AgentSymlinkProvider.JUNIE to InstallNativeAgentOperations::unlinkJunieAgents,
    )
    providers.forEach { (provider, fn) ->
      try {
        unlinked += fn(baseRequest)
      } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
        throw cancellation
      } catch (error: IOException) {
        log.warning(
          "skill-bill remove unlink failed for ${provider.name}: ${error::class.simpleName.orEmpty()}",
        )
        failures += UnlinkFailure(provider = provider, message = error.message.orEmpty())
      }
    }
    if (failures.isNotEmpty()) {
      val summary = failures.joinToString(separator = "; ") { "${it.provider.name}: ${it.message}" }
      throw SkillBillRollbackException("Agent symlink unlink failed for: $summary")
    }
    return unlinked
  }

  /** F-RUNCATCHING-SILENT: structured per-provider failure. */
  private data class UnlinkFailure(
    val provider: AgentSymlinkProvider,
    val message: String,
  )

  private data class RollbackEntry(val path: Path, val bytes: ByteArray?, val wasDirectory: Boolean)

  private fun stashFile(path: Path, stash: MutableList<RollbackEntry>) {
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      stash += RollbackEntry(path = path, bytes = Files.readAllBytes(path), wasDirectory = false)
    }
  }

  private fun stashTree(root: Path, stash: MutableList<RollbackEntry>) {
    if (Files.isSymbolicLink(root)) {
      // Symlinks restored as themselves are an edge case we don't currently roll back; leave note.
      return
    }
    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
              stash += RollbackEntry(path = file, bytes = Files.readAllBytes(file), wasDirectory = false)
            }
            return java.nio.file.FileVisitResult.CONTINUE
          }

          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
            stash += RollbackEntry(path = dir, bytes = null, wasDirectory = true)
            return java.nio.file.FileVisitResult.CONTINUE
          }
        },
      )
    } else if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
      stash += RollbackEntry(path = root, bytes = Files.readAllBytes(root), wasDirectory = false)
    }
  }

  private fun deletePath(target: Path) {
    if (Files.isSymbolicLink(target)) {
      Files.deleteIfExists(target)
      return
    }
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      Files.walkFileTree(
        target,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
            Files.delete(file)
            return java.nio.file.FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return java.nio.file.FileVisitResult.CONTINUE
          }
        },
      )
      return
    }
    Files.deleteIfExists(target)
  }

  private fun attemptRollback(stash: List<RollbackEntry>): Boolean = try {
    // Re-create directories first (sorted ascending by depth), then write files back.
    stash.filter { it.wasDirectory }
      .sortedBy { it.path.nameCount }
      .forEach { entry ->
        if (!Files.isDirectory(entry.path, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(entry.path)
        }
      }
    stash.filterNot { it.wasDirectory }.forEach { entry ->
      val bytes = entry.bytes ?: return@forEach
      Files.createDirectories(entry.path.parent ?: return@forEach)
      Files.write(entry.path, bytes)
    }
    true
  } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
    // F-CANCEL-ROLLBACK: cancellation must propagate even from inside rollback. We do not return
    // false here because the caller is going to rethrow `cancellation` regardless; rethrowing
    // makes the contract explicit.
    throw cancellation
  } catch (_: Exception) {
    // F-ERROR-PROPAGATE: narrowed from Throwable -> Exception so JVM Error escapes to the
    // supervisor.
    false
  }

  private companion object {
    private val log: Logger = Logger.getLogger("skillbill.skillremove.SkillRemoveJvmFileSystem")
  }
}
