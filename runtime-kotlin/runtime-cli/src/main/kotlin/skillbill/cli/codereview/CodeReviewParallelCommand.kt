package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
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
  "Removed. Dual-agent parallel lanes are disconnected; use code-review for single-agent review.",
  runner = runner,
  state = state,
  configResolutionService = configResolutionService,
) {
  override fun run() {
    throw UsageError(
      "code-review-parallel dual-agent lanes are removed. " +
        "Use 'skill-bill code-review' for single-agent inline or delegated review.",
    )
  }
}
