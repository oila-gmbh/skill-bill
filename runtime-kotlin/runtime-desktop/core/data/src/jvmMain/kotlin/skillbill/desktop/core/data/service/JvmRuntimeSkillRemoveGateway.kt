@file:Suppress("TooGenericExceptionCaught", "MaxLineLength")

package skillbill.desktop.core.data.service

import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.DesktopAgentSymlinkProvider
import skillbill.desktop.core.domain.model.DesktopAgentSymlinkUnlink
import skillbill.desktop.core.domain.model.DesktopManifestEdit
import skillbill.desktop.core.domain.model.DesktopManifestEditKind
import skillbill.desktop.core.domain.model.DesktopReadmeCatalogEdit
import skillbill.desktop.core.domain.model.DesktopReadmeCatalogEditKind
import skillbill.desktop.core.domain.model.DesktopReadmeCatalogWarning
import skillbill.desktop.core.domain.model.DesktopSkillRemovalPreview
import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.domain.skillremove.SkillBillRollbackException
import skillbill.domain.skillremove.SkillRemove
import skillbill.domain.skillremove.SkillRemoveErrorSanitizer
import skillbill.domain.skillremove.model.AgentSymlinkProvider
import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEditKind
import skillbill.domain.skillremove.model.ReadmeCatalogWarning
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.error.SkillBillRuntimeException
import skillbill.skillremove.SkillRemoveJvmFileSystem
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.logging.Logger
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway as RuntimeSkillRemoveGatewayInterface

/**
 * SKILL-46: sibling gateway under `core/data/.../service/`. Bridges the desktop ViewModel and
 * the runtime-domain `SkillRemove` service.
 *
 * Construction notes (matching the F-107 pattern on `RuntimeRepoBrowserService` and
 * `JvmRuntimeScaffoldGateway`):
 * - the underlying service-factory is exposed via an `internal var` seam (`serviceFactory`)
 *   instead of a constructor lambda — that keeps the public ABI from leaking runtime-domain
 *   types into KSP's classpath through `implementation`-only dependencies.
 *
 * Catch posture (mirrors `JvmRuntimeScaffoldGateway`):
 * - rethrow [CancellationException] verbatim.
 * - map [SkillBillRuntimeException] to [DesktopSkillRemovalResult.Failed] — `rollbackComplete`
 *   is `false` only for [SkillBillRollbackException]; every other runtime exception leaves the
 *   repo clean.
 * - defensive `catch (Exception)` returns `rollbackComplete = false` (we cannot prove rollback
 *   ran for a non-runtime exception).
 * - JVM [Error] is NOT caught.
 */
@Inject
@SingleIn(UserScope::class)
class JvmRuntimeSkillRemoveGateway : RuntimeSkillRemoveGatewayInterface {
  /**
   * F-107 seam: tests swap this to feed scripted runtime-domain `SkillRemove` instances without
   * exercising the real on-disk file system. The default builds a `SkillRemove` backed by the
   * JVM file-system implementation.
   */
  internal var serviceFactory: () -> SkillRemove = { SkillRemove(SkillRemoveJvmFileSystem()) }

  override suspend fun preview(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult =
    invoke(request, dryRun = true)

  override suspend fun execute(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult =
    invoke(request, dryRun = false)

  @Suppress("TooGenericExceptionCaught")
  private fun invoke(request: DesktopSkillRemovalRequest, dryRun: Boolean): DesktopSkillRemovalResult = try {
    val domainRequest = request.toDomainRequest()
    val service = serviceFactory()
    val result = if (dryRun) service.previewRemoval(domainRequest) else service.executeRemoval(domainRequest)
    result.toDesktopResult().sanitizeMessages(request.repoRootAbsolutePath)
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: SkillBillRuntimeException) {
    // F-S04: sanitize absolute paths out of the failure message BEFORE the dialog/CLI sees it.
    log.info("JvmRuntimeSkillRemoveGateway failed: ${error::class.simpleName.orEmpty()}")
    DesktopSkillRemovalResult.Failed(
      exceptionName = error::class.simpleName.orEmpty(),
      exceptionMessage = SkillRemoveErrorSanitizer.sanitize(error.message.orEmpty(), request.repoRootAbsolutePath),
      rollbackComplete = error !is SkillBillRollbackException,
    )
  } catch (error: Exception) {
    log.info("JvmRuntimeSkillRemoveGateway failed (generic): ${error::class.simpleName.orEmpty()}")
    DesktopSkillRemovalResult.Failed(
      exceptionName = error::class.simpleName.orEmpty().ifBlank { "Exception" },
      exceptionMessage = SkillRemoveErrorSanitizer.sanitize(error.message.orEmpty(), request.repoRootAbsolutePath),
      rollbackComplete = false,
    )
  }

  private companion object {
    private val log: Logger = Logger.getLogger("skillbill.desktop.core.data.service.JvmRuntimeSkillRemoveGateway")
  }
}

/**
 * F-S04: every Success/Failed message that reaches the dialog/CLI is sanitized via the same
 * helper so the UI never leaks absolute repo paths.
 */
private fun DesktopSkillRemovalResult.sanitizeMessages(repoRootAbsolutePath: String): DesktopSkillRemovalResult =
  when (this) {
    is DesktopSkillRemovalResult.Failed -> copy(
      exceptionMessage = SkillRemoveErrorSanitizer.sanitize(exceptionMessage, repoRootAbsolutePath),
    )
    is DesktopSkillRemovalResult.Preview, is DesktopSkillRemovalResult.Success -> this
  }

private fun DesktopSkillRemovalRequest.toDomainRequest(): SkillRemovalRequest = SkillRemovalRequest(
  target = target.toDomainTarget(),
  repoRootAbsolutePath = repoRootAbsolutePath,
)

private fun DesktopSkillRemovalTarget.toDomainTarget(): SkillRemovalTarget = when (this) {
  is DesktopSkillRemovalTarget.HorizontalSkill ->
    SkillRemovalTarget.HorizontalSkill(skillName = skillName, allowShipped = allowShipped)
  is DesktopSkillRemovalTarget.PlatformPack ->
    SkillRemovalTarget.PlatformPack(platform = platform, allowShipped = allowShipped)
  is DesktopSkillRemovalTarget.AddOn ->
    SkillRemovalTarget.AddOn(relativePath = relativePath)
}

private fun SkillRemovalResult.toDesktopResult(): DesktopSkillRemovalResult = when (this) {
  is SkillRemovalResult.Preview -> DesktopSkillRemovalResult.Preview(preview = preview.toDesktopPreview())
  is SkillRemovalResult.Success -> DesktopSkillRemovalResult.Success(
    preview = preview.toDesktopPreview(),
    removedPaths = removedPaths,
    editedManifests = editedManifests,
    unlinkedSymlinks = unlinkedSymlinks,
    readmeWarnings = readmeWarnings.map(ReadmeCatalogWarning::toDesktop),
  )
  is SkillRemovalResult.Failed -> DesktopSkillRemovalResult.Failed(
    exceptionName = exceptionName,
    exceptionMessage = exceptionMessage,
    rollbackComplete = rollbackComplete,
  )
}

private fun SkillRemovalPreview.toDesktopPreview(): DesktopSkillRemovalPreview = DesktopSkillRemovalPreview(
  filesystemPaths = filesystemPaths,
  manifestEdits = manifestEdits.map(ManifestEdit::toDesktop),
  agentSymlinkUnlinks = agentSymlinkUnlinks.map(AgentSymlinkUnlink::toDesktop),
  readmeCatalogEdits = readmeCatalogEdits.map(ReadmeCatalogEdit::toDesktop),
  skillDirRoot = skillDirRoot,
  cascadedSkillNames = cascadedSkillNames,
)

private fun ManifestEdit.toDesktop(): DesktopManifestEdit = DesktopManifestEdit(
  manifestPath = manifestPath,
  editKind = when (editKind) {
    ManifestEditKind.REMOVE_CODE_REVIEW_AREA -> DesktopManifestEditKind.REMOVE_CODE_REVIEW_AREA
    ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE -> DesktopManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE
    ManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY -> DesktopManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY
    ManifestEditKind.REMOVE_AREA_METADATA_ENTRY -> DesktopManifestEditKind.REMOVE_AREA_METADATA_ENTRY
    ManifestEditKind.REMOVE_DECLARED_FILES_BASELINE -> DesktopManifestEditKind.REMOVE_DECLARED_FILES_BASELINE
    ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY -> DesktopManifestEditKind.REMOVE_POINTERS_BLOCK_KEY
  },
  detail = detail,
)

private fun AgentSymlinkUnlink.toDesktop(): DesktopAgentSymlinkUnlink = DesktopAgentSymlinkUnlink(
  provider = when (provider) {
    AgentSymlinkProvider.CLAUDE -> DesktopAgentSymlinkProvider.CLAUDE
    AgentSymlinkProvider.CODEX -> DesktopAgentSymlinkProvider.CODEX
    AgentSymlinkProvider.OPENCODE -> DesktopAgentSymlinkProvider.OPENCODE
    AgentSymlinkProvider.JUNIE -> DesktopAgentSymlinkProvider.JUNIE
  },
  path = path,
)

private fun ReadmeCatalogEdit.toDesktop(): DesktopReadmeCatalogEdit = DesktopReadmeCatalogEdit(
  readmePath = readmePath,
  kind = kind.toDesktop(),
  detail = detail,
)

private fun ReadmeCatalogEditKind.toDesktop(): DesktopReadmeCatalogEditKind = when (this) {
  ReadmeCatalogEditKind.REMOVE_CATALOG_ROW -> DesktopReadmeCatalogEditKind.REMOVE_CATALOG_ROW
  ReadmeCatalogEditKind.DECREMENT_SECTION_COUNT -> DesktopReadmeCatalogEditKind.DECREMENT_SECTION_COUNT
}

private fun ReadmeCatalogWarning.toDesktop(): DesktopReadmeCatalogWarning = DesktopReadmeCatalogWarning(
  readmePath = readmePath,
  kind = kind.toDesktop(),
  reason = reason,
)
