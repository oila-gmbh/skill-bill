package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator

/**
 * The AC-001 declaration: static, workflow-owned configuration naming exactly one projection for one
 * consumer phase. [declaredFieldNames] closes the projection's shape, so a field outside the list is
 * an undeclared field rather than a silently accepted extension.
 */
@Suppress("LongParameterList") // one flat declaration record; grouping would hide the governed fields
data class PhaseHandoffProjectionDeclaration(
  val consumerPhaseId: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionName: String,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val budget: FeatureTaskRuntimeHandoffProjectionBudget,
  val declaredFieldNames: List<String>,
  val checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  val required: Boolean = true,
  /**
   * AC-010 gate: a lossless private-artifact reference may stand in for inline content only where the
   * consumer dereferences it through a runtime-owned deterministic operation.
   */
  val allowsPrivateArtifactReference: Boolean = false,
  /**
   * When set, the projection delivers a compact reference of this kind in place of inline content.
   * The reference is minted by the runtime from the source's durable identity, so dereferencing it
   * is a deterministic runtime operation — never a model-driven retrieval.
   */
  val inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
  val producerIteration: FeatureTaskRuntimeProducerIteration =
    FeatureTaskRuntimeProducerIteration(
      phaseId = (sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)?.producingPhaseId
        ?: consumerPhaseId,
      iteration = 1,
    ),
  val authorizedReferenceKinds: Set<FeatureTaskRuntimeCompactReferenceKind> =
    listOfNotNull(inlineAlternative).toSet(),
) {
  init {
    require(consumerPhaseId.isNotBlank()) { "PhaseHandoffProjectionDeclaration.consumerPhaseId must be non-blank." }
    require(PROJECTION_NAME_PATTERN.matches(projectionName)) {
      "PhaseHandoffProjectionDeclaration.projectionName must match ${PROJECTION_NAME_PATTERN.pattern}, " +
        "was '$projectionName'."
    }
    require(projectionContractId.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractId must be non-blank."
    }
    require(projectionContractVersion.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractVersion must be non-blank."
    }
    require(declaredFieldNames.isNotEmpty()) {
      "PhaseHandoffProjectionDeclaration '$projectionName' must declare at least one field name; an open " +
        "projection shape cannot be validated."
    }
    require(declaredFieldNames.distinct().size == declaredFieldNames.size) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares duplicate field names."
    }
    require(declaredFieldNames.none { it in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES }) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares a forbidden raw-context field name."
    }
    require(inlineAlternative == null || inlineAlternative in authorizedReferenceKinds) {
      "PhaseHandoffProjectionDeclaration '$projectionName' inline alternative must be explicitly authorized."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime phase-handoff declaration wire seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
    "consumer_phase_id" to consumerPhaseId,
    "projection_name" to projectionName,
    "source" to sourceRef.toDeclarationMap(),
    "projection_contract" to mapOf("id" to projectionContractId, "version" to projectionContractVersion),
    "prompt_visibility" to promptVisibility.wireValue,
    "budget" to mapOf(
      "max_utf8_bytes" to budget.maxUtf8Bytes,
      "max_collection_items" to budget.maxCollectionItems,
    ),
    "checkpoint_policy" to checkpointPolicy.wireValue,
    "producer_iteration" to mapOf(
      "phase_id" to producerIteration.phaseId,
      "iteration" to producerIteration.iteration,
    ),
    "declared_fields" to declaredFieldNames,
    "required" to required,
    "allows_private_artifact_reference" to allowsPrivateArtifactReference,
  ).apply {
    inlineAlternative?.let { put("inline_alternative", it.wireValue) }
    if (authorizedReferenceKinds.isNotEmpty()) {
      put("authorized_reference_kinds", authorizedReferenceKinds.map { it.wireValue }.sorted())
    }
  }

  companion object {
    @OpenBoundaryMap("Strict feature-task-runtime phase-handoff declaration decode")
    fun fromArtifactMap(
      raw: Map<String, Any?>,
      foundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
    ): PhaseHandoffProjectionDeclaration {
      foundationValidator.validateDeclaration(raw, "phase-handoff-declaration")
      val allowed = setOf(
        "contract_version", "consumer_phase_id", "projection_name", "source", "projection_contract",
        "prompt_visibility", "budget", "checkpoint_policy", "producer_iteration", "declared_fields",
        "required", "allows_private_artifact_reference", "inline_alternative", "authorized_reference_kinds",
      )
      invalidIf(
        raw.keys.any { it !in allowed } ||
          raw["contract_version"] != FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
      )
      val source = raw["source"] as? Map<*, *> ?: invalid()
      val sourceRef = sourceRefOf(source)
      val contract = raw["projection_contract"] as? Map<*, *> ?: invalid()
      val budget = raw["budget"] as? Map<*, *> ?: invalid()
      val producer = raw["producer_iteration"] as? Map<*, *> ?: invalid()
      val references = (raw["authorized_reference_kinds"] as? List<*>).orEmpty().map {
        FeatureTaskRuntimeCompactReferenceKind.fromWire(it as? String ?: invalid())
      }.toSet()
      val inlineAlternative = (raw["inline_alternative"] as? String)
        ?.let(FeatureTaskRuntimeCompactReferenceKind::fromWire)
      return PhaseHandoffProjectionDeclaration(
        consumerPhaseId = raw.string("consumer_phase_id"),
        sourceRef = sourceRef,
        projectionName = raw.string("projection_name"),
        projectionContractId = contract.string("id"),
        projectionContractVersion = contract.string("version"),
        promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.fromWire(raw.string("prompt_visibility")),
        budget = FeatureTaskRuntimeHandoffProjectionBudget(
          maxUtf8Bytes = budget.int("max_utf8_bytes"),
          maxCollectionItems = budget.int("max_collection_items"),
        ),
        declaredFieldNames = (raw["declared_fields"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid(),
        checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.fromWire(raw.string("checkpoint_policy")),
        required = raw.boolean("required"),
        allowsPrivateArtifactReference = raw.boolean("allows_private_artifact_reference"),
        producerIteration = FeatureTaskRuntimeProducerIteration(
          phaseId = producer.string("phase_id"),
          iteration = producer.int("iteration"),
        ),
        inlineAlternative = inlineAlternative,
        authorizedReferenceKinds = references,
      )
    }

    private fun sourceRefOf(source: Map<*, *>): FeatureTaskRuntimeHandoffSourceRef = when (source["kind"]) {
      "upstream_phase_output" -> FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(source.string("id"))
      "run_invariant_field" -> FeatureTaskRuntimeHandoffSourceRef.RunInvariantField(
        FeatureTaskRuntimeRunInvariantPromptField.fromWire(source.string("id")),
      )
      "derived_ceremony_scaling" -> FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling
      "addon_content" -> FeatureTaskRuntimeHandoffSourceRef.AddonContentRef(source.string("id"))
      FeatureTaskRuntimeHandoffSourceRef.SHARED_REVIEW_EVIDENCE_WIRE ->
        FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      FeatureTaskRuntimeHandoffSourceRef.REPAIR_LEDGER_WIRE ->
        FeatureTaskRuntimeHandoffSourceRef.RepairLedger
      FeatureTaskRuntimeHandoffSourceRef.PRIOR_GAP_MEMORY_WIRE ->
        FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory
      else -> invalid()
    }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid()
    private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: invalid()
    private fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean ?: invalid()
    private fun invalidIf(condition: Boolean) {
      if (condition) invalid()
    }
    private fun invalid(): Nothing = throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
      sourceLabel = "phase-handoff-declaration",
      reason = "unsupported version, unknown field, or malformed closed-world value",
    )
  }
}
