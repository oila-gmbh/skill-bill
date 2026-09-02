package skillbill.ports.workflow.decomposition.runtime
import skillbill.error.InvalidDecompositionManifestSchemaError

fun invalidManifest(sourceLabel: String, reason: String): Nothing =
  throw InvalidDecompositionManifestSchemaError(sourceLabel = sourceLabel, reason = reason)
