@file:Suppress("LongParameterList")

package skillbill.cli

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

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
