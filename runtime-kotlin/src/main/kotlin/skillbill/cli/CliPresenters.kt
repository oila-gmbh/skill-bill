package skillbill.cli

import skillbill.application.LearningListResult
import skillbill.application.LearningResolveResult
import skillbill.application.TriageResult
import skillbill.learnings.LearningEntry

internal data class CliLearningListPresentation(
  val entries: List<CliLearningLine>,
)

internal data class CliLearningLine(
  val reference: String,
  val status: String,
  val scopeLabel: String,
  val title: String,
)

internal data class CliResolvedLearningsPresentation(
  val scopePrecedence: String,
  val repoScopeKey: String?,
  val skillName: String?,
  val appliedLearnings: String,
  val entries: List<CliResolvedLearningLine>,
)

internal data class CliResolvedLearningLine(
  val reference: String,
  val scopeLabel: String,
  val title: String,
  val ruleText: String,
)

internal data class CliNumberedFindingsPresentation(
  val reviewRunId: String,
  val findings: List<CliNumberedFindingLine>,
)

internal data class CliNumberedFindingLine(
  val number: Int,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
)

internal data class CliTriagePresentation(
  val reviewRunId: String,
  val decisions: List<CliTriageDecisionLine>,
)

internal data class CliTriageDecisionLine(
  val number: Int,
  val findingId: String,
  val outcomeType: String,
  val note: String,
)

internal fun LearningListResult.toCliPresentation(): CliLearningListPresentation =
  CliLearningListPresentation(entries = learnings.map(LearningEntry::toCliLearningLine))

internal fun LearningResolveResult.toCliPresentation(): CliResolvedLearningsPresentation =
  CliResolvedLearningsPresentation(
    scopePrecedence = scopePrecedence.joinToString(" > ") { scope -> scope.wireName },
    repoScopeKey = repoScopeKey,
    skillName = skillName,
    appliedLearnings = summarizeAppliedLearnings(learnings),
    entries = learnings.map(LearningEntry::toCliResolvedLearningLine),
  )

internal fun TriageResult.toCliNumberedFindingsPresentation(reviewRunId: String): CliNumberedFindingsPresentation =
  CliNumberedFindingsPresentation(
    reviewRunId = reviewRunId,
    findings =
    findings.map { finding ->
      CliNumberedFindingLine(
        number = finding.number,
        findingId = finding.findingId,
        severity = finding.severity,
        confidence = finding.confidence,
        location = finding.location,
        description = finding.description,
      )
    },
  )

internal fun TriageResult.toCliTriagePresentation(reviewRunId: String): CliTriagePresentation = CliTriagePresentation(
  reviewRunId = reviewRunId,
  decisions =
  recorded.map { decision ->
    CliTriageDecisionLine(
      number = decision.number,
      findingId = decision.findingId,
      outcomeType = decision.outcomeType,
      note = decision.note,
    )
  },
)

private fun LearningEntry.toCliLearningLine(): CliLearningLine = CliLearningLine(
  reference = reference,
  status = status,
  scopeLabel = scopedLabel(),
  title = title,
)

private fun LearningEntry.toCliResolvedLearningLine(): CliResolvedLearningLine = CliResolvedLearningLine(
  reference = reference,
  scopeLabel = scopedLabel(),
  title = title,
  ruleText = ruleText,
)

private fun LearningEntry.scopedLabel(): String =
  if (scopeKey.isNotEmpty()) "${scope.wireName}:$scopeKey" else scope.wireName

private fun summarizeAppliedLearnings(entries: List<LearningEntry>): String =
  if (entries.isEmpty()) "none" else entries.joinToString(", ") { entry -> entry.reference }
