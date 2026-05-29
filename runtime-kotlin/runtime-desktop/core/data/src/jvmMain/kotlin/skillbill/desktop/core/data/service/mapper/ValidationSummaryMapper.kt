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
 *     through the scaffold gateway. SKILL-52.3 subtask 3 retired the
 *     `@OpenBoundaryMap` `payload: Map<String, Any?>` field on
 *     `ScaffoldValidateResult`; the mapper now reads the typed `status` field for
 *     the pass/fail decision and the typed `issues: List<String>` field, re-parsing
 *     each issue string through the canonical `RepoValidationIssue.fromRawIssue`
 *     port helper. No raw map is indexed.
 *
 * Keeping both translations in this mapper file keeps service code in
 * `runtime-desktop:core:data` free of raw-shape decoding.
 */
internal fun RepoValidationReport.toValidationSummary(root: Path): ValidationSummary = ValidationSummary(
  state = if (passed) ValidationRunState.PASSED else ValidationRunState.FAILED,
  issues = structuredIssues.map { issue -> issue.toValidationIssue(root) },
)

internal fun ScaffoldValidateResult.toSelectedValidationSummary(root: Path): ValidationSummary = ValidationSummary(
  state = if (status == "pass") ValidationRunState.PASSED else ValidationRunState.FAILED,
  issues = issues.map { rawIssue -> RepoValidationIssue.fromRawIssue(rawIssue).toValidationIssue(root) },
)

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
