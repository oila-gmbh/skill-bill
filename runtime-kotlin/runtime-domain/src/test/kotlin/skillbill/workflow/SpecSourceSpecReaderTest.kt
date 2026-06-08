package skillbill.workflow

import skillbill.workflow.model.SpecSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpecSourceSpecReaderTest {
  @Test
  fun `parses an explicit linear spec_source line`() {
    val specText = """
      # Feature spec

      spec_source: linear
    """.trimIndent()

    assertEquals(SpecSource.LINEAR, SpecSourceSpecReader.parseSpecSource(specText))
  }

  @Test
  fun `defaults to local when the spec_source line is absent`() {
    val specText = """
      # Feature spec

      feature_size: MEDIUM
    """.trimIndent()

    assertEquals(SpecSource.LOCAL, SpecSourceSpecReader.parseSpecSource(specText))
  }

  @Test
  fun `loud-fails on an unrecognized spec_source value`() {
    val specText = "spec_source: github"

    val error = assertFailsWith<IllegalArgumentException> {
      SpecSourceSpecReader.parseSpecSource(specText)
    }
    assertEquals(true, error.message?.contains("github"))
  }
}
