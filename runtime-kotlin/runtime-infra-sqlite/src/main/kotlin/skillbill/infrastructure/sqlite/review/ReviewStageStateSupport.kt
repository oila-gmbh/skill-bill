package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.sql.Connection
import java.time.Instant

fun recordFindingVerdicts(connection: Connection, reviewRunId: String, verdicts: List<ReviewFindingVerdict>) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_finding_verdicts (
      review_run_id,
      finding_id,
      stage,
      claim_verdict,
      scope_disposition,
      citations,
      severity_adjustment_direction,
      severity_adjustment_justification,
      recorded_at,
      contract_version,
      rejection_reason
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id, finding_id, stage) DO UPDATE SET
      claim_verdict = excluded.claim_verdict,
      scope_disposition = excluded.scope_disposition,
      citations = excluded.citations,
      severity_adjustment_direction = excluded.severity_adjustment_direction,
      severity_adjustment_justification = excluded.severity_adjustment_justification,
      recorded_at = excluded.recorded_at,
      contract_version = excluded.contract_version,
      rejection_reason = excluded.rejection_reason
    """.trimIndent(),
  ).use { statement ->
    verdicts.forEach { verdict ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, verdict.findingRef)
      statement.setString(PARAM_THREE, verdict.stage.wireValue)
      statement.setString(PARAM_FOUR, verdict.claimVerdict.wireValue)
      statement.setString(PARAM_FIVE, verdict.scopeDisposition?.wireValue)
      statement.setString(PARAM_SIX, encodeCitations(verdict.citations))
      statement.setString(PARAM_SEVEN, verdict.severityAdjustment?.direction?.wireValue)
      statement.setString(PARAM_EIGHT, verdict.severityAdjustment?.justification)
      statement.setString(PARAM_NINE, verdict.recordedAt)
      statement.setString(PARAM_TEN, verdict.contractVersion)
      statement.setString(PARAM_ELEVEN, verdict.rejectionReason)
      statement.executeUpdate()
    }
  }
}

fun fetchFindingVerdicts(connection: Connection, reviewRunId: String): List<ReviewFindingVerdict> =
  connection.prepareStatement(
    """
    SELECT finding_id, stage, claim_verdict, scope_disposition, citations,
           severity_adjustment_direction, severity_adjustment_justification,
           recorded_at, contract_version, rejection_reason
    FROM review_run_finding_verdicts
    WHERE review_run_id = ?
    ORDER BY stage, finding_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          val direction = resultSet.getString("severity_adjustment_direction")
          val justification = resultSet.getString("severity_adjustment_justification")
          add(
            ReviewFindingVerdict(
              stage = ReviewStage.fromWire(resultSet.getString("stage")),
              findingRef = resultSet.getString("finding_id"),
              claimVerdict = ReviewClaimVerdict.fromWire(resultSet.getString("claim_verdict")),
              scopeDisposition = resultSet.getString("scope_disposition")
                ?.let(ReviewScopeDisposition::fromWire),
              citations = decodeCitations(resultSet.getString("citations")),
              severityAdjustment = if (direction == null || justification == null) {
                null
              } else {
                ReviewSeverityAdjustment(
                  ReviewSeverityAdjustmentDirection.fromWire(direction),
                  justification,
                )
              },
              recordedAt = resultSet.getString("recorded_at"),
              contractVersion = resultSet.getString("contract_version"),
              rejectionReason = resultSet.getString("rejection_reason"),
            ),
          )
        }
      }
    }
  }

fun recordReviewPassClaims(
  connection: Connection,
  reviewRunId: String,
  findings: List<ParallelReviewMergedFinding>,
) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_pass_claims (review_run_id, claims_json, recorded_at)
    VALUES (?, ?, ?)
    ON CONFLICT(review_run_id) DO NOTHING
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.setString(PARAM_TWO, encodePassClaims(findings))
    statement.setString(PARAM_THREE, Instant.now().toString())
    statement.executeUpdate()
  }
}

fun fetchReviewPassClaims(connection: Connection, reviewRunId: String): ReviewPassClaimSnapshot? =
  connection.prepareStatement(
    """
    SELECT claims_json
    FROM review_run_pass_claims
    WHERE review_run_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) return null
      ReviewPassClaimSnapshot(decodePassClaims(resultSet.getString("claims_json")))
    }
  }

fun recordStageBoundary(connection: Connection, reviewRunId: String, boundary: ReviewStageBoundary) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_stage_boundaries (
      review_run_id, stage, reached, recorded_at, contract_version
    ) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id, stage) DO UPDATE SET
      reached = excluded.reached,
      recorded_at = excluded.recorded_at,
      contract_version = excluded.contract_version
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.setString(PARAM_TWO, boundary.stage.wireValue)
    statement.setString(PARAM_THREE, boundary.reached.wireValue)
    statement.setString(PARAM_FOUR, boundary.recordedAt)
    statement.setString(PARAM_FIVE, boundary.contractVersion)
    statement.executeUpdate()
  }
}

fun fetchStageBoundaries(connection: Connection, reviewRunId: String): List<ReviewStageBoundary> =
  connection.prepareStatement(
    """
    SELECT stage, reached, recorded_at, contract_version
    FROM review_run_stage_boundaries
    WHERE review_run_id = ?
    ORDER BY stage
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            ReviewStageBoundary(
              stage = ReviewStage.fromWire(resultSet.getString("stage")),
              reached = ReviewStageReached.fromWire(resultSet.getString("reached")),
              recordedAt = resultSet.getString("recorded_at"),
              contractVersion = resultSet.getString("contract_version"),
            ),
          )
        }
      }
    }
  }

fun recordSpecProjectionReference(
  connection: Connection,
  reviewRunId: String,
  reference: ReviewSpecProjectionReference,
) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_spec_projections (
      review_run_id, spec_path, content_digest, absence_reason, recorded_at
    ) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id) DO UPDATE SET
      spec_path = excluded.spec_path,
      content_digest = excluded.content_digest,
      absence_reason = excluded.absence_reason,
      recorded_at = excluded.recorded_at
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.setString(PARAM_TWO, reference.specPath)
    statement.setString(PARAM_THREE, reference.contentDigest)
    statement.setString(PARAM_FOUR, reference.absenceReason)
    statement.setString(PARAM_FIVE, Instant.now().toString())
    statement.executeUpdate()
  }
}

fun fetchSpecProjectionReference(connection: Connection, reviewRunId: String): ReviewSpecProjectionReference? =
  connection.prepareStatement(
    """
    SELECT spec_path, content_digest, absence_reason
    FROM review_run_spec_projections
    WHERE review_run_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) return null
      ReviewSpecProjectionReference(
        specPath = resultSet.getString("spec_path"),
        contentDigest = resultSet.getString("content_digest"),
        absenceReason = resultSet.getString("absence_reason"),
      )
    }
  }

private fun encodePassClaims(findings: List<ParallelReviewMergedFinding>): String =
  JsonSupport.mapToJsonString(
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

private fun decodePassClaims(raw: String): List<ParallelReviewMergedFinding> {
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

private fun stringList(raw: Any?): List<String> =
  (raw as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

private fun chainList(raw: Any?): List<List<String>> =
  (raw as? List<*>)?.map(::stringList) ?: emptyList()

private fun intValue(raw: Any?): Int? = when (raw) {
  is Int -> raw
  is Long -> raw.toInt()
  else -> null
}

private fun encodeCitations(citations: List<ReviewFindingCitation>): String =
  citations.joinToString("\n") { citation -> "${citation.path}\t${citation.line}" }

private fun decodeCitations(raw: String?): List<ReviewFindingCitation> =
  raw.orEmpty().lineSequence().filter { it.isNotBlank() }.map { line ->
    val path = line.substringBefore('\t')
    val lineNumber = line.substringAfter('\t').toInt()
    ReviewFindingCitation(path, lineNumber)
  }.toList()
