package skillbill.cli.codereview

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.cli.core.CliRunState

@Inject
class CodeReviewParallelCommand(
  runner: ParallelCodeReviewRunner,
  state: CliRunState,
  configResolutionService: ConfigResolutionService,
) : CodeReviewDriverCommand(
  "code-review-parallel",
  "Run two review agents in parallel on the same diff and merge findings.",
  agent2Required = true,
  runner = runner,
  state = state,
  configResolutionService = configResolutionService,
)
