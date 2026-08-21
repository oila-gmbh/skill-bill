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
  "Run a standalone single-agent runtime-driven code review (inline or delegated).",
  runner = runner,
  state = state,
  configResolutionService = configResolutionService,
)
