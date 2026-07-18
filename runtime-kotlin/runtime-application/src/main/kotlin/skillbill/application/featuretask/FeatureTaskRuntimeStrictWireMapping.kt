package skillbill.application.featuretask

internal val AUDIT_REPAIR_PLAN_KEYS = setOf("contract_version", "gaps")
internal val AUDIT_REPAIR_GAP_KEYS = setOf(
  "gap_id",
  "acceptance_criterion_ref",
  "acceptance_criterion_text",
  "failure_evidence",
  "diagnosis",
  "affected_boundary",
  "repair_items",
)
internal val AUDIT_REPAIR_ITEM_KEYS = setOf(
  "repair_item_id",
  "intended_outcome",
  "implementation_actions",
  "affected_paths_or_symbols",
  "required_verification",
  "depends_on",
  "status",
)

internal fun requireExactWireKeys(map: Map<String, Any?>, source: String, expectedKeys: Set<String>) {
  val unknown = map.keys - expectedKeys
  val missing = expectedKeys - map.keys
  if (unknown.isNotEmpty()) invalidWire(source, "contains unknown fields: ${unknown.sorted().joinToString()}")
  if (missing.isNotEmpty()) invalidWire(source, "is missing required fields: ${missing.sorted().joinToString()}")
}
