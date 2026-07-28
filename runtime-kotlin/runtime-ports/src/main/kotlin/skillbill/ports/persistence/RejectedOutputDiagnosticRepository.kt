package skillbill.ports.persistence

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
)

data class RejectedOutputDiagnosticSelector(
  val workflowId: String,
  val phaseId: String? = null,
  val attempt: Int? = null,
)

data class RejectedOutputDiagnosticRecord(
  val metadata: RejectedOutputDiagnostic,
  val payload: ByteArray?,
) {
  override fun toString(): String = "RejectedOutputDiagnosticRecord(metadata=$metadata, payload=<hidden>)"
}

sealed class RejectedOutputDiagnosticError(message: String) : RuntimeException(message) {
  class Absent(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is absent.")
  class Expired(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' has expired.")
  class Oversized(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is oversized.")
  class Corrupt(identity: String) : RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' is corrupt.")
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
  class Retrieval(reason: String) : RejectedOutputDiagnosticError("Rejected output diagnostic retrieval failed: $reason")
  class Conflict(identity: String) :
    RejectedOutputDiagnosticError("Rejected output diagnostic '$identity' conflicts with immutable evidence.")
}

interface RejectedOutputDiagnosticRepository {
  fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord
  fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic>
  fun read(identity: String): RejectedOutputDiagnosticRecord
  fun markExpired(before: Instant): Int
  fun delete(selector: RejectedOutputDiagnosticSelector): Int
}

fun interface RejectedOutputDiagnosticPermissions {
  fun applyRestrictivePermissions()
}
