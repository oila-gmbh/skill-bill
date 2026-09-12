package skillbill.error

class AuditRepairProviderSessionError(reason: String, cause: Throwable? = null) :
  IllegalStateException("Audit provider session could not be established: $reason", cause)
