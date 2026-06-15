@file:Suppress("LongParameterList")

package skillbill.cli.core

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject
import skillbill.cli.codereview.CodeReviewMergeCommand
import skillbill.cli.codereview.CodeReviewParallelCommand
import skillbill.cli.config.ConfigCommand
import skillbill.cli.featuretask.FeatureTaskRuntimeDeprecatedRunCommand
import skillbill.cli.featuretask.FeatureTaskRuntimeRunCommand
import skillbill.cli.goal.GoalRunCommand
import skillbill.cli.learning.LearningsCommand
import skillbill.cli.repovalidation.RepoValidationCliCommands
import skillbill.cli.review.ReviewTopLevelCommands
import skillbill.cli.scaffold.ScaffoldTopLevelCommands
import skillbill.cli.skillremove.RemoveCliCommand
import skillbill.cli.system.DoctorCliCommand
import skillbill.cli.system.UpdateCheckCommand
import skillbill.cli.system.UpdateCommand
import skillbill.cli.system.VersionCommand
import skillbill.cli.telemetry.TelemetryCommand
import skillbill.cli.workflow.WorkflowTopLevelCommands

@Inject
class ReviewCliCommandGroup(
  reviewCommands: ReviewTopLevelCommands,
  learningsCommand: LearningsCommand,
  telemetryCommand: TelemetryCommand,
) {
  val commands: List<CliktCommand> =
    reviewCommands.commands + listOf(
      learningsCommand,
      telemetryCommand,
    )
}

@Inject
class ScaffoldCliCommandGroup(
  scaffoldCommands: ScaffoldTopLevelCommands,
) {
  val commands: List<CliktCommand> = scaffoldCommands.commands
}

@Inject
class UtilityCliCommandGroup(
  workflowCommands: WorkflowTopLevelCommands,
  repoValidationCommands: RepoValidationCliCommands,
  goalRunCommand: GoalRunCommand,
  featureTaskRunCommand: FeatureTaskRuntimeRunCommand,
  featureTaskRuntimeDeprecatedRunCommand: FeatureTaskRuntimeDeprecatedRunCommand,
  versionCommand: VersionCommand,
  updateCommand: UpdateCommand,
  updateCheckCommand: UpdateCheckCommand,
  doctorCommand: DoctorCliCommand,
  removeCommand: RemoveCliCommand,
  codeReviewParallelCommand: CodeReviewParallelCommand,
  codeReviewMergeCommand: CodeReviewMergeCommand,
  configCommand: ConfigCommand,
) {
  val commands: List<CliktCommand> =
    workflowCommands.commands + repoValidationCommands.commands + listOf(
      goalRunCommand,
      featureTaskRunCommand,
      featureTaskRuntimeDeprecatedRunCommand,
      versionCommand,
      updateCommand,
      updateCheckCommand,
      doctorCommand,
      removeCommand,
      codeReviewParallelCommand,
      codeReviewMergeCommand,
      configCommand,
    )
}

@Inject
class TopLevelCliCommands(
  reviewCommands: ReviewCliCommandGroup,
  scaffoldCommands: ScaffoldCliCommandGroup,
  utilityCommands: UtilityCliCommandGroup,
) {
  val rootCommands: List<CliktCommand> =
    reviewCommands.commands + scaffoldCommands.commands + utilityCommands.commands
}
