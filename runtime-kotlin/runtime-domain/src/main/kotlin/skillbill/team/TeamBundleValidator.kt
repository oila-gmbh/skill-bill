package skillbill.team

import skillbill.boundary.OpenBoundaryMap
import skillbill.team.model.TeamBundle
import java.nio.file.Path

interface TeamBundleValidator {
  @OpenBoundaryMap("Team bundle wire map at the schema-validation seam")
  fun validate(bundle: Map<String, Any?>, sourceLabel: String, repoRoot: Path): TeamBundle

  fun validateYamlText(yamlText: String, sourceLabel: String, repoRoot: Path): TeamBundle
}
