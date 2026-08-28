package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError

enum class FeatureTaskRuntimeFindingVerificationDispositionVerdict(val wireValue: String) {
  VERIFIED("verified"),
  REJECTED("rejected"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeFindingVerificationDispositionVerdict =
      entries.firstOrNull { it.wireValue == value.trim().lowercase() }
        ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
          "finding verification disposition must be verified or rejected, was '$value'.",
        )
  }
}

data class FeatureTaskRuntimeFindingVerificationDisposition(
  val findingId: String,
  val disposition: FeatureTaskRuntimeFindingVerificationDispositionVerdict,
  val reason: String? = null,
  val selectedBoundaryHeadings: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance> = emptyList(),
  val boundaryContextUnavailable: Boolean = false,
) {
  init {
    if (findingId.isBlank()) {
      throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
        "finding verification disposition finding_id must be non-blank.",
      )
    }
  }

  @OpenBoundaryMap("Finding verification disposition at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = buildMap {
    put("finding_id", findingId)
    put("disposition", disposition.wireValue)
    reason?.let { put("reason", it) }
    if (selectedBoundaryHeadings.isNotEmpty()) {
      put("selected_boundary_headings", selectedBoundaryHeadings.map { it.toArtifactMap() })
    }
    if (boundaryContextUnavailable) put("boundary_context_unavailable", true)
  }

  companion object {
    @OpenBoundaryMap("Finding verification disposition decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeFindingVerificationDisposition {
      val findingId = (raw["finding_id"] as? String)?.trim()?.takeIf(String::isNotBlank) ?: invalid(path, "finding_id")
      val disposition = (raw["disposition"] as? String)
        ?.let(FeatureTaskRuntimeFindingVerificationDispositionVerdict::fromWire)
        ?: invalid(path, "disposition")
      val reason = (raw["reason"] as? String)?.trim()?.takeIf(String::isNotBlank)
      val selectedBoundaryHeadings = FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.parseList(
        raw["selected_boundary_headings"],
        "$path.selected_boundary_headings",
      )
      val boundaryContextUnavailable = raw["boundary_context_unavailable"] == true
      return FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = findingId,
        disposition = disposition,
        reason = reason,
        selectedBoundaryHeadings = selectedBoundaryHeadings,
        boundaryContextUnavailable = boundaryContextUnavailable,
      )
    }

    fun parseList(raw: Any?, path: String): List<FeatureTaskRuntimeFindingVerificationDisposition> {
      val entries = raw as? List<*> ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
        "$path must be an array of finding verification dispositions.",
      )
      return entries.mapIndexed { index, entry ->
        val map = JsonSupport.anyToStringAnyMap(entry)
          ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
            "$path[$index] must be an object.",
          )
        fromArtifactMap(map, "$path[$index]")
      }
    }

    private fun invalid(path: String, field: String): Nothing =
      throw InvalidFeatureTaskRuntimeFindingVerificationRecordError("$path.$field must be a non-blank string.")
  }
}

data class FeatureTaskRuntimeFindingVerificationVerdict(
  val dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
) {
  val verifiedDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>
    get() = dispositions.filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED }

  val rejectedDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>
    get() = dispositions.filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.REJECTED }
}

fun validateDispositionCoverage(
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  reviewFindingIds: Set<String>,
): String? {
  val dispositionIds = dispositions.map { it.findingId }
  val duplicates = dispositionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
  if (duplicates.isNotEmpty()) {
    return "finding verification must carry exactly one disposition per review finding; " +
      "duplicate finding_id: ${duplicates.sorted().joinToString()}."
  }
  val foreign = dispositionIds.filter { it !in reviewFindingIds }.toSet()
  if (foreign.isNotEmpty()) {
    return "finding verification dispositions name finding ids absent from the preceding review pass: " +
      foreign.sorted().joinToString() + "."
  }
  val omitted = reviewFindingIds - dispositionIds.toSet()
  if (omitted.isNotEmpty()) {
    return "finding verification must disposition every review finding exactly once; " +
      "omitted finding_id: ${omitted.sorted().joinToString()}."
  }
  return null
}
