package skillbill.ports.scaffold.repo

import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationRequest
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationResult

/**
 * Capability port for repo-validation that the scaffolder needs after staging a target. The
 * adapter wraps the existing governed-skill validation pipeline; pure-policy callers receive
 * a structured pass/fail result without performing filesystem walks themselves.
 */
fun interface ScaffoldRepoValidationPort {
  fun validateAuthoringTarget(request: ScaffoldAuthoringValidationRequest): ScaffoldAuthoringValidationResult
}
