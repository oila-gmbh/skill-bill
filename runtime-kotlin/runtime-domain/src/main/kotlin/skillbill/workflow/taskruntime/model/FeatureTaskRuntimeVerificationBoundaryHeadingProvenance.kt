package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidFeatureTaskRuntimeFindingVerificationRecordError

data class FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
  val headingId: String,
  val sourcePath: String,
) {
  init {
    require(headingId.isNotBlank()) { "verification boundary heading_id must be non-blank." }
    require(sourcePath.isNotBlank()) { "verification boundary source_path must be non-blank." }
  }

  @OpenBoundaryMap("Verification boundary heading provenance at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = mapOf(
    "heading_id" to headingId,
    "source_path" to sourcePath,
  )

  companion object {
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeVerificationBoundaryHeadingProvenance {
      val headingId = (raw["heading_id"] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: invalid(path, "heading_id")
      val sourcePath = (raw["source_path"] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: invalid(path, "source_path")
      return FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(headingId, sourcePath)
    }

    fun parseList(raw: Any?, path: String): List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance> {
      val entries = raw as? List<*> ?: return emptyList()
      return entries.mapIndexed { index, entry ->
        val map = JsonCodec.anyToStringAnyMap(entry)
          ?: invalid("$path[$index]", "entry")
        fromArtifactMap(map, "$path[$index]")
      }
    }

    private fun invalid(path: String, field: String): Nothing =
      throw InvalidFeatureTaskRuntimeFindingVerificationRecordError("$path.$field must be a non-blank string.")
  }
}
