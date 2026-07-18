package skillbill.workflow

import skillbill.workflow.model.SpecSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpecSourceSpecReaderTest {
  @Test
  fun `absent source defaults to local`() {
    assertEquals(SpecSource.LOCAL, SpecSourceSpecReader.parseSpecSource("# Spec\n"))
  }

  @Test
  fun `linear source is parsed from front matter`() {
    assertEquals(
      SpecSource.LINEAR,
      SpecSourceSpecReader.parseSpecSource("---\nspec_source: linear\n---\n# Spec\n"),
    )
  }

  @Test
  fun `unknown source fails loudly`() {
    assertFailsWith<IllegalArgumentException> {
      SpecSourceSpecReader.parseSpecSource("spec_source: github\n")
    }
  }
}
