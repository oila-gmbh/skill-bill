package skillbill.ports.workflow.decomposition

internal fun unavailableDecompositionManifestFileStore(): Nothing {
  error("Decomposition manifest file store is not configured for this runtime.")
}
