package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeDatabasePathPlumbingArchitectureTest {
  @Test
  fun `application and ports main source omit db override plumbing from signatures and requests`() {
    val modules = setOf("runtime-application", "runtime-ports")
    val violations =
      declaredMainSourceFiles()
        .filter { file -> modules.any { module -> "/$module/" in file.relativePath } }
        .flatMap { file -> dbOverridePlumbingViolations(file) }
        .sorted()

    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `db override plumbing scanner rejects synthetic signature and request field regressions`() {
    val applicationViolation =
      dbOverridePlumbingViolations(
        syntheticSourceFile(
          "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/example/ExampleService.kt",
          """
          package skillbill.example

          class ExampleService {
            fun load(dbPathOverride: String?) = dbPathOverride
          }
          """.trimIndent(),
        ),
      )
    val portsViolation =
      dbOverridePlumbingViolations(
        syntheticSourceFile(
          "runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/example/ExampleRequest.kt",
          """
          package skillbill.example

          data class ExampleRequest(val dbOverride: String?)
          """.trimIndent(),
        ),
      )
    val allowedContextOwnedField =
      dbOverridePlumbingViolations(
        syntheticSourceFile(
          "runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/model/RuntimeContext.kt",
          """
          package skillbill.model

          data class EnvironmentContext(
            val dbPathOverride: String? = null,
          )
          """.trimIndent(),
        ),
      )

    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/example/ExampleService.kt:4 " +
          "threads db override plumbing",
      ),
      applicationViolation,
    )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/example/ExampleRequest.kt:3 " +
          "threads db override plumbing",
      ),
      portsViolation,
    )
    assertEquals(emptyList(), allowedContextOwnedField)
  }

  private fun dbOverridePlumbingViolations(file: SourceFile): List<String> {
    val isApplication = "/runtime-application/" in file.relativePath
    val isPorts = "/runtime-ports/" in file.relativePath
    if (!isApplication && !isPorts) return emptyList()

    return file.source.lines().mapIndexedNotNull { index, line ->
      if (!DB_OVERRIDE_NAME_PATTERN.containsMatchIn(line)) return@mapIndexedNotNull null
      val trimmed = line.trimStart()
      if (
        isPorts &&
        file.relativePath.endsWith("skillbill/model/RuntimeContext.kt") &&
        trimmed.startsWith("val dbPathOverride")
      ) {
        return@mapIndexedNotNull null
      }
      "${file.relativePath}:${index + 1} threads db override plumbing"
    }
  }

  private companion object {
    val DB_OVERRIDE_NAME_PATTERN = Regex("""\b(dbOverride|dbPathOverride)\b""")
  }
}
