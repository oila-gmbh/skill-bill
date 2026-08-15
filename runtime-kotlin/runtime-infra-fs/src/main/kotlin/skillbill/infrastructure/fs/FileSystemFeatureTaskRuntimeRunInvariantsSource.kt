package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.review.spec.GovernedSpecSectionParser.MANDATES_HEADINGS
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemFeatureTaskRuntimeRunInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariants {
    val normalizedPath = specPath.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalizedPath)) {
      "feature-task-runtime spec path '$normalizedPath' must point to a readable spec file."
    }
    val specText = Files.readString(normalizedPath)
    return FeatureTaskRuntimeRunInvariants(
      specReference = normalizedPath.toString(),
      featureSize = parseFeatureSize(specText),
      acceptanceCriteria = GovernedSpecSectionParser.parseListSection(specText) {
        it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
      },
      mandatesAndOverrides = GovernedSpecSectionParser.parseListSection(specText) { it in MANDATES_HEADINGS },
    )
  }

  private fun parseFeatureSize(specText: String): FeatureTaskRuntimeFeatureSize {
    val rawValue = FEATURE_SIZE_LINE.find(specText)?.groupValues?.get(1)
      ?: return FeatureTaskRuntimeFeatureSize.DEFAULT
    return FeatureTaskRuntimeFeatureSize.fromWire(rawValue)
  }

  private companion object {
    val FEATURE_SIZE_LINE = Regex("""(?im)^\s*(?:feature[_ -]size|size)\s*:\s*([^\r\n#]+)(?:\s+#.*)?$""")
  }
}
