package skillbill.cli.codereview

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.cli.core.CliRunState

@Inject
class CodeReviewCommand(
  runner: ParallelCodeReviewRunner,
  state: CliRunState,
  configResolutionService: ConfigResolutionService,
) : CodeReviewDriverCommand(
  "code-review",
  "Run a standalone runtime-driven code review. An omitted second lane uses code_review_parallel_agent, then none.",
  agent2Required = false,
  runner = runner,
  state = state,
  configResolutionService = configResolutionService,
)
