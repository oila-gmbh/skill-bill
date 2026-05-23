package skillbill.application

import skillbill.error.InvalidDecompositionManifestSchemaError

internal fun invalidManifest(sourceLabel: String, reason: String): Nothing =
  throw InvalidDecompositionManifestSchemaError(sourceLabel = sourceLabel, reason = reason)
