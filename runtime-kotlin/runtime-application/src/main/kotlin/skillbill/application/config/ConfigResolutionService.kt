package skillbill.application.config

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigResolution
import skillbill.config.model.SpecType
import skillbill.config.model.parseExecutionMatrix
import skillbill.error.MalformedMachineConfigError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.telemetry.TelemetryConfigStore
import java.nio.file.Path

/**
 * Resolves repository-local workflow settings and machine-wide model directives through their
 * respective configuration ports. Malformed values fail loudly with typed errors.
 */
@Inject
class ConfigResolutionService(
  private val repoLocalConfigPort: RepoLocalConfigPort,
  private val machineConfigStore: TelemetryConfigStore,
) {
  fun resolveExecutionMatrix(): ExecutionMatrix? {
    val configPath = machineConfigStore.configPath()
    val payload = try {
      machineConfigStore.read()?.payload
    } catch (error: IllegalArgumentException) {
      throw MalformedMachineConfigError(
        path = configPath.toString(),
        key = "",
        value = "<document>",
        reason = "is not valid JSON.",
        cause = error,
      )
    } ?: return null
    if (!payload.containsKey(EXECUTION_MATRIX_KEY)) return null
    return when (val parsed = parseExecutionMatrix(payload[EXECUTION_MATRIX_KEY])) {
      is ExecutionMatrixParse.Valid -> parsed.matrix
      is ExecutionMatrixParse.Invalid -> throw MalformedMachineConfigError(
        path = configPath.toString(),
        key = parsed.keyPath,
        value = parsed.value,
        reason = parsed.reason,
      )
    }
  }

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
