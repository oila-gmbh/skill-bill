package skillbill.ports.goalrunner.model

data class GoalRunnerAcceptance(
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
)
