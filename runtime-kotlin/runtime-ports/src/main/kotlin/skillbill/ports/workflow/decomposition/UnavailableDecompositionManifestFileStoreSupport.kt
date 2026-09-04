package skillbill.ports.workflow.decomposition

internal fun unavailableDecompositionManifestStore(): Nothing {
  error("Decomposition manifest file store is not configured for this runtime.")
}
