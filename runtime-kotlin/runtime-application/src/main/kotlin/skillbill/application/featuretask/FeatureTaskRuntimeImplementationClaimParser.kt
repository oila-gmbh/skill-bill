package skillbill.application.featuretask

import skillbill.contracts.JsonSupport

internal fun featureTaskRuntimeImplementationClaimFrom(
  outputMap: Map<String, Any?>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationClaim {
  val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
  val value = produced["value"]?.toString()?.trim().orEmpty()
  val prompt = produced["prompt"]?.toString()?.trim()?.takeIf(String::isNotBlank)
  return FeatureTaskRuntimeImplementationClaim(value = value, prompt = prompt)
}

internal fun Map<String, Any?>.stringList(key: String): List<String> =
  (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }.filter(String::isNotBlank)
