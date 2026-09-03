package skillbill.cli.install

import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.ports.install.model.NativeAgentLinkOutcome
import java.nio.file.Path

private const val GOAL_CONTINUATION_ENV = "SKILL_BILL_GOAL_CONTINUATION"
private const val GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE = 64

internal fun CliRunState.refuseInstallMutationDuringGoalContinuation(
  inputs: CliRunInputs,
  commandName: String,
): Boolean {
  if (inputs.environment[GOAL_CONTINUATION_ENV] != "1") {
    return false
  }
  val message =
    "Refusing to run skill-bill install $commandName during skill-bill goal-continuation.\n" +
      "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  completeText(
    "$message\n",
    mapOf(
      "status" to "error",
      "error" to message,
      "exit_code" to GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
    ),
    exitCode = GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
  )
  return true
}

internal fun completeNativeAgentLinkOutcome(state: CliRunState, outcome: NativeAgentLinkOutcome) {
  val text = (
    outcome.linked.map { path -> "linked\t$path" } +
      outcome.skipped.map { entry -> "skipped\t${entry.path}\t${entry.reason}" }
    ).joinToString("\n")
  state.completeText(
    text,
    mapOf(
      "linked" to outcome.linked.map(Path::toString),
      "skipped" to outcome.skipped.map { skip ->
        mapOf("path" to skip.path.toString(), "reason" to skip.reason)
      },
    ),
  )
}
