package skillbill.ports.diagnostics.model

import java.time.Instant

enum class RejectedOutputLifecycle { STORED, OVERSIZED, EXPIRED }

data class RejectedOutputDiagnostic(
  val identity: String,
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val rule: String,
  val path: String,
  val reason: String,
  val agentId: String,
  val model: String,
  val recordedAt: Instant,
  val byteSize: Long,
  val sha256: String,
  val lifecycle: RejectedOutputLifecycle,
  /**
   * Ordinal of a repair turn launched inside a single phase attempt, zero for an ordinary attempt.
   * A gate repair cycle re-runs an agent without advancing [attempt], so this is what keeps two
   * turns of the same attempt independently addressable.
   */
  val repairTurn: Int = 0,
) {
  init {
    require(repairTurn >= 0) { "Rejected output diagnostic repair turn must not be negative." }
  }
}

data class RejectedOutputDiagnosticSelector(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
  /**
   * Narrows to one repair turn within [attempt]. Without it, an attempt that ran a gate repair cycle
   * resolves to several diagnostics, which is what makes a raw-body read ambiguous.
   */
  val repairTurn: Int? = null,
)

data class RejectedOutputDiagnosticRecord(
  val metadata: RejectedOutputDiagnostic,
  val payload: ByteArray?,
) {
  override fun toString(): String = "RejectedOutputDiagnosticRecord(metadata=$metadata, payload=<hidden>)"
}

data class ProducerOutputEvidence(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val agentId: String,
  val model: String,
  val recordedAt: Instant,
  val byteSize: Long,
  val sha256: String,
  val payload: ByteArray?,
  val generation: Int = 0,
  /** See [RejectedOutputDiagnostic.repairTurn]; the two keys advance together. */
  val repairTurn: Int = 0,
) {
  init {
    require(generation >= 0) { "Producer output evidence generation must not be negative." }
    require(repairTurn >= 0) { "Producer output evidence repair turn must not be negative." }
  }

  override fun toString(): String = "ProducerOutputEvidence(workflowId=$workflowId, phaseId=$phaseId, " +
    "generation=$generation, attempt=$attempt, repairTurn=$repairTurn, payload=<hidden>)"
}

/**
 * The payload-free primary key of one retained producer capture. Safe to surface in operator text: it
 * carries identifiers and ordinals only, never retained bytes.
 */
fun ProducerOutputEvidence.evidenceKey(): String = "$workflowId:$phaseId:$generation:$attempt:$repairTurn:$agentId"

sealed class RejectedOutputDiagnosticError(message: String) : RuntimeException(message) {
  class Absent(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is absent.")
  class Expired(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' has expired.")
  class Oversized(
    identity: String,
  ) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is oversized.")
  class Corrupt(identity: String, cause: Throwable? = null) :
    RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is corrupt.") {
    init {
      if (cause != null) initCause(cause)
    }
  }
  class Permission(operation: String, cause: Throwable? = null) :
    RejectedOutputDiagnosticError("Rejected output diagnostic permission operation '$operation' failed.") {
    init {
      if (cause != null) initCause(cause)
    }
  }
  class Persistence(operation: String, cause: Throwable? = null) :
    RejectedOutputDiagnosticError("Rejected output diagnostic persistence operation '$operation' failed.") {
    init {
      if (cause != null) initCause(cause)
    }
  }
  class Retrieval(
    reason: String,
  ) : RejectedOutputDiagnosticError("Rejected output diagnostic retrieval failed: $reason")
  class InvalidRequest(reason: String) :
    RejectedOutputDiagnosticError("Rejected output diagnostic request is invalid: $reason")
  class InvalidConfiguration(reason: String) :
    RejectedOutputDiagnosticError("Rejected output diagnostic configuration is invalid: $reason")
  class Conflict(identity: String) :
    RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' conflicts with immutable evidence.")
}
