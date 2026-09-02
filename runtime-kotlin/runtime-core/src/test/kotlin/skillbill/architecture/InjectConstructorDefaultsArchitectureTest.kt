package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class InjectConstructorDefaultsArchitectureTest {
  @Test
  fun `runtime-application main has no inject constructor defaults beyond baseline`() {
    val baseline = ArchitectureScanSupport.parseStringSetBaseline(
      ArchitectureBaselineSupport.readBaseline("inject-constructor-defaults-baseline.txt"),
    )
    val violations = ArchitectureScanSupport.injectConstructorDefaultViolations(baseline)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
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
}
