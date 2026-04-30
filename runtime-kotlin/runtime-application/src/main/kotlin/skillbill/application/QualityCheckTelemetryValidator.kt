package skillbill.application

import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest

private val qualityCheckScopeTypes = listOf("files", "working_tree", "branch_diff", "repo")
private val qualityCheckResults = listOf("pass", "fail", "skipped", "unsupported_stack")

fun validateQualityCheckStarted(request: QualityCheckStartedRequest): String? =
  validateEnum(request.scopeType, qualityCheckScopeTypes, "scope_type")

fun validateQualityCheckFinished(request: QualityCheckFinishedRequest): String? =
  validateEnum(request.result, qualityCheckResults, "result")
