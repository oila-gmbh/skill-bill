package skillbill.cli.models

import skillbill.learnings.LearningScope

internal data class LearningsAddArgs(
  val scope: LearningScope,
  val scopeKey: String,
  val title: String,
  val rule: String,
  val reason: String,
  val fromRun: String,
  val fromFinding: String,
  val format: CliFormat,
)

internal data class LearningsListArgs(
  val status: String,
  val format: CliFormat,
)

internal data class LearningsShowArgs(
  val id: Int,
  val format: CliFormat,
)

internal data class LearningsResolveArgs(
  val repo: String?,
  val skill: String?,
  val reviewSessionId: String?,
  val format: CliFormat,
)

internal data class LearningsEditArgs(
  val id: Int,
  val scope: LearningScope?,
  val scopeKey: String?,
  val title: String?,
  val rule: String?,
  val reason: String?,
  val format: CliFormat,
)

internal data class LearningStatusArgs(
  val id: Int,
  val format: CliFormat,
)

internal data class LearningDeleteArgs(
  val id: Int,
  val format: CliFormat,
)
