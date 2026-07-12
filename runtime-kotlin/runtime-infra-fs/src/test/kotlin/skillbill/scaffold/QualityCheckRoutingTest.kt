package skillbill.scaffold

import skillbill.scaffold.platformpack.routeQualityCheck
import skillbill.testing.repoRootFromTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
  fun `ordinary Kotlin and multiplatform paths apply adjacent-pack dominance`() {
    val cases = listOf(
      "Feature.kt" to "kotlin",
      "src/main/kotlin/example/Feature.kt" to "kotlin",
      "shared/src/commonMain/kotlin/example/Feature.kt" to "kmp",
      "shared/src/androidMain/kotlin/example/Feature.kt" to "kmp",
      "shared/src/iosMain/kotlin/example/Feature.kt" to "kmp",
      "plugins { kotlin(\"multiplatform\") }" to "kmp",
    )

    cases.forEach { (evidence, expected) ->
      assertEquals(expected, assertNotNull(routeQualityCheck(repoRootFromTest(), listOf(evidence))).detectedStack)
    }
  }

  @Test
  fun `genuinely mixed Kotlin and KMP ownership fails explicitly`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      routeQualityCheck(
        repoRootFromTest(),
        listOf("server/src/main/kotlin/App.kt", "shared/src/commonMain/kotlin/Shared.kt"),
      )
    }

    assertTrue(failure.message.orEmpty().contains("ambiguous for [kmp, kotlin]"))
  }

  @Test
  fun `literal signals do not match unrelated substrings`() {
    val route = routeQualityCheck(
      repoRootFromTest(),
      listOf("docs/unexpected-results.md", "docs/actuality.md", "docs/toolkit-notes.md"),
    )

    assertNull(route)
  }

  @Test
  fun `unresolved mixed-stack evidence does not select by pack ordering`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      routeQualityCheck(repoRootFromTest(), listOf("src/main.go", "src/main.rs"))
    }

    assertTrue(failure.message.orEmpty().contains("ambiguous"))
  }
}
