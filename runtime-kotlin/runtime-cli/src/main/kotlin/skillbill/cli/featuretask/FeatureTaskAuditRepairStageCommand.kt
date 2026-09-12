package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.FeatureTaskPhaseSettlementService

@Inject
class FeatureTaskAuditRepairStageCommand(
  private val settlementService: FeatureTaskPhaseSettlementService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "audit-stage",
  "Record one durable audit-repair stage from a JSON request.",
) {
  private val requestJson by option("--request-json", help = "JSON object containing the stage request.")
  private val format by formatOption()

  override fun run() {
    val raw = JsonCodec.parseObjectOrNull(requestJson ?: inputs.stdinText.orEmpty())
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: throw UsageError("audit-stage requires a JSON object from --request-json or stdin.")
    val result = settlementService.auditStage(raw)
    state.complete(result, format)
  }
}
