package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance

internal fun dispositionBoundaryContextFailure(
  section: FeatureTaskRuntimeFindingBoundaryMemorySection?,
  disposition: FeatureTaskRuntimeFindingVerificationDisposition,
): String? {
  if (section == null || !section.discovery.boundaryContextUnavailable) return null
  return when {
    !disposition.boundaryContextUnavailable ->
      "finding verification disposition for ${disposition.findingId} must set " +
        "boundary_context_unavailable when no eligible boundary owns its paths."
    disposition.selectedBoundaryHeadings.isNotEmpty() ->
      "finding verification disposition for ${disposition.findingId} must not select " +
        "boundary headings when boundary context is unavailable."
    else -> null
  }
}

internal fun validateDeliveredWithPersisted(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  pending: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String? {
  val matchFailure = FeatureTaskRuntimeFindingVerificationBoundaryMemory
    .validatePersistedBoundarySelectionsMatch(sections, dispositions, persisted)
  if (matchFailure != null) return matchFailure
  return pendingDeliveredMismatch(pending, persisted)
}

internal fun pendingDeliveredMismatch(
  pending: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String? = pending.firstNotNullOfOrNull { (findingId, pendingSelections) ->
  val persistedForFinding = persisted[findingId]
    ?: return@firstNotNullOfOrNull "finding verification disposition for $findingId selected boundary headings but " +
      "resolved entry bodies were not yet delivered; re-read the briefing after the runtime records selections."
  if (!boundaryHeadingsMatch(persistedForFinding, pendingSelections)) {
    "finding verification disposition for $findingId must reuse the persisted " +
      "selected_boundary_headings verbatim; do not change heading selections after the initial settlement pass."
  } else {
    null
  }
}
