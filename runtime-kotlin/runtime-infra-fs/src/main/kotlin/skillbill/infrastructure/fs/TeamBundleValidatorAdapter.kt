package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.team.TeamBundleSchemaValidator
import skillbill.team.TeamBundleValidator

@Inject
class TeamBundleValidatorAdapter : TeamBundleValidator {
  override fun validate(bundle: Map<String, Any?>, sourceLabel: String) {
    TeamBundleSchemaValidator.validate(bundle, sourceLabel)
  }

  override fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?> =
    TeamBundleSchemaValidator.validateYamlText(yamlText, sourceLabel)
}
