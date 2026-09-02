package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class InjectConstructorDefaultsArchitectureTest {
  @Test
  fun `runtime-application main has no inject constructor defaults beyond baseline`() {
    assertEquals(
      emptySet(),
      baseline("inject-constructor-defaults-baseline.txt"),
      "The runtime-application inject-defaults rule is absolute; its baseline must stay empty.",
    )
    val violations = ArchitectureScanSupport.injectConstructorDefaultViolations(
      baseline = baseline("inject-constructor-defaults-baseline.txt"),
      scanRoot = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
    )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli inject defaults equal the recorded census`() {
    val current = ArchitectureScanSupport.injectConstructorDefaultSites(
      PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
    ).map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }.toSet()
    assertEquals(
      baseline("runtime-cli-inject-constructor-defaults-baseline.txt"),
      current,
      "Re-record runtime-cli-inject-constructor-defaults-baseline.txt with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  @Test
  fun `inject constructor default scanner fires on synthetic default argument`() {
    val source = """
      package skillbill.example

      import me.tatarka.inject.annotations.Inject
      import skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver

      @Inject
      data class SyntheticInjectBag(
        val reviewDriver: FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver { _ ->
          error("auto-approve")
        },
      )
    """.trimIndent()
    val violations = ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticInjectBag.kt",
      source = source,
    ).map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
      .filter { encoded -> encoded !in emptySet<String>() }
      .map { encoded -> "$encoded has a default argument on an @Inject constructor or dependency bag." }
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticInjectBag.kt::SyntheticInjectBag::reviewDriver " +
          "has a default argument on an @Inject constructor or dependency bag.",
      ),
      violations,
    )
  }

  @Test
  fun `inject constructor default scanner sees past a visibility modifier`() {
    val source = """
      package skillbill.example

      import me.tatarka.inject.annotations.Inject
      import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
      import skillbill.ports.diagnostics.RuntimeDiagnostics

      @Inject
      public class ModifierShieldedInjectClass(
        private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
      )
    """.trimIndent()
    val sites = ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/ModifierShieldedInjectClass.kt",
      source = source,
    ).map { site -> "${site.symbol}::${site.parameter}" }
    assertEquals(listOf("ModifierShieldedInjectClass::diagnostics"), sites)
  }

  @Test
  fun `inject scanner reports non-private property defaults of a class without a primary constructor`() {
    val source = """
      package skillbill.example

      import me.tatarka.inject.annotations.Inject

      @Inject
      class SyntheticRunState {
        var dbOverride: String? = null
        var stdinText: String? = null
        var environment: Map<String, String> = emptyMap()
        var externalCommandRunner: CommandRunner = ProcessCommandRunner
        var userHome: Path = Path.of("/tmp")
        var liveStdout: (String) -> Unit = {}
        var liveStderr: (String) -> Unit = {}
        var result: ExecutionResult? = null
        private var stdinLineIterator: Iterator<String>? = null

        fun complete(payload: Map<String, Any?>, exitCode: Int = 0) {
          result = ExecutionResult(exitCode = exitCode)
        }
      }
    """.trimIndent()
    val parameters = ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticRunState.kt",
      source = source,
    ).map { site -> site.parameter }
    assertEquals(
      listOf(
        "dbOverride",
        "stdinText",
        "environment",
        "externalCommandRunner",
        "userHome",
        "liveStdout",
        "liveStderr",
        "result",
      ),
      parameters,
    )
  }

  @Test
  fun `inject scanner keeps reading defaults past a literal holding an unbalanced delimiter`() {
    val source = """
      package skillbill.example

      import me.tatarka.inject.annotations.Inject

      @Inject
      class LiteralDefaultRunState {
        var openBrace: String = "{"
        var closingParen: Char = ')'
        var stdinText: String? = null

        fun complete(exitCode: Int = 0) {
          val localOnly = exitCode
        }
      }
    """.trimIndent()
    val parameters = ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
      relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/LiteralDefaultRunState.kt",
      source = source,
    ).map { site -> site.parameter }
    assertEquals(listOf("openBrace", "closingParen", "stdinText"), parameters)
  }

  private fun baseline(name: String): Set<String> =
    ArchitectureScanSupport.parseStringSetBaseline(ArchitectureBaselineSupport.readBaseline(name))
}
