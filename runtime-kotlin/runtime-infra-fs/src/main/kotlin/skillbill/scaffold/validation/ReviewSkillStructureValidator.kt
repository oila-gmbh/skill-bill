package skillbill.scaffold.validation

import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidReviewSkillStructureError
import skillbill.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object ReviewSkillStructureValidator {
  fun validate(pack: Path) {
    val violations = violations(pack)
    if (violations.isNotEmpty()) {
      throw InvalidReviewSkillStructureError(
        "Platform pack '${pack.fileName}' violates the governed review-skill structure: " +
          violations.joinToString("; ") { violation ->
            val display = displayPath(pack, violation.path)
            "$display: ${violation.rule}"
          },
      )
    }
  }

  fun violations(pack: Path): List<ReviewSkillStructureViolation> {
    if (pack.name == "platform-packs") {
      return Files.list(pack).use { packDirectories ->
        packDirectories.filter(Files::isDirectory).toList().flatMap(::violations)
      }
    }
    val manifest = manifest(pack) ?: return listOf(
      ReviewSkillStructureViolation(pack.resolve("platform.yaml"), "platform manifest mapping"),
    )
    val reviewFiles = contentFiles(pack)
    val hasReviewSurface =
      declaredBaseline(manifest) != null ||
        declaredAreas(manifest).isNotEmpty() ||
        reviewFiles.isNotEmpty()
    return buildList {
      if (hasReviewSurface) {
        addAll(ReviewSkillStructureValidatorManifest.manifestViolations(pack, manifest))
        addAll(
          reviewFiles.flatMap { file ->
            ReviewSkillStructureValidatorContent.contentViolations(pack, manifest, file)
          },
        )
        addAll(ReviewSkillStructureValidatorContent.nativeAgentViolations(pack, manifest))
        addAll(
          ReviewSkillStructureValidatorContent.authoredSidecarViolations(reviewFiles, manifest),
        )
      }
      addAll(ReviewSkillStructureValidatorContent.qualityCheckViolations(pack, manifest))
      addAll(
        allContentFiles(pack).flatMap(::severityViolations),
      )
    }
  }
}

internal data class ReviewSkillStructureViolation(val path: Path, val rule: String) {
  override fun toString(): String = "$path: $rule"
}

internal fun validateReviewSkillStructure(pack: PlatformManifest) {
  val baseline = pack.declaredFiles.baseline ?: return
  val bundle = baseline.parent.resolve("native-agents").resolve(NATIVE_AGENT_BUNDLE_FILE)
  if (!Files.isRegularFile(bundle)) return

  val actualAgents = parseNativeAgentBundle(bundle)
  val actualNames = actualAgents.map { it.name }
  val specialistNames = pack.declaredCodeReviewAreas
    .map { area -> pack.declaredFiles.areas.getValue(area).parent.fileName.toString() }
    .toSet()
  val baselineName = baseline.parent.fileName.toString()
  val expectedNames = specialistNames + baselineName
  val actualNameSet = actualNames.toSet()
  val governedNameSet = actualAgents.filter { it.composition != null }.map { it.name }.toSet()
  val unknown = governedNameSet - expectedNames
  if (actualNames.size != actualNameSet.size || unknown.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': native-agent bundle may not declare duplicate agents or unknown " +
        "governed-content agents; unknown=${unknown.sorted()}.",
    )
  }
}
