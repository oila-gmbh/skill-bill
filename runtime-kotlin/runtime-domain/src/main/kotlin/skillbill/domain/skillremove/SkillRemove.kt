@file:Suppress("TooGenericExceptionCaught", "ThrowsCount", "MaxLineLength", "InstanceOfCheckForException")

package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.error.SkillBillRuntimeException
import java.nio.file.Paths
import java.util.logging.Logger

/**
 * Domain service that orchestrates skill removal (SKILL-46).
 *
 * Owns the typed refusal policy (`.bill-shared`, `kotlin`, `kmp`) and the preview-then-execute
 * lifecycle. All I/O is delegated to the [SkillRemoveFileSystem] port so the unit tests can fake
 * the install primitives in-memory.
 *
 * Contract:
 * - [previewRemoval] never mutates the repo. It returns a [SkillRemovalResult.Preview] dossier or
 *   throws [SkillRemovalRefusedException] for guarded targets.
 * - [executeRemoval] previews first, then applies the cascade. It returns [SkillRemovalResult.Success]
 *   on the happy path or [SkillRemovalResult.Failed] on any runtime exception.
 *
 * Catch posture in [executeRemoval]:
 * - kotlinx `CancellationException` propagates verbatim (re-thrown first by intent — see the docs
 *   on [tryExecute] below for the precise check).
 * - [SkillBillRuntimeException] is caught and mapped — `rollbackComplete = false` only when the
 *   exception is a [SkillBillRollbackException], `true` otherwise.
 * - The generic [Exception] catch defensively forces `rollbackComplete = false` because we cannot
 *   prove rollback ran for a non-runtime exception.
 * - JVM [Error] is NOT caught so it can propagate to the supervisor.
 */
class SkillRemove(
  private val fileSystem: SkillRemoveFileSystem,
) {
  fun previewRemoval(request: SkillRemovalRequest): SkillRemovalResult.Preview {
    // F-S01: validate input BEFORE any filesystem call so a malicious target string can never
    // even be resolved against repoRoot.
    TargetValidation.validateOrRefuse(request)
    enforceRefusalPolicy(request)
    val cascadedSkillNames = computeCascadedSkillNames(request)
    val preview = SkillRemovalPreview(
      filesystemPaths = fileSystem.resolveCascadeFilesystemPaths(request, cascadedSkillNames),
      manifestEdits = fileSystem.planManifestEdits(request, cascadedSkillNames),
      agentSymlinkUnlinks = fileSystem.planAgentSymlinkUnlinks(request, cascadedSkillNames),
      readmeCatalogEdits = fileSystem.planReadmeCatalogEdits(request),
      skillDirRoot = skillDirRootFor(request.target),
      cascadedSkillNames = cascadedSkillNames,
    )
    return SkillRemovalResult.Preview(preview)
  }

  fun executeRemoval(request: SkillRemovalRequest): SkillRemovalResult = tryExecute {
    // F-S01: same gate as previewRemoval — validate every argument before resolving against the
    // filesystem so /etc/passwd / .. cannot reach the executor.
    TargetValidation.validateOrRefuse(request)
    enforceRefusalPolicy(request)
    val cascadedSkillNames = computeCascadedSkillNames(request)
    val preview = SkillRemovalPreview(
      filesystemPaths = fileSystem.resolveCascadeFilesystemPaths(request, cascadedSkillNames),
      manifestEdits = fileSystem.planManifestEdits(request, cascadedSkillNames),
      agentSymlinkUnlinks = fileSystem.planAgentSymlinkUnlinks(request, cascadedSkillNames),
      readmeCatalogEdits = fileSystem.planReadmeCatalogEdits(request),
      skillDirRoot = skillDirRootFor(request.target),
      cascadedSkillNames = cascadedSkillNames,
    )
    // F-004-RELIABILITY-LOG: structured info at start of execute. Strip absolute paths via
    // [SkillRemoveErrorSanitizer] so we never leak filesystem layout into the log stream.
    log.info(
      "skill-bill remove begin: target=${describeTargetForLog(request.target)} " +
        "cascadedSkills=${cascadedSkillNames.size} " +
        "filesystemPaths=${preview.filesystemPaths.size}",
    )
    val applied = try {
      fileSystem.applyCascade(request, preview)
    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
      throw cancellation
    } catch (error: Throwable) {
      // Re-throw; failure logging happens in tryExecute below where we have the exception class.
      log.info("skill-bill remove failed: exceptionName=${error::class.simpleName.orEmpty()}")
      throw error
    }
    log.info(
      "skill-bill remove success: removedPaths=${applied.removedPaths.size} " +
        "editedManifests=${applied.editedManifests.size} " +
        "unlinkedSymlinks=${applied.unlinkedSymlinks.size}",
    )
    SkillRemovalResult.Success(
      preview = preview,
      removedPaths = applied.removedPaths,
      editedManifests = applied.editedManifests,
      unlinkedSymlinks = applied.unlinkedSymlinks,
      readmeWarnings = applied.readmeWarnings,
    )
  }

  /**
   * F-S03: after [TargetValidation] proves the identifier is well-formed, canonicalize the
   * concrete target path against the repo root and compare via [Path.startsWith] (not name
   * equality). This catches identifiers that differ in casing on case-insensitive file systems,
   * symlink-aliasing, or any other surface the prior exact-equality check could miss.
   */
  private fun enforceRefusalPolicy(request: SkillRemovalRequest) {
    val repoRoot = Paths.get(request.repoRootAbsolutePath).toAbsolutePath().normalize()
    val target = request.target
    val billSharedSkillRoot = repoRoot.resolve("skills/$BILL_SHARED_NAME").normalize()
    val kotlinSkillRoot = repoRoot.resolve("skills/kotlin").normalize()
    val kmpSkillRoot = repoRoot.resolve("skills/kmp").normalize()
    val kotlinPackRoot = repoRoot.resolve("platform-packs/kotlin").normalize()
    val kmpPackRoot = repoRoot.resolve("platform-packs/kmp").normalize()
    when (target) {
      is SkillRemovalTarget.HorizontalSkill -> {
        val candidate = repoRoot.resolve("skills/${target.skillName}").normalize()
        if (candidate.startsWith(billSharedSkillRoot)) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
            "Removal of '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
          )
        }
        val protectedShipped = candidate.startsWith(kotlinSkillRoot) || candidate.startsWith(kmpSkillRoot)
        if (!target.allowShipped && protectedShipped) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED,
            "Refusing to remove shipped surface '${target.skillName}' without allowShipped=true.",
          )
        }
      }
      is SkillRemovalTarget.PlatformPack -> {
        val candidate = repoRoot.resolve("platform-packs/${target.platform}").normalize()
        // F-S03: `.bill-shared` is never deletable, whether requested as a skill OR a platform pack.
        if (target.platform == BILL_SHARED_NAME) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
            "Removal of platform pack '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
          )
        }
        val protectedShipped = candidate.startsWith(kotlinPackRoot) || candidate.startsWith(kmpPackRoot)
        if (!target.allowShipped && protectedShipped) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED,
            "Refusing to remove shipped platform pack '${target.platform}' without allowShipped=true.",
          )
        }
      }
      is SkillRemovalTarget.AddOn -> Unit
    }
  }

  private fun computeCascadedSkillNames(request: SkillRemovalRequest): List<String> =
    when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill ->
        listOf(target.skillName) +
          fileSystem.discoverCascadedSkillNames(request)
            .filter { it != target.skillName }
      is SkillRemovalTarget.PlatformPack -> emptyList()
      is SkillRemovalTarget.AddOn -> emptyList()
    }

  private fun skillDirRootFor(target: SkillRemovalTarget): String = when (target) {
    is SkillRemovalTarget.HorizontalSkill -> "skills/${target.skillName}"
    is SkillRemovalTarget.PlatformPack -> "platform-packs/${target.platform}"
    is SkillRemovalTarget.AddOn -> target.relativePath
  }

  /**
   * Wraps the happy-path block with the documented catch posture. Cancellation must propagate
   * verbatim (kotlinx coroutines contract); [SkillBillRuntimeException] subclasses map to
   * [SkillRemovalResult.Failed]; generic [Exception] is caught with `rollbackComplete = false`;
   * JVM [Error] is not caught.
   */
  private inline fun tryExecute(block: () -> SkillRemovalResult): SkillRemovalResult = try {
    block()
  } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
    // stdlib's CancellationException is the contract type the kotlinx coroutine machinery throws.
    // We re-throw verbatim so coroutine cancellation propagates regardless of whether the caller
    // is on a coroutine dispatcher (Dispatchers.Default hop in the desktop ViewModel) or a
    // direct-blocking call from the CLI.
    throw cancellation
  } catch (error: SkillBillRuntimeException) {
    SkillRemovalResult.Failed(
      exceptionName = error::class.simpleName.orEmpty(),
      exceptionMessage = error.message.orEmpty(),
      rollbackComplete = error !is SkillBillRollbackException,
    )
  } catch (error: Exception) {
    SkillRemovalResult.Failed(
      exceptionName = error::class.simpleName.orEmpty().ifBlank { "Exception" },
      exceptionMessage = error.message.orEmpty(),
      rollbackComplete = false,
    )
  }

  /**
   * F-004-RELIABILITY-LOG helper: returns a stable, log-safe identifier for the target. The
   * identifiers here never contain absolute repo paths so logs are safe to ship.
   */
  private fun describeTargetForLog(target: SkillRemovalTarget): String = when (target) {
    is SkillRemovalTarget.HorizontalSkill -> "skill:${target.skillName}"
    is SkillRemovalTarget.PlatformPack -> "platform:${target.platform}"
    is SkillRemovalTarget.AddOn -> "addon:${target.relativePath}"
  }

  companion object {
    const val BILL_SHARED_NAME: String = ".bill-shared"
    val SHIPPED_HORIZONTAL_SKILLS: Set<String> = setOf("kotlin", "kmp")
    val SHIPPED_PLATFORMS: Set<String> = setOf("kotlin", "kmp")
    private val log: Logger = Logger.getLogger("skillbill.domain.skillremove.SkillRemove")
  }
}
