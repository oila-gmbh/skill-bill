package skillbill.cli.skillremove

import skillbill.application.scaffold.SkillRemoveService
import skillbill.cli.core.CliRunState
import skillbill.cli.model.CliFormat

internal data class RemoveCommandExecutionRequest(
  val state: CliRunState,
  val skillRemoveService: SkillRemoveService,
  val rawTarget: String?,
  val repoRoot: String,
  val dryRun: Boolean,
  val allowShipped: Boolean,
  val format: CliFormat,
)
