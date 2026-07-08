package skillbill.team

import skillbill.boundary.OpenBoundaryMap

interface TeamBundleValidator {
  @OpenBoundaryMap("Team bundle wire map at the schema-validation seam")
  fun validate(bundle: Map<String, Any?>, sourceLabel: String)

  @OpenBoundaryMap("Parsed team bundle wire map at the schema-validation seam")
  fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?>
}
