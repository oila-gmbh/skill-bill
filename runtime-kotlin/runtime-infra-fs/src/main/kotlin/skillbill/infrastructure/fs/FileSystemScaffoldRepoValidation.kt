package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.scaffold.repo.ScaffoldRepoValidationPort
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationRequest
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationResult
import skillbill.scaffold.AuthoringTarget
import skillbill.scaffold.validateTarget

/**
 * Filesystem adapter for [ScaffoldRepoValidationPort]. Builds the existing
 * `skillbill.scaffold.AuthoringTarget` model from the typed request and delegates to the
 * existing `validateTarget` IO seam in `runtime-infra-fs`. The structured result lets
 * pure-policy callers branch on pass/fail without reading the filesystem themselves.
 */
@Inject
class FileSystemScaffoldRepoValidation : ScaffoldRepoValidationPort {
  override fun validateAuthoringTarget(
    request: ScaffoldAuthoringValidationRequest,
  ): ScaffoldAuthoringValidationResult {
    val target = AuthoringTarget(
      skillName = request.skillName,
      packageName = request.packageName,
      platform = request.platform,
      displayName = request.displayName,
      family = request.family,
      area = request.area,
      skillFile = request.skillFile,
      contentFile = request.contentFile,
    )
    val issues = validateTarget(target, request.repoRoot)
    return ScaffoldAuthoringValidationResult(issues = issues)
  }
}
