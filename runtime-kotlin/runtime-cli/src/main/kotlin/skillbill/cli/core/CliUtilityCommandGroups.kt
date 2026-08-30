package skillbill.cli.core

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject
import skillbill.cli.agentaddon.AgentAddonCommand
import skillbill.cli.codereview.CodeReviewCommand
import skillbill.cli.config.ConfigCommand
import skillbill.cli.featuretask.FeatureTaskRuntimeDeprecatedRunCommand
import skillbill.cli.featuretask.FeatureTaskRuntimeRunCommand
import skillbill.cli.goal.GoalRunCommand
import skillbill.cli.repovalidation.RepoValidationCliCommands
import skillbill.cli.skillremove.RemoveCliCommand
import skillbill.cli.system.DoctorCliCommand
import skillbill.cli.system.UninstallCommand
import skillbill.cli.system.UpdateCheckCommand
import skillbill.cli.system.UpdateCommand
import skillbill.cli.system.VersionCommand
import skillbill.cli.work.WorkTopLevelCommands
import skillbill.cli.workflow.WorkflowTopLevelCommands

@Inject
internal class WorkflowGoalFeatureCliCommands(
  val workflowCommands: WorkflowTopLevelCommands,
  val repoValidationCommands: RepoValidationCliCommands,
  val goalRunCommand: GoalRunCommand,
  val featureTaskRunCommand: FeatureTaskRuntimeRunCommand,
  val featureTaskRuntimeDeprecatedRunCommand: FeatureTaskRuntimeDeprecatedRunCommand,
)

@Inject
internal class SystemMaintenanceCliCommands(
  val versionCommand: VersionCommand,
  val updateCommand: UpdateCommand,
  val updateCheckCommand: UpdateCheckCommand,
  val uninstallCommand: UninstallCommand,
  val doctorCommand: DoctorCliCommand,
  val removeCommand: RemoveCliCommand,
)

@Inject
internal class MiscCliCommands(
  val codeReviewCommand: CodeReviewCommand,
  val configCommand: ConfigCommand,
  val workCommands: WorkTopLevelCommands,
  val agentAddonCommand: AgentAddonCommand,
)
