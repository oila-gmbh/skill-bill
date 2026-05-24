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
}
