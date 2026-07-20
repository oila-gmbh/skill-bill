package skillbill.contracts.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DecompositionManifestSchemaValidatorTest {
  @Test
  fun `malformed decomposition manifest YAML fails with typed schema error`() {
    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestSchemaValidator.validateYamlText("contract_version: [", "malformed.yaml")
    }

    assertContains(error.reason, "YAML is malformed")
  }

  @Test
  fun `non object decomposition manifest YAML fails with typed schema error`() {
    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestSchemaValidator.validateYamlText("- contract_version: 0.1", "array.yaml")
    }

    assertContains(error.reason, "<root> must be an object")
  }

  @Test
  fun `manifest without spec_source validates under the bumped contract`() {
    DecompositionManifestSchemaValidator.validateYamlText(validManifestYaml(), "no-spec-source.yaml")
  }

  @Test
  fun `manifest with explicit linear spec_source and linear_issue_id validates`() {
    val yaml = validManifestYaml(
      specSourceValue = "linear",
      subtaskLinearIssueId = "SKILL-512",
    )

    DecompositionManifestSchemaValidator.validateYamlText(yaml, "linear-spec-source.yaml")
  }

  @Test
  fun `manifest with unsupported spec_source value fails with typed schema error`() {
    val yaml = validManifestYaml(specSourceValue = "github")

    val error = assertFailsWith<InvalidDecompositionManifestSchemaError> {
      DecompositionManifestSchemaValidator.validateYamlText(yaml, "bad-spec-source.yaml")
    }

    assertContains(error.reason, "spec_source")
  }

  private fun validManifestYaml(specSourceValue: String? = null, subtaskLinearIssueId: String? = null): String {
    val lines = mutableListOf(
      "contract_version: \"0.5\"",
      "issue_key: SKILL-71",
      "feature_name: local-config-and-linear-spec-mode",
      "parent_spec_path: .feature-specs/SKILL-71-local-config/spec.md",
    )
    if (specSourceValue != null) {
      lines += "spec_source: $specSourceValue"
    }
    lines += listOf(
      "execution_model: same_branch_commit_per_subtask",
      "base_branch: main",
      "feature_branch: feature/SKILL-71-local-config",
      "stack_branches: []",
      "current_subtask_intent:",
      "  subtask_id: 1",
      "  action: start",
      "subtasks:",
      "  - id: 1",
      "    name: Foundation",
      "    spec_path: .feature-specs/SKILL-71-local-config/spec_subtask_1_foundation.md",
      "    status: pending",
      "    branch: null",
      "    commit_sha: null",
      "    workflow_id: null",
      "    blocked_reason: null",
      "    last_resumable_step: null",
    )
    if (subtaskLinearIssueId != null) {
      lines += "    linear_issue_id: \"$subtaskLinearIssueId\""
    }
    lines += "    dependencies: []"
    return lines.joinToString("\n")
  }
}
