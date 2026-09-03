package skillbill.cli.codereview

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliRunInputs

@Inject
class CodeReviewCommand(
  runner: ParallelCodeReviewRunner,
  state: CliRunState,
  inputs: CliRunInputs,
) : CodeReviewDriverCommand(
  "code-review",
  "Run a standalone single-agent runtime-driven code review (inline or delegated).",
  runner = runner,
  state = state,
  inputs = inputs,
) {
  private val commitArgument by argument(
    name = "commit",
    help = "Commit to review against its first parent.",
  ).optional()

  override val commitTarget: String?
    get() = commitArgument
}
