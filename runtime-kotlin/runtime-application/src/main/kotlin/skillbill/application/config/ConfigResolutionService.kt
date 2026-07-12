package skillbill.application.config

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigResolution
import skillbill.config.model.SpecType
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import java.nio.file.Path

/**
 * Surfaces the repo-local config precedence points (`explicit arg > config value > built-in
 * default`) over [RepoLocalConfigPort]. A malformed config propagates the typed
 * [skillbill.error.MalformedRepoLocalConfigError] unchanged so callers loud-fail instead
 * of silently defaulting.
 */
@Inject
class ConfigResolutionService(
  private val repoLocalConfigPort: RepoLocalConfigPort,
) {
  fun resolveExecutionMatrix(repoRoot: Path): ExecutionMatrix? =
    repoLocalConfigPort.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.executionMatrix

  fun resolveSpecType(repoRoot: Path, explicit: SpecType?): SpecType {
    val config = repoLocalConfigPort.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config
    return RepoLocalConfigResolution.resolve(explicit, config.specType, SpecType.LOCAL)
  }

  /**
   * Resolves the code-review parallel lane-2 agent: `explicit parallel: arg > config
   * code_review_parallel_agent > none`. A validated config value (or [explicit]) wins; a missing
   * key or no config file resolves to `none` (single-lane). Reading a malformed config loud-fails.
   */
  fun resolveCodeReviewParallelAgent(repoRoot: Path, explicit: String?): String {
    val config = repoLocalConfigPort.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config
    return RepoLocalConfigResolution.resolve(
      explicit,
      config.codeReviewParallelAgent,
      RepoLocalConfig.NO_PARALLEL_AGENT,
    )
  }
}
