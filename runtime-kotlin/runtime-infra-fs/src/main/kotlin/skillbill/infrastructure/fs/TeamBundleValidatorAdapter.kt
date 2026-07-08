package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.team.TeamBundleSchemaValidator
import skillbill.contracts.team.TeamBundleSourceValidator
import skillbill.team.TeamBundleValidator
import skillbill.team.model.TeamBundle
import skillbill.team.model.TeamBundleParser
import java.nio.file.Path

@Inject
class TeamBundleValidatorAdapter : TeamBundleValidator {
  override fun validate(bundle: Map<String, Any?>, sourceLabel: String, repoRoot: Path): TeamBundle {
    TeamBundleSchemaValidator.validate(bundle, sourceLabel)
    val canonicalBundle = TeamBundleSourceValidator.validateSources(bundle, repoRoot, sourceLabel)
    return TeamBundleParser.parse(canonicalBundle, sourceLabel)
  }

  override fun validateYamlText(yamlText: String, sourceLabel: String, repoRoot: Path): TeamBundle {
    val bundle = TeamBundleSchemaValidator.validateYamlText(yamlText, sourceLabel)
    val canonicalBundle = TeamBundleSourceValidator.validateSources(bundle, repoRoot, sourceLabel)
    return TeamBundleParser.parse(canonicalBundle, sourceLabel)
  }
}
