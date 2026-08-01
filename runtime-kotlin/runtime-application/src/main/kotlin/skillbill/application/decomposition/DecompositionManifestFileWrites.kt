package skillbill.application.decomposition

import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestCodec
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionManifestRepairEvidence
import skillbill.workflow.model.DecompositionManifestValidationResult
import skillbill.workflow.model.requireAccepted
import skillbill.workflow.toWireMap
import java.nio.file.Path

/**
 * Decomposition manifest parse/emission seam. This is where workflow artifact maps and
 * repo-local YAML text from the workflow file-store port are schema-validated before
 * callers persist or return them.
 */
fun loadDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestFileStore,
  validator: DecompositionManifestValidator,
): DecompositionManifest {
  return loadValidatedDecompositionManifest(path, fileStore, validator).manifest
}

internal data class LoadedDecompositionManifest(
  val manifest: DecompositionManifest,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)

internal fun loadValidatedDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestFileStore,
  validator: DecompositionManifestValidator,
): LoadedDecompositionManifest {
  val validated = validateDecompositionManifestYaml(path, fileStore, validator)
  return LoadedDecompositionManifest(
    manifest = DecompositionManifestCodec.decodeMap(validated.manifest, path.toString()),
    repairEvidence = validated.repairEvidence,
  )
}

internal data class ValidatedDecompositionManifestYaml(
  val manifest: Map<String, Any?>,
  val yamlText: String,
  val repairEvidence: DecompositionManifestRepairEvidence?,
)

internal fun validateDecompositionManifestYaml(
  path: Path,
  fileStore: DecompositionManifestFileStore,
  validator: DecompositionManifestValidator,
): ValidatedDecompositionManifestYaml {
  val yamlText = fileStore.readText(path)
  return when (val result = validator.validateYamlTextResult(yamlText, path.toString())) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = null,
    )
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = result.evidence,
    )
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(path.toString())
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun decodeDecompositionManifestMap(
  wireMap: Map<String, Any?>,
  validator: DecompositionManifestValidator,
  sourceLabel: String = "<in-memory>",
): DecompositionManifest {
  validator.validate(wireMap, sourceLabel)
  return DecompositionManifestCodec.decodeMap(wireMap, sourceLabel)
}

fun encodeDecompositionManifestMap(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  sourceLabel: String = "<in-memory>",
): Map<String, Any?> {
  val wireMap = manifest.toWireMap()
  validator.validate(wireMap, sourceLabel)
  return wireMap
}

fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
  sourceLabel: String = "<in-memory>",
): String {
  return encodeValidatedDecompositionManifestYaml(manifest, validator, fileStore, sourceLabel).yamlText
}

internal fun encodeValidatedDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
  sourceLabel: String = "<in-memory>",
): ValidatedDecompositionManifestYaml {
  val wireMap = encodeDecompositionManifestMap(manifest, validator, sourceLabel)
  val yamlText = fileStore.encodeManifestYaml(wireMap)
  return when (val result = validator.validateYamlTextResult(yamlText, sourceLabel)) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = null,
    )
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> ValidatedDecompositionManifestYaml(
      manifest = result.manifest,
      yamlText = result.yamlText,
      repairEvidence = result.evidence,
    )
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(sourceLabel)
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun writeDecompositionManifestText(target: Path, content: String, fileStore: DecompositionManifestFileStore) {
  fileStore.writeTextAtomically(target, content)
}
