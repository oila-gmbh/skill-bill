package skillbill.scaffold

import skillbill.scaffold.platformpack.routeQualityCheck
import skillbill.testing.repoRootFromTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QualityCheckRoutingTest {
  @Test
  fun `every maintained dominant stack routes directly to its own declared checker`() {
    val cases = listOf(
      Triple("go", "services/orders/main.go", "bill-go-code-check"),
      Triple("ios", "App.xcodeproj/project.pbxproj", "bill-ios-code-check"),
      Triple("kotlin", "config/detekt.yml", "bill-kotlin-code-check"),
      Triple("kmp", "shared/src/commonMain/kotlin/App.kt org.jetbrains.kotlin.multiplatform", "bill-kmp-code-check"),
      Triple("php", "composer.json", "bill-php-code-check"),
      Triple("python", "pyproject.toml", "bill-python-code-check"),
      Triple("rust", "Cargo.toml", "bill-rust-code-check"),
      Triple("typescript", "tsconfig.json", "bill-typescript-code-check"),
    )

    cases.forEach { (stack, evidence, checker) ->
      val route = assertNotNull(routeQualityCheck(repoRootFromTest(), listOf(evidence)))
      assertEquals(stack, route.detectedStack)
      assertEquals(checker, route.routedSkill)
      assertFalse(route.fallback)
      assertNull(route.fallbackReason)
    }
  }
}
