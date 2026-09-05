package skillbill.infrastructure.fs

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidPortableReviewBaselineSchemaError
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaselineCodec
import java.nio.file.Files
import java.nio.file.Path

class FileSystemPortableReviewBaselinePersistence @Inject constructor() : PortableReviewBaselinePersistence {
  private val yamlMapper = YAMLMapper()
  private val bundleJournal = DecompositionManifestBundleJournal()

  override fun read(path: Path): PortableReviewBaseline? {
    if (!Files.isRegularFile(path)) return null
    return runCatching {
      val text = Files.readString(path)
      if (text.isBlank()) return null
      val raw = JsonSupport.anyToStringAnyMap(yamlMapper.readValue(text, Any::class.java))
        ?: return null
      PortableReviewBaselineCodec.decode(raw)
    }.getOrElse { error ->
      throw InvalidPortableReviewBaselineSchemaError(
        "Portable review baseline at '$path' is malformed: ${error.message.orEmpty()}",
        error,
      )
    }
  }

  override fun writeAtomically(path: Path, artifact: PortableReviewBaseline) {
    val yaml = yamlMapper.writeValueAsString(PortableReviewBaselineCodec.encode(artifact))
    bundleJournal.writeAtomically(path, yaml)
  }
}
