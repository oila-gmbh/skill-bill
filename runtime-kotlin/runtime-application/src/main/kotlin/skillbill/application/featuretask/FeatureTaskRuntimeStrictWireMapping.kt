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

// The durable audit-repair state is a Kotlin-governed workflow artifact, not a YAML runtime contract: its
// shape is enforced by these exact-key sets plus the domain invariants, and drift fails loudly at the read
// seam through InvalidWorkflowStateSchemaError. It deliberately carries no contract_version, because a
// version field advertises a governing schema, coherence checks, and a parity test that do not exist for
// it. Legacy rows that still carry one are rejected here as unknown fields; operators delete or migrate
// the affected workflow rows out of band, which is the repository's documented recovery path.
internal val AUDIT_REPAIR_STATE_KEYS = setOf(
  "accepted_plans",
  "latest_plan",
  "execution_history",
  "prior_gap_dispositions",
  "unresolved_gap_ledger",
  "repository_fingerprint",
  "progress",
)
internal val AUDIT_REPAIR_RESULT_KEYS = setOf(
  "repair_item_id",
  "outcome",
  "changed_paths_or_symbols",
  "executed_verification",
  "result_evidence",
)
internal val AUDIT_REPAIR_DISPOSITION_KEYS = setOf("gap_id", "status", "evidence")
internal val AUDIT_REPAIR_LEDGER_KEYS = setOf("gaps", "closed_generation_high_water_marks")
internal val AUDIT_REPAIR_UNRESOLVED_GAP_KEYS = setOf("gap_id", "acceptance_criterion_ref", "generation")
internal val AUDIT_REPAIR_PROGRESS_KEYS = setOf(
  "first_pass_convergence",
  "recurring_gap_count",
  "new_gap_count",
  "attempted_repair_item_count",
  "resolved_repair_item_count",
  "audit_gap_iteration_count",
)

internal fun requireExactWireKeys(map: Map<String, Any?>, source: String, expectedKeys: Set<String>) {
  val unknown = map.keys - expectedKeys
  val missing = expectedKeys - map.keys
  if (unknown.isNotEmpty()) invalidWire(source, "contains unknown fields: ${unknown.sorted().joinToString()}")
  if (missing.isNotEmpty()) invalidWire(source, "is missing required fields: ${missing.sorted().joinToString()}")
}

internal fun Map<String, Any?>.requiredArray(field: String, source: String): List<Any?> =
  (this[field] as? List<*>)?.toList() ?: invalidWire("$source.$field", "must be an array")
