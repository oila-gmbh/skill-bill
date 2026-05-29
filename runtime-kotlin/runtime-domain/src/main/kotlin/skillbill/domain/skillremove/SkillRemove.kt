@file:Suppress("TooGenericExceptionCaught", "ThrowsCount", "MaxLineLength", "InstanceOfCheckForException")

package skillbill.domain.skillremove

import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.error.SkillBillRuntimeException
import java.nio.file.Paths

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
    // F-004-RELIABILITY-LOG: structured begin/failure/success logging is emitted by the
    // [SkillRemoveFileSystem] adapter (SKILL-52.3) so the pure domain stays effect-free; the
    // adapter strips absolute paths so we never leak filesystem layout into the log stream.
    val applied = fileSystem.applyCascade(request, preview)
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
    when (target) {
      is SkillRemovalTarget.HorizontalSkill -> {
        val candidate = repoRoot.resolve("skills/${target.skillName}").normalize()
        if (candidate.startsWith(billSharedSkillRoot)) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
            "Removal of '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
          )
        }
        // SKILL-49: horizontal `bill-*` skills are the product surface (bill-code-review,
        // bill-feature-implement, etc.) and join the shipped-protection set alongside the
        // `kotlin` / `kmp` pre-shells. Predicate sourced from SkillRemovalTarget so the desktop
        // mirror (`isBuiltInName`) and the domain refusal agree on the line.
        val protectedShipped = candidate.startsWith(kotlinSkillRoot) ||
          candidate.startsWith(kmpSkillRoot) ||
          target.skillName.startsWith(SkillRemovalTarget.HORIZONTAL_PRODUCT_PREFIX)
        if (!target.allowShipped && protectedShipped) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED,
            "Refusing to remove shipped surface '${target.skillName}' without allowShipped=true.",
          )
        }
      }
      is SkillRemovalTarget.PlatformPack -> {
        // F-S03: `.bill-shared` is never deletable, whether requested as a skill OR a platform pack.
        if (target.platform == BILL_SHARED_NAME) {
          throw SkillRemovalRefusedException(
            SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
            "Removal of platform pack '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
          )
        }
        // SKILL-49: shipped first-party platform packs (`kotlin`, `kmp`) are user-removable.
        // Platform packs are the user-extension surface; forks may drop packs they do not use.
        // `--allow-shipped` is no longer required on this axis; the horizontal-skill axis still
        // gates `kotlin` / `kmp` pre-shells separately so the pack and its pre-shell can't go out
        // of sync from the wrong tree node.
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

  companion object {
    const val BILL_SHARED_NAME: String = ".bill-shared"
    val SHIPPED_HORIZONTAL_SKILLS: Set<String> = setOf("kotlin", "kmp")
    val SHIPPED_PLATFORMS: Set<String> = setOf("kotlin", "kmp")
  }
}
