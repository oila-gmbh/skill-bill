package skillbill.application.config

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.COMPACTION_KEY
import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsParse
import skillbill.config.model.EXECUTION_MATRIX_KEY
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionMatrixParse
import skillbill.config.model.RepoLocalConfigResolution
import skillbill.config.model.SpecType
import skillbill.config.model.parseCompactionSettings
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

  /**
   * Compaction is on by default, so an absent `compaction` key resolves to [CompactionSettings.DEFAULT]
   * rather than null: a phase agent that inherits a 1M-context model would otherwise never reach its
   * provider-side auto-compaction trigger and would re-read its whole history on every turn.
   */
  fun resolveCompactionSettings(): CompactionSettings {
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
    } ?: return CompactionSettings.DEFAULT
    if (!payload.containsKey(COMPACTION_KEY)) return CompactionSettings.DEFAULT
    return when (val parsed = parseCompactionSettings(payload[COMPACTION_KEY])) {
      is CompactionSettingsParse.Valid -> parsed.settings
      is CompactionSettingsParse.Invalid -> throw MalformedMachineConfigError(
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
}
