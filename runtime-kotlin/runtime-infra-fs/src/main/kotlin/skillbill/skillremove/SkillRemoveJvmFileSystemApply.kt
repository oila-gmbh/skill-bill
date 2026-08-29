package skillbill.skillremove

import skillbill.domain.skillremove.SkillBillRollbackException
import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AppliedCascade
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogWarning
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.install.nativeagent.InstallNativeAgentOperations
import skillbill.install.nativeagent.NativeAgentLinkRequest
import skillbill.scaffold.manifest.removeAddonReferences
import skillbill.scaffold.manifest.removeCodeReviewArea
import skillbill.scaffold.manifest.removeDeclaredFilesBaseline
import skillbill.scaffold.manifest.removeDeclaredQualityCheckFile
import skillbill.scaffold.manifest.removePointersBlockKey
import skillbill.scaffold.manifest.removeSkillClassPointer
import skillbill.scaffold.platformpack.ReadmeCatalogEdits
import skillbill.scaffold.platformpack.ReadmeEditOutcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger
import java.nio.file.FileVisitResult.CONTINUE
import kotlin.coroutines.cancellation.CancellationException
import java.nio.file.FileVisitResult

internal class SkillRemoveJvmFileSystemApply(
  private val home: Path?,
) {
  fun applyCascade(request: SkillRemovalRequest, preview: SkillRemovalPreview): AppliedCascade {
    val repoRoot = skillRemoveRepoRoot(request)
    val rollbackStash = mutableListOf<RollbackEntry>()
    val removedPaths = mutableListOf<String>()
    val editedManifests = mutableListOf<String>()
    val unlinkedSymlinks = mutableListOf<String>()
    val readmeWarnings = mutableListOf<ReadmeCatalogWarning>()
    log.info(
      "skill-bill remove begin: target=${describeTargetForLog(request.target)} " +
        "cascadedSkills=${preview.cascadedSkillNames.size} " +
        "filesystemPaths=${preview.filesystemPaths.size}",
    )
    try {

      preview.manifestEdits.forEach { edit ->
        stashFile(repoRoot.resolve(edit.manifestPath), rollbackStash)
      }
      preview.readmeCatalogEdits.forEach { edit ->
        stashFile(repoRoot.resolve(edit.readmePath), rollbackStash)
      }

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
          ManifestEditKind.REMOVE_DECLARED_FILES_BASELINE -> {
            removeDeclaredFilesBaseline(manifest)
          }
          ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY -> {
            removePointersBlockKey(manifest, edit.detail)
          }
          ManifestEditKind.REMOVE_ADDON_REFERENCES -> {
            removeAddonReferences(manifest, edit.detail)
          }
          ManifestEditKind.REMOVE_SKILL_CLASS_POINTER -> {
            removeSkillClassPointer(manifest, edit.detail)
          }
        }
        editedManifests += edit.manifestPath
      }

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

      val absolutePaths = preview.filesystemPaths
        .map { rel -> repoRoot.resolve(rel) }
        .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
      absolutePaths.forEach { stashTree(it, rollbackStash) }
      absolutePaths.forEach { absolute ->
        deletePath(absolute)
        removedPaths += absolute.toString().replace('\\', '/')
      }

      val symlinks = unlinkProviderAgents(request)
      unlinkedSymlinks += symlinks.map { it.toString().replace('\\', '/') }

      log.info(
        "skill-bill remove success: removedPaths=${removedPaths.size} " +
          "editedManifests=${editedManifests.size} " +
          "unlinkedSymlinks=${unlinkedSymlinks.size}",
      )
      return AppliedCascade(
        removedPaths = removedPaths,
        editedManifests = editedManifests,
        unlinkedSymlinks = unlinkedSymlinks,
        readmeWarnings = readmeWarnings,
      )
    } catch (cancellation: CancellationException) {
      log.info("skill-bill remove failed: exceptionName=${cancellation::class.simpleName.orEmpty()}")
      attemptRollback(rollbackStash)
      throw cancellation
    } catch (error: Exception) {
      log.info("skill-bill remove failed: exceptionName=${error::class.simpleName.orEmpty()}")
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
  fun providerUnlink(provider: AgentSymlinkProvider): (NativeAgentLinkRequest) -> List<Path> = when (provider) {
    AgentSymlinkProvider.CLAUDE -> InstallNativeAgentOperations::unlinkClaudeAgents
    AgentSymlinkProvider.CODEX -> InstallNativeAgentOperations::unlinkCodexAgents
    AgentSymlinkProvider.JUNIE -> InstallNativeAgentOperations::unlinkJunieAgents
    AgentSymlinkProvider.CURSOR -> InstallNativeAgentOperations::unlinkCursorAgents
  }

  fun unlinkProviderAgents(request: SkillRemovalRequest): List<Path> {
    val repoRoot = skillRemoveRepoRoot(request)
    val target = request.target
    val resolvedHome = skillRemoveUserHome(request, home)
    val platformPacksRoot = repoRoot.resolve("platform-packs")
    val skillsRoot = repoRoot.resolve("skills")
    val selectedPlatforms: List<String>? = when (target) {
      is SkillRemovalTarget.PlatformPack -> listOf(target.platform)
      is SkillRemovalTarget.HorizontalSkill,
      is SkillRemovalTarget.AddOn,
      is SkillRemovalTarget.ExternalAddOn,
      -> null
    }
    val baseRequest = NativeAgentLinkRequest(
      platformPacksRoot = platformPacksRoot,
      skillsRoot = skillsRoot,
      home = resolvedHome,
      selectedPlatforms = selectedPlatforms,
    )
    val unlinked = mutableListOf<Path>()
    val failures = mutableListOf<UnlinkFailure>()
    AgentSymlinkProvider.entries.forEach { provider ->
      try {
        unlinked += providerUnlink(provider)(baseRequest)
      } catch (cancellation: CancellationException) {
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
      return
    }
    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
              stash += RollbackEntry(path = file, bytes = Files.readAllBytes(file), wasDirectory = false)
            }
            return CONTINUE
          }

          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            stash += RollbackEntry(path = dir, bytes = null, wasDirectory = true)
            return CONTINUE
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
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return CONTINUE
          }
        },
      )
      return
    }
    Files.deleteIfExists(target)
  }

  private fun attemptRollback(stash: List<RollbackEntry>): Boolean = try {
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
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (_: Exception) {
    false
  }

  private companion object {
    private val log: Logger = Logger.getLogger("skillbill.skillremove.SkillRemoveJvmFileSystem")
  }
}

