package skillbill.error

class InvalidAuditRepairCycleSchemaError(val reason: String) :
  ShellContentContractException("Invalid audit repair cycle: $reason")

class AuditRepairCycleConflictError(val reason: String, val needsRecovery: Boolean = false) :
  ShellContentContractException("Audit repair cycle conflict: $reason")
