package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.FullValidateRepairParsedArtifacts
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FullValidateRepairPlanItem
import skillbill.workflow.taskruntime.model.FullValidateSubstantiationReceipt

object FullValidateRepairArtifacts {
  fun parse(output: FeatureTaskRuntimePhaseOutput): FullValidateRepairParsedArtifacts {
    val envelope = JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return FullValidateRepairParsedArtifacts(plan = null, receipts = emptyList())
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
    return FullValidateRepairParsedArtifacts(
      plan = parsePlan(produced["validation_repair_plan"]),
      receipts = parseReceipts(produced["substantiation_receipts"]),
    )
  }

  private fun parsePlan(raw: Any?): List<FullValidateRepairPlanItem>? {
    if (raw == null) return null
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val identities = (map["identities"] as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        .orEmpty()
      if (identities.isEmpty()) null else FullValidateRepairPlanItem(identities)
    }
  }

  private fun parseReceipts(raw: Any?): List<FullValidateSubstantiationReceipt> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val identity = (map["identity"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val paths = (map["changed_paths_or_symbols"] as? List<*>)
        ?.mapNotNull { it as? String }
        .orEmpty()
      FullValidateSubstantiationReceipt(
        identity = identity,
        rootCause = (map["root_cause"] as? String).orEmpty(),
        changedPathsOrSymbols = paths,
        rationale = (map["rationale"] as? String).orEmpty(),
      )
    }
  }
}
