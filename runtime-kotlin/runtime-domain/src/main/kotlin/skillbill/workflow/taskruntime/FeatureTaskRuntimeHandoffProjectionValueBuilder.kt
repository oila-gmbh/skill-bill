package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal object FeatureTaskRuntimeHandoffProjectionValueBuilder {
  private val phaseProjectionContractIds: Set<String> = setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_CLEARANCE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
  )

  /**
   * Selects named values from the validated phase envelope. The declaration is the allowlist:
   * summary, narration, raw output, reports, progress and telemetry have no route into this method.
   * Structured list entries are independently serialized so collection budgets count every item.
   */
  fun phaseProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: FeatureTaskRuntimePhaseOutput,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    if (declaration.projectionContractId !in phaseProjectionContractIds) return null
    val envelope = output.normalizedOutput?.envelope
      ?: JsonSupport.parseObjectOrNull(output.payload)?.let { JsonSupport.jsonElementToValue(it) }
        ?.let(JsonSupport::anyToStringAnyMap)
      ?: rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "validated producer output could not be decoded as an object.",
      )
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
    val runtimeOwned = runtimeOwnedPhaseProjectionValues(inputs, declaration, produced, envelope)
    return declaration.declaredFieldNames.mapNotNull { name ->
      val value = runtimeOwned[name] ?: when {
        declaration.projectionContractId ==
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE -> null
        name == "verdict" -> envelope[name]
        else -> resolveDeclaredPhaseField(produced, name)
      }
      value?.let {
        FeatureTaskRuntimeHandoffProjectionField(name, projectionValue(name, it, inputs, declaration))
      }
    }
  }

  private fun runtimeOwnedPhaseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
    envelope: Map<String, Any?>,
  ): Map<String, Any?> = when (declaration.projectionContractId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE -> mapOf(
      "clearance_status" to auditClearanceStatus(envelope),
      "review_scope" to FeatureTaskRuntimePhaseWorkflowQueries
        .ceremonyScaling(inputs.runInvariants.featureSize)
        .reviewScope
        .wireValue,
      "repository_checkpoint" to checkpointFingerprint(inputs),
      "verdict" to auditClearanceStatus(envelope),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST -> mapOf(
      "unresolved_blocker_findings" to verifiedFindingsProjection(inputs, produced),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT -> mapOf(
      "findings" to reviewFindingsForVerificationProjection(produced),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS -> mapOf(
      "finding_dispositions" to produced["finding_dispositions"],
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT -> mapOf(
      "changed_paths" to inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty(),
      "tests_added" to (produced["tests_added"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "tests_updated" to (produced["tests_updated"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "deviations" to (produced["deviations"] as? List<*>).orEmpty().filterIsInstance<String>(),
      "repository_checkpoint" to checkpointFingerprint(inputs),
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE ->
      phaseProseProjectionValues(inputs, declaration, produced)
    else -> FeatureTaskRuntimeHandoffProjectionFinalization.finalizationProjectionValues(inputs, declaration)
  }.filterValues { it != null }

  private fun checkpointFingerprint(inputs: FeatureTaskRuntimeHandoffProjectionInputs): Map<String, String>? =
    inputs.resolvedCheckpoint?.let { mapOf("fingerprint" to it.fingerprint) }

  private fun phaseProseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
  ): Map<String, Any?> {
    val value = resolveDeclaredPhaseField(produced, "value")
      ?: rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "produced_outputs.value is required for phase prose handoff.",
      )
    val valueText = value.toString()
    if (valueText.isBlank()) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "produced_outputs.value must contain non-blank prose for phase handoff.",
      )
    }
    val fields = linkedMapOf<String, Any?>("value" to valueText)
    resolveDeclaredPhaseField(produced, "prompt")
      ?.toString()
      ?.takeIf(String::isNotBlank)
      ?.let { fields["directive"] = it }
    return fields
  }

  private fun auditClearanceStatus(envelope: Map<String, Any?>): String? =
    (envelope["verdict"] as? String)?.takeIf(String::isNotBlank)

  private fun verifiedFindingsProjection(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    produced: Map<String, Any?>,
  ): List<Map<String, Any?>> {
    val reviewProduced = inputs.resolvedUpstream.outputsByPhaseId[
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    ]?.let(FeatureTaskRuntimeHandoffProjectionFinalization::genericProducedOutputs).orEmpty()
    val reviewFindingsById = reviewFindingsForVerificationProjection(reviewProduced)
      .associateBy { it["finding_id"]?.toString().orEmpty() }
    return FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      produced["finding_dispositions"],
      "produced_outputs.finding_dispositions",
    )
      .filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED }
      .map { disposition ->
        val review = reviewFindingsById[disposition.findingId]
        val severity = (review?.get("severity") as? String)
          ?.trim()
          ?.lowercase()
          ?.takeIf(String::isNotBlank)
          ?: "blocker"
        mapOf(
          "finding_id" to disposition.findingId,
          "severity" to severity,
          "location" to (review?.get("location") ?: "repository"),
          "expected_outcome" to (
            review?.get("message")?.toString()?.takeIf(String::isNotBlank)
              ?: disposition.reason
              ?: "Verified finding."
            ),
          "criterion_refs" to emptyList<String>(),
          "task_refs" to emptyList<String>(),
        )
      }
  }

  private fun reviewFindingsForVerificationProjection(produced: Map<String, Any?>): List<Map<String, Any?>> =
    (produced["findings"] as? List<*>).orEmpty()
      .mapNotNull(JsonSupport::anyToStringAnyMap)
      .map { finding ->
        val severity = (finding["severity"] as? String)?.takeIf(String::isNotBlank) ?: "blocker"
        mapOf(
          "finding_id" to (finding["finding_id"] ?: finding["f_number"] ?: finding["id"]),
          "severity" to severity,
          "location" to (
            finding["location"] ?: finding["repository_path"] ?: finding["path"] ?: "repository"
            ),
          "message" to (
            finding["message"] ?: finding["description"] ?: finding["expected_outcome"] ?: "Review finding."
            ),
          "issue_category" to (finding["issue_category"] ?: finding["category"] ?: "other"),
          "claim_verdict" to finding["claim_verdict"],
          "scope_disposition" to finding["scope_disposition"],
        ).filterValues { it != null }
      }

  /**
   * Phase producers own named result objects (`validation_result`, `history_result`,
   * `commit_push_result`, and `pr_result`). Resolve only those governed containers; searching every
   * nested object would turn a closed projection into an accidental context-discovery mechanism.
   */
  private fun resolveDeclaredPhaseField(produced: Map<String, Any?>, name: String): Any? {
    produced[name]?.let { return it }
    val resultContainers = listOf(
      "audit_result",
      "review_result",
      "validation_result",
      "build_receipt",
      "history_result",
      "commit_push_result",
      "pr_result",
    )
    val nested = resultContainers.firstNotNullOfOrNull { container ->
      JsonSupport.anyToStringAnyMap(produced[container])?.get(name)
    }
    if (nested != null) return nested
    return null
  }

  private fun projectionValue(
    name: String,
    value: Any,
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionValue {
    if (name == FeatureTaskRuntimeHandoffProjectionEnvelopeWire.REPOSITORY_CHECKPOINT_FIELD) {
      val checkpoint = JsonSupport.anyToStringAnyMap(value)
      val fingerprint = (checkpoint?.get("fingerprint") as? String)?.takeIf(String::isNotBlank)
        ?: rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "repository_checkpoint must contain a non-blank fingerprint.",
        )
      return FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        fingerprint,
      )
    }
    return when (value) {
      is Iterable<*> -> FeatureTaskRuntimeHandoffProjectionValue.TextList(
        value.map { item ->
          when (item) {
            is String -> item
            is Map<*, *> -> JsonSupport.mapToJsonString(
              item.entries.associate { (key, entryValue) -> key.toString() to entryValue },
            )
            else -> item.toString()
          }
        },
      )
      is Map<*, *> -> FeatureTaskRuntimeHandoffProjectionValue.Text(
        JsonSupport.mapToJsonString(value.entries.associate { (key, entryValue) -> key.toString() to entryValue }),
      )
      else -> FeatureTaskRuntimeHandoffProjectionValue.Text(value.toString())
    }
  }
}
