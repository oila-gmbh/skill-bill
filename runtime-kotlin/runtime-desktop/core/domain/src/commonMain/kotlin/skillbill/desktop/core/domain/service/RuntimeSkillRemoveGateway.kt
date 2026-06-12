package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult

/**
 * SKILL-46: desktop-facing seam over the runtime-domain `SkillRemove` service. Mirrors the
 * `RuntimeScaffoldGateway` pattern (suspend-friendly, never throws across the coroutine boundary)
 * so the ViewModel can stay free of try/catch.
 *
 * Both functions accept a single payload-builder ([DesktopSkillRemovalRequest]) so preview and
 * execute are guaranteed to operate against the same captured state — the ViewModel constructs
 * the request once on Main before the background dispatcher hop.
 *
 * The JVM implementation lives in `core/data/.../service/RuntimeSkillRemoveGateway.kt` (sibling
 * gateway, NOT a 7th surface on `RuntimeRepoBrowserService`).
 */
interface RuntimeSkillRemoveGateway {
  /**
   * Compute the removal cascade dossier without mutating the repo. Returns either
   * [DesktopSkillRemovalResult.Preview] or [DesktopSkillRemovalResult.Failed] (when a refusal
   * or programmer error occurs). NEVER returns [DesktopSkillRemovalResult.Success].
   */
  suspend fun preview(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult

  /**
   * Apply the removal cascade. Returns [DesktopSkillRemovalResult.Success] on the happy path
   * or [DesktopSkillRemovalResult.Failed] otherwise. NEVER returns
   * [DesktopSkillRemovalResult.Preview].
   *
   * `Failed.rollbackComplete` is `false` only when the runtime raised a rollback exception,
   * meaning the repo may be partially mutated. The ViewModel uses that flag to set
   * `partialMutationLocked = true` (F-102/F-408-plat).
   */
  suspend fun execute(request: DesktopSkillRemovalRequest): DesktopSkillRemovalResult
}
