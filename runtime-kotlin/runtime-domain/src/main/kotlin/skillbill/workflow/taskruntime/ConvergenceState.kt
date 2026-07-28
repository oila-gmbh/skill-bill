package skillbill.workflow.taskruntime

import java.security.MessageDigest
import java.time.Instant

const val CONVERGENCE_STATE_CONTRACT_VERSION: String = "0.1"
const val CONVERGENCE_ID_MAX_LENGTH: Int = 160
const val CONVERGENCE_SUMMARY_MAX_LENGTH: Int = 512
const val CONVERGENCE_CLASSIFICATION_MAX_LENGTH: Int = 64

enum class ConvergenceRecordKind {
  IMPLEMENTATION_OUTCOME,
  IMPLEMENTATION_OBLIGATION,
  AUDIT_GAP,
  AUDIT_REPAIR,
  REVIEW_FINDING,
  REVIEW_DISPOSITION,
  REPOSITORY_CHECKPOINT,
  LEGACY_IMPORT,
}

enum class ConvergenceStatus { OPEN, RESOLVED, COMPLETED, FAILED, QUARANTINED }

data class ConvergenceProvenance(
  val workflowId: String,
  val generation: Int,
  val phaseId: String,
  val attempt: Int? = null,
  val reviewPass: Int? = null,
) {
  init {
    require(workflowId.isSafeIdentity())
    require(generation > 0)
    require(phaseId in setOf("implement", "audit", "review"))
    require(attempt == null || attempt in 1..10000)
    require(reviewPass == null || reviewPass in 1..10000)
  }
}

data class ConvergenceRecord(
  val recordId: String,
  val logicalId: String,
  val kind: ConvergenceRecordKind,
  val provenance: ConvergenceProvenance,
  val evidenceDigest: String,
  val createdAt: String,
  val status: ConvergenceStatus,
  val classification: String? = null,
  val summary: String? = null,
  val parentLogicalId: String? = null,
  val path: String? = null,
  val evidenceRef: String? = null,
) {
  init {
    require(recordId.isSafeIdentity() && logicalId.isSafeIdentity())
    require(recordId == ConvergenceIdentities.record(logicalId, provenance.generation))
    require(parentLogicalId == null || parentLogicalId.isSafeIdentity())
    require(evidenceDigest.matches(Regex("[a-f0-9]{64}")))
    require(runCatching { Instant.parse(createdAt) }.isSuccess)
    require(classification == null || (
      classification.length in 1..CONVERGENCE_CLASSIFICATION_MAX_LENGTH &&
        classification.matches(Regex("[a-z][a-z0-9_-]*"))
      ))
    require(summary == null || summary.length in 1..CONVERGENCE_SUMMARY_MAX_LENGTH)
    require(path == null || path.length in 1..512)
    require(evidenceRef == null || evidenceRef.length in 1..512)
    when (kind) {
      ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
      ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
      -> require(provenance.phaseId == "implement" && provenance.attempt != null && provenance.reviewPass == null)
      ConvergenceRecordKind.AUDIT_GAP -> require(
        provenance.phaseId == "audit" && provenance.attempt == null && provenance.reviewPass == null &&
          parentLogicalId == null,
      )
      ConvergenceRecordKind.AUDIT_REPAIR -> require(
        provenance.phaseId == "audit" && provenance.attempt == null && provenance.reviewPass == null &&
          parentLogicalId != null,
      )
      ConvergenceRecordKind.REVIEW_FINDING -> require(
        provenance.phaseId == "review" && provenance.attempt == null && provenance.reviewPass != null &&
          parentLogicalId == null,
      )
      ConvergenceRecordKind.REVIEW_DISPOSITION -> require(
        provenance.phaseId == "review" && provenance.attempt == null && provenance.reviewPass != null &&
          parentLogicalId != null,
      )
      ConvergenceRecordKind.REPOSITORY_CHECKPOINT -> require(
        provenance.attempt == null && provenance.reviewPass == null && evidenceRef != null,
      )
      ConvergenceRecordKind.LEGACY_IMPORT -> require(
        provenance.attempt == null && provenance.reviewPass == null,
      )
    }
  }
}

data class UnresolvedConvergence(
  val implementationObligations: List<ConvergenceRecord>,
  val auditRepairs: List<ConvergenceRecord>,
  val reviewBlockers: List<ConvergenceRecord>,
)

sealed interface ReplayResult {
  data class Appended(val record: ConvergenceRecord) : ReplayResult
  data class Identical(val record: ConvergenceRecord) : ReplayResult
  data class Conflict(val existing: ConvergenceRecord, val proposed: ConvergenceRecord) : ReplayResult
}

object ConvergenceIdentities {
  fun logical(workflowId: String, kind: ConvergenceRecordKind, stableKey: String): String =
    "logical:${digest("$workflowId|${kind.name}|$stableKey")}"

  fun record(logicalId: String, generation: Int): String =
    "record:${digest("$logicalId|$generation")}"

  fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
}

fun Iterable<ConvergenceRecord>.currentByLogicalIdentity(): Map<String, ConvergenceRecord> =
  groupBy(ConvergenceRecord::logicalId).mapValues { (_, records) ->
    records.maxWith(compareBy<ConvergenceRecord> { it.provenance.generation }.thenBy { it.createdAt })
  }

private fun String.isSafeIdentity(): Boolean =
  length in 1..CONVERGENCE_ID_MAX_LENGTH && matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*"))
