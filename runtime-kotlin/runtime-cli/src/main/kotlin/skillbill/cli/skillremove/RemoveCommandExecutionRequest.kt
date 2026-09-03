package skillbill.cli.skillremove

import skillbill.application.scaffold.SkillRemoveService
import skillbill.cli.core.CliRunInputs
import skillbill.cli.model.CliFormat

internal data class RemoveCommandExecutionRequest(
  val inputs: CliRunInputs,
  val skillRemoveService: SkillRemoveService,
  val rawTarget: String?,
  val repoRoot: String,
  val dryRun: Boolean,
  val allowShipped: Boolean,
  val format: CliFormat,
)
