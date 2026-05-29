package skillbill.scaffold

import skillbill.ports.scaffold.model.ScaffoldSkillStatus

/**
 * SKILL-52.3 subtask 3 — Typed intermediate results produced by [AuthoringOperations]
 * and consumed by `FileSystemScaffoldGateway`. These replace the legacy
 * `Map<String, Any?>` open-boundary payloads so the gateway constructs the typed port
 * result DTOs from strongly-typed fields. Wire-shape serialization lives in the
 * adapter mappers (`runtime-cli` / `runtime-desktop`).
 */
internal data class AuthoringListResult(
  val repoRoot: String,
  val skillCount: Int,
  val skills: List<ScaffoldSkillStatus>,
)

/**
 * Post-mutation status shared by fill / saveExactContent / editWithBodyFile. The
 * per-operation bookkeeping fields (`updated_section`, `used_editor`, etc.) are added
 * by the operation that wraps this status.
 */
internal data class AuthoringMutationResult(
  val status: ScaffoldSkillStatus,
  val wrapperRegenerated: Boolean,
)

internal data class AuthoringFillResult(
  val mutation: AuthoringMutationResult,
  val updatedSection: String?,
  val validatorRan: Boolean,
)

internal data class AuthoringSaveExactContentResult(
  val mutation: AuthoringMutationResult,
  val validatorRan: Boolean,
)

internal data class AuthoringEditWithBodyFileResult(
  val usedEditor: Boolean,
  val guidedSections: List<String>,
  val updatedSection: String?,
  val validatorRan: Boolean,
  val mutation: AuthoringMutationResult,
)

internal data class AuthoringExplain(
  val explanation: String,
  val editableSurface: List<String>,
  val generatedSurface: List<String>,
  val governedSidecars: List<String>,
  val normalWorkflow: List<String>,
  val notes: List<String>,
  val skill: AuthoringExplainSkill? = null,
)

internal data class AuthoringExplainSkill(
  val skillName: String,
  val contentFile: String,
  val renderCommand: String,
  val recommendedCommands: List<String>,
)

internal data class AuthoringValidateResult(
  val repoRoot: String,
  val mode: String,
  val skillNames: List<String>?,
  val status: String,
  val issues: List<String>,
  val suggestedCommands: List<String>?,
)

internal data class AuthoringUpgradeResult(
  val repoRoot: String,
  val regeneratedCount: Int,
  val regeneratedFiles: List<String>,
  val contentMdTouched: Boolean,
  val shellCeremonyTouched: Boolean,
  val validatorRan: Boolean,
)
