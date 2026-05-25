package skillbill.desktop.core.data.service.mapper

import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.validation.model.RepoValidationIssue
import skillbill.ports.validation.model.RepoValidationIssueSeverity
import skillbill.ports.validation.model.RepoValidationReport
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * SKILL-52.2 subtask 5 — Adapter-side mapper from typed runtime-application /
 * runtime-ports validation results into the desktop UI `ValidationSummary` shape.
 *
 * Two seams are mapped here:
 *
 *  1. [RepoValidationReport.toValidationSummary] — full-repo validation. The
 *     report is already fully typed; the mapper only translates severity codes
 *     and normalises issue source paths into repo-relative portable form.
 *  2. [ScaffoldValidateResult.toSelectedValidationSummary] — per-skill validation
 *     through the scaffold gateway. This entry point retains a documented
 *     `@OpenBoundaryMap` `payload: Map<String, Any?>` field on the typed result
 *     model (`skillbill.ports.scaffold.repo.model.ScaffoldValidateResult.payload`
 *     is on the open-boundary allow-list). The mapper reads `status` from the
 *     **typed** field (the source of truth for the pass/fail decision) and reads
 *     the legacy `issues` array shape from the payload as raw strings, then
 *     re-parses each through the canonical `RepoValidationIssue.fromRawIssue`
 *     port helper. Business policy (the pass/fail decision) stays in the typed
 *     field; only the legacy wire-shape decoding of issue strings is contained
 *     in this mapper.
 *
 * Keeping both translations in this mapper file keeps service code in
 * `runtime-desktop:core:data` free of raw-map indexing and lets the
 * [skillbill.architecture.RuntimeDesktopGatewayPolicyTest] guard scan for direct
 * `.payload[...]` reads on service implementations.
 */
internal fun RepoValidationReport.toValidationSummary(root: Path): ValidationSummary = ValidationSummary(
  state = if (passed) ValidationRunState.PASSED else ValidationRunState.FAILED,
  issues = structuredIssues.map { issue -> issue.toValidationIssue(root) },
)

internal fun ScaffoldValidateResult.toSelectedValidationSummary(root: Path): ValidationSummary {
  val rawIssues = payload["issues"] as? List<*> ?: emptyList<Any?>()
  return ValidationSummary(
    state = if (status == "pass") ValidationRunState.PASSED else ValidationRunState.FAILED,
    issues = rawIssues
      .filterIsInstance<String>()
      .map { rawIssue -> RepoValidationIssue.fromRawIssue(rawIssue).toValidationIssue(root) },
  )
}

internal fun RepoValidationIssue.toValidationIssue(root: Path): ValidationIssue = ValidationIssue(
  severity = severity.toDomain(),
  code = code,
  message = message,
  sourcePath = sourcePath?.let { path -> normalizeSourcePath(root, path) },
  exceptionName = exceptionName,
)

private fun RepoValidationIssueSeverity.toDomain(): ValidationSeverity = when (this) {
  RepoValidationIssueSeverity.ERROR -> ValidationSeverity.ERROR
  RepoValidationIssueSeverity.WARNING -> ValidationSeverity.WARNING
  RepoValidationIssueSeverity.INFO -> ValidationSeverity.INFO
}

private fun normalizeSourcePath(root: Path, sourcePath: String): String {
  val trimmed = sourcePath.trim()
  if (trimmed.isBlank()) {
    return ""
  }
  return runCatching {
    val absolute = java.nio.file.Path.of(trimmed)
    if (absolute.isAbsolute) {
      absolute.toAbsolutePath().normalize().relativeTo(root).toString().replace('\\', '/')
    } else {
      trimmed.replace('\\', '/')
    }
  }.getOrDefault(trimmed.replace('\\', '/'))
}
