package skillbill.managedskill

import skillbill.error.InvalidManagedSkillRecordSchemaError
import java.nio.file.Path

internal inline fun <T> managedRecordOperation(source: Path, reason: String, operation: () -> T): T =
  managedRecordOperation(source.toString(), reason, operation)

internal inline fun <T> managedRecordOperation(source: String, reason: String, operation: () -> T): T =
  runCatching(operation).getOrElse { error ->
    when (error) {
      is InvalidManagedSkillRecordSchemaError -> throw error
      is Exception -> throw InvalidManagedSkillRecordSchemaError(source, reason, error)
      else -> throw error
    }
  }

internal fun invalidManagedRecord(source: Path, reason: String): Nothing =
  invalidManagedRecord(source.toString(), reason)

internal fun invalidManagedRecord(source: String, reason: String): Nothing =
  throw InvalidManagedSkillRecordSchemaError(source, reason)
