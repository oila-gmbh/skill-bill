package skillbill.config.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ValidationGateRepoConfigModelsTest {
  @Test
  fun `parseGradleWrapperPath accepts repo-relative wrappers and rejects traversal`() {
    assertEquals("runtime-kotlin/gradlew", parseGradleWrapperPath("runtime-kotlin/gradlew"))
    assertEquals("runtime-kotlin/gradlew", parseGradleWrapperPath("./runtime-kotlin/gradlew"))
    assertEquals("gradlew", parseGradleWrapperPath("./gradlew"))
    assertNull(parseGradleWrapperPath(null))
    assertNull(parseGradleWrapperPath(""))
    assertNull(parseGradleWrapperPath("/abs/gradlew"))
    assertNull(parseGradleWrapperPath("../gradlew"))
    assertNull(parseGradleWrapperPath("runtime-kotlin/../gradlew"))
  }

  @Test
  fun `applyValidationGateGradleWrapper rewrites leading gradlew and injects nested -p`() {
    assertEquals(
      listOf("runtime-kotlin/gradlew", "-p", "runtime-kotlin", "check"),
      applyValidationGateGradleWrapper(listOf("./gradlew", "check"), "runtime-kotlin/gradlew"),
    )
    assertEquals(
      listOf("gradlew", "check"),
      applyValidationGateGradleWrapper(listOf("./gradlew", "check"), "gradlew"),
    )
    assertEquals(
      listOf("./gradlew", "check"),
      applyValidationGateGradleWrapper(listOf("./gradlew", "check"), null),
    )
    assertEquals(
      listOf("echo", "check"),
      applyValidationGateGradleWrapper(listOf("echo", "check"), "runtime-kotlin/gradlew"),
    )
  }

  @Test
  fun `parseValidationGateRepoConfig reads gradle_wrapper`() {
    val parsed = assertIs<ValidationGateRepoConfigParse.Valid>(
      parseValidationGateRepoConfig(mapOf("gradle_wrapper" to "runtime-kotlin/gradlew")),
    )
    assertEquals("runtime-kotlin/gradlew", parsed.config.gradleWrapper)
  }

  @Test
  fun `parseValidationGateRepoConfig rejects absolute paths and unknown keys`() {
    val absolute = assertIs<ValidationGateRepoConfigParse.Invalid>(
      parseValidationGateRepoConfig(mapOf("gradle_wrapper" to "/abs/gradlew")),
    )
    assertEquals("validation_gate.gradle_wrapper", absolute.keyPath)

    val unknown = assertIs<ValidationGateRepoConfigParse.Invalid>(
      parseValidationGateRepoConfig(mapOf("working_directory" to "runtime-kotlin")),
    )
    assertEquals("validation_gate.working_directory", unknown.keyPath)
  }
}
