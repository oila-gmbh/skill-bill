package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewFindingCitation

internal fun encodePassClaims(findings: List<ParallelReviewMergedFinding>): String = JsonSupport.mapToJsonString(
  mapOf(
    "findings" to findings.map { finding ->
      mapOf(
        "f_number" to finding.fNumber,
        "agent_ids" to finding.agentIds,
        "severity" to finding.severity.name,
        "confidence" to finding.confidence,
        "location" to finding.location,
        "description" to finding.description,
        "specialist_skill_names" to finding.specialistSkillNames,
        "origin_layer_chains" to finding.originLayerChains,
        "repository_path" to finding.repositoryPath,
        "line" to finding.line,
        "commit_shas" to finding.commitShas,
      )
    },
  ),
)

internal fun decodePassClaims(raw: String): List<ParallelReviewMergedFinding> {
  val root = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return emptyList()
  val items = root["findings"] as? List<*> ?: return emptyList()
  return items.mapNotNull { item ->
    val map = JsonSupport.anyToStringAnyMap(item) ?: return@mapNotNull null
    val fNumber = map["f_number"] as? String ?: return@mapNotNull null
    val severityName = map["severity"] as? String ?: return@mapNotNull null
    val severity = runCatching { ParallelReviewSeverity.valueOf(severityName) }.getOrNull()
      ?: return@mapNotNull null
    ParallelReviewMergedFinding(
      fNumber = fNumber,
      agentIds = stringList(map["agent_ids"]),
      severity = severity,
      confidence = map["confidence"] as? String ?: "",
      location = map["location"] as? String ?: "",
      description = map["description"] as? String ?: "",
      specialistSkillNames = stringList(map["specialist_skill_names"]),
      originLayerChains = chainList(map["origin_layer_chains"]),
      repositoryPath = map["repository_path"] as? String,
      line = intValue(map["line"]),
      commitShas = stringList(map["commit_shas"]),
    )
  }
}

internal fun encodeCitations(citations: List<ReviewFindingCitation>): String =
  ReviewFindingCitation.encodeList(citations)

internal fun decodeCitations(raw: String?): List<ReviewFindingCitation> = ReviewFindingCitation.decodeList(raw)

private fun stringList(raw: Any?): List<String> = (raw as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

private fun chainList(raw: Any?): List<List<String>> = (raw as? List<*>)?.map(::stringList) ?: emptyList()

private fun intValue(raw: Any?): Int? = when (raw) {
  is Int -> raw
  is Long -> raw.toInt()
  else -> null
}
